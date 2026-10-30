package com.hippo.ehviewer.ui

import android.app.Application
import androidx.paging.PagingSource
import com.ehviewer.core.data.model.asEntity
import com.ehviewer.core.database.model.GalleryEntity
import com.ehviewer.core.database.model.HistoryInfo
import com.ehviewer.core.model.BaseGalleryInfo
import com.hippo.ehviewer.EhDB
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import splitties.init.injectAsAppCtx

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class HistoryUndoTest {
    private val fixtures = mutableListOf<BaseGalleryInfo>()

    @Before
    fun initializeContext() {
        RuntimeEnvironment.getApplication().injectAsAppCtx()
    }

    @After
    fun cleanFixtures() = runBlocking(Dispatchers.IO) {
        EhDB.clearHistoryInfo()
        EhDB.removeLocalFavorites(fixtures)
    }

    @Test
    fun undoRestoresTheOriginalTimestampAndGallery() = runBlocking(Dispatchers.IO) {
        val info = gallery(8301, "history")
        EhDB.putHistoryInfo(info)
        val removed = requireNotNull(EhDB.removeHistoryInfoForUndo(info.asEntity()))
        assertTrue(history().isEmpty())
        EhDB.restoreHistoryInfoAfterUndo(info.asEntity(), removed)
        assertEquals("history", history().single().title)
        assertEquals(removed.time, requireNotNull(EhDB.removeHistoryInfoForUndo(info.asEntity())).time)
        assertNull(EhDB.removeHistoryInfoForUndo(info.asEntity()))
    }

    @Test
    fun undoDoesNotOverwriteANewerVisitOrGalleryMetadata() = runBlocking(Dispatchers.IO) {
        val old = gallery(8302, "old")
        EhDB.putHistoryInfo(old)
        val removed = requireNotNull(EhDB.removeHistoryInfoForUndo(old.asEntity()))
        val newer = gallery(8302, "updated")
        EhDB.putHistoryInfo(newer)
        val newVisit = requireNotNull(EhDB.removeHistoryInfoForUndo(newer.asEntity()))
        val newerTime = removed.time + 1000
        EhDB.restoreHistoryInfoAfterUndo(newer.asEntity(), HistoryInfo(newVisit.gid, newerTime))
        EhDB.restoreHistoryInfoAfterUndo(old.asEntity(), removed)
        assertEquals("updated", history().single().title)
        assertEquals(newerTime, requireNotNull(EhDB.removeHistoryInfoForUndo(newer.asEntity())).time)
    }

    @Test
    fun historyRemovalAndUndoPreserveLocalFavorites() = runBlocking(Dispatchers.IO) {
        val info = gallery(8303, "favorite")
        EhDB.putLocalFavorites(listOf(info))
        EhDB.putHistoryInfo(info)
        val removed = requireNotNull(EhDB.removeHistoryInfoForUndo(info.asEntity()))
        assertTrue(history().isEmpty())
        assertEquals(1, EhDB.localFavCount.first())
        EhDB.restoreHistoryInfoAfterUndo(info.asEntity(), removed)
        assertEquals(1, EhDB.localFavCount.first())
        assertEquals(info.gid, history().single().gid)
    }

    private fun gallery(gid: Long, title: String) = BaseGalleryInfo(gid = gid, token = "history", title = title).also { fixtures += it }

    private suspend fun history(): List<GalleryEntity> {
        val result = EhDB.historyLazyList.load(PagingSource.LoadParams.Refresh(key = null, loadSize = 20, placeholdersEnabled = false))
        check(result is PagingSource.LoadResult.Page)
        return result.data
    }
}
