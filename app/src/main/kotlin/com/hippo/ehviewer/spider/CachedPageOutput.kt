// Copyright 2026 Hycnyl. SPDX-License-Identifier: GPL-3.0-or-later
package com.hippo.ehviewer.spider

import com.ehviewer.core.files.delete
import com.ehviewer.core.files.exists
import java.io.File
import okio.Path

internal enum class ExportFileAction { OVERWRITE, SKIP }

internal data class ExportFileDecision(val action: ExportFileAction, val remember: Boolean = false)

// A remembered choice applies to this export only. Normal download writes never use it.
internal class CachedPageOutput(private val checkpoint: () -> Unit, private val onConflict: suspend (Path) -> ExportFileDecision) {
    private var remembered: ExportFileAction? = null
    var skippedFiles = 0
        private set

    suspend fun write(source: File, destination: Path): Boolean {
        checkpoint()
        if (!destination.exists()) {
            // An orphaned marker has no page to protect. Allow a fresh export to recover.
            (destination.parent!! / rescueMarkerName(destination.name)).delete()
            commitRescueFile(source, destination, checkpoint)
            return true
        }
        val action = remembered ?: onConflict(destination).also { if (it.remember) remembered = it.action }.action
        checkpoint()
        if (action == ExportFileAction.SKIP) {
            skippedFiles++
            return false
        }
        overwriteRescueFile(source, destination, checkpoint)
        return true
    }
}
