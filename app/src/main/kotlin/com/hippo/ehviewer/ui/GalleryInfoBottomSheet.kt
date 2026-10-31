package com.hippo.ehviewer.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.plus
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.HowToVote
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ehviewer.core.i18n.R
import com.ehviewer.core.model.GalleryDetail
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.EhEngine
import com.hippo.ehviewer.client.EhUrl
import com.hippo.ehviewer.client.EhUtils
import com.hippo.ehviewer.client.data.GalleryVersionLoader
import com.hippo.ehviewer.client.data.GalleryVersionRef
import com.hippo.ehviewer.client.data.GalleryVersionSeed
import com.hippo.ehviewer.client.parser.GalleryDetailUrlParser
import com.hippo.ehviewer.client.thumbUrl
import com.hippo.ehviewer.util.addTextToClipboard
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal fun GalleryVersionRef.galleryUrl(): String? = if (canRequest) EhUrl.getGalleryDetailUrl(gid, token!!) else null

@Composable
context(_: Context)
fun GalleryInfoBottomSheet(detail: GalleryDetail, onNavigate: (String) -> Unit) {
    val loader = remember(detail.gid, detail.token) {
        GalleryVersionLoader(GalleryVersionSeed.from(detail), fetch = { refs ->
            EhEngine.getGalleryVersionMetadata(refs, GalleryVersionRef(detail.gid, detail.token).galleryUrl())
        }, fetchCandidates = { ref ->
            EhEngine.getGalleryVersionCandidates(ref, GalleryVersionRef(detail.gid, detail.token).galleryUrl())
        })
    }
    val chain by loader.state.collectAsState()
    val scope = rememberCoroutineScope()
    var expanded by remember(loader) { mutableStateOf(false) }
    var expansionJob by remember(loader) { mutableStateOf<Job?>(null) }
    LaunchedEffect(loader) { loader.loadKeys() }
    val viewedRef = GalleryVersionRef(detail.gid, detail.token)
    val parentRef = chain.parent.ref?.let { ref -> chain.nodes[ref.gid]?.ref?.takeIf { it.canRequest } ?: ref }
    val links = GalleryInfoLinks(
        gallery = viewedRef.galleryUrl(),
        thumb = detail.thumbKey?.takeIf(String::isNotBlank)?.let { detail.thumbUrl },
        parent = detail.parent?.takeIf(String::isNotBlank) ?: parentRef?.galleryUrl(),
        torrent = if (viewedRef.canRequest) EhUrl.getTorrentUrl(detail.gid, detail.token) else null,
    )
    val fields = galleryInfoFields(
        detail,
        chain.nodes[detail.gid],
        chain.parent,
        links,
        EhUtils.getCategory(detail.category).takeUnless { detail.category <= 0 || detail.category == EhUtils.UNKNOWN },
    ).associateBy { it.key }
    fun copyValue(text: String?, galleryLink: Boolean = false) {
        text ?: return
        if (galleryLink) Settings.clipboardTextHashCode = text.hashCode()
        addTextToClipboard(text, true)
    }
    fun copyField(field: GalleryInfoField) = copyValue(field.value.raw, field.isGalleryLink)
    val visibleNodes = chain.visibleNodes(expanded)
    val versionColumnWidth = rememberGalleryVersionColumnWidth(visibleNodes.mapNotNull { it.posted }.maxByOrNull(String::length))
    LazyColumn(contentPadding = WindowInsets.systemBars.only(WindowInsetsSides.Bottom).asPaddingValues() + PaddingValues(12.dp)) {
        item("title") {
            Text(stringResource(R.string.gallery_info), Modifier.fillMaxWidth().padding(bottom = 12.dp), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium)
        }
        item("overview") {
            GalleryInfoOverview(fields, ::copyField, links.gallery?.let { { onNavigate(it) } })
        }
        item("details") {
            GalleryInfoCard(R.string.gallery_info_basic, rowGap = 2.dp) {
                val remaining = listOf(
                    GalleryInfoFieldKey.URL,
                    GalleryInfoFieldKey.VISIBLE,
                    GalleryInfoFieldKey.PARENT,
                    GalleryInfoFieldKey.FAVORITED,
                    GalleryInfoFieldKey.FAVORITE_NAME,
                    GalleryInfoFieldKey.THUMB,
                    GalleryInfoFieldKey.TORRENT_COUNT,
                    GalleryInfoFieldKey.TORRENT_URL,
                )
                remaining.forEach { key ->
                    val field = fields.getValue(key)
                    val url = field.value.raw?.takeIf { field.isGalleryLink && GalleryDetailUrlParser.parse(it) != null }
                    GalleryInfoValueRow(
                        stringResource(key.label),
                        field.value,
                        { copyField(field) },
                        url?.let { { onNavigate(it) } },
                        displayText = if (key == GalleryInfoFieldKey.PARENT) field.value.raw?.trimEnd('/') else null,
                    )
                }
            }
        }
        item("versions_header") {
            GalleryVersionHeader(
                chain,
                expanded,
                onToggle = {
                    expanded = !expanded
                    if (expanded) expansionJob = scope.launch { loader.expand() } else expansionJob?.cancel()
                },
                onRetry = {
                    val retryTrace = chain.needsFullRetry(expanded)
                    if (retryTrace) expanded = true
                    expansionJob = scope.launch { loader.retry(retryTrace) }
                },
            )
        }
        itemsIndexed(visibleNodes, key = { _, node -> "version_${node.ref.gid}" }) { index, node ->
            GalleryVersionTimelineNode(
                node,
                chain.roles(node.ref.gid),
                columnWidth = versionColumnWidth,
                first = index == 0,
                last = index == visibleNodes.lastIndex,
                solidBefore = index > 0 && chain.isDirectParent(visibleNodes[index - 1].ref.gid, node.ref.gid),
                solidAfter = index < visibleNodes.lastIndex && chain.isDirectParent(node.ref.gid, visibleNodes[index + 1].ref.gid),
                onNavigate = onNavigate,
                onCopy = { copyValue(it) },
            )
        }
        item("versions_footer") { GalleryVersionFooter() }
    }
}

@Composable
private fun GalleryInfoOverview(fields: Map<GalleryInfoFieldKey, GalleryInfoField>, onCopy: (GalleryInfoField) -> Unit, onNavigate: (() -> Unit)?) {
    GalleryInfoCard {
        for (key in listOf(GalleryInfoFieldKey.TITLE, GalleryInfoFieldKey.TITLE_JPN)) {
            GalleryInfoTitle(stringResource(key.label), fields.getValue(key).value, onNavigate, secondary = key == GalleryInfoFieldKey.TITLE_JPN)
        }
        val rows = listOf(
            listOf(GalleryInfoFieldKey.CATEGORY to Icons.Default.Category, GalleryInfoFieldKey.LANGUAGE to Icons.Default.Language, GalleryInfoFieldKey.RATING to Icons.Default.Star),
            listOf(GalleryInfoFieldKey.SIZE to Icons.Default.DataUsage, GalleryInfoFieldKey.PAGES to Icons.AutoMirrored.Default.MenuBook, GalleryInfoFieldKey.FAVORITE_COUNT to Icons.Default.Favorite),
            listOf(GalleryInfoFieldKey.RATING_COUNT to Icons.Default.HowToVote, GalleryInfoFieldKey.POSTED to Icons.Default.Schedule, GalleryInfoFieldKey.UPLOADER to Icons.Default.Person),
        )
        GalleryInfoGrid(columns = 3) {
            for ((key, icon) in rows.flatten()) {
                val field = fields.getValue(key)
                val value = if (key == GalleryInfoFieldKey.CATEGORY) field.value.copy(raw = field.value.raw?.uppercase(Locale.ROOT)) else field.value
                GalleryInfoCompactValue(stringResource(key.label), value, { onCopy(field) }, icon = icon)
            }
        }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            for (key in listOf(GalleryInfoFieldKey.GID, GalleryInfoFieldKey.TOKEN)) {
                val field = fields.getValue(key)
                GalleryInfoCompactValue(stringResource(key.label), field.value, { onCopy(field) }, Modifier.weight(1f))
            }
        }
    }
}
