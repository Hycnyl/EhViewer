// Copyright 2026 Hycnyl. SPDX-License-Identifier: GPL-3.0-or-later
package com.hippo.ehviewer.ui.settings

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.ehviewer.core.i18n.R
import com.ehviewer.core.util.logcat
import com.hippo.ehviewer.EhApplication
import com.hippo.ehviewer.coil.ReadCacheCleaner
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import moe.tarsin.coroutines.runSuspendCatching
import moe.tarsin.snackbar

@Composable
context(ctx: Context, _: SnackbarHostState)
internal fun ReadCacheCleanupPreference() {
    var visible by remember { mutableStateOf(false) }
    var selected by remember { mutableIntStateOf(0) }
    var cleaning by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val title = stringResource(R.string.read_cache_cleanup)
    Preference(title = title, summary = stringResource(R.string.read_cache_cleanup_summary)) { visible = true }
    if (visible) {
        val choices = listOf(
            stringResource(R.string.read_cache_cleanup_day),
            stringResource(R.string.read_cache_cleanup_week),
            stringResource(R.string.read_cache_cleanup_month),
            stringResource(R.string.read_cache_cleanup_year),
        )
        fun dismiss() {
            job?.cancel()
            visible = false
        }
        AlertDialog(
            onDismissRequest = ::dismiss,
            title = { Text(title) },
            text = {
                Column(Modifier.selectableGroup()) {
                    choices.forEachIndexed { index, label ->
                        Row(
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(
                                selected = selected == index,
                                enabled = !cleaning,
                                role = Role.RadioButton,
                                onClick = { selected = index },
                            ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = selected == index, onClick = null, enabled = !cleaning, modifier = Modifier.padding(end = 16.dp))
                            Text(label)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = !cleaning, onClick = {
                    val unit = listOf(ChronoUnit.DAYS, ChronoUnit.WEEKS, ChronoUnit.MONTHS, ChronoUnit.YEARS)[selected]
                    val cutoff = ZonedDateTime.now().minus(1, unit).toInstant().toEpochMilli()
                    cleaning = true
                    job = scope.launch {
                        val result = try {
                            runSuspendCatching {
                                runInterruptible(Dispatchers.IO) { ReadCacheCleaner.clearBefore(EhApplication.imageCache, cutoff) }
                            }
                        } finally {
                            cleaning = false
                        }
                        visible = false
                        result.onSuccess { count ->
                            snackbar(ctx.getString(R.string.read_cache_cleanup_done, count))
                        }.onFailure {
                            logcat(it)
                            snackbar(ctx.getString(R.string.read_cache_cleanup_failed))
                        }
                    }
                }) { Text(stringResource(if (cleaning) R.string.read_cache_cleanup_running else R.string.read_cache_cleanup_action)) }
            },
            dismissButton = { TextButton(onClick = ::dismiss) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
}
