// Copyright 2026 Hycnyl. SPDX-License-Identifier: GPL-3.0-or-later
package com.hippo.ehviewer.spider

import android.app.Application
import android.os.Environment
import android.os.ParcelFileDescriptor
import com.ehviewer.core.database.model.DownloadInfo
import com.ehviewer.core.files.exists
import com.ehviewer.core.files.mkdirs
import com.ehviewer.core.files.openFileDescriptor
import com.ehviewer.core.model.BaseGalleryInfo
import com.hippo.ehviewer.EhApplication
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.getImageKey
import com.hippo.ehviewer.coil.edit
import com.hippo.ehviewer.download.DownloadManager
import com.hippo.ehviewer.download.archiveFile
import com.hippo.ehviewer.download.downloadLocation
import com.hippo.ehviewer.download.tempDownloadDir
import java.io.File
import java.io.IOException
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.Path
import okio.Path.Companion.toOkioPath
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowEnvironment
import splitties.init.injectAsAppCtx

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class CachedPageCompatibilityTest {
    @Before
    fun initializeContext() {
        RuntimeEnvironment.getApplication().injectAsAppCtx()
        ShadowEnvironment.setExternalStorageState(Environment.MEDIA_MOUNTED)
        clearDownloadFixtures()
    }

    @After
    fun clearDownloadFixtures() = runBlocking(Dispatchers.IO) {
        // Robolectric can reuse the manager singleton between methods. A start-all test
        // must not enqueue unfinished galleries created by a different fixture.
        DownloadManager.stopAllDownload()
        DownloadManager.deleteRangeDownload(DownloadManager.downloadInfoList.map { it.gid }.toLongArray())
    }

    private val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=")

    @Test
    fun bothFormatsSaveCachedCoverAndCbzUsesTheNativeArchivePath() = runBlocking(Dispatchers.IO) {
        val root = File(RuntimeEnvironment.getApplication().filesDir, "cover-downloads").apply { mkdirs() }
        setDownloadLocation(root.toOkioPath())
        setModePreference(false)
        for (format in RescueFormat.entries) {
            val gid = 8100L + format.ordinal
            val info = BaseGalleryInfo(gid = gid, token = "cover", title = "cover", pages = 3, thumbKey = "cover/$gid.png")
            val dirname = "$gid-cover"
            EhDB.putDownloadDirname(gid, dirname)
            val source = info.tempDownloadDir!!.apply { mkdirs() }
            (if (format == RescueFormat.CBZ) listOf(2) else listOf(0, 2)).forEach { (source / perFilename(it, "png")).toFile().writeBytes(png) }
            if (format == RescueFormat.CBZ) {
                EhApplication.imageCache.edit(getImageKey(gid, 0)) {
                    metadata.toFile().writeText("png")
                    data.toFile().writeBytes(png)
                }
            }
            EhApplication.imageCache.edit(info.thumbKey!!) {
                metadata.toFile().writeText("cover")
                data.toFile().writeBytes(png)
            }
            val result = exportCachedPages(info, format) { _, _ -> }
            val dir = downloadLocation / dirname
            assertTrue(result.downloadRegistered)
            assertArrayEquals(png, read(dir / "thumb.png"))
            if (format == RescueFormat.CBZ) {
                assertEquals(dir / "$gid.cbz", DownloadManager.getDownloadInfo(gid)!!.archiveFile)
                assertFalse((dir / SpiderQueen.SPIDER_INFO_FILENAME).exists())
                assertFalse((dir / perFilename(0, "png")).exists())
                ZipFile((dir / "$gid.cbz").toFile()).use { zip ->
                    assertEquals(listOf("00000001.png", "00000003.png", COMIC_INFO_FILE), zip.entries().asSequence().map { it.name }.toList())
                    assertTrue(zip.entries().asSequence().all { it.method == ZipEntry.STORED })
                    assertArrayEquals(png, zip.getInputStream(zip.getEntry("00000003.png")).readBytes())
                }
            }
        }
    }

    @Test
    fun looseExportResumesInEitherModeAndCbzUsesTheOriginalArchiveFastPath() = runBlocking(Dispatchers.IO) {
        val root = File(RuntimeEnvironment.getApplication().filesDir, "compatibility-downloads").apply { mkdirs() }
        setDownloadLocation(root.toOkioPath())
        var gid = 8200L
        for (cbz in listOf(false, true)) {
            for (format in RescueFormat.entries) {
                for (switchMode in listOf(false, true)) {
                    val scenario = "saveAsCbz=$cbz, export=$format, switch=$switchMode"
                    val info = BaseGalleryInfo(gid = ++gid, token = "local-token", title = "fixture", pages = 3)
                    val dirname = "${info.gid}-fixture"
                    EhDB.putDownloadDirname(info.gid, dirname)
                    setModePreference(cbz)
                    val download = downloadLocation / dirname
                    val temporary = info.tempDownloadDir!!
                    // Put source pages in the opposite native directory. Real file I/O, not
                    // a mocked "exists" answer, must materialize the current mode's pages.
                    val source = if (cbz) download else temporary
                    source.mkdirs()
                    listOf(0, 2).forEach { (source / perFilename(it, "png")).toFile().writeBytes(png) }
                    val result = exportCachedPages(info, format) { _, _ -> }
                    assertEquals(scenario, listOf(0, 2), (result.indices + result.skippedIndices).distinct().sorted())
                    assertEquals(scenario, 3, result.totalPages)
                    assertTrue(scenario, result.downloadRegistered)
                    val entry = DownloadManager.getDownloadInfo(info.gid)!!
                    assertEquals(scenario, DownloadInfo.STATE_NONE, entry.state)
                    if (format == RescueFormat.CBZ) {
                        val archive = download / "${info.gid}.cbz"
                        assertEquals(scenario, archive, entry.archiveFile)
                        ZipFile(archive.toFile()).use { zip ->
                            listOf(0, 2).forEach { index ->
                                assertArrayEquals(scenario, png, zip.getInputStream(zip.getEntry(perFilename(index, "png"))).readBytes())
                            }
                        }
                        setModePreference(if (switchMode) !cbz else cbz)
                        withTimeout(1500) { DownloadManager.startDownload(info, null) }
                        withTimeout(1500) { while (entry.state != DownloadInfo.STATE_FINISH) delay(10) }
                        listOf(0, 2).forEach { assertArrayEquals(scenario, png, read(source / perFilename(it, "png"))) }
                        DownloadManager.stopAllDownload()
                        continue
                    }
                    assertNull(scenario, entry.archiveFile)
                    val native = if (cbz) temporary else download
                    listOf(0, 2).forEach { assertArrayEquals(scenario, png, read(native / perFilename(it, "png"))) }
                    // Loose rescue must not trigger archive cleanup on cancel/retry.
                    val paused = SpiderDen(info, dirname).apply { initDownloadDirIfExist() }
                    assertFalse(scenario, paused.postArchive())
                    listOf(0, 2).forEach { assertArrayEquals(scenario, png, read(source / perFilename(it, "png"))) }

                    setModePreference(if (switchMode) !cbz else cbz)
                    val target = if (Settings.saveAsCbz.value) temporary else download
                    val den = SpiderDen(info, dirname).apply {
                        initDownloadDirIfExist()
                        setMode(SpiderQueen.MODE_DOWNLOAD)
                    }
                    for (index in listOf(0, 2)) {
                        assertTrue(scenario, index in den)
                        den.getImageSource(index).use { page ->
                            assertEquals(scenario, target, page.source.parent)
                            assertArrayEquals(scenario, png, read(page.source))
                        }
                    }
                    assertFalse(scenario, 1 in den)
                    // Supply the one genuinely missing page, then check every archive input
                    // through the normal reader accessor before normal post-archive cleanup.
                    (target / perFilename(1, "png")).toFile().writeBytes(png)
                    val complete = SpiderDen(info, dirname).apply {
                        initDownloadDirIfExist()
                        setMode(SpiderQueen.MODE_DOWNLOAD)
                    }
                    val archive = download / "${info.gid}.cbz"
                    ZipOutputStream(archive.toFile().outputStream()).use { zip ->
                        repeat(info.pages) { index ->
                            assertTrue(scenario, index in complete)
                            complete.getImageSource(index).use { page ->
                                assertEquals(scenario, target, page.source.parent)
                                val bytes = read(page.source)
                                assertArrayEquals(scenario, png, bytes)
                                zip.putNextEntry(ZipEntry(perFilename(index, page.type)))
                                zip.write(bytes)
                                zip.closeEntry()
                            }
                        }
                    }
                    assertEquals(scenario, archive, entry.archiveFile)
                    assertEquals(scenario, Settings.saveAsCbz.value, complete.postArchive())
                }
            }
        }
    }

    @Test
    fun mismatchedMetadataDoesNotBecomeAnAuthoritativePageCount() = runBlocking(Dispatchers.IO) {
        val root = File(RuntimeEnvironment.getApplication().filesDir, "metadata-downloads").apply { mkdirs() }
        setDownloadLocation(root.toOkioPath())
        val info = BaseGalleryInfo(gid = 8501, token = "current", title = "metadata", pages = 0)
        EhDB.putDownloadDirname(info.gid, "8501-metadata")
        val dir = (downloadLocation / "8501-metadata").apply { mkdirs() }
        (dir / perFilename(2, "png")).toFile().writeBytes(png)
        SpiderInfo(info.gid, "different-token", 5000).write(dir / SpiderQueen.SPIDER_INFO_FILENAME)
        writeComicInfo(info.getComicInfo().copy(pageCount = 6000, web = "https://exhentai.org/g/999/foreign/"), dir / COMIC_INFO_FILE)
        val inventory = inspectCachedPages(info)
        assertFalse(inventory.knownTotal)
        assertEquals(3, inventory.totalPages)
        assertEquals(UNKNOWN_RESCUE_SCAN, inventory.scannedUpperBound)
        // Even a count supplied by the caller cannot make foreign on-disk reading
        // metadata usable. Preserve it and report that native handoff is incomplete.
        info.pages = 3
        val result = exportCachedPages(info, RescueFormat.LOOSE) { _, _ -> }
        assertFalse(result.downloadRegistered)
        assertFalse(DownloadManager.containDownloadInfo(info.gid))
        assertEquals("different-token", readCompatFromPath(dir / SpiderQueen.SPIDER_INFO_FILENAME)!!.token)
    }

    @Test
    fun unknownTotalStillReadsOfflineAndCannotBecomeAFakeCompletedDownload() = runBlocking(Dispatchers.IO) {
        val root = File(RuntimeEnvironment.getApplication().filesDir, "unknown-downloads").apply { mkdirs() }
        setDownloadLocation(root.toOkioPath())
        setModePreference(false)
        val info = BaseGalleryInfo(gid = 8601, token = "unknown", title = "unknown", pages = 0)
        EhDB.putDownloadDirname(info.gid, "8601-unknown")
        val directory = (downloadLocation / "8601-unknown").apply { mkdirs() }
        listOf(2, 7).forEach { (directory / perFilename(it, "png")).toFile().writeBytes(png) }
        val result = exportCachedPages(info, RescueFormat.LOOSE) { _, _ -> }
        assertTrue(result.downloadRegistered)
        assertFalse(result.knownTotal)
        assertEquals(8, result.totalPages)
        val state = readCompatFromPath(directory / SpiderQueen.SPIDER_INFO_FILENAME)!!
        assertEquals(0, state.pages)
        assertEquals(8, state.cachedPageCount)
        assertEquals(0, readComicInfo(directory / COMIC_INFO_FILE)!!.pageCount)
        val entry = DownloadManager.getDownloadInfo(info.gid)!!
        assertEquals(0, entry.pages)
        val queen = SpiderQueen.obtainSpiderQueen(entry, SpiderQueen.MODE_READ)
        try {
            withTimeout(3000) { queen.awaitReady() }
            assertEquals(8, queen.size)
            listOf(2, 7).forEach { index -> queen.spiderDen.getImageSource(index).use { assertArrayEquals(png, read(it.source)) } }
            assertTrue(runCatching { DownloadManager.withCachedPageExport(info.gid, "busy") {} }.isFailure)
        } finally {
            SpiderQueen.releaseSpiderQueen(queen, SpiderQueen.MODE_READ)
            withTimeout(3000) { queen.coroutineContext.job.join() }
        }
        // Reader cleanup must persist the unknown-total marker, not its temporary size.
        assertEquals(0, readCompatFromPath(directory / SpiderQueen.SPIDER_INFO_FILENAME)!!.pages)
        withTimeout(1500) { DownloadManager.startDownload(entry.galleryInfo, null) }
        assertEquals(DownloadInfo.STATE_NONE, entry.state)
        assertNull(entry.archiveFile)
        val again = inspectCachedPages(entry)
        assertFalse(again.knownTotal)
        val trusted = BaseGalleryInfo(gid = info.gid, token = info.token, pages = 36)
        assertEquals(36, state.forLocalReading(trusted).pages)
        assertEquals(0, state.forLocalReading(trusted).cachedPageCount)
        // Creating a standard CBZ also works alongside an older loose rescue marker.
        exportCachedPages(entry.galleryInfo, RescueFormat.CBZ) { _, _ -> }
        assertEquals(directory / "${info.gid}.cbz", entry.archiveFile)
        withTimeout(1500) { DownloadManager.startDownload(entry.galleryInfo, null) }
        withTimeout(1500) { while (entry.state != DownloadInfo.STATE_FINISH) delay(10) }
        DownloadManager.stopAllDownload()
    }

    @Test
    fun exportingGalleryDoesNotBlockUnrelatedDownloadQueue() = runBlocking(Dispatchers.IO) {
        val root = File(RuntimeEnvironment.getApplication().filesDir, "queue-downloads").apply { mkdirs() }
        setDownloadLocation(root.toOkioPath())
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val rescue = async {
            DownloadManager.withCachedPageExport(8401, "busy") {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()
        try {
            val other = BaseGalleryInfo(gid = 8402, token = "other", title = "other", pages = 1)
            EhDB.putDownloadDirname(other.gid, "8402-other")
            val directory = (root.toOkioPath() / "8402-other").apply { mkdirs() }
            (directory / "8402.cbz").toFile().writeBytes(byteArrayOf(1))
            // The complete-archive fast path exercises actual queue/state notification and
            // avoids any gallery network request or native archiver in the JVM environment.
            withTimeout(1500) { DownloadManager.startDownload(other, null) }
            assertTrue(DownloadManager.containDownloadInfo(other.gid))
            assertFalse(release.isCompleted)
            withTimeout(1500) { while (DownloadManager.getDownloadState(other.gid) != DownloadInfo.STATE_FINISH) delay(10) }
            DownloadManager.stopAllDownload()
        } finally {
            release.complete(Unit)
            rescue.await()
        }
    }

    @Test
    fun conflictChoicesApplyToPagesArchivesCoverAndMetadataForThisExportOnly() = runBlocking(Dispatchers.IO) {
        val root = File(RuntimeEnvironment.getApplication().filesDir, "conflict-downloads").apply { mkdirs() }
        setDownloadLocation(root.toOkioPath())
        setModePreference(false)
        for (format in RescueFormat.entries) {
            val gid = 8800L + format.ordinal
            val info = BaseGalleryInfo(gid = gid, token = "conflicts", title = "conflicts", pages = 3, thumbKey = "conflict/$gid.png")
            val dirname = "$gid-conflicts"
            EhDB.putDownloadDirname(gid, dirname)
            val source = info.tempDownloadDir!!.apply { mkdirs() }
            listOf(0, 2).forEach { (source / perFilename(it, "png")).toFile().writeBytes(png) }
            EhApplication.imageCache.edit(info.thumbKey!!) {
                metadata.toFile().writeText("cover")
                data.toFile().writeBytes(png)
            }
            val dir = downloadLocation / dirname
            dir.mkdirs()
            val orphan = dir / rescueMarkerName(if (format == RescueFormat.CBZ) "$gid.cbz" else perFilename(0, "png"))
            orphan.toFile().writeText("interrupted export")
            exportCachedPages(info, format, onConflict = { error("New destination must not ask") }) { _, _ -> }
            assertFalse(orphan.exists())
            val cover = dir / "thumb.png"
            val previousCover = "previous cover".toByteArray()
            cover.toFile().writeBytes(previousCover)
            info.pages = 36
            var asks = 0
            val skipped = exportCachedPages(info, format, onConflict = {
                asks++
                ExportFileDecision(ExportFileAction.SKIP, remember = true)
            }) { _, _ -> }
            assertEquals(1, asks)
            assertTrue(skipped.indices.isEmpty())
            assertEquals(listOf(0, 2), skipped.skippedIndices)
            assertTrue(skipped.skippedFiles >= 3)
            assertArrayEquals(previousCover, read(cover))
            assertEquals(3, readComicInfo(dir / COMIC_INFO_FILE)!!.pageCount)

            val overwritten = exportCachedPages(info, format, onConflict = {
                asks++
                ExportFileDecision(ExportFileAction.OVERWRITE, remember = true)
            }) { _, _ -> }
            assertEquals(2, asks) // Remembered skip from the preceding export was not retained.
            assertEquals(listOf(0, 2), overwritten.indices)
            assertEquals(0, overwritten.skippedFiles)
            assertArrayEquals(png, read(cover))
            assertEquals(36, readComicInfo(dir / COMIC_INFO_FILE)!!.pageCount)
            if (format == RescueFormat.CBZ) {
                ZipFile((dir / "$gid.cbz").toFile()).use { zip ->
                    assertArrayEquals(png, zip.getInputStream(zip.getEntry("00000003.png")).readBytes())
                }
            }
            assertFalse(dir.toFile().listFiles()!!.any { it.name.endsWith(".previous") || it.name.endsWith(".tmp") })
        }
    }

    @Test
    fun interruptedOverwriteRestoresThePreviousFileIncludingEmptyFiles() {
        val dir = File(RuntimeEnvironment.getApplication().filesDir, "overwrite-rollback").apply { mkdirs() }
        val source = File(dir, "new-file").apply { writeBytes(png) }
        for (oldBytes in listOf("previous file".toByteArray(), byteArrayOf())) {
            for (incomplete in listOf(false, true)) {
                val target = File(dir, "destination").apply { writeBytes(oldBytes) }.toOkioPath()
                val marker = target.parent!! / rescueMarkerName(target.name)
                if (incomplete) marker.toFile().writeText(target.name)
                var checkpoints = 0
                assertTrue(
                    runCatching {
                        overwriteRescueFile(source, target) {
                            if (++checkpoints == 2) throw CancellationException("Interrupted after old file was backed up")
                        }
                    }.isFailure,
                )
                assertArrayEquals(oldBytes, read(target))
                assertEquals(incomplete, marker.exists())
                overwriteRescueFile(source, target) {}
                assertFalse(marker.exists())
                assertArrayEquals(png, read(target))
                assertArrayEquals(png, source.readBytes())
                assertFalse(dir.listFiles()!!.any { it.name.endsWith(".previous") || it.name.endsWith(".tmp") })
            }
        }
    }

    private suspend fun setDownloadLocation(path: Path) {
        downloadLocation = path
        // Preferences commit asynchronously; wait before resolving fixture directories.
        withTimeout(10_000) { while (downloadLocation != path) delay(10) }
    }

    private suspend fun setModePreference(value: Boolean) {
        // Commit the fixture directly instead of racing the UI's asynchronous setter.
        withTimeout(10_000) { Settings.withMutableSnapshot { it[Settings.saveAsCbz] = value } }
        assertEquals(value, Settings.saveAsCbz.value)
    }

    @Test
    fun batchAndStartAllSkipOnlyTheExportingGalleryAndResumeAfterward() = runBlocking(Dispatchers.IO) {
        val root = File(RuntimeEnvironment.getApplication().filesDir, "batch-downloads").apply { mkdirs() }
        setDownloadLocation(root.toOkioPath())
        val galleries = (8701L..8703L).map { gid ->
            val info = BaseGalleryInfo(gid = gid, token = "batch", title = "batch", pages = 1)
            val dirname = "$gid-batch"
            val dir = (downloadLocation / dirname).apply { mkdirs() }
            (dir / "$gid.cbz").toFile().writeBytes(byteArrayOf(1))
            DownloadManager.restoreDownload(info, dirname)
            info
        }
        val first = galleries.first().gid
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val rescue = async {
            DownloadManager.withCachedPageExport(first, "busy") {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()
        try {
            withTimeout(1500) { DownloadManager.startRangeDownload(longArrayOf(first, galleries[1].gid)) }
            assertEquals(DownloadInfo.STATE_NONE, DownloadManager.getDownloadState(first))
            withTimeout(1500) { while (DownloadManager.getDownloadState(galleries[1].gid) != DownloadInfo.STATE_FINISH) delay(10) }
            withTimeout(1500) { DownloadManager.startAllDownload() }
            assertEquals(DownloadInfo.STATE_NONE, DownloadManager.getDownloadState(first))
            withTimeout(1500) { while (DownloadManager.getDownloadState(galleries[2].gid) != DownloadInfo.STATE_FINISH) delay(10) }
            assertFalse(release.isCompleted)
            DownloadManager.stopAllDownload()
        } finally {
            release.complete(Unit)
            rescue.await()
        }
        withTimeout(1500) { DownloadManager.startRangeDownload(longArrayOf(first)) }
        withTimeout(1500) { while (DownloadManager.getDownloadState(first) != DownloadInfo.STATE_FINISH) delay(10) }
        galleries.forEach { assertTrue(DownloadManager.containDownloadInfo(it.gid)) }
        DownloadManager.stopAllDownload()
    }

    @Test
    fun failedOrCancelledCopyLeavesOriginalsIntactAndCanRetry() {
        val dir = File(RuntimeEnvironment.getApplication().filesDir, "copy-failures").apply { mkdirs() }
        val source = File(dir, "source.png").apply { writeBytes(png) }
        val healthy = File(dir, "healthy.png").apply { writeBytes(png) }
        for (cancelAfterPublish in listOf(false, true)) {
            val target = dir.toOkioPath() / "target-$cancelAfterPublish.png"
            var opens = 0
            val result = runCatching {
                commitLocalFile(
                    openSource = {
                        if (++opens == 2 && !cancelAfterPublish) throw IOException("injected source failure")
                        source.inputStream()
                    },
                    destination = target,
                    checkpoint = {
                        if (cancelAfterPublish && (target.parent!! / rescueMarkerName(target.name)).exists()) throw CancellationException("injected cancellation")
                    },
                )
            }
            assertTrue(result.isFailure)
            assertFalse(target.exists())
            assertFalse((target.parent!! / rescueMarkerName(target.name)).exists())
            assertFalse(dir.listFiles()!!.any { it.name.endsWith(".tmp") })
            assertArrayEquals(png, source.readBytes())
            assertArrayEquals(png, healthy.readBytes())
            copyLocalPageSafely(source.toOkioPath(), target)
            assertArrayEquals(png, read(target))
            assertTrue(runCatching { copyLocalPageSafely(source.toOkioPath(), healthy.toOkioPath()) }.isFailure)
            assertArrayEquals(png, healthy.readBytes())
        }
    }

    private fun read(path: Path): ByteArray = ParcelFileDescriptor.AutoCloseInputStream(path.openFileDescriptor("r")).use { it.readBytes() }
}
