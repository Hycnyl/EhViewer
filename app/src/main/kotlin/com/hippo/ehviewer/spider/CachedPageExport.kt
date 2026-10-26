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
    val indices get() = sources.map { it.page.index }.distinct().sorted()
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
internal suspend fun inspectCachedPages(info: GalleryInfo): RescueInventory = withIOContext {
    require(info.gid > 0)
    val warnings = mutableListOf<String>()
    val download = DownloadManager.getDownloadInfo(info.gid)
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
    val dirname = download?.dirname ?: EhDB.getDownloadDirname(info.gid)
        ?: FileUtils.sanitizeFilename("${info.gid}-${getSuitableTitle(local)}")
    val directory = downloadLocation / dirname
    val directories = listOfNotNull(directory, local.tempDownloadDir).distinct()
    val spiders = buildList {
        directories.forEach { dir -> readCompatFromPath(dir / SPIDER_INFO_FILENAME)?.takeIf { it.gid == info.gid }?.let(::add) }
        runSuspendCatching { readFromCache(info.gid) }.getOrNull()?.takeIf { it.gid == info.gid }?.let(::add)
    }
    if (local.token.isEmpty()) local.token = spiders.firstOrNull()?.token.orEmpty()
    if (spiders.any { it.token != local.token }) warnings += appCtx.getString(R.string.cached_export_token_conflict)
    val spider = spiders.firstOrNull { it.token == local.token && it.pages in 1..MAX_RESCUE_PAGES }
    val comicCounts = directories.mapNotNull { readComicInfo(it / COMIC_INFO_FILE)?.pageCount }
    val knownPages = rescuePageCount(local.pages, *spiders.map { it.pages }.toIntArray(), *comicCounts.toIntArray())
    val largestFilePage = directories.flatMap { it.list() }.mapNotNull { parseRescuePageName(it.name)?.index?.plus(1) }.maxOrNull() ?: 0
    val scan = maxOf(knownPages.takeIf { it > 0 } ?: UNKNOWN_RESCUE_SCAN, largestFilePage)
    val sources = SpiderDen(local).inspectLocalPages(directories, scan) { name, error ->
        if (warnings.size < 100) warnings += appCtx.getString(R.string.cached_export_page_failed, name, error.localizedMessage.orEmpty())
    }
    val total = rescuePageCount(knownPages, sources.maxOfOrNull { it.page.index + 1 } ?: 0)
    local.pages = total
    if (sources.groupBy { it.page.index }.any { (_, pages) -> pages.map { it.page.extension }.distinct().size > 1 }) {
        warnings += appCtx.getString(R.string.cached_export_conflicts)
    }
    RescueInventory(local, dirname, directory, sources, total, knownPages > 0, scan, spider, warnings)
}

internal suspend fun exportCachedPages(info: GalleryInfo, format: RescueFormat, progress: (Int, Int) -> Unit): RescueResult = withIOContext {
    DownloadManager.withCachedPageExport(info.gid, appCtx.getString(R.string.cached_export_active)) {
        val inventory = inspectCachedPages(info)
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
            val existingComic = readComicInfo(inventory.directory / COMIC_INFO_FILE)?.takeIf { it.pageCount in 1..MAX_RESCUE_PAGES }
            val comicWritten = runSuspendCatching {
                writeComicInfo((existingComic ?: inventory.info.getComicInfo()).copy(pageCount = inventory.totalPages), comic.toOkioPath())
                check(readComicInfo(comic.toOkioPath())?.pageCount == inventory.totalPages)
            }.onFailure { warn(COMIC_INFO_FILE, it) }.isSuccess
            val state = File(staging, SPIDER_INFO_FILENAME)
            val spider = inventory.spiderInfo
            val stateWritten = runSuspendCatching {
                SpiderInfo(
                    gid = info.gid,
                    token = inventory.info.token,
                    pages = inventory.totalPages,
                    pTokenMap = spider?.pTokenMap?.filter { (index, token) -> index in 0 until inventory.totalPages && token.isNotBlank() }?.toMutableMap() ?: hashMapOf(),
                    previewPerPage = spider?.previewPerPage ?: -1,
                ).write(state.toOkioPath())
                check(readCompatFromPath(state.toOkioPath())?.let { it.gid == info.gid && it.pages == inventory.totalPages && it.token == inventory.info.token } == true)
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
                        zip.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?><ComicInfo><PageCount>${inventory.totalPages}</PageCount></ComicInfo>".toByteArray())
                    }
                    zip.closeEntry()
                }
                verifyRescueZip(archive, staged.keys.map { it.name }.toSet() + COMIC_INFO_FILE, checkpoint)
                commitRescueFile(archive, directory / name, checkpoint)
                destinations += (directory / name).toString()
                saved += staged.keys.map { it.index }
            }

            val nativeDirectory = if (format == RescueFormat.LOOSE) directory else inventory.info.tempDownloadDir
            if (nativeDirectory != null) {
                runSuspendCatching { nativeDirectory.mkdirs() }.onFailure { warn(nativeDirectory.toString(), it) }
                val existing = nativeDirectory.list().filter { parseRescuePageName(it.name) != null }.groupBy { parseRescuePageName(it.name)!!.index }
                staged.entries.groupBy { it.key.index }.forEach { (index, versions) ->
                    checkpoint()
                    val primary = versions.first()
                    val old = existing[index].orEmpty()
                    val readableOld = old.firstOrNull { file ->
                        !(nativeDirectory / rescueMarkerName(file.name)).exists() && runSuspendCatching {
                            file.withRescueInput { readRescueBytes(it, extension = parseRescuePageName(file.name)!!.extension, checkpoint = checkpoint) }
                        }.isSuccess
                    }
                    val usable = readableOld != null || (
                        old.isEmpty() && runSuspendCatching {
                            commitRescueFile(primary.value, nativeDirectory / primary.key.name, checkpoint)
                        }.onFailure { warn(primary.key.name, it) }.isSuccess
                        )
                    if (usable) {
                        native += index
                        if (format == RescueFormat.LOOSE) saved += index
                    }
                    if (format == RescueFormat.LOOSE) {
                        versions.filter { it != primary || !usable || (readableOld != null && readableOld.name != it.key.name) }.forEach { (page, file) ->
                            val backup = directory / "cached-page-conflicts" / staging.name / page.name
                            runSuspendCatching {
                                backup.parent!!.mkdirs()
                                commitRescueFile(file, backup, checkpoint)
                                saved += index
                                destinations += backup.toString()
                                warnings += appCtx.getString(R.string.cached_export_conflict_saved, page.name)
                            }.onFailure { warn(page.name, it) }
                        }
                    }
                }
                if (native.isNotEmpty()) destinations += nativeDirectory.toString()
            }
            check(saved.isNotEmpty()) { appCtx.getString(R.string.cached_export_save_failed) }
            // Metadata is best effort. Never replace any existing file, including damaged metadata.
            metadata.forEach { file ->
                val destination = directory / file.name
                if (!destination.exists()) {
                    runSuspendCatching { commitRescueFile(file, destination, checkpoint) }.onFailure { warn(file.name, it) }
                } else {
                    val valid = if (file == comic) readComicInfo(destination) != null else readCompatFromPath(destination)?.let { it.gid == info.gid && it.token == inventory.info.token && it.pages >= inventory.totalPages } == true
                    if (!valid) warnings += appCtx.getString(R.string.cached_export_metadata_preserved, file.name)
                }
            }
            var registered = false
            if (native.containsAll(saved)) {
                runSuspendCatching {
                    EhDB.putDownloadDirname(info.gid, inventory.dirname)
                    if (!DownloadManager.containDownloadInfo(info.gid)) DownloadManager.restoreDownload(inventory.info, inventory.dirname)
                    registered = DownloadManager.containDownloadInfo(info.gid)
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

private inline fun <T> Path.withRescueInput(block: (java.io.InputStream) -> T): T = ParcelFileDescriptor.AutoCloseInputStream(openFileDescriptor("r")).use(block)

// Write .tmp, close, re-read and verify CRC/length, then publish. The marker also protects
// readers after a crash on SAF providers which cannot rename. SpiderDen ignores marked pages.
private fun commitRescueFile(source: File, destination: Path, checkpoint: () -> Unit) {
    val marker = destination.parent!! / rescueMarkerName(destination.name)
    check(!destination.exists() && !marker.exists()) { "Destination already exists: $destination" }
    val temporary = destination.parent!! / ".${destination.name}.${UUID.randomUUID()}.tmp"
    val expected = source.inputStream().use { readRescueBytes(it, checkpoint = checkpoint) }
    var ownsDestination = false
    var ownsMarker = false
    var committed = false
    try {
        source.inputStream().use { input ->
            ParcelFileDescriptor.AutoCloseOutputStream(temporary.openFileDescriptor("wt")).use { readRescueBytes(input, it, checkpoint = checkpoint) }
        }
        check(temporary.withRescueInput { readRescueBytes(it, checkpoint = checkpoint) } == expected) { "Staging verification failed" }
        checkpoint()
        check(!destination.exists() && !marker.exists()) { "Destination appeared during export: $destination" }
        ownsMarker = true
        ParcelFileDescriptor.AutoCloseOutputStream(marker.openFileDescriptor("wt")).use { it.write(destination.name.toByteArray()) }
        ownsDestination = true
        runSuspendCatching { temporary moveTo destination }
        if (!destination.exists()) {
            temporary.withRescueInput { input ->
                ParcelFileDescriptor.AutoCloseOutputStream(destination.openFileDescriptor("wt")).use { readRescueBytes(input, it, checkpoint = checkpoint) }
            }
        }
        check(destination.withRescueInput { readRescueBytes(it, checkpoint = checkpoint) } == expected) { "Destination verification failed" }
        marker.delete()
        check(!marker.exists()) { "Cannot remove incomplete marker: $marker" }
        committed = true
    } finally {
        runCatching { temporary.delete() }
        if (!committed && ownsDestination) runCatching { destination.delete() }
        // If cleanup cannot remove the incomplete destination, retain its reader guard.
        if (!committed && ownsMarker && !destination.exists()) runCatching { marker.delete() }
    }
}
