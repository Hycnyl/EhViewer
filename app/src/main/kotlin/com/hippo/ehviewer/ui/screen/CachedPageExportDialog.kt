// Copyright 2026 Hycnyl. SPDX-License-Identifier: GPL-3.0-or-later
package com.hippo.ehviewer.ui.screen

import android.text.format.Formatter
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ehviewer.core.i18n.R
import com.ehviewer.core.model.GalleryInfo
import com.hippo.ehviewer.spider.RescueFormat
import com.hippo.ehviewer.spider.RescueInventory
import com.hippo.ehviewer.spider.RescueResult
import com.hippo.ehviewer.spider.UNKNOWN_RESCUE_SCAN
import com.hippo.ehviewer.spider.exportCachedPages
import com.hippo.ehviewer.spider.inspectCachedPages
import com.hippo.ehviewer.util.displayString
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import moe.tarsin.coroutines.runSuspendCatching

@Composable
internal fun CachedPageExportDialog(info: GalleryInfo, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var inventory by remember { mutableStateOf<RescueInventory?>(null) }
    var result by remember { mutableStateOf<RescueResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(false) }
    var completed by remember { mutableIntStateOf(0) }
    var total by remember { mutableIntStateOf(0) }
    var scanLimit by remember { mutableIntStateOf(UNKNOWN_RESCUE_SCAN) }
    var selectedFormat by remember { mutableStateOf(RescueFormat.LOOSE) }
    var job by remember { mutableStateOf<Job?>(null) }
    LaunchedEffect(info.gid, scanLimit) {
        inventory = null
        error = null
        runSuspendCatching { inspectCachedPages(info, scanLimit) }.onSuccess { inventory = it }.onFailure { error = it.displayString() }
    }
    fun dismiss() {
        job?.cancel()
        onDismiss()
    }
    fun export() {
        running = true
        job = scope.launch {
            try {
                runSuspendCatching {
                    exportCachedPages(info, selectedFormat, scanLimit) { done, count ->
                        completed = done
                        total = count
                    }
                }.onSuccess { result = it }.onFailure { error = it.displayString() }
            } finally {
                running = false
            }
        }
    }
    val report = result
    val found = inventory
    val canExport = !running && report == null && error == null && found?.indices?.isNotEmpty() == true
    AlertDialog(
        onDismissRequest = ::dismiss,
        icon = {
            Icon(
                when {
                    error != null -> Icons.Outlined.ErrorOutline
                    report != null -> Icons.Outlined.CheckCircle
                    else -> Icons.Outlined.CloudOff
                },
                contentDescription = null,
                tint = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
        },
        title = { Text(stringResource(R.string.cached_export)) },
        text = {
            Column(
                Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                when {
                    error != null -> ExportNotice(stringResource(R.string.cached_export_error, error.orEmpty()), warning = true)
                    report != null -> {
                        ExportMetrics(report.indices.size, report.totalPages.takeIf { report.knownTotal }, saved = true)
                        ExportBadge(Icons.Outlined.CheckCircle, stringResource(R.string.cached_export_saved))
                        if (report.downloadRegistered) {
                            ExportNotice(stringResource(if (report.knownTotal) R.string.cached_export_registered else R.string.cached_export_offline_registered))
                        } else {
                            ExportNotice(stringResource(R.string.cached_export_not_registered), warning = true)
                        }
                        Text(stringResource(R.string.cached_export_locations), style = MaterialTheme.typography.labelLarge)
                        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                            SelectionContainer {
                                Text(
                                    report.destinations.joinToString("\n\n"),
                                    Modifier.padding(12.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                        }
                        Text(
                            stringResource(R.string.cached_export_page_numbers, report.indices.take(100).joinToString { (it + 1).toString() }),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (report.failedIndices.isNotEmpty()) ExportNotice(stringResource(R.string.cached_export_failed_pages, report.failedIndices.size), warning = true)
                        ExportWarnings(report.warnings)
                    }
                    running -> {
                        if (found != null) ExportMetrics(found.indices.size, found.totalPages.takeIf { found.knownTotal })
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(stringResource(R.string.cached_export_progress, completed, total), style = MaterialTheme.typography.bodyMedium)
                    }
                    found == null -> {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        ExportBadge(Icons.Outlined.CloudOff, stringResource(R.string.cached_export_scanning))
                    }
                    else -> {
                        ExportMetrics(found.indices.size, found.totalPages.takeIf { found.knownTotal })
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            ExportBadge(Icons.Outlined.CloudOff, stringResource(R.string.cached_export_local_badge))
                            if (found.indices.isNotEmpty()) {
                                ExportBadge(Icons.Outlined.PhotoLibrary, stringResource(R.string.cached_export_range, found.indices.first() + 1, found.indices.last() + 1))
                                ExportBadge(Icons.Outlined.FolderOpen, Formatter.formatShortFileSize(LocalContext.current, found.sourceBytes))
                            }
                        }
                        if (found.indices.isEmpty()) ExportNotice(stringResource(R.string.cached_export_empty))
                        if (!found.knownTotal) {
                            Text(stringResource(R.string.cached_export_unknown, found.scannedUpperBound), style = MaterialTheme.typography.bodySmall)
                            Text(stringResource(R.string.cached_export_scan_limit), style = MaterialTheme.typography.labelLarge)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(2_000, 10_000, 50_000).forEach { limit ->
                                    FilterChip(selected = scanLimit == limit, onClick = { scanLimit = limit }, label = { Text("%,d".format(limit)) })
                                }
                            }
                        }
                        if (found.indices.isNotEmpty()) {
                            RescueFormat.entries.forEach { format ->
                                ExportFormatOption(format, format == selectedFormat) { selectedFormat = format }
                            }
                            Text(stringResource(R.string.cached_export_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        ExportWarnings(found.warnings)
                    }
                }
            }
        },
        confirmButton = {
            if (canExport) {
                Button(onClick = ::export) {
                    Icon(Icons.Outlined.SaveAlt, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(if (selectedFormat == RescueFormat.LOOSE) R.string.cached_export_loose else R.string.cached_export_cbz))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = ::dismiss) {
                Text(stringResource(if (report != null || error != null) android.R.string.ok else android.R.string.cancel))
            }
        },
    )
}

@Composable
private fun ExportMetrics(pages: Int, total: Int?, saved: Boolean = false) {
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.primaryContainer) {
        Row(Modifier.fillMaxWidth().padding(18.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(if (saved) R.string.cached_export_saved_pages else R.string.cached_export_found_pages), style = MaterialTheme.typography.labelMedium)
                Text("%,d".format(pages), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.cached_export_gallery_total), style = MaterialTheme.typography.labelMedium)
                Text(total?.let { "%,d".format(it) } ?: stringResource(R.string.cached_export_unknown_value), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun ExportBadge(icon: ImageVector, text: String) {
    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, contentDescription = null, Modifier.size(14.dp))
            Text(text, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun ExportNotice(text: String, warning: Boolean = false) {
    Surface(shape = RoundedCornerShape(12.dp), color = if (warning) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(if (warning) Icons.Outlined.ErrorOutline else Icons.Outlined.Info, contentDescription = null, Modifier.size(18.dp))
            Text(text, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ExportFormatOption(format: RescueFormat, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = onClick).padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(if (format == RescueFormat.LOOSE) Icons.Outlined.PhotoLibrary else Icons.Outlined.Archive, contentDescription = null)
            Column(Modifier.weight(1f)) {
                Text(stringResource(if (format == RescueFormat.LOOSE) R.string.cached_export_loose else R.string.cached_export_cbz), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(if (format == RescueFormat.LOOSE) R.string.cached_export_loose_description else R.string.cached_export_cbz_description), style = MaterialTheme.typography.bodySmall)
            }
            RadioButton(selected, onClick = null)
        }
    }
}

@Composable
private fun ExportWarnings(warnings: List<String>) {
    if (warnings.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    Column {
        TextButton(onClick = { expanded = !expanded }) { Text(stringResource(R.string.cached_export_warnings, minOf(30, warnings.size))) }
        if (expanded) warnings.take(30).forEach { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp)) }
    }
}
