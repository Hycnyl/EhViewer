// Copyright 2026 Hycnyl. SPDX-License-Identifier: GPL-3.0-or-later
package com.hippo.ehviewer.updater

internal fun matchesUpdateApk(name: String, abi: String, flavor: String): Boolean {
    val lower = name.lowercase()
    val legacy = "-marshmallow-" in lower
    return lower.endsWith("-$abi.apk") && (if (flavor == "marshmallow") legacy else !legacy)
}

internal fun isNewerUpdate(current: String, candidate: String, currentSha: String, candidateSha: String?): Boolean {
    fun parts(value: String): List<Int>? {
        val numbers = value.removePrefix("v").substringBefore('-').split('.')
        if (numbers.size != 3) return null
        return numbers.map { it.toIntOrNull() ?: return null }
    }
    val installed = parts(current) ?: return false
    val offered = parts(candidate) ?: return false
    for (index in installed.indices) {
        if (offered[index] != installed[index]) return offered[index] > installed[index]
    }
    return candidateSha?.matches(Regex("[0-9a-fA-F]{40}")) == true && !candidateSha.startsWith(currentSha, ignoreCase = true)
}

internal fun chooseUpdateApkName(names: List<String>, abi: String, flavor: String): String? = names.firstOrNull { matchesUpdateApk(it, abi, flavor) } ?: names.firstOrNull { matchesUpdateApk(it, "universal", flavor) }
