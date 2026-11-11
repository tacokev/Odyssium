package com.skintclub.odyssey.playback

import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.StreamMetadata
import androidx.media3.common.audio.AudioProcessorChain
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import java.nio.ByteBuffer

/**
 * The same chain as media3's DefaultAudioSink.DefaultAudioProcessorChain (silence skipping, then
 * Sonic for tempo and pitch), except that Sonic only ever sees one end-of-stream per stream.
 *
 * Every tempo/pitch change makes DefaultAudioSink drain the pipeline first, and while draining,
 * AudioProcessingPipeline calls queueEndOfStream() on Sonic again on every pass. In media3
 * 1.11.1 each repeat makes Sonic pad and process more silence, which can produce a frame or so of
 * new output every time - so it never reports ended, the drain never finishes, and the playback
 * thread spins until ExoPlayer gives up with a "player stuck" error. Seen with the pitch at -4
 * (and lower), where the episode froze and nothing else would play for a while.
 */
class OdysseyAudioProcessorChain : AudioProcessorChain {
    private val silenceSkipping = SilenceSkippingAudioProcessor(2_000_000, 20_000, 256)
    private val sonic = SonicAudioProcessor()
    private val processors = arrayOf<AudioProcessor>(silenceSkipping, EndOfStreamOnce(sonic))

    override fun getAudioProcessors(): Array<AudioProcessor> = processors

    override fun applyPlaybackParameters(playbackParameters: PlaybackParameters): PlaybackParameters {
        sonic.setSpeed(playbackParameters.speed)
        sonic.setPitch(playbackParameters.pitch)
        return playbackParameters
    }

    override fun applySkipSilenceEnabled(skipSilenceEnabled: Boolean): Boolean {
        silenceSkipping.setEnabled(skipSilenceEnabled)
        return skipSilenceEnabled
    }

    override fun getMediaDuration(playoutDuration: Long): Long =
        if (sonic.isActive) sonic.getMediaDuration(playoutDuration) else playoutDuration

    override fun getSkippedOutputFrameCount(): Long = silenceSkipping.skippedFrames
}

/** Passes only the first [queueEndOfStream] after a flush (or reset) on to [delegate]. */
private class EndOfStreamOnce(private val delegate: AudioProcessor) : AudioProcessor {
    private var endQueued = false

    override fun configure(inputAudioFormat: AudioFormat): AudioFormat = delegate.configure(inputAudioFormat)

    override fun isActive(): Boolean = delegate.isActive

    override fun queueInput(inputBuffer: ByteBuffer) = delegate.queueInput(inputBuffer)

    override fun queueEndOfStream() {
        if (endQueued) return
        endQueued = true
        delegate.queueEndOfStream()
    }

    override fun getOutput(): ByteBuffer = delegate.output

    override fun isEnded(): Boolean = delegate.isEnded

    override fun flush(streamMetadata: StreamMetadata) {
        endQueued = false
        delegate.flush(streamMetadata)
    }

    override fun reset() {
        endQueued = false
        delegate.reset()
    }

    override fun getDurationAfterProcessorApplied(durationUs: Long): Long =
        delegate.getDurationAfterProcessorApplied(durationUs)
}
