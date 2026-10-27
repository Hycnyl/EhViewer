// Copyright 2026 Hycnyl. SPDX-License-Identifier: GPL-3.0-or-later
package com.hippo.ehviewer.updater

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tachiyomi.data.release.GithubRelease

class UpdateSelectionTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun everyDeliveredAbiSelectsItsOwnApkAndNeverChecksumsOrAnotherAbi() {
        val abis = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86", "universal")
        val names = listOf("SHA256SUMS.txt", "mapping.txt") + abis.map { "EhViewer-1.15.1-$it.apk" }
        for (abi in abis) assertEquals("EhViewer-1.15.1-$abi.apk", chooseUpdateApkName(names, abi, "default"))
        assertFalse(matchesUpdateApk("EhViewer-1.15.1-x86_64.apk", "x86", "default"))
        assertEquals("EhViewer-1.15.1-universal.apk", chooseUpdateApkName(listOf(names.last()), "arm64-v8a", "default"))
        assertNull(chooseUpdateApkName(listOf("SHA256SUMS.txt"), "arm64-v8a", "default"))
        assertNull(chooseUpdateApkName(names, "arm64-v8a", "marshmallow"))
        assertTrue(matchesUpdateApk("EhViewer-1.14.6-marshmallow-arm64-v8a.apk", "arm64-v8a", "marshmallow"))
    }

    @Test
    fun previewTagDoesNotReofferCurrentBuildOrDowngrade() {
        val current = "a".repeat(40)
        val different = "b".repeat(40)
        assertFalse(isNewerUpdate("1.15.1", "v1.15.1-pre.4", current.take(7), current))
        assertFalse(isNewerUpdate("1.15.1", "v1.15.0-pre.3", current.take(7), different))
        assertTrue(isNewerUpdate("1.15.0", "v1.15.1-pre.4", current.take(7), different))
        assertTrue(isNewerUpdate("1.15.1", "v1.15.1-pre.5", current.take(7), different))
        assertFalse(isNewerUpdate("1.15.1", "v1.15.1", current.take(7), "main"))
        assertFalse(isNewerUpdate("1.15.1", "bad.1.16.0", current.take(7), different))
    }

    @Test
    fun githubPreviewJsonUsesBrowserApkUrl() {
        val release = json.decodeFromString<GithubRelease>(
            """{"tag_name":"v1.15.1-pre.4","body":null,"html_url":"https://github.com/Hycnyl/EhViewer/releases/tag/v1.15.1-pre.4","target_commitish":"${"a".repeat(40)}","prerelease":true,"assets":[{"name":"SHA256SUMS.txt","url":"https://api.github.com/wrong"},{"name":"EhViewer-1.15.1-x86.apk","url":"https://api.github.com/repos/Hycnyl/EhViewer/releases/assets/1","browser_download_url":"https://github.com/Hycnyl/EhViewer/releases/download/v1.15.1-pre.4/EhViewer-1.15.1-x86.apk"}]}""",
        )
        assertTrue(release.prerelease)
        assertEquals("https://github.com/Hycnyl/EhViewer/releases/download/v1.15.1-pre.4/EhViewer-1.15.1-x86.apk", release.getDownloadLink("x86", "default"))
    }
}
