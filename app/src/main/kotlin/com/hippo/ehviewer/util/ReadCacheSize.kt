// Copyright 2026 Hycnyl. SPDX-License-Identifier: GPL-3.0-or-later
package com.hippo.ehviewer.util

import java.math.BigDecimal
import java.math.RoundingMode

// Keep the original MB preference and convert to bytes before any multiplication can overflow.
internal fun readCacheSizeBytes(megabytes: Int): Long = megabytes.coerceAtLeast(320).toLong() * 1024 * 1024

internal fun customReadCacheSizeMb(gigabytes: String): Int? {
    val value = gigabytes.trim().replace(',', '.').toBigDecimalOrNull() ?: return null
    if (value < BigDecimal.valueOf(5)) return null
    val megabytes = value.multiply(BigDecimal.valueOf(1024))
    if (megabytes > BigDecimal.valueOf(Int.MAX_VALUE.toLong())) return null
    return megabytes.toInt()
}

internal fun readCacheSizeGb(megabytes: Int): String = BigDecimal.valueOf(megabytes.toLong())
    .divide(BigDecimal.valueOf(1024), 3, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
