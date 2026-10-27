// Copyright 2026 Hycnyl. SPDX-License-Identifier: GPL-3.0-or-later
package com.hippo.ehviewer.ui.settings

import android.content.Context
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.integerArrayResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.ehviewer.core.i18n.R
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.collectAsState
import com.hippo.ehviewer.ui.showRestartDialog
import com.hippo.ehviewer.ui.tools.DialogState
import com.hippo.ehviewer.ui.tools.awaitSelectItem
import com.hippo.ehviewer.util.customReadCacheSizeMb
import com.hippo.ehviewer.util.readCacheSizeGb
import kotlinx.coroutines.launch

@Composable
context(_: Context, _: DialogState)
internal fun ReadCacheSizePreference() {
    val current by Settings.readCacheSize.collectAsState()
    val entries = stringArrayResource(com.hippo.ehviewer.R.array.read_cache_size_entries)
    val values = integerArrayResource(com.hippo.ehviewer.R.array.read_cache_size_entry_values)
    val custom = stringResource(R.string.read_cache_size_custom)
    val selected = values.indexOf(current).takeIf { it >= 0 } ?: values.size
    val scope = rememberCoroutineScope()
    var showCustom by remember { mutableStateOf(false) }
    fun save(megabytes: Int) {
        if (megabytes == current) return
        scope.launch {
            Settings.withMutableSnapshot { it[Settings.readCacheSize] = megabytes }
            showRestartDialog()
        }
    }
    Preference(
        title = stringResource(R.string.settings_advanced_read_cache_size),
        summary = entries.getOrNull(selected) ?: "${readCacheSizeGb(current)} GB",
    ) {
        scope.launch {
            val choice = awaitSelectItem(entries.toList() + custom, title = R.string.settings_advanced_read_cache_size, selected = selected)
            if (choice == values.size) showCustom = true else save(values[choice])
        }
    }
    if (showCustom) {
        val input = rememberTextFieldState(if (current >= 5120) readCacheSizeGb(current) else "5")
        val megabytes = customReadCacheSizeMb(input.text.toString())
        AlertDialog(
            onDismissRequest = { showCustom = false },
            title = { Text(stringResource(R.string.settings_advanced_read_cache_size)) },
            text = {
                OutlinedTextField(
                    state = input,
                    suffix = { Text("GB") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    lineLimits = TextFieldLineLimits.SingleLine,
                )
            },
            confirmButton = {
                TextButton(enabled = megabytes != null, onClick = {
                    showCustom = false
                    megabytes?.let(::save)
                }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { showCustom = false }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
}
