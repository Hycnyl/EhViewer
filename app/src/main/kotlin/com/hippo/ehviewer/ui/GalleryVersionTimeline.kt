package com.hippo.ehviewer.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Schedule
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
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.gallery_info_versions), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (expanded || chain.hasMiddleVersions == true) {
                    TextButton(onClick = onToggle, enabled = expanded || !chain.isLoading) {
                        Text(stringResource(if (expanded) R.string.gallery_version_collapse else R.string.gallery_version_expand), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            val roles = listOf(
                GalleryVersionRole.FIRST to chain.first,
                GalleryVersionRole.PARENT to chain.parent,
                GalleryVersionRole.VIEWED to GalleryVersionLink.to(chain.nodes.getValue(chain.viewedGid).ref),
                GalleryVersionRole.LATEST to chain.latest,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                roles.forEach { (role, link) ->
                    val value = link.ref?.gid?.takeIf { it > 0 }?.toString() ?: if (link == GalleryVersionLink.None) "None" else "Null"
                    Text(versionRoleName(role) + ": " + value, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (chain.isLoading) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.gallery_version_loading), style = MaterialTheme.typography.bodySmall)
                }
            } else if (!chain.isComplete) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.gallery_version_incomplete), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
                }
            }
        }
    }
}

@Composable
internal fun GalleryVersionFooter() {
    Surface(Modifier.fillMaxWidth().padding(bottom = 8.dp).height(12.dp), shape = MaterialTheme.shapes.large.copy(topStart = CornerSize(0.dp), topEnd = CornerSize(0.dp)), color = MaterialTheme.colorScheme.surfaceContainerLow) {}
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
    onCopy: (String?) -> Unit,
) {
    val isViewed = GalleryVersionRole.VIEWED in roles
    val url = node.ref.galleryUrl()
    val lineColor = MaterialTheme.colorScheme.outlineVariant
    val primary = MaterialTheme.colorScheme.primary
    val background = MaterialTheme.colorScheme.surfaceContainerLow
    Surface(Modifier.fillMaxWidth(), color = background) {
        Box(Modifier.padding(horizontal = 12.dp)) {
            Canvas(Modifier.matchParentSize()) {
                val x = 12.dp.toPx()
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
            if (isViewed) Icon(Icons.Default.LocationOn, contentDescription = stringResource(R.string.gallery_version_viewed), tint = primary, modifier = Modifier.align(Alignment.TopStart).padding(top = 8.dp).size(24.dp))
            Row(Modifier.fillMaxWidth()) {
                Spacer(Modifier.width(24.dp))
                Surface(
                    modifier = Modifier.weight(1f).padding(start = 6.dp, bottom = 8.dp),
                    shape = MaterialTheme.shapes.medium,
                    color = if (isViewed) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                    border = if (isViewed) BorderStroke(1.dp, primary) else null,
                ) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            roles.forEach { role ->
                                Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.secondaryContainer) {
                                    Text(versionRoleName(role), Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            val expunged = when (node.expunged) {
                                true -> "True"
                                false -> "False"
                                null -> "Null"
                            }
                            val expungedLabel = stringResource(R.string.gallery_version_expunged)
                            Text(
                                expungedLabel + ": " + expunged,
                                Modifier.clickable(enabled = node.expunged != null, onClickLabel = stringResource(R.string.gallery_info_copy_field, expungedLabel)) { onCopy(expunged) }.padding(horizontal = 4.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        GalleryInfoTitle(stringResource(R.string.key_title), GalleryInfoValue.text(node.title), url?.let { { onNavigate(it) } })
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val gid = GalleryInfoValue(node.ref.gid.takeIf { it > 0 }?.toString())
                            GalleryInfoCompactValue(stringResource(R.string.key_gid), gid, { onCopy(gid.raw) }, Modifier.weight(0.8f))
                            val posted = GalleryInfoValue.text(node.posted)
                            GalleryInfoCompactValue(stringResource(R.string.key_posted), posted, { onCopy(posted.raw) }, Modifier.weight(1.5f), Icons.Default.Schedule)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val pages = GalleryInfoValue.number(node.pages)
                            GalleryInfoCompactValue(stringResource(R.string.key_pages), pages, { onCopy(pages.raw) }, Modifier.weight(1f), Icons.AutoMirrored.Default.MenuBook)
                            val size = node.sizeText?.let(GalleryInfoValue::text) ?: GalleryInfoValue.bytes(node.fileSizeBytes)
                            GalleryInfoCompactValue(stringResource(R.string.key_size), size, { onCopy(size.raw) }, Modifier.weight(1f), Icons.Default.DataUsage)
                        }
                    }
                }
            }
        }
    }
}
