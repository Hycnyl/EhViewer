// Copyright 2026 Hycnyl. SPDX-License-Identifier: GPL-3.0-or-later
package com.hippo.ehviewer.util

import coil3.disk.DiskCache
import okio.Path.Companion.toOkioPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReadCacheSizeTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun customGbUsesTheExistingMbPreferenceWithoutLosingCommonFractions() {
        assertEquals(5120, customReadCacheSizeMb("5"))
        assertEquals(5632, customReadCacheSizeMb("5.5"))
        assertEquals(5632, customReadCacheSizeMb("5,5"))
        assertEquals(20480, customReadCacheSizeMb("20"))
        assertEquals("5.5", readCacheSizeGb(5632))
        assertEquals("20", readCacheSizeGb(20480))
    }

    @Test
    fun invalidSmallAndOverflowingInputsAreRejected() {
        for (input in listOf("", "4.999", "-5", "abc", "NaN", "1e30", "2097152")) assertNull(input, customReadCacheSizeMb(input))
        assertEquals(2147482624, customReadCacheSizeMb("2097151"))
    }

    @Test
    fun largeByteBudgetsKeepTheirFullLongValueAndLegacySizesStayTheSame() {
        assertEquals(671088640L, readCacheSizeBytes(640))
        assertEquals(5368709120L, readCacheSizeBytes(5120))
        assertEquals(21474836480L, readCacheSizeBytes(20480))
        assertEquals(2251799812636672L, readCacheSizeBytes(Int.MAX_VALUE))
        assertEquals(335544320L, readCacheSizeBytes(-1))
    }

    @Test
    fun coilAcceptsTwentyGbWithoutPreallocatingItOnDisk() {
        val directory = temporary.newFolder()
        val cache = DiskCache.Builder().directory(directory.toOkioPath()).maxSizeBytes(readCacheSizeBytes(20480)).build()
        try {
            assertEquals(21474836480L, cache.maxSize)
            assertTrue(directory.listFiles()!!.isEmpty())
        } finally {
            cache.shutdown()
        }
    }
}
