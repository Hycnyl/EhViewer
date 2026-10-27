// Copyright 2026 Hycnyl. SPDX-License-Identifier: GPL-3.0-or-later
package com.hippo.ehviewer.spider

import android.os.ParcelFileDescriptor
import com.ehviewer.core.files.delete
import com.ehviewer.core.files.exists
import com.ehviewer.core.files.moveTo
import com.ehviewer.core.files.openFileDescriptor
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import moe.tarsin.coroutines.runSuspendCatching
import okio.Path

internal inline fun <T> Path.withRescueInput(block: (java.io.InputStream) -> T): T = ParcelFileDescriptor.AutoCloseInputStream(openFileDescriptor("r")).use(block)

// Write .tmp, close, re-read and verify CRC/length, then publish. The marker also protects
// readers after a crash on SAF providers which cannot rename. SpiderDen ignores marked pages.
internal fun commitRescueFile(source: File, destination: Path, checkpoint: () -> Unit) = commitLocalFile({ source.inputStream() }, destination, checkpoint)

// Used only after an explicit export overwrite choice. Retain the previous file until
// publication succeeds, and restore it if cancellation or an I/O error interrupts the write.
internal fun overwriteRescueFile(source: File, destination: Path, checkpoint: () -> Unit) {
    val previous = destination.parent!! / ".${destination.name}.${UUID.randomUUID()}.previous"
    val marker = destination.parent!! / rescueMarkerName(destination.name)
    val wasIncomplete = marker.exists()
    fun copyExisting(from: Path, to: Path) {
        if (from.withRescueInput { it.read() } == -1) {
            ParcelFileDescriptor.AutoCloseOutputStream(to.openFileDescriptor("wt")).use {}
        } else {
            copyLocalPageSafely(from, to)
        }
    }
    var replacing = false
    var keepPrevious = false
    try {
        copyExisting(destination, previous)
        checkpoint()
        replacing = true
        destination.delete()
        marker.delete()
        commitRescueFile(source, destination, checkpoint)
    } catch (error: Throwable) {
        if (replacing) {
            try {
                destination.delete()
                marker.delete()
                copyExisting(previous, destination)
                if (wasIncomplete) ParcelFileDescriptor.AutoCloseOutputStream(marker.openFileDescriptor("wt")).use { it.write(destination.name.toByteArray()) }
            } catch (restoreError: Throwable) {
                keepPrevious = true
                throw IOException("Previous file retained at $previous after restore failed", restoreError).apply { addSuppressed(error) }
            }
        }
        throw error
    } finally {
        if (!keepPrevious) runCatching { previous.delete() }
    }
}

internal fun copyLocalPageSafely(source: Path, destination: Path) = commitLocalFile(
    { ParcelFileDescriptor.AutoCloseInputStream(source.openFileDescriptor("r")) },
    destination,
    {},
)

internal fun commitLocalFile(openSource: () -> InputStream, destination: Path, checkpoint: () -> Unit) {
    val marker = destination.parent!! / rescueMarkerName(destination.name)
    check(!destination.exists() && !marker.exists()) { "Destination already exists: $destination" }
    val temporary = destination.parent!! / ".${destination.name}.${UUID.randomUUID()}.tmp"
    val expected = openSource().use { readRescueBytes(it, checkpoint = checkpoint) }
    var ownsDestination = false
    var ownsMarker = false
    var committed = false
    try {
        openSource().use { input ->
            ParcelFileDescriptor.AutoCloseOutputStream(temporary.openFileDescriptor("wt")).use { readRescueBytes(input, it, checkpoint = checkpoint) }
        }
        check(temporary.withRescueInput { readRescueBytes(it, checkpoint = checkpoint) } == expected) { "Staging verification failed" }
        checkpoint()
        check(!destination.exists() && !marker.exists()) { "Destination appeared during export: $destination" }
        ownsMarker = true
        ParcelFileDescriptor.AutoCloseOutputStream(marker.openFileDescriptor("wt")).use { it.write(destination.name.toByteArray()) }
        ownsDestination = true
        runSuspendCatching { temporary moveTo destination }
        if (!destination.exists()) {
            temporary.withRescueInput { input ->
                ParcelFileDescriptor.AutoCloseOutputStream(destination.openFileDescriptor("wt")).use { readRescueBytes(input, it, checkpoint = checkpoint) }
            }
        }
        check(destination.withRescueInput { readRescueBytes(it, checkpoint = checkpoint) } == expected) { "Destination verification failed" }
        marker.delete()
        check(!marker.exists()) { "Cannot remove incomplete marker: $marker" }
        committed = true
    } finally {
        runCatching { temporary.delete() }
        if (!committed && ownsDestination) runCatching { destination.delete() }
        // If cleanup cannot remove the incomplete destination, retain its reader guard.
        if (!committed && ownsMarker && !destination.exists()) runCatching { marker.delete() }
    }
}
