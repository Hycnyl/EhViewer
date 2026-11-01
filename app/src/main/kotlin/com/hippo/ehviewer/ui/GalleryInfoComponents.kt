package com.hippo.ehviewer.ui

import android.text.format.Formatter
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ehviewer.core.i18n.R
import java.text.NumberFormat

@Composable
internal fun GalleryInfoValue.display(): String {
    val ctx = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    return remember(raw, absent, format, locale) {
        val value = raw ?: return@remember text
        when (format) {
            GalleryInfoFormat.TEXT -> value
            GalleryInfoFormat.INTEGER -> value.toLongOrNull()?.let { NumberFormat.getIntegerInstance(locale).format(it) } ?: value
            GalleryInfoFormat.DECIMAL -> value.toDoubleOrNull()?.let { NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 2 }.format(it) } ?: value
            GalleryInfoFormat.BYTES -> value.toLongOrNull()?.let { Formatter.formatFileSize(ctx, it) } ?: value
        }
    }
}

@Composable
internal fun GalleryInfoCard(@StringRes title: Int? = null, rowGap: Dp = 6.dp, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth().padding(bottom = 10.dp), shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(rowGap)) {
            if (title != null) Text(stringResource(title), Modifier.padding(bottom = 4.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}

/** Measure the tallest value once, then give every cell the same width and height. */
@Composable
internal fun GalleryInfoGrid(columns: Int, columnGap: Dp = 6.dp, content: @Composable () -> Unit) {
    Layout(content, Modifier.fillMaxWidth()) { measurables, constraints ->
        val gap = columnGap.roundToPx()
        val rowGap = 6.dp.roundToPx()
        val cellWidth = ((constraints.maxWidth - gap * (columns - 1)) / columns).coerceAtLeast(0)
        val cellHeight = measurables.maxOfOrNull { it.minIntrinsicHeight(cellWidth) } ?: 0
        val rows = (measurables.size + columns - 1) / columns
        val placeables = measurables.map { it.measure(Constraints.fixed(cellWidth, cellHeight)) }
        layout(constraints.maxWidth, rows * cellHeight + (rows - 1).coerceAtLeast(0) * rowGap) {
            placeables.forEachIndexed { index, placeable ->
                placeable.placeRelative((index % columns) * (cellWidth + gap), (index / columns) * (cellHeight + rowGap))
            }
        }
    }
}

/** Read-only fields consume taps so a clickable version card cannot open underneath them. */
internal fun Modifier.consumeGalleryInfoTaps() = pointerInput(Unit) {
    detectTapGestures(onTap = {})
}

/** Current-gallery titles use ellipsis; timeline titles retain their scrollable viewport. */
@Composable
internal fun GalleryInfoTitle(
    label: String,
    value: GalleryInfoValue,
    onClick: (() -> Unit)?,
    secondary: Boolean = false,
    scrollable: Boolean = true,
    onClickLabel: String? = null,
) {
    val style = if (secondary) MaterialTheme.typography.bodySmall else MaterialTheme.typography.titleSmall
    val maxHeight = with(LocalDensity.current) { style.lineHeight.toDp() * 2 }
    Text(
        value.text,
        modifier = Modifier.fillMaxWidth()
            .then(if (scrollable) Modifier.heightIn(max = maxHeight).verticalScroll(rememberScrollState()) else Modifier)
            .then(
                if (onClick != null) {
                    Modifier.clickable(onClickLabel = onClickLabel ?: stringResource(R.string.gallery_list_search_bar_open_gallery), onClick = onClick)
                } else {
                    Modifier.consumeGalleryInfoTaps()
                },
            )
            .semantics { contentDescription = "$label: ${value.text}" },
        style = style,
        maxLines = if (scrollable) Int.MAX_VALUE else 2,
        overflow = if (scrollable) TextOverflow.Clip else TextOverflow.Ellipsis,
        color = if (secondary) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
internal fun GalleryInfoCompactValue(
    label: String,
    value: GalleryInfoValue,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    onClickLabel: String? = null,
) {
    val text = value.display()
    Row(
        modifier.clip(MaterialTheme.shapes.extraSmall)
            .then(
                if (onClick != null && value.raw != null) {
                    Modifier.clickable(onClickLabel = onClickLabel ?: stringResource(R.string.gallery_info_copy_field, label), onClick = onClick)
                } else {
                    Modifier.consumeGalleryInfoTaps()
                },
            )
            .semantics(mergeDescendants = true) { contentDescription = "$label: $text" }
            .heightIn(min = 32.dp).padding(horizontal = 2.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(text, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun GalleryInfoValueRow(label: String, value: GalleryInfoValue, onCopy: (() -> Unit)? = null, onNavigate: (() -> Unit)? = null, displayText: String? = null) {
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.extraSmall)
            .then(
                if (onCopy != null && value.raw != null) {
                    Modifier.combinedClickable(
                        onClickLabel = stringResource(R.string.gallery_info_copy_field, label),
                        onClick = onCopy,
                        onLongClickLabel = if (onNavigate != null) stringResource(R.string.gallery_list_search_bar_open_gallery) else null,
                        onLongClick = onNavigate,
                    )
                } else {
                    Modifier.consumeGalleryInfoTaps()
                },
            ).heightIn(min = 36.dp).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, Modifier.width(80.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(displayText ?: value.display(), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = if (onNavigate != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
    }
}
