package com.hippo.ehviewer.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ehviewer.core.i18n.R
import com.hippo.ehviewer.client.data.GalleryVersionChain
import com.hippo.ehviewer.client.data.GalleryVersionLink
import com.hippo.ehviewer.client.data.GalleryVersionNode
import com.hippo.ehviewer.client.data.GalleryVersionRole

@Composable
private fun versionRoleName(role: GalleryVersionRole) = stringResource(
    when (role) {
        GalleryVersionRole.FIRST -> R.string.gallery_version_first
        GalleryVersionRole.PARENT -> R.string.key_parent
        GalleryVersionRole.VIEWED -> R.string.gallery_version_viewed
        GalleryVersionRole.LATEST -> R.string.gallery_version_latest
    },
)

@Composable
internal fun GalleryVersionHeader(chain: GalleryVersionChain, expanded: Boolean, onToggle: () -> Unit, onRetry: () -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large.copy(bottomStart = CornerSize(0.dp), bottomEnd = CornerSize(0.dp)), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.gallery_info_versions), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val roles = listOf(
                GalleryVersionRole.FIRST to chain.first,
                GalleryVersionRole.PARENT to chain.parent,
                GalleryVersionRole.VIEWED to GalleryVersionLink.to(chain.nodes.getValue(chain.viewedGid).ref),
                GalleryVersionRole.LATEST to chain.latest,
            )
            roles.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { (role, link) ->
                        Column(Modifier.weight(1f)) {
                            Text(versionRoleName(role), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(link.ref?.gid?.takeIf { it > 0 }?.toString() ?: if (link == GalleryVersionLink.None) "None" else "Null", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            Box(Modifier.fillMaxWidth().heightIn(min = 48.dp), contentAlignment = Alignment.CenterStart) {
                if (chain.isLoading) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.gallery_version_loading), style = MaterialTheme.typography.bodySmall)
                    }
                } else if (!chain.isComplete) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.gallery_version_incomplete), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
                    }
                }
            }
            if (expanded || chain.hasMiddleVersions == true) {
                TextButton(onClick = onToggle, enabled = expanded || !chain.isLoading) {
                    Text(stringResource(if (expanded) R.string.gallery_version_collapse else R.string.gallery_version_expand))
                }
            }
        }
    }
}

@Composable
internal fun GalleryVersionFooter() {
    Surface(Modifier.fillMaxWidth().padding(bottom = 12.dp).height(24.dp), shape = MaterialTheme.shapes.large.copy(topStart = CornerSize(0.dp), topEnd = CornerSize(0.dp)), color = MaterialTheme.colorScheme.surfaceContainerLow) {}
}

@Composable
internal fun GalleryVersionTimelineNode(
    node: GalleryVersionNode,
    roles: List<GalleryVersionRole>,
    first: Boolean,
    last: Boolean,
    solidBefore: Boolean,
    solidAfter: Boolean,
    onNavigate: (String) -> Unit,
    onCopy: (String?, Boolean) -> Unit,
) {
    val isViewed = GalleryVersionRole.VIEWED in roles
    val url = node.ref.galleryUrl()
    val lineColor = MaterialTheme.colorScheme.outlineVariant
    val primary = MaterialTheme.colorScheme.primary
    val background = MaterialTheme.colorScheme.surfaceContainerLow
    Surface(Modifier.fillMaxWidth(), color = background) {
        Row(Modifier.padding(horizontal = 16.dp).height(IntrinsicSize.Min)) {
            Box(Modifier.width(28.dp).fillMaxHeight()) {
                Canvas(Modifier.fillMaxSize()) {
                    val x = size.width / 2
                    val y = 20.dp.toPx()
                    fun line(start: Float, end: Float, solid: Boolean) {
                        drawPath(
                            Path().apply {
                                moveTo(x, start)
                                lineTo(x, end)
                            },
                            lineColor,
                            style = Stroke(2.dp.toPx(), pathEffect = if (solid) null else PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))),
                        )
                    }
                    if (!first) line(0f, y, solidBefore)
                    if (!last) line(y, size.height, solidAfter)
                    if (isViewed) drawCircle(background, 12.dp.toPx(), Offset(x, y)) else drawCircle(primary, 4.dp.toPx(), Offset(x, y))
                }
                if (isViewed) Icon(Icons.Default.LocationOn, contentDescription = stringResource(R.string.gallery_version_viewed), tint = primary, modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp).size(24.dp))
            }
            Surface(
                onClick = { url?.let(onNavigate) },
                enabled = url != null,
                modifier = Modifier.weight(1f).padding(start = 8.dp, bottom = 12.dp),
                shape = MaterialTheme.shapes.medium,
                color = if (isViewed) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                border = if (isViewed) BorderStroke(1.dp, primary) else null,
            ) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            roles.forEach { role ->
                                Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.secondaryContainer) {
                                    Text(versionRoleName(role), Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                        GalleryInfoCopyButton(stringResource(R.string.key_url), url != null) { onCopy(url, true) }
                    }
                    val values = listOf(
                        R.string.key_title to GalleryInfoValue.text(node.title),
                        R.string.key_gid to GalleryInfoValue(node.ref.gid.takeIf { it > 0 }?.toString()),
                        R.string.key_posted to GalleryInfoValue.text(node.posted),
                        R.string.key_pages to GalleryInfoValue.number(node.pages),
                        R.string.key_size to (node.sizeText?.let(GalleryInfoValue::text) ?: GalleryInfoValue.bytes(node.fileSizeBytes)),
                    )
                    values.forEach { (label, value) ->
                        GalleryInfoValueRow(stringResource(label), value, { onCopy(value.raw, false) }, valueStyle = if (label == R.string.key_title) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium)
                    }
                    val expunged = when (node.expunged) {
                        true -> "True"
                        false -> "False"
                        null -> "Null"
                    }
                    Text(stringResource(R.string.gallery_version_expunged) + ": " + expunged, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
