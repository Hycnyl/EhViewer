package com.hippo.ehviewer.ui

import android.text.format.Formatter
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
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
internal fun GalleryInfoCopyButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp), shapes = IconButtonDefaults.shapes()) {
        Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.gallery_info_copy_field, label), modifier = Modifier.size(20.dp))
    }
}

@Composable
internal fun GalleryInfoCard(@StringRes title: Int, rowGap: Dp = 12.dp, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth().padding(bottom = 12.dp), shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(rowGap)) {
            Text(stringResource(title), modifier = Modifier.padding(bottom = if (rowGap < 12.dp) 8.dp else 0.dp), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}

@Composable
internal fun GalleryInfoValueRow(
    label: String,
    value: GalleryInfoValue,
    onCopy: () -> Unit,
    icon: ImageVector? = null,
    onNavigate: (() -> Unit)? = null,
    valueStyle: TextStyle = MaterialTheme.typography.bodyMedium,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (icon != null) Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                value.display(),
                style = valueStyle,
                color = if (onNavigate != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                modifier = if (onNavigate != null) Modifier.clickable(onClick = onNavigate).heightIn(min = 48.dp).padding(vertical = 8.dp) else Modifier.padding(vertical = 4.dp),
            )
        }
        GalleryInfoCopyButton(label, value.raw != null, onCopy)
    }
}

@Composable
internal fun GalleryInfoIdentifierRow(label: String, value: GalleryInfoValue, onCopy: () -> Unit, onNavigate: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.width(80.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value.display(),
            Modifier.weight(1f).then(if (onNavigate != null) Modifier.clickable(onClick = onNavigate).heightIn(min = 48.dp).padding(vertical = 8.dp) else Modifier),
            style = MaterialTheme.typography.bodySmall,
            color = if (onNavigate != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
        GalleryInfoCopyButton(label, value.raw != null, onCopy)
    }
}
