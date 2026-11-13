package com.skintclub.odyssey.discord

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

sealed interface GatewayEvent {
    data object Ready : GatewayEvent
    data class Disconnected(val code: Int, val fatal: Boolean) : GatewayEvent
    data object TokenExpired : GatewayEvent
}

/**
 * A connection to Discord's gateway, signed in with the user's OAuth token, used only to set
 * their presence. Heartbeats, resumes and reconnects with backoff on its own.
 * (Adapted from Metrolist's Discord integration, GPL-3.0.)
 */
class DiscordGateway(
    private val scope: CoroutineScope,
    private val token: () -> String?,
) {
    private val _events = MutableSharedFlow<GatewayEvent>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<GatewayEvent> = _events

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    @Volatile private var webSocket: WebSocket? = null
    @Volatile private var open = false
    @Volatile private var seq = 0
    @Volatile private var sessionId: String? = null
    @Volatile private var url = DEFAULT_URL
    @Volatile private var attempts = 0
    @Volatile private var heartbeatJob: Job? = null
    private val lastAck = AtomicLong(0)

    /** Each socket gets an id, so callbacks from one we've replaced are ignored. */
    private val socketIds = AtomicLong(0)
    @Volatile private var activeId = 0L

    val isOpen: Boolean get() = open

    /** Opens a fresh connection and identifies. */
    suspend fun connect() {
        close(4000, "reconnecting")
        sessionId = null
        seq = 0
        url = DEFAULT_URL
        attempts = 0
        openSocket()
        identify()
    }

    fun close(code: Int = 1000, reason: String? = null) {
        heartbeatJob?.cancel()
        activeId = socketIds.incrementAndGet()
        open = false
        webSocket?.let { runCatching { it.close(code, reason) } }
        webSocket = null
    }

    /** Sends a frame; false if the connection isn't open. */
    fun send(frame: String): Boolean {
        val ws = webSocket
        return open && ws != null && ws.send(frame)
    }

    private suspend fun openSocket() {
        val id = socketIds.incrementAndGet()
        activeId = id
        val opened = CompletableDeferred<Unit>()
        webSocket = http.newWebSocket(Request.Builder().url(url).build(), listener(id, opened))
        opened.await()
    }

    private fun identify() {
        val props = JSONObject().put("os", "android").put("browser", "Discord Android").put("device", "Odyssium")
        send(frame(OP_IDENTIFY, JSONObject().put("token", bearer()).put("intents", 0).put("properties", props)))
    }

    private fun resume(sessionId: String) {
        send(frame(OP_RESUME, JSONObject().put("token", bearer()).put("session_id", sessionId).put("seq", seq)))
    }

    private fun bearer() = "Bearer ${token().orEmpty()}"

    private fun listener(id: Long, opened: CompletableDeferred<Unit>) = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (id != activeId) return
            open = true
            opened.complete(Unit)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (id != activeId) return
            scope.launch { runCatching { handle(JSONObject(text)) }.onFailure { Timber.w(it, "Discord gateway frame") } }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            scope.launch { onClosed(id, code) }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (id != activeId) return
            Timber.w(t, "Discord gateway failure")
            opened.completeExceptionally(t)
            scope.launch { onClosed(id, response?.code ?: 4000) }
        }
    }

    private suspend fun handle(json: JSONObject) {
        if (!json.isNull("s")) json.optInt("s").takeIf { it > 0 }?.let { seq = it }
        val d = json.optJSONObject("d")
        when (json.optInt("op", -1)) {
            OP_HELLO -> startHeartbeat(d?.optLong("heartbeat_interval") ?: 41_250L)
            OP_HEARTBEAT_ACK -> lastAck.set(System.currentTimeMillis())
            OP_HEARTBEAT -> send(heartbeat())
            OP_DISPATCH -> when (json.optString("t")) {
                "READY" -> {
                    sessionId = d?.optString("session_id")
                    d?.optString("resume_gateway_url")?.takeIf { it.isNotEmpty() }?.let { url = "$it/?v=10&encoding=json" }
                    attempts = 0
                    _events.emit(GatewayEvent.Ready)
                }
                "RESUMED" -> {
                    attempts = 0
                    _events.emit(GatewayEvent.Ready)
                }
            }
            OP_INVALID_SESSION -> {
                if (json.optBoolean("d") != true) sessionId = null
                webSocket?.close(4000, "invalid session")
            }
            OP_RECONNECT -> webSocket?.close(4000, "reconnect requested")
        }
    }

    private suspend fun onClosed(id: Long, code: Int) {
        if (id != activeId) return
        open = false
        heartbeatJob?.cancel()
        webSocket = null
        when (code) {
            // Bad token: let the manager refresh it and connect again.
            4004 -> {
                _events.emit(GatewayEvent.Disconnected(code, fatal = false))
                _events.emit(GatewayEvent.TokenExpired)
            }
            // Bad intents / shard / version: retrying won't help.
            4010, 4011, 4012, 4013, 4014 -> _events.emit(GatewayEvent.Disconnected(code, fatal = true))
            else -> {
                _events.emit(GatewayEvent.Disconnected(code, fatal = false))
                if (attempts >= MAX_ATTEMPTS) return
                attempts++
                val backoff = (1000L shl (attempts - 1)).coerceAtMost(64_000L)
                delay(if (code == 429) 60_000L else backoff + Random.nextLong(backoff / 4 + 1))
                if (id != activeId) return
                val resumable = sessionId?.takeIf { code !in NO_RESUME && seq > 0 }
                runCatching {
                    openSocket()
                    if (resumable != null) resume(resumable) else identify()
                }.onFailure { Timber.w(it, "Discord gateway reconnect") }
            }
        }
    }

    private fun startHeartbeat(intervalMs: Long) {
        heartbeatJob?.cancel()
        lastAck.set(System.currentTimeMillis())
        heartbeatJob = scope.launch {
            // First beat after a random fraction of the interval, as Discord asks.
            delay((intervalMs * Random.nextDouble()).toLong())
            while (isActive && open) {
                val sentAt = System.currentTimeMillis()
                send(heartbeat())
                delay(intervalMs)
                if (lastAck.get() < sentAt) {
                    webSocket?.close(4000, "heartbeat timeout")
                    break
                }
            }
        }
    }

    private fun heartbeat() = JSONObject().put("op", OP_HEARTBEAT).put("d", if (seq > 0) seq else JSONObject.NULL).toString()

    private fun frame(op: Int, d: JSONObject) = JSONObject().put("op", op).put("d", d).toString()

    companion object {
        private const val DEFAULT_URL = "wss://gateway.discord.gg/?v=10&encoding=json"
        private const val MAX_ATTEMPTS = 7
        private val NO_RESUME = setOf(4007, 4009)

        private const val OP_DISPATCH = 0
        private const val OP_HEARTBEAT = 1
        private const val OP_IDENTIFY = 2
        const val OP_PRESENCE_UPDATE = 3
        private const val OP_RESUME = 6
        private const val OP_RECONNECT = 7
        private const val OP_INVALID_SESSION = 9
        private const val OP_HELLO = 10
        private const val OP_HEARTBEAT_ACK = 11
    }
}
