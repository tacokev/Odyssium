package com.skintclub.odyssey.ui.screens

import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.skintclub.odyssey.LocalPlayerAwareWindowInsets
import com.skintclub.odyssey.LocalPlayerConnection
import com.skintclub.odyssey.R
import com.skintclub.odyssey.club.ClubClient
import com.skintclub.odyssey.club.ClubContentDetails
import com.skintclub.odyssey.club.ClubLink
import com.skintclub.odyssey.club.parseClubLink
import com.skintclub.odyssey.playback.queues.OdysseyQueue
import com.skintclub.odyssey.playback.queues.odysseyEpisodeToMediaItem
import com.skintclub.odyssey.viewmodels.OdysseyViewModel
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.CancellationException

const val ODYSSEY_CONTENT_ROUTE = "odyssey_content/{contentId}"

fun odysseyContentRoute(contentId: String) = "odyssey_content/${Uri.encode(contentId)}"

/**
 * The details page for content that isn't an album episode - a podcast, an article (a blog
 * post, "Coming Soon"...), or an episode not filed in an album yet - laid out like the episode
 * page: cover art, title, what it is and when, then Play for audio or the article itself.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OdysseyContentDetailsScreen(
    navController: NavController,
    viewModel: OdysseyViewModel,
    contentId: String,
) {
    val context = LocalContext.current
    val playerConnection = LocalPlayerConnection.current
    val albums by viewModel.albums.collectAsState()
    val clubClient = remember { ClubClient(context) }
    // Shown straight away from what's cached, while the full details load.
    val cached = remember(contentId) { viewModel.cachedContent(contentId) }
    var details by remember(contentId) { mutableStateOf(clubClient.cachedContentDetails(contentId)) }
    var failed by remember(contentId) { mutableStateOf(false) }
    var showTitleMenu by remember { mutableStateOf(false) }
    var responseToShow by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(contentId) {
        if (details != null) return@LaunchedEffect
        details = try {
            clubClient.getContentDetails(contentId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
        failed = details == null
    }

    responseToShow?.let { raw ->
        ContentResponseDialog(raw, onDismiss = { responseToShow = null })
    }

    val loaded = details
    val type = loaded?.type ?: cached?.type
    val subtype = loaded?.subtype ?: cached?.subtype
    val title = loaded?.shortName?.takeIf { it.isNotBlank() } ?: loaded?.name?.takeIf { it.isNotBlank() }
        ?: cached?.name?.takeIf { it.isNotBlank() } ?: cached?.shortName.orEmpty()
    val imageUrl = listOf(loaded?.thumbnailLarge, loaded?.thumbnailMedium, cached?.thumbnailMedium, loaded?.thumbnailSmall, cached?.thumbnailSmall)
        .firstOrNull { !it.isNullOrBlank() }
    val description = (loaded?.description ?: cached?.description)?.takeIf { it.isNotBlank() }
    val isAudio = type == "Audio"
    val isArticle = type == "Article"
    val listing = remember(contentId, albums) { viewModel.listingGroupingOf(contentId) }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            DetailsPoster(imageUrl)

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Bottom)),
            ) {
                Box {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.combinedClickable(
                            onClick = {},
                            onLongClick = { showTitleMenu = true },
                        ),
                    )
                    DropdownMenu(
                        expanded = showTitleMenu,
                        onDismissRequest = { showTitleMenu = false },
                    ) {
                        val raw = loaded?.rawJson
                        DropdownMenuItem(
                            text = { Text("View response") },
                            enabled = raw != null,
                            onClick = {
                                showTitleMenu = false
                                responseToShow = raw
                            },
                        )
                    }
                }

                Spacer(Modifier.height(6.dp))

                // What it is (and for an article, when), then the grouping it's listed under (a
                // podcast's year...). Audio's air date is with its other details below.
                val date = if (isArticle) loaded?.lastPublishedDate ?: cached?.lastPublishedDate else null
                val facts = listOfNotNull(kindLabel(type, subtype), formatDate(date))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = facts.joinToString("  •  ") + if (facts.isNotEmpty() && listing != null) "  •  " else "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (listing != null) {
                        Text(
                            text = listing.name,
                            style = MaterialTheme.typography.bodyMedium,
                            textDecoration = TextDecoration.Underline,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            modifier = Modifier.clickable {
                                navController.navigate("odyssey_grouping/${Uri.encode(listing.id)}")
                            },
                        )
                    }
                }

                if (isAudio) {
                    Spacer(Modifier.height(20.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        // Played in the list it's in (an album, a podcast's year...), or alone.
                        fun queue(): Pair<List<Map<String, Any>>, Int>? =
                            viewModel.queueFor(contentId)
                                ?: loaded?.let { listOf(contentEpisodeMap(it, cached?.albumName)) to 0 }
                        Button(
                            onClick = {
                                val (episodes, index) = queue() ?: return@Button
                                playerConnection?.playQueue(OdysseyQueue(context, episodes, index))
                            },
                            contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.play),
                                contentDescription = null,
                                modifier = Modifier.size(ButtonDefaults.IconSize),
                            )
                            Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                            Text(stringResource(R.string.play))
                        }
                        OutlinedButton(
                            onClick = {
                                val (episodes, index) = queue() ?: return@OutlinedButton
                                playerConnection?.playNext(odysseyEpisodeToMediaItem(context, episodes[index]))
                            },
                            contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.playlist_play),
                                contentDescription = null,
                                modifier = Modifier.size(ButtonDefaults.IconSize),
                            )
                            Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                            Text(stringResource(R.string.play_next))
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))

                if (description != null) {
                    Text(text = description, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(24.dp))
                }

                when {
                    loaded == null && !failed -> {
                        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.height(24.dp))
                    }
                    loaded == null -> {
                        Text(
                            text = "Couldn't load this from the club.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(24.dp))
                    }
                    isArticle -> {
                        ArticleBody(
                            html = loaded.contentBody?.takeIf { it.isNotBlank() } ?: loaded.contentBodyHtml.orEmpty(),
                            onClubLink = viewModel::openClubLink,
                        )
                        Spacer(Modifier.height(24.dp))
                    }
                    else -> {
                        // Air date, themes, characters, crew, devotional, recommendations - from
                        // the same details, already loaded.
                        OdysseyEpisodeExtraDetails(navController, contentId, albums)
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
        }

        DetailsBackButton(onClick = navController::navigateUp)
    }
}

/** "Podcast", "Blog", "What's New"... */
private fun kindLabel(type: String?, subtype: String?): String? = when {
    subtype == "Whats New" -> "What's New"
    !subtype.isNullOrBlank() -> subtype
    else -> type?.takeIf { it.isNotBlank() }
}

/** "2026-11-11T00:00:00.000Z" -> "November 11, 2026". Only the date part, as the club stores
 * midnight US time. */
private fun formatDate(date: String?): String? =
    date?.take(10)
        ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        ?.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG))

/** Content that's in no cached list, as a playable episode. It has no CDN path, so it plays
 * through a content request (see odysseyEpisodeToMediaItem). */
private fun contentEpisodeMap(details: ClubContentDetails, albumName: String?): Map<String, Any> = buildMap {
    put("id", details.id)
    put("name", details.shortName ?: details.name.orEmpty())
    put("album_name", albumName.orEmpty())
    put("download_url", "")
    put("image_small", details.thumbnailSmall.orEmpty())
    put("image_medium", details.thumbnailMedium.orEmpty())
    put("image_large", details.thumbnailLarge ?: details.thumbnailMedium.orEmpty())
    put("is_bookmarked", false)
    details.shortName?.let { put("short_name", it) }
    details.description?.let { put("description", it) }
}

/** An image, with the link around it if it's in one (its own <a>, with nothing else in it). */
private val imgTagRegex = Regex(
    """(?:<a\b[^>]*\bhref\s*=\s*["']([^"']+)["'][^>]*>\s*)?<img\b[^>]*>(?:\s*</img>)?(?:\s*</a>)?""",
    RegexOption.IGNORE_CASE,
)
private val imgSrcRegex = Regex("""\bsrc\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)

/** Inline colors and sizes are for the club's white page; here they'd be black on black. */
private val styleAttributeRegex = Regex("""\s(?:style|color|bgcolor|face|size)\s*=\s*("[^"]*"|'[^']*')""", RegexOption.IGNORE_CASE)
private val tagRegex = Regex("""<[^>]+>""")

private sealed interface ArticleBlock {
    data class Text(val html: String) : ArticleBlock
    /** [link]: where tapping it goes (a printable PDF, say), if it's a link. */
    data class Image(val url: String, val link: String?) : ArticleBlock
}

/** [html] as runs of text and the images between them, in order, without the club page's own
 * colors and font sizes. Empty runs (just tags and spaces) are dropped. */
private fun articleBlocks(html: String): List<ArticleBlock> {
    val clean = styleAttributeRegex.replace(html, "")
    val blocks = mutableListOf<ArticleBlock>()
    fun addText(part: String) {
        val visible = tagRegex.replace(part, "").replace("&nbsp;", " ").trim()
        if (visible.isNotEmpty()) blocks += ArticleBlock.Text(part)
    }
    var last = 0
    imgTagRegex.findAll(clean).forEach { match ->
        addText(clean.substring(last, match.range.first))
        val link = match.groupValues[1].takeIf { it.isNotBlank() }
        imgSrcRegex.find(match.value)?.groupValues?.get(1)?.let { blocks += ArticleBlock.Image(it, link) }
        last = match.range.last + 1
    }
    addText(clean.substring(last))
    return blocks
}

/** An article's body: its text, with working links, and its pictures full width. A link to
 * club content or a grouping opens it in the app; anything else, in the browser. */
@Composable
private fun ArticleBody(html: String, onClubLink: (ClubLink) -> Unit) {
    val uriHandler = LocalUriHandler.current
    val linkColor = MaterialTheme.colorScheme.primary
    val blocks = remember(html) { articleBlocks(html) }
    fun open(url: String) {
        val absolute = if (url.startsWith("/")) "https://app.adventuresinodyssey.com$url" else url
        parseClubLink(absolute)?.let(onClubLink) ?: runCatching { uriHandler.openUri(absolute) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        blocks.forEach { block ->
            when (block) {
                is ArticleBlock.Image -> AsyncImage(
                    model = block.url,
                    contentDescription = null,
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .then(if (block.link != null) Modifier.clickable { open(block.link) } else Modifier),
                )
                is ArticleBlock.Text -> {
                    val text = remember(block.html, linkColor) {
                        AnnotatedString.fromHtml(
                            htmlString = block.html,
                            linkStyles = TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)),
                            linkInteractionListener = { link ->
                                (link as? LinkAnnotation.Url)?.url?.let(::open)
                            },
                        ).trimEnd()
                    }
                    Text(text = text, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

private fun AnnotatedString.trimEnd(): AnnotatedString {
    var end = length
    while (end > 0 && this[end - 1].isWhitespace()) end--
    return subSequence(0, end)
}
