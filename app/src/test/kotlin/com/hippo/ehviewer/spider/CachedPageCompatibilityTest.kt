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
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.download.DownloadManager
import com.hippo.ehviewer.download.archiveFile
import com.hippo.ehviewer.download.downloadLocation
import com.hippo.ehviewer.download.tempDownloadDir
import java.io.File
import java.io.IOException
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import okio.Path
import okio.Path.Companion.toOkioPath
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
    }

    private val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=")

    @Test
    fun exportAndResumeMatrixReadsCurrentModeAndKeepsSnapshots() = runBlocking(Dispatchers.IO) {
        val root = File(RuntimeEnvironment.getApplication().filesDir, "compatibility-downloads").apply { mkdirs() }
        downloadLocation = root.toOkioPath()
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
                    assertEquals(scenario, listOf(0, 2), result.indices)
                    assertEquals(scenario, 3, result.totalPages)
                    assertTrue(scenario, result.downloadRegistered)
                    val entry = DownloadManager.getDownloadInfo(info.gid)!!
                    assertEquals(scenario, DownloadInfo.STATE_NONE, entry.state)
                    assertNull(scenario, entry.archiveFile)
                    val native = if (cbz) temporary else download
                    listOf(0, 2).forEach { assertArrayEquals(scenario, png, read(native / perFilename(it, "png"))) }
                    val snapshot = download / "${info.gid}.cached-partial.cbz"
                    assertEquals(scenario, format == RescueFormat.CBZ, snapshot.exists())
                    // A rescue snapshot must not trigger normal archive cleanup on cancel/retry.
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
                    if (format == RescueFormat.CBZ) assertTrue(scenario, snapshot.exists())
                }
            }
        }
    }

    @Test
    fun mismatchedMetadataDoesNotBecomeAnAuthoritativePageCount() = runBlocking(Dispatchers.IO) {
        val root = File(RuntimeEnvironment.getApplication().filesDir, "metadata-downloads").apply { mkdirs() }
        downloadLocation = root.toOkioPath()
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
        downloadLocation = root.toOkioPath()
        setModePreference(false)
        val info = BaseGalleryInfo(gid = 8601, token = "unknown", title = "unknown", pages = 0)
        EhDB.putDownloadDirname(info.gid, "8601-unknown")
        val directory = (downloadLocation / "8601-unknown").apply { mkdirs() }
        listOf(2, 7).forEach { (directory / perFilename(it, "png")).toFile().writeBytes(png) }
        val result = exportCachedPages(info, RescueFormat.CBZ) { _, _ -> }
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
    }

    @Test
    fun exportingGalleryDoesNotBlockUnrelatedDownloadQueue() = runBlocking(Dispatchers.IO) {
        val root = File(RuntimeEnvironment.getApplication().filesDir, "queue-downloads").apply { mkdirs() }
        downloadLocation = root.toOkioPath()
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

    private suspend fun setModePreference(value: Boolean) {
        Settings.saveAsCbz.value = value
        withTimeout(1500) { Settings.saveAsCbz.valueFlow().first { it == value } }
        withTimeout(1500) { while (Settings.saveAsCbz.value != value) yield() }
    }

    @Test
    fun batchAndStartAllSkipOnlyTheExportingGalleryAndResumeAfterward() = runBlocking(Dispatchers.IO) {
        val root = File(RuntimeEnvironment.getApplication().filesDir, "batch-downloads").apply { mkdirs() }
        downloadLocation = root.toOkioPath()
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
