package com.hippo.ehviewer.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.plus
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ehviewer.core.i18n.R
import com.ehviewer.core.model.GalleryDetail
import com.hippo.ehviewer.client.EhEngine
import com.hippo.ehviewer.client.data.GalleryNewerVersionLoader
import com.hippo.ehviewer.client.data.GalleryVersionRole
import com.hippo.ehviewer.client.data.GalleryVersionSeed
import com.hippo.ehviewer.util.addTextToClipboard
import kotlinx.coroutines.launch

@Composable
context(_: Context)
fun GalleryNewerVersionsBottomSheet(detail: GalleryDetail, onNavigate: (String) -> Unit) {
    val loader = remember(detail.gid, detail.token) {
        val seed = GalleryVersionSeed.from(detail)
        GalleryNewerVersionLoader(seed.viewed, seed.newer, fetch = { refs ->
            EhEngine.getGalleryVersionMetadata(refs, seed.viewed.ref.galleryUrl())
        })
    }
    val state by loader.state.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(loader) { loader.load() }
    val visible = state.orderedGids.mapNotNull(state.nodes::get)
    val width = rememberGalleryVersionColumnWidth(visible.mapNotNull { it.posted }.maxByOrNull(String::length))
    LazyColumn(contentPadding = WindowInsets.systemBars.only(WindowInsetsSides.Bottom).asPaddingValues() + PaddingValues(12.dp)) {
        item("title") {
            Text(stringResource(R.string.gallery_newer_versions), Modifier.fillMaxWidth().padding(bottom = 12.dp), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium)
        }
        item("status") {
            Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large.copy(bottomStart = CornerSize(0.dp), bottomEnd = CornerSize(0.dp)), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                if (state.isLoading) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.gallery_version_loading), style = MaterialTheme.typography.bodySmall)
                    }
                } else if (!state.isComplete || state.diagnostics.issues.isNotEmpty()) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(if (state.isComplete) R.string.gallery_version_data_issue else R.string.gallery_newer_versions_incomplete), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { scope.launch { loader.load(retry = true) } }) { Text(stringResource(R.string.action_retry)) }
                        }
                        GalleryVersionDiagnosticsContent(state.diagnostics)
                    }
                } else {
                    Spacer(Modifier.size(8.dp))
                }
            }
        }
        itemsIndexed(visible, key = { _, node -> "newer_" + node.ref.gid }) { index, node ->
            val roles = buildList {
                if (node.ref.gid == state.current.ref.gid) {
                    add(GalleryVersionRole.VIEWED)
                } else if (node.ref.gid == state.latestGid) {
                    add(GalleryVersionRole.LATEST)
                }
            }
            GalleryVersionTimelineNode(
                node = node,
                roles = roles,
                columnWidth = width,
                first = index == 0,
                last = index == visible.lastIndex,
                solidBefore = index > 0 && state.isDirectParent(visible[index - 1].ref.gid, node.ref.gid),
                solidAfter = index < visible.lastIndex && state.isDirectParent(node.ref.gid, visible[index + 1].ref.gid),
                onNavigate = onNavigate,
                onCopy = { value -> value?.let { addTextToClipboard(it, true) } },
                navigateOnCardClick = true,
            )
        }
        item("footer") { GalleryVersionFooter() }
    }
}
