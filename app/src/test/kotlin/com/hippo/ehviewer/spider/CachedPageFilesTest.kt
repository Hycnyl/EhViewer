// Copyright 2026 Hycnyl. SPDX-License-Identifier: GPL-3.0-or-later
package com.hippo.ehviewer.spider

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.util.Base64
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CachedPageFilesTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=")

    @Test
    fun gapsKeepOriginalPageNumbersAndGalleryCount() {
        val pages = listOf("00000003.png", "00000008.webp").map { parseRescuePageName(it)!! }
        assertEquals(listOf(2, 7), pages.map { it.index })
        assertEquals(listOf("00000003.png", "00000008.webp"), pages.map { it.name })
        assertEquals(36, rescuePageCount(36, pages.maxOf { it.index + 1 }))
        assertEquals(8, rescuePageCount(0, -1, Int.MAX_VALUE, pages.maxOf { it.index + 1 }))
        assertNull(parseRescuePageName("00000000.jpg"))
        assertNull(parseRescuePageName("00100001.jpg"))
        assertNull(parseRescuePageName("00000003.jpg.tmp"))
        assertNull(parseRescuePageName("cover.jpg"))
        assertNull(parseRescuePageName("../00000003.jpg"))
        assertNull(parseRescuePageName("00000003.jpg/extra"))
        assertNull(parseRescuePageName("00000003.xml"))
    }

    @Test
    fun committedImageBytesArePreservedAndIncompleteCandidatesFail() {
        val output = ByteArrayOutputStream()
        val fingerprint = readRescueBytes(ByteArrayInputStream(png), output, "png")
        assertEquals(png.size.toLong(), fingerprint.size)
        assertArrayEquals(png, output.toByteArray())
        assertThrows(IOException::class.java) { readRescueBytes(ByteArrayInputStream(png.copyOf(png.size - 5)), extension = "png") }
        assertThrows(IOException::class.java) { readRescueBytes(ByteArrayInputStream(byteArrayOf()), extension = "png") }
        assertThrows(IOException::class.java) { readRescueBytes(ByteArrayInputStream("<html>not an image</html>".toByteArray()), extension = "jpg") }
    }

    @Test
    fun shortReadsAndNonSeekableStreamsAreSupported() {
        val stream = object : ByteArrayInputStream(png) {
            override fun read(bytes: ByteArray, offset: Int, length: Int) = super.read(bytes, offset, minOf(3, length))
        }
        assertEquals(png.size.toLong(), readRescueBytes(stream, extension = "png").size)
    }

    @Test
    fun cancellationInterruptsStreaming() {
        var checkpoints = 0
        assertThrows(CancellationException::class.java) {
            readRescueBytes(ByteArrayInputStream(ByteArray(1_000_000))) {
                if (++checkpoints == 3) throw CancellationException("Canceled")
            }
        }
        assertEquals(3, checkpoints)
    }

    @Test
    fun repeatedSnapshotsNeverUseCompleteArchiveNamesOrOverwriteHistory() {
        val existing = mutableSetOf("123.cbz", "123.zip", "123.cached-partial.cbz")
        val first = rescueArchiveName(123) { it in existing }
        existing += first
        val second = rescueArchiveName(123) { it in existing }
        assertEquals("123.cached-partial-2.cbz", first)
        assertEquals("123.cached-partial-3.cbz", second)
        assertFalse(first == "123.cbz" || first == "123.zip")
    }

    @Test
    fun sparseZipIsReadableAndCrcCorruptionIsRejected() {
        val file = temporary.newFile("123.cached-partial.cbz")
        val names = setOf("00000003.png", "00000008.png", "ComicInfo.xml")
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.setLevel(0)
            names.forEach { name ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(if (name.endsWith(".png")) png else "<ComicInfo><PageCount>36</PageCount></ComicInfo>".toByteArray())
                zip.closeEntry()
            }
        }
        verifyRescueZip(file, names)
        ZipFile(file).use { zip ->
            assertArrayEquals(png, zip.getInputStream(zip.getEntry("00000008.png")).use { it.readBytes() })
        }
        // Alter the central directory CRC while leaving the entry data readable.
        RandomAccessFile(file, "rw").use { archive ->
            val bytes = ByteArray(archive.length().toInt()).also { archive.readFully(it) }
            val offset = (0 until bytes.size - 4).first { bytes[it] == 0x50.toByte() && bytes[it + 1] == 0x4b.toByte() && bytes[it + 2] == 1.toByte() && bytes[it + 3] == 2.toByte() }
            archive.seek((offset + 16).toLong())
            archive.write(bytes[offset + 16].toInt() xor 1)
        }
        assertThrows(IllegalStateException::class.java) { verifyRescueZip(file, names) }
    }

    @Test
    fun unexpectedZipEntriesAreRejected() {
        val file = temporary.newFile("unsafe.cbz")
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("../00000003.png"))
            zip.write(png)
            zip.closeEntry()
        }
        assertThrows(IllegalStateException::class.java) { verifyRescueZip(file, setOf("00000003.png")) }
    }
}
