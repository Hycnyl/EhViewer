// Copyright 2026 Hycnyl. SPDX-License-Identifier: GPL-3.0-or-later
package com.hippo.ehviewer.coil

import coil3.disk.DiskCache
import java.io.File
import okio.Path.Companion.toOkioPath
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReadCacheCleanerTest {
    @get:Rule val temporary = TemporaryFolder()
    private val cutoff = 1_700_000_000_000L

    private fun cache(directory: File) = DiskCache.Builder().directory(directory.toOkioPath()).maxSizeBytes(128L * 1024 * 1024).build()

    private fun write(cache: DiskCache, key: String, metadataTime: Long, dataTime: Long = metadataTime) {
        assertTrue(
            cache.edit(key) {
                metadata.toFile().writeText("jpg")
                data.toFile().writeText(key)
            },
        )
        cache.openSnapshot(key)!!.use {
            assertTrue(it.metadata.toFile().setLastModified(metadataTime))
            assertTrue(it.data.toFile().setLastModified(dataTime))
        }
    }

    @Test
    fun preexistingCacheKeepsRecentAndBoundaryEntriesAndItsJournalSizesRemainValid() {
        val directory = temporary.newFolder("image_cache")
        val download = temporary.newFile("download.cbz").apply { writeText("download") }
        cache(directory).apply {
            write(this, "old", cutoff - 1000)
            write(this, "boundary", cutoff)
            write(this, "recent", cutoff + 1000)
            write(this, "recent-metadata", cutoff + 1000, cutoff - 1000)
            write(this, "recent-data", cutoff - 1000, cutoff + 1000)
            shutdown()
        }
        val unrelated = File(directory, "unrelated.tmp").apply { writeText("unrelated") }
        val reopened = cache(directory)
        val before = reopened.size
        assertEquals(1, ReadCacheCleaner.clearBefore(reopened, cutoff))
        assertNull(reopened.openSnapshot("old"))
        assertEquals(before - 6, reopened.size) // Three metadata bytes and three data bytes.
        for (key in listOf("boundary", "recent", "recent-metadata", "recent-data")) {
            reopened.openSnapshot(key)!!.use { assertEquals(key, it.data.toFile().readText()) }
        }
        val after = reopened.size
        reopened.shutdown()
        cache(directory).apply {
            assertEquals(after, size)
            assertNull(openSnapshot("old"))
            openSnapshot("recent")!!.close()
            shutdown()
        }
        assertEquals("download", download.readText())
        assertEquals("unrelated", unrelated.readText())
    }

    @Test
    fun heldReaderSnapshotKeepsItsBytesUntilItCloses() {
        val cache = cache(temporary.newFolder())
        try {
            write(cache, "held", cutoff - 1000)
            val snapshot = cache.openSnapshot("held")!!
            val data = snapshot.data.toFile()
            val before = data.readBytes()
            assertEquals(1, ReadCacheCleaner.clearBefore(cache, cutoff))
            assertNull(cache.openSnapshot("held"))
            assertArrayEquals(before, data.readBytes())
            snapshot.close()
            assertFalse(data.exists())
            assertEquals(0L, cache.size)
        } finally {
            cache.shutdown()
        }
    }

    @Test
    fun ongoingWriteIsSkippedAndCanCommitNormally() {
        val cache = cache(temporary.newFolder())
        try {
            write(cache, "editing", cutoff - 1000)
            val editor = cache.openEditor("editing")!!
            editor.data.toFile().writeText("new image")
            assertEquals(0, ReadCacheCleaner.clearBefore(cache, cutoff))
            editor.commit()
            cache.openSnapshot("editing")!!.use { assertEquals("new image", it.data.toFile().readText()) }
            assertEquals(0, ReadCacheCleaner.clearBefore(cache, cutoff))
        } finally {
            cache.shutdown()
        }
    }

    @Test
    fun interruptedCleanupStopsBeforeDeletingAnything() {
        val cache = cache(temporary.newFolder())
        try {
            write(cache, "old", cutoff - 1000)
            try {
                Thread.currentThread().interrupt()
                assertThrows(InterruptedException::class.java) { ReadCacheCleaner.clearBefore(cache, cutoff) }
            } finally {
                Thread.interrupted()
            }
            cache.openSnapshot("old")!!.use { assertNotNull(it) }
        } finally {
            cache.shutdown()
        }
    }
}
