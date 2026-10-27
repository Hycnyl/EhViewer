// Copyright 2026 Hycnyl. SPDX-License-Identifier: GPL-3.0-or-later
package com.hippo.ehviewer.spider

import com.ehviewer.core.files.list
import com.ehviewer.core.model.GalleryInfo
import com.ehviewer.core.util.withIOContext
import com.hippo.ehviewer.EhApplication.Companion.imageCache
import com.hippo.ehviewer.client.getImageKey
import com.hippo.ehviewer.client.getThumbKey
import com.hippo.ehviewer.coil.read
import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import moe.tarsin.coroutines.runSuspendCatching
import okio.Path

// Cache rescue reads committed local files directly, without creating a reader or downloader.
internal suspend fun inspectLocalPages(gid: Long, directories: List<Path>, scanLimit: Int, warning: (String, Throwable) -> Unit): List<LocalRescuePage> = withIOContext {
    val context = currentCoroutineContext()
    val pages = mutableListOf<LocalRescuePage>()
    fun inspect(path: Path, name: RescuePageName, cached: Boolean = false) {
        runSuspendCatching {
            context.ensureActive()
            val fingerprint = path.withRescueInput {
                readRescueBytes(it, extension = name.extension, checkpoint = { context.ensureActive() })
            }
            pages += LocalRescuePage(name, path.takeUnless { cached }, fingerprint.size)
        }.onFailure { warning(name.name, it) }
    }
    directories.distinct().forEach { dir ->
        context.ensureActive()
        val files = dir.list().sortedBy { it.name }
        val names = files.map { it.name }.toSet()
        files.forEach { file ->
            parseRescuePageName(file.name)?.takeUnless { rescueMarkerName(file.name) in names }?.let { inspect(file, it) }
        }
    }
    repeat(scanLimit.coerceIn(0, MAX_RESCUE_PAGES)) { index ->
        context.ensureActive()
        runSuspendCatching {
            imageCache.read(getImageKey(gid, index)) {
                val extension = metadata.toFile().readText().trim().lowercase()
                val name = requireNotNull(parseRescuePageName(perFilename(index, extension))) { "Invalid cached image extension" }
                inspect(data, name, cached = true)
            }
        }.onFailure { warning(perFilename(index), it) }
    }
    pages
}

internal suspend fun copyLocalPageTo(gid: Long, page: LocalRescuePage, destination: File): RescueFingerprint? = withIOContext {
    val context = currentCoroutineContext()
    fun copy(path: Path) = path.withRescueInput { input ->
        destination.outputStream().use { output -> readRescueBytes(input, output, page.page.extension) { context.ensureActive() } }
    }
    page.path?.let(::copy) ?: imageCache.read(getImageKey(gid, page.page.index)) {
        check(metadata.toFile().readText().trim().lowercase() == page.page.extension) { "Cached image changed during export" }
        copy(data)
    }
}

internal fun copyCachedCoverTo(info: GalleryInfo, staging: File, checkpoint: () -> Unit): File? {
    val key = info.thumbKey ?: return null
    val extension = key.substringBefore('?').substringAfterLast('.').lowercase()
    if (parseRescuePageName("00000001.$extension") == null) return null
    val file = File(staging, "thumb.$extension")
    for (cacheKey in listOf(key, getThumbKey(key)).distinct()) {
        val copied = imageCache.read(cacheKey) {
            data.withRescueInput { input ->
                file.outputStream().use { output -> readRescueBytes(input, output, checkpoint = checkpoint) }
            }
        }
        if (copied != null) return file
    }
    return null
}
