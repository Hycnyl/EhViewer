// Copyright 2026 Hycnyl. SPDX-License-Identifier: GPL-3.0-or-later
package com.hippo.ehviewer.spider

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

internal const val MAX_RESCUE_PAGES = 100_000
internal const val UNKNOWN_RESCUE_SCAN = 2_000
private val rescueNamePattern = Regex("^([0-9]{8,})\\.([a-zA-Z0-9]{2,5})$")
private val rescueExtensions = setOf("jpg", "jpeg", "png", "gif", "webp", "avif", "bmp", "jxl")

internal data class RescuePageName(val index: Int, val extension: String) {
    val name: String get() = String.format(Locale.ROOT, "%08d.%s", index + 1, extension)
}

internal fun parseRescuePageName(name: String): RescuePageName? {
    val match = rescueNamePattern.matchEntire(name) ?: return null
    val page = match.groupValues[1].toIntOrNull()?.takeIf { it in 1..MAX_RESCUE_PAGES } ?: return null
    val extension = match.groupValues[2].lowercase(Locale.ROOT).takeIf { it in rescueExtensions } ?: return null
    return RescuePageName(page - 1, extension)
}

internal fun rescueMarkerName(name: String) = ".$name.rescue-incomplete"

internal fun rescuePageCount(vararg counts: Int) = counts.filter { it in 1..MAX_RESCUE_PAGES }.maxOrNull() ?: 0

internal fun rescueArchiveName(gid: Long, exists: (String) -> Boolean): String {
    for (number in 1..9_999) {
        val suffix = if (number == 1) "" else "-$number"
        val name = "$gid.cached-partial$suffix.cbz"
        if (!exists(name)) return name
    }
    throw IOException("Too many cached-page snapshots")
}

internal data class RescueFingerprint(val size: Long, val crc: Long)

// Constant memory; also works with non-seekable SAF streams. No bitmap decode or re-encoding.
internal fun readRescueBytes(
    input: InputStream,
    output: OutputStream? = null,
    extension: String? = null,
    checkpoint: () -> Unit = {},
): RescueFingerprint {
    val buffer = ByteArray(64 * 1024)
    val head = ByteArray(32)
    val tail = ByteArray(12)
    val crc = CRC32()
    var size = 0L
    while (true) {
        checkpoint()
        val count = input.read(buffer)
        if (count == -1) break
        if (count == 0) continue
        if (size < head.size) buffer.copyInto(head, size.toInt(), 0, minOf(count, head.size - size.toInt()))
        if (count >= tail.size) {
            buffer.copyInto(tail, 0, count - tail.size, count)
        } else {
            tail.copyInto(tail, 0, count, tail.size)
            buffer.copyInto(tail, tail.size - count, 0, count)
        }
        output?.write(buffer, 0, count)
        crc.update(buffer, 0, count)
        size += count
    }
    if (size == 0L) throw IOException("Empty file")
    if (extension != null) {
        fun byte(index: Int) = head[index].toInt() and 255
        fun littleEndianSize() = (4..7).fold(0L) { value, i -> value or (byte(i).toLong() shl ((i - 4) * 8)) }
        val valid = size >= 12 && when (extension) {
            "jpg", "jpeg" -> byte(0) == 255 && byte(1) == 216 && byte(2) == 255 && tail[10] == (-1).toByte() && tail[11] == (-39).toByte()
            "png" -> head.copyOfRange(0, 8).contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)) && String(tail, 4, 4, Charsets.US_ASCII) == "IEND"
            "gif" -> String(head, 0, 6, Charsets.US_ASCII) in setOf("GIF87a", "GIF89a") && tail[11] == 59.toByte()
            "webp" -> String(head, 0, 4, Charsets.US_ASCII) == "RIFF" && String(head, 8, 4, Charsets.US_ASCII) == "WEBP" && littleEndianSize() + 8 == size
            "bmp" -> byte(0) == 66 && byte(1) == 77 && (2..5).fold(0L) { value, i -> value or (byte(i).toLong() shl ((i - 2) * 8)) } == size
            "avif" -> String(head, 4, 4, Charsets.US_ASCII) == "ftyp" && String(head, 8, 24, Charsets.US_ASCII).let { "avif" in it || "avis" in it }
            "jxl" -> (byte(0) == 255 && byte(1) == 10) || head.copyOfRange(0, 12).contentEquals(byteArrayOf(0, 0, 0, 12, 74, 88, 76, 32, 13, 10, -121, 10))
            else -> false
        }
        if (!valid) throw IOException("Unrecognized or incomplete $extension image")
    }
    return RescueFingerprint(size, crc.value)
}

internal fun verifyRescueZip(file: File, expectedNames: Set<String>, checkpoint: () -> Unit = {}) {
    ZipFile(file).use { zip ->
        val seen = mutableSetOf<String>()
        zip.entries().asSequence().forEach { entry ->
            checkpoint()
            check(entry.name in expectedNames && seen.add(entry.name)) { "Unexpected or duplicate ZIP entry: ${entry.name}" }
            check(!entry.isDirectory && entry.method in setOf(ZipEntry.DEFLATED, ZipEntry.STORED))
            val fingerprint = zip.getInputStream(entry).use { readRescueBytes(it, checkpoint = checkpoint) }
            check(fingerprint.size == entry.size && fingerprint.crc == entry.crc) { "ZIP integrity check failed: ${entry.name}" }
        }
        check(seen == expectedNames) { "Missing ZIP entries" }
    }
}
