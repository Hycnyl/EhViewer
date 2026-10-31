package com.hippo.ehviewer.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.plus
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.HowToVote
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Badge
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal fun GalleryVersionRef.galleryUrl(): String? = if (canRequest) EhUrl.getGalleryDetailUrl(gid, token!!) else null

@Composable
context(_: Context)
fun GalleryInfoBottomSheet(detail: GalleryDetail, onNavigate: (String) -> Unit) {
    val loader = remember(detail.gid, detail.token) {
        GalleryVersionLoader(GalleryVersionSeed.from(detail), fetch = { refs ->
            EhEngine.getGalleryVersionMetadata(refs, GalleryVersionRef(detail.gid, detail.token).galleryUrl())
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
    LazyColumn(contentPadding = WindowInsets.systemBars.only(WindowInsetsSides.Bottom).asPaddingValues() + PaddingValues(16.dp)) {
        item("title") {
            Text(stringResource(R.string.gallery_info), Modifier.fillMaxWidth().padding(bottom = 20.dp), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleLarge)
        }
        item("overview") {
            GalleryInfoCard(R.string.gallery_info_overview) {
                for (key in listOf(GalleryInfoFieldKey.TITLE, GalleryInfoFieldKey.TITLE_JPN)) {
                    val field = fields.getValue(key)
                    GalleryInfoValueRow(
                        stringResource(key.label),
                        field.value,
                        { copyField(field) },
                        valueStyle = if (key == GalleryInfoFieldKey.TITLE) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.bodyMedium,
                    )
                }
                val category = fields.getValue(GalleryInfoFieldKey.CATEGORY)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(category.key.label), style = MaterialTheme.typography.labelMedium)
                    Badge(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
                        Text(category.value.text, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                    Spacer(Modifier.weight(1f))
                    GalleryInfoCopyButton(stringResource(category.key.label), category.value.raw != null) { copyField(category) }
                }
            }
        }
        item("statistics") { GalleryCoreMetrics(fields, ::copyField) }
        item("versions_header") {
            GalleryVersionHeader(
                chain,
                expanded,
                onToggle = {
                    expanded = !expanded
                    if (expanded) expansionJob = scope.launch { loader.expand() } else expansionJob?.cancel()
                },
                onRetry = {
                    val retryTrace = expanded || (chain.expansionAttempted && !chain.traceComplete && chain.hasMiddleVersions == true)
                    if (retryTrace) expanded = true
                    expansionJob = scope.launch { if (retryTrace) loader.expand(retry = true) else loader.loadKeys(retry = true) }
                },
            )
        }
        itemsIndexed(visibleNodes, key = { _, node -> "version_${node.ref.gid}" }) { index, node ->
            GalleryVersionTimelineNode(
                node,
                chain.roles(node.ref.gid),
                first = index == 0,
                last = index == visibleNodes.lastIndex,
                solidBefore = index > 0 && chain.isDirectParent(visibleNodes[index - 1].ref.gid, node.ref.gid),
                solidAfter = index < visibleNodes.lastIndex && chain.isDirectParent(node.ref.gid, visibleNodes[index + 1].ref.gid),
                onNavigate = onNavigate,
                onCopy = { text, isUrl -> copyValue(text, isUrl) },
            )
        }
        item("versions_footer") { GalleryVersionFooter() }
        item("basic") {
            GalleryInfoCard(R.string.gallery_info_basic) {
                val basic = GalleryInfoFieldKey.entries.filter { it.group == GalleryInfoGroup.BASIC }
                basic.forEachIndexed { index, key ->
                    val field = fields.getValue(key)
                    val url = field.value.raw?.takeIf { field.isGalleryLink && GalleryDetailUrlParser.parse(it) != null }
                    GalleryInfoValueRow(stringResource(key.label), field.value, { copyField(field) }, basicIcon(key), url?.let { { onNavigate(it) } })
                    if (index != basic.lastIndex) HorizontalDivider()
                }
            }
        }
        item("favorites") {
            GalleryInfoCard(R.string.gallery_info_favorites) {
                GalleryInfoFieldKey.entries.filter { it.group == GalleryInfoGroup.FAVORITES }.forEach { key ->
                    val field = fields.getValue(key)
                    GalleryInfoValueRow(stringResource(key.label), field.value, { copyField(field) })
                }
            }
        }
        item("resources") {
            GalleryInfoCard(R.string.gallery_info_resources, rowGap = 4.dp) {
                val resources = GalleryInfoFieldKey.entries.filter { it.group == GalleryInfoGroup.RESOURCES }
                resources.forEachIndexed { index, key ->
                    val field = fields.getValue(key)
                    val url = field.value.raw?.takeIf { field.isGalleryLink && GalleryDetailUrlParser.parse(it) != null }
                    GalleryInfoIdentifierRow(stringResource(key.label), field.value, { copyField(field) }, onNavigate = url?.let { { onNavigate(it) } })
                    if (index != resources.lastIndex) HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun GalleryCoreMetrics(fields: Map<GalleryInfoFieldKey, GalleryInfoField>, onCopy: (GalleryInfoField) -> Unit) {
    GalleryInfoCard(R.string.gallery_info_statistics) {
        val metrics = listOf(GalleryInfoFieldKey.PAGES, GalleryInfoFieldKey.SIZE, GalleryInfoFieldKey.FAVORITE_COUNT, GalleryInfoFieldKey.RATING, GalleryInfoFieldKey.RATING_COUNT)
        BoxWithConstraints {
            val columns = if (maxWidth >= 560.dp) 3 else 2
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                metrics.chunked(columns).forEach { row ->
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { key ->
                            val field = fields.getValue(key)
                            Surface(Modifier.weight(1f).fillMaxHeight(), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                                Column(Modifier.padding(12.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(metricIcon(key), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                        Spacer(Modifier.weight(1f))
                                        GalleryInfoCopyButton(stringResource(key.label), field.value.raw != null) { onCopy(field) }
                                    }
                                    Text(field.value.display(), style = MaterialTheme.typography.headlineSmall)
                                    Text(stringResource(key.label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

private fun metricIcon(key: GalleryInfoFieldKey) = when (key) {
    GalleryInfoFieldKey.PAGES -> Icons.AutoMirrored.Default.MenuBook
    GalleryInfoFieldKey.SIZE -> Icons.Default.DataUsage
    GalleryInfoFieldKey.FAVORITE_COUNT -> Icons.Default.Favorite
    GalleryInfoFieldKey.RATING -> Icons.Default.Star
    else -> Icons.Default.HowToVote
}

private fun basicIcon(key: GalleryInfoFieldKey) = when (key) {
    GalleryInfoFieldKey.UPLOADER -> Icons.Default.Person
    GalleryInfoFieldKey.POSTED -> Icons.Default.Schedule
    GalleryInfoFieldKey.PARENT -> Icons.Default.AccountTree
    GalleryInfoFieldKey.VISIBLE -> Icons.Default.Visibility
    else -> Icons.Default.Language
}
