// Copyright 2026 Hycnyl. SPDX-License-Identifier: GPL-3.0-or-later
package com.hippo.ehviewer.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ehviewer.core.i18n.R
import com.ehviewer.core.model.GalleryInfo
import com.hippo.ehviewer.spider.RescueFormat
import com.hippo.ehviewer.spider.RescueInventory
import com.hippo.ehviewer.spider.RescueResult
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
    var job by remember { mutableStateOf<Job?>(null) }
    LaunchedEffect(info.gid) {
        runSuspendCatching { inspectCachedPages(info) }.onSuccess { inventory = it }.onFailure { error = it.displayString() }
    }
    fun dismiss() {
        job?.cancel()
        onDismiss()
    }
    fun export(format: RescueFormat) {
        running = true
        job = scope.launch {
            try {
                runSuspendCatching {
                    exportCachedPages(info, format) { done, count ->
                        completed = done
                        total = count
                    }
                }.onSuccess { result = it }.onFailure { error = it.displayString() }
            } finally {
                running = false
            }
        }
    }
    AlertDialog(
        onDismissRequest = ::dismiss,
        title = { Text(stringResource(R.string.cached_export)) },
        text = {
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                val report = result
                val found = inventory
                when {
                    error != null -> Text(stringResource(R.string.cached_export_error, error.orEmpty()))
                    report != null -> {
                        Text(
                            if (report.knownTotal) {
                                stringResource(R.string.cached_export_success, report.indices.size, report.totalPages)
                            } else {
                                stringResource(R.string.cached_export_success_unknown, report.indices.size)
                            },
                        )
                        Text(stringResource(R.string.cached_export_page_numbers, report.indices.take(100).joinToString { (it + 1).toString() }))
                        report.destinations.forEach { Text(it) }
                        if (report.downloadRegistered) Text(stringResource(R.string.cached_export_registered))
                        if (report.failedIndices.isNotEmpty()) Text(stringResource(R.string.cached_export_failed_pages, report.failedIndices.size))
                        report.warnings.take(30).forEach { Text(it) }
                    }
                    running -> {
                        LinearProgressIndicator()
                        Text(stringResource(R.string.cached_export_progress, completed, total))
                    }
                    found == null -> {
                        LinearProgressIndicator()
                        Text(stringResource(R.string.cached_export_scanning))
                    }
                    found.indices.isEmpty() -> Text(stringResource(R.string.cached_export_empty))
                    else -> {
                        Text(stringResource(R.string.cached_export_inventory, found.indices.size, found.indices.first() + 1, found.indices.last() + 1))
                        if (found.knownTotal) {
                            Text(stringResource(R.string.cached_export_total, found.totalPages))
                        } else {
                            Text(stringResource(R.string.cached_export_unknown, found.scannedUpperBound))
                        }
                        Text(stringResource(R.string.cached_export_help))
                        found.warnings.take(10).forEach { Text(it) }
                    }
                }
            }
        },
        confirmButton = {
            if (!running && result == null && error == null && inventory?.indices?.isNotEmpty() == true) {
                TextButton(onClick = { export(RescueFormat.LOOSE) }) { Text(stringResource(R.string.cached_export_loose)) }
                TextButton(onClick = { export(RescueFormat.CBZ) }) { Text(stringResource(R.string.cached_export_cbz)) }
            }
        },
        dismissButton = {
            TextButton(onClick = ::dismiss) {
                Text(stringResource(if (result != null || error != null) android.R.string.ok else android.R.string.cancel))
            }
        },
    )
}
