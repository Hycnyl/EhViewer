// Copyright 2026 Hycnyl. SPDX-License-Identifier: GPL-3.0-or-later
package com.hippo.ehviewer.spider

import android.os.ParcelFileDescriptor
import com.ehviewer.core.files.delete
import com.ehviewer.core.files.exists
import com.ehviewer.core.files.list
import com.ehviewer.core.files.mkdirs
import com.ehviewer.core.files.moveTo
import com.ehviewer.core.files.openFileDescriptor
import com.ehviewer.core.i18n.R
import com.ehviewer.core.model.BaseGalleryInfo
import com.ehviewer.core.model.GalleryInfo
import com.ehviewer.core.util.withIOContext
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.EhUtils.getSuitableTitle
import com.hippo.ehviewer.download.DownloadManager
import com.hippo.ehviewer.download.downloadLocation
import com.hippo.ehviewer.download.tempDownloadDir
import com.hippo.ehviewer.spider.SpiderQueen.Companion.SPIDER_INFO_FILENAME
import com.hippo.ehviewer.util.FileUtils
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import moe.tarsin.coroutines.runSuspendCatching
import okio.Path
import okio.Path.Companion.toOkioPath
import splitties.init.appCtx

internal enum class RescueFormat { LOOSE, CBZ }

internal data class LocalRescuePage(val page: RescuePageName, val path: Path?, val size: Long)

internal data class RescueInventory(
    val info: BaseGalleryInfo,
    val dirname: String,
    val directory: Path,
    val sources: List<LocalRescuePage>,
    val totalPages: Int,
    val knownTotal: Boolean,
    val scannedUpperBound: Int,
    val spiderInfo: SpiderInfo?,
    val warnings: List<String>,
) {
    val indices = sources.map { it.page.index }.distinct().sorted()
    val sourceBytes = sources.distinctBy { it.page }.sumOf { it.size }
}

internal data class RescueResult(
    val indices: List<Int>,
    val failedIndices: List<Int>,
    val totalPages: Int,
    val knownTotal: Boolean,
    val destinations: List<String>,
    val downloadRegistered: Boolean,
    val warnings: List<String>,
)

// All metadata comes from arguments, the local database, local XML/CBOR or committed cache
// snapshots. In particular, do not use SpiderDen.writeComicInfo() or a SpiderQueen here.
internal suspend fun inspectCachedPages(info: GalleryInfo, fallbackScanLimit: Int = UNKNOWN_RESCUE_SCAN): RescueInventory = withIOContext {
    require(info.gid > 0)
    val warnings = mutableListOf<String>()
    val existingDownload = DownloadManager.getDownloadInfo(info.gid)
    val download = existingDownload?.takeIf { info.token.isEmpty() || it.token == info.token }
    if (existingDownload != null && download == null) warnings += appCtx.getString(R.string.cached_export_token_conflict)
    val local = BaseGalleryInfo(
        gid = info.gid,
        token = info.token.ifEmpty { download?.token.orEmpty() },
        title = info.title?.takeIf { it.isNotBlank() } ?: download?.title ?: info.gid.toString(),
        titleJpn = info.titleJpn ?: download?.titleJpn,
        thumbKey = info.thumbKey ?: download?.thumbKey,
        category = info.category.takeIf { it != 0 } ?: download?.category ?: 0,
        posted = info.posted ?: download?.posted,
        uploader = info.uploader ?: download?.uploader,
        disowned = info.disowned,
        rating = info.rating,
        rated = info.rated,
        simpleTags = info.simpleTags ?: download?.simpleTags,
        pages = rescuePageCount(info.pages, download?.pages ?: 0),
        thumbWidth = info.thumbWidth,
        thumbHeight = info.thumbHeight,
        simpleLanguage = info.simpleLanguage ?: download?.simpleLanguage,
        favoriteSlot = info.favoriteSlot,
        favoriteName = info.favoriteName,
        favoriteNote = info.favoriteNote,
    )
    val dirname = existingDownload?.dirname ?: EhDB.getDownloadDirname(info.gid)
        ?: FileUtils.sanitizeFilename("${info.gid}-${getSuitableTitle(local)}")
    val directory = downloadLocation / dirname
    val directories = listOfNotNull(directory, local.tempDownloadDir).distinct()
    val spiders = buildList {
        directories.forEach { dir -> readCompatFromPath(dir / SPIDER_INFO_FILENAME)?.takeIf { it.gid == info.gid }?.let(::add) }
        runSuspendCatching { readFromCache(info.gid) }.getOrNull()?.takeIf { it.gid == info.gid }?.let(::add)
    }
    if (local.token.isEmpty()) local.token = spiders.firstOrNull()?.token.orEmpty()
    if (spiders.any { it.token != local.token }) warnings += appCtx.getString(R.string.cached_export_token_conflict)
    val matchingSpiders = spiders.filter { it.matchesIdentity(local) }
    val spider = matchingSpiders.firstOrNull { it.pages in 1..MAX_RESCUE_PAGES || it.cachedPageCount in 1..MAX_RESCUE_PAGES }
    val comicCounts = directories.mapNotNull { dir ->
        readComicInfo(dir / COMIC_INFO_FILE)?.let { comic ->
            comic.trustedPageCount(local).also { if (it == null) warnings += appCtx.getString(R.string.cached_export_metadata_preserved, COMIC_INFO_FILE) }
        }
    }
    val knownPages = rescuePageCount(local.pages, *matchingSpiders.filter { it.cachedPageCount == 0 }.map { it.pages }.toIntArray(), *comicCounts.toIntArray())
    val cachedRange = rescuePageCount(*matchingSpiders.map { it.cachedPageCount }.toIntArray())
    val largestFilePage = directories.flatMap { it.list() }.mapNotNull { parseRescuePageName(it.name)?.index?.plus(1) }.maxOrNull() ?: 0
    val scan = maxOf(knownPages.takeIf { it > 0 } ?: fallbackScanLimit.coerceIn(1, MAX_RESCUE_PAGES), largestFilePage, cachedRange)
    val sources = SpiderDen(local).inspectLocalPages(directories, scan) { name, error ->
        if (warnings.size < 100) warnings += appCtx.getString(R.string.cached_export_page_failed, name, error.localizedMessage.orEmpty())
    }
    val observed = sources.maxOfOrNull { it.page.index + 1 } ?: 0
    val total = rescuePageCount(knownPages, observed, cachedRange)
    val knownTotal = knownPages > 0 && knownPages >= maxOf(observed, cachedRange)
    local.pages = total
    if (sources.groupBy { it.page.index }.any { (_, pages) -> pages.map { it.page.extension }.distinct().size > 1 }) {
        warnings += appCtx.getString(R.string.cached_export_conflicts)
    }
    RescueInventory(local, dirname, directory, sources, total, knownTotal, scan, spider, warnings)
}

internal suspend fun exportCachedPages(info: GalleryInfo, format: RescueFormat, fallbackScanLimit: Int = UNKNOWN_RESCUE_SCAN, progress: (Int, Int) -> Unit): RescueResult = withIOContext {
    DownloadManager.withCachedPageExport(info.gid, appCtx.getString(R.string.cached_export_active)) {
        val inventory = inspectCachedPages(info, fallbackScanLimit)
        check(inventory.sources.isNotEmpty()) { appCtx.getString(R.string.cached_export_empty) }
        val warnings = inventory.warnings.toMutableList()
        fun warn(name: String, error: Throwable) {
            if (warnings.size < 100) warnings += appCtx.getString(R.string.cached_export_page_failed, name, error.localizedMessage.orEmpty())
        }
        val context = currentCoroutineContext()
        val checkpoint = { context.ensureActive() }
        val staging = File(appCtx.cacheDir, "cached-page-export/${UUID.randomUUID()}").apply { check(mkdirs()) }
        val den = SpiderDen(inventory.info)
        val staged = linkedMapOf<RescuePageName, File>()
        try {
            val candidates = inventory.sources.groupBy { it.page }
            candidates.entries.forEachIndexed { number, (page, sources) ->
                checkpoint()
                val file = File(staging, page.name)
                for (source in sources) {
                    val copied = runSuspendCatching { den.copyLocalPageTo(source, file) }.onFailure { warn(page.name, it) }.getOrNull()
                    if (copied != null) {
                        staged[page] = file
                        break
                    }
                }
                if (page !in staged) {
                    file.delete()
                    warnings += appCtx.getString(R.string.cached_export_source_gone, page.name)
                }
                progress(number + 1, candidates.size)
            }
            check(staged.isNotEmpty()) { appCtx.getString(R.string.cached_export_empty) }
            val comic = File(staging, COMIC_INFO_FILE)
            val existingComic = readComicInfo(inventory.directory / COMIC_INFO_FILE)?.takeIf { it.trustedPageCount(inventory.info) != null }
            val authoritativePages = if (inventory.knownTotal) inventory.totalPages else 0
            val comicWritten = runSuspendCatching {
                writeComicInfo((existingComic ?: inventory.info.getComicInfo()).copy(pageCount = authoritativePages), comic.toOkioPath())
                check(readComicInfo(comic.toOkioPath())?.pageCount == authoritativePages)
            }.onFailure { warn(COMIC_INFO_FILE, it) }.isSuccess
            val state = File(staging, SPIDER_INFO_FILENAME)
            val spider = inventory.spiderInfo
            val stateWritten = runSuspendCatching {
                SpiderInfo(
                    gid = info.gid,
                    token = inventory.info.token,
                    pages = authoritativePages,
                    pTokenMap = spider?.pTokenMap?.filter { (index, token) -> index in 0 until inventory.totalPages && token.isNotBlank() }?.toMutableMap() ?: hashMapOf(),
                    previewPerPage = spider?.previewPerPage ?: -1,
                    cachedPageCount = if (inventory.knownTotal) 0 else inventory.totalPages,
                ).write(state.toOkioPath())
                check(readCompatFromPath(state.toOkioPath())?.let { it.matchesIdentity(inventory.info) && it.pages == authoritativePages && (inventory.knownTotal || it.cachedPageCount == inventory.totalPages) } == true)
            }.onFailure { warn(SPIDER_INFO_FILENAME, it) }.isSuccess
            val metadata = listOfNotNull(comic.takeIf { comicWritten }, state.takeIf { stateWritten })

            val directory = inventory.directory.apply { mkdirs() }
            val destinations = mutableListOf<String>()
            val saved = mutableSetOf<Int>()
            val native = mutableSetOf<Int>()
            if (format == RescueFormat.CBZ) {
                val name = rescueArchiveName(info.gid) { (directory / it).exists() || (directory / rescueMarkerName(it)).exists() }
                val archive = File(staging, name)
                ZipOutputStream(archive.outputStream().buffered()).use { zip ->
                    zip.setLevel(Deflater.NO_COMPRESSION)
                    staged.forEach { (page, file) ->
                        checkpoint()
                        zip.putNextEntry(ZipEntry(page.name))
                        file.inputStream().use { readRescueBytes(it, zip, checkpoint = checkpoint) }
                        zip.closeEntry()
                    }
                    zip.putNextEntry(ZipEntry(COMIC_INFO_FILE))
                    if (comicWritten) {
                        comic.inputStream().use { readRescueBytes(it, zip, checkpoint = checkpoint) }
                    } else {
                        zip.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?><ComicInfo><PageCount>$authoritativePages</PageCount></ComicInfo>".toByteArray())
                    }
                    zip.closeEntry()
                }
                verifyRescueZip(archive, staged.keys.map { it.name }.toSet() + COMIC_INFO_FILE, checkpoint)
                commitRescueFile(archive, directory / name, checkpoint)
                destinations += (directory / name).toString()
                saved += staged.keys.map { it.index }
            }

            fun savePages(destinationDir: Path, keepExtraVersions: Boolean, exportOutput: Boolean): Set<Int> {
                val readable = mutableSetOf<Int>()
                runSuspendCatching { destinationDir.mkdirs() }.onFailure { warn(destinationDir.toString(), it) }
                val existing = destinationDir.list().sortedBy { it.name }.filter { parseRescuePageName(it.name) != null }
                    .groupBy { parseRescuePageName(it.name)!!.index }
                staged.entries.groupBy { it.key.index }.forEach { (index, versions) ->
                    checkpoint()
                    val primary = versions.first()
                    val old = existing[index].orEmpty()
                    val readableOld = old.firstOrNull { file ->
                        !(destinationDir / rescueMarkerName(file.name)).exists() && runSuspendCatching {
                            file.withRescueInput { readRescueBytes(it, extension = parseRescuePageName(file.name)!!.extension, checkpoint = checkpoint) }
                        }.isSuccess
                    }
                    val usable = readableOld != null || (
                        old.isEmpty() && runSuspendCatching {
                            commitRescueFile(primary.value, destinationDir / primary.key.name, checkpoint)
                        }.onFailure { warn(primary.key.name, it) }.isSuccess
                        )
                    if (usable) {
                        readable += index
                        if (exportOutput) saved += index
                    }
                    if (keepExtraVersions) {
                        versions.filter { it != primary || !usable || (readableOld != null && readableOld.name != it.key.name) }.forEach { (page, file) ->
                            val backup = directory / "cached-page-conflicts" / staging.name / page.name
                            runSuspendCatching {
                                backup.parent!!.mkdirs()
                                commitRescueFile(file, backup, checkpoint)
                                if (exportOutput) saved += index
                                destinations += backup.toString()
                                warnings += appCtx.getString(R.string.cached_export_conflict_saved, page.name)
                            }.onFailure { warn(page.name, it) }
                        }
                    }
                }
                if (readable.isNotEmpty()) destinations += destinationDir.toString()
                return readable
            }
            // The portable export format and the native download format are independent.
            val loosePages = if (format == RescueFormat.LOOSE) savePages(directory, true, true) else emptySet()
            val nativeDirectory = if (Settings.saveAsCbz.value) inventory.info.tempDownloadDir else directory
            if (nativeDirectory != null) {
                native += if (nativeDirectory == directory && format == RescueFormat.LOOSE) {
                    loosePages
                } else {
                    savePages(nativeDirectory, false, false)
                }
            }
            check(saved.isNotEmpty()) { appCtx.getString(R.string.cached_export_save_failed) }
            // Metadata is best effort. Never replace any existing file, including damaged metadata.
            metadata.forEach { file ->
                val destination = directory / file.name
                if (!destination.exists()) {
                    runSuspendCatching { commitRescueFile(file, destination, checkpoint) }.onFailure { warn(file.name, it) }
                } else {
                    val valid = if (file == comic) {
                        readComicInfo(destination)?.let { it.trustedPageCount(inventory.info) != null || (!inventory.knownTotal && it.pageCount == 0) } == true
                    } else {
                        readCompatFromPath(destination)?.let {
                            it.matchesIdentity(inventory.info) && if (inventory.knownTotal) {
                                it.pages >= inventory.totalPages
                            } else {
                                it.pages == 0 && it.cachedPageCount >= inventory.totalPages
                            }
                        } == true
                    }
                    if (!valid) warnings += appCtx.getString(R.string.cached_export_metadata_preserved, file.name)
                }
            }
            var registered = false
            val readableState = readCompatFromPath(directory / SPIDER_INFO_FILENAME)?.let {
                it.matchesIdentity(inventory.info) && if (inventory.knownTotal) {
                    it.cachedPageCount == 0 && it.pages in inventory.totalPages..MAX_RESCUE_PAGES
                } else {
                    it.pages == 0 && it.cachedPageCount in inventory.totalPages..MAX_RESCUE_PAGES
                }
            } == true
            if (!inventory.knownTotal) warnings += appCtx.getString(R.string.cached_export_reading_range, inventory.totalPages)
            if (native.containsAll(saved) && readableState) {
                runSuspendCatching {
                    EhDB.putDownloadDirname(info.gid, inventory.dirname)
                    inventory.info.pages = authoritativePages
                    val existing = DownloadManager.getDownloadInfo(info.gid)
                    if (existing == null) {
                        DownloadManager.restoreDownload(inventory.info, inventory.dirname)
                    } else if (inventory.knownTotal && existing.token == inventory.info.token && existing.pages < authoritativePages) {
                        existing.pages = authoritativePages
                        EhDB.putGalleryInfo(existing.galleryInfo)
                    }
                    registered = DownloadManager.getDownloadInfo(info.gid)?.token == inventory.info.token
                }.onFailure { warn(inventory.dirname, it) }
            }
            if (!registered) warnings += appCtx.getString(R.string.cached_export_not_registered)
            RescueResult(saved.sorted(), inventory.indices - saved, inventory.totalPages, inventory.knownTotal, destinations.distinct(), registered, warnings)
        } finally {
            // Only this invocation's private staging directory is removed. All sources and
            // already verified destination files survive errors and cancellation.
            staging.deleteRecursively()
        }
    }
}
