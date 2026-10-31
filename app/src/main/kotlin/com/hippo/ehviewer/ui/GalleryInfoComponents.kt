package com.hippo.ehviewer.ui

import android.text.format.Formatter
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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

/** The title keeps a two-line viewport; dragging reveals the rest without enlarging the card. */
@Composable
internal fun GalleryInfoTitle(label: String, value: GalleryInfoValue, onNavigate: (() -> Unit)?, secondary: Boolean = false) {
    val style = if (secondary) MaterialTheme.typography.bodySmall else MaterialTheme.typography.titleSmall
    val maxHeight = with(LocalDensity.current) { style.lineHeight.toDp() * 2 }
    Text(
        value.text,
        modifier = Modifier.fillMaxWidth().heightIn(max = maxHeight)
            .verticalScroll(rememberScrollState())
            .clickable(enabled = onNavigate != null, onClickLabel = stringResource(R.string.gallery_list_search_bar_open_gallery)) { onNavigate?.invoke() }
            .semantics { contentDescription = "$label: ${value.text}" },
        style = style,
        color = if (secondary) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
internal fun GalleryInfoCompactValue(
    label: String,
    value: GalleryInfoValue,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    val text = value.display()
    Row(
        modifier.clip(MaterialTheme.shapes.extraSmall)
            .clickable(enabled = value.raw != null, onClickLabel = stringResource(R.string.gallery_info_copy_field, label), onClick = onCopy)
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
internal fun GalleryInfoValueRow(label: String, value: GalleryInfoValue, onCopy: () -> Unit, onNavigate: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.extraSmall)
            .combinedClickable(
                enabled = value.raw != null,
                onClickLabel = stringResource(R.string.gallery_info_copy_field, label),
                onClick = onCopy,
                onLongClickLabel = if (onNavigate != null) stringResource(R.string.gallery_list_search_bar_open_gallery) else null,
                onLongClick = onNavigate,
            ).heightIn(min = 36.dp).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, Modifier.width(80.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value.display(), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = if (onNavigate != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
    }
}
