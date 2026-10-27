// Copyright 2026 Hycnyl. SPDX-License-Identifier: GPL-3.0-or-later
package com.hippo.ehviewer.spider

import com.ehviewer.core.files.exists
import com.ehviewer.core.files.list
import com.ehviewer.core.files.mkdirs
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
import java.util.UUID
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
    val skippedIndices: List<Int>,
    val skippedFiles: Int,
    val failedIndices: List<Int>,
    val totalPages: Int,
    val knownTotal: Boolean,
    val destinations: List<String>,
    val downloadRegistered: Boolean,
    val coverSaved: Boolean,
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
            comic.trustedPageCount(local).also { if (it == null) warnings += appCtx.getString(R.string.cached_export_metadata_unmatched, COMIC_INFO_FILE) }
        }
    }
    val knownPages = rescuePageCount(local.pages, *matchingSpiders.filter { it.cachedPageCount == 0 }.map { it.pages }.toIntArray(), *comicCounts.toIntArray())
    val cachedRange = rescuePageCount(*matchingSpiders.map { it.cachedPageCount }.toIntArray())
    val largestFilePage = directories.flatMap { it.list() }.mapNotNull { parseRescuePageName(it.name)?.index?.plus(1) }.maxOrNull() ?: 0
    val scan = maxOf(knownPages.takeIf { it > 0 } ?: fallbackScanLimit.coerceIn(1, MAX_RESCUE_PAGES), largestFilePage, cachedRange)
    val sources = inspectLocalPages(local.gid, directories, scan) { name, error ->
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

internal suspend fun exportCachedPages(
    info: GalleryInfo,
    format: RescueFormat,
    fallbackScanLimit: Int = UNKNOWN_RESCUE_SCAN,
    onConflict: suspend (Path) -> ExportFileDecision = { ExportFileDecision(ExportFileAction.SKIP) },
    progress: (Int, Int) -> Unit,
): RescueResult = withIOContext {
    DownloadManager.withCachedPageExport(info.gid, appCtx.getString(R.string.cached_export_active)) {
        val inventory = inspectCachedPages(info, fallbackScanLimit)
        check(inventory.sources.isNotEmpty()) { appCtx.getString(R.string.cached_export_empty) }
        val warnings = inventory.warnings.toMutableList()
        fun warn(name: String, error: Throwable) {
            if (warnings.size < 100) warnings += appCtx.getString(R.string.cached_export_page_failed, name, error.localizedMessage.orEmpty())
        }
        val context = currentCoroutineContext()
        val checkpoint = { context.ensureActive() }
        val output = CachedPageOutput(checkpoint, onConflict)
        val staging = File(appCtx.cacheDir, "cached-page-export/${UUID.randomUUID()}").apply { check(mkdirs()) }
        val staged = linkedMapOf<RescuePageName, File>()
        val fingerprints = mutableMapOf<RescuePageName, RescueFingerprint>()
        try {
            val candidates = inventory.sources.groupBy { it.page }
            candidates.entries.forEachIndexed { number, (page, sources) ->
                checkpoint()
                val file = File(staging, page.name)
                for (source in sources) {
                    val copied = runSuspendCatching { copyLocalPageTo(info.gid, source, file) }.onFailure { warn(page.name, it) }.getOrNull()
                    if (copied != null) {
                        staged[page] = file
                        fingerprints[page] = copied
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
            val authoritativePages = if (inventory.knownTotal) inventory.totalPages else 0
            val comic = File(staging, COMIC_INFO_FILE)
            val existingComic = readComicInfo(inventory.directory / COMIC_INFO_FILE)?.takeIf { it.trustedPageCount(inventory.info) != null }
            val comicWritten = runSuspendCatching {
                writeComicInfo((existingComic ?: inventory.info.getComicInfo()).copy(pageCount = authoritativePages), comic.toOkioPath())
            }.onFailure { warn(COMIC_INFO_FILE, it) }.isSuccess
            val metadata = mutableListOf<File>()
            if (comicWritten) metadata += comic
            if (format == RescueFormat.LOOSE) {
                val state = File(staging, SPIDER_INFO_FILENAME)
                val spider = inventory.spiderInfo
                runSuspendCatching {
                    SpiderInfo(
                        gid = info.gid,
                        token = inventory.info.token,
                        pages = authoritativePages,
                        pTokenMap = spider?.pTokenMap?.filter { (index, token) -> index in 0 until inventory.totalPages && token.isNotBlank() }?.toMutableMap() ?: hashMapOf(),
                        previewPerPage = spider?.previewPerPage ?: -1,
                        cachedPageCount = if (inventory.knownTotal) 0 else inventory.totalPages,
                    ).write(state.toOkioPath())
                    metadata += state
                }.onFailure { warn(SPIDER_INFO_FILENAME, it) }
            }

            val directory = inventory.directory.apply { mkdirs() }
            val destinations = mutableListOf<String>()
            val saved = mutableSetOf<Int>()
            val skipped = mutableSetOf<Int>()
            var nativeReady = false
            if (format == RescueFormat.CBZ) {
                // Managed CBZ files are read in entry order by the original archive loader.
                // Match normal downloads: one image per page, sorted by original page number.
                val pages = staged.entries.distinctBy { it.key.index }.sortedBy { it.key.index }
                val name = "${info.gid}.cbz"
                val archive = File(staging, name)
                val archiveComic = if (comicWritten) {
                    comic
                } else {
                    File(staging, "fallback.xml").apply {
                        writeText("<?xml version=\"1.0\" encoding=\"UTF-8\"?><ComicInfo><PageCount>$authoritativePages</PageCount></ComicInfo>")
                    }
                }
                ZipOutputStream(archive.outputStream().buffered()).use { zip ->
                    fun putStored(name: String, file: File, fingerprint: RescueFingerprint) {
                        zip.putNextEntry(
                            ZipEntry(name).apply {
                                method = ZipEntry.STORED
                                size = fingerprint.size
                                compressedSize = fingerprint.size
                                crc = fingerprint.crc
                            },
                        )
                        file.inputStream().use { readRescueBytes(it, zip, checkpoint = checkpoint) }
                        zip.closeEntry()
                    }
                    pages.forEach { (page, file) ->
                        checkpoint()
                        putStored(page.name, file, fingerprints.getValue(page))
                    }
                    putStored(COMIC_INFO_FILE, archiveComic, archiveComic.inputStream().use { readRescueBytes(it, checkpoint = checkpoint) })
                }
                verifyRescueZip(archive, pages.map { it.key.name }.toSet() + COMIC_INFO_FILE, checkpoint)
                val target = directory / name
                if (output.write(archive, target)) saved += pages.map { it.key.index } else skipped += pages.map { it.key.index }
                nativeReady = isReadableRescueFile(target)
                destinations += target.toString()
            } else {
                suspend fun savePages(destination: Path, exported: Boolean): Set<Int> {
                    destination.mkdirs()
                    staged.forEach { (page, file) ->
                        runSuspendCatching {
                            val written = output.write(file, destination / page.name)
                            if (exported) {
                                if (written) saved += page.index else skipped += page.index
                            }
                        }.onFailure { warn(page.name, it) }
                    }
                    return destination.list().mapNotNull { file ->
                        parseRescuePageName(file.name)?.takeIf { isReadableRescueFile(file) }?.index
                    }.toSet()
                }
                val loose = savePages(directory, true)
                val nativeDirectory = if (Settings.saveAsCbz.value) inventory.info.tempDownloadDir else directory
                val native = if (nativeDirectory == directory) loose else nativeDirectory?.let { savePages(it, false) }.orEmpty()
                nativeReady = native.containsAll(saved + skipped)
                destinations += directory.toString()
            }
            check(saved.isNotEmpty() || skipped.isNotEmpty()) { appCtx.getString(R.string.cached_export_save_failed) }
            metadata.forEach { file ->
                runSuspendCatching { output.write(file, directory / file.name) }.onFailure { warn(file.name, it) }
            }
            // DownloadThumbInterceptor normally creates this file when the list displays a
            // cover. Rescue must persist an already cached cover without that UI side effect.
            runSuspendCatching { copyCachedCoverTo(inventory.info, staging, checkpoint) }.onFailure { warn("thumb", it) }.getOrNull()?.let { cover ->
                runSuspendCatching { output.write(cover, directory / cover.name) }.onFailure { warn(cover.name, it) }
            }
            val coverSaved = directory.list().any { it.name.startsWith("thumb.") && isReadableRescueFile(it) }
            if (!coverSaved) warnings += appCtx.getString(R.string.cached_export_cover_unavailable)
            val readableState = format == RescueFormat.CBZ || readCompatFromPath(directory / SPIDER_INFO_FILENAME)?.let {
                it.matchesIdentity(inventory.info) && (
                    (it.cachedPageCount == 0 && it.pages in inventory.totalPages..MAX_RESCUE_PAGES) ||
                        (it.cachedPageCount in 1..MAX_RESCUE_PAGES && (it.cachedPageCount >= inventory.totalPages || inventory.knownTotal))
                    )
            } == true
            var registered = false
            if (nativeReady && readableState) {
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
            RescueResult(
                indices = saved.sorted(),
                skippedIndices = (skipped - saved).sorted(),
                skippedFiles = output.skippedFiles,
                failedIndices = inventory.indices - saved - skipped,
                totalPages = inventory.totalPages,
                knownTotal = inventory.knownTotal,
                destinations = destinations.distinct(),
                downloadRegistered = registered,
                coverSaved = coverSaved,
                warnings = warnings,
            )
        } finally {
            staging.deleteRecursively()
        }
    }
}

private fun isReadableRescueFile(file: Path): Boolean = !(file.parent!! / rescueMarkerName(file.name)).exists() &&
    runCatching { file.withRescueInput { it.read() >= 0 } }.getOrDefault(false)
