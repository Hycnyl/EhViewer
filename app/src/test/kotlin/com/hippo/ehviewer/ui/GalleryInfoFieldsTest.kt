package com.hippo.ehviewer.ui

import com.ehviewer.core.model.BaseGalleryInfo
import com.ehviewer.core.model.GalleryCommentList
import com.ehviewer.core.model.GalleryDetail
import com.hippo.ehviewer.client.data.GalleryVersionLink
import com.hippo.ehviewer.client.data.GalleryVersionNode
import com.hippo.ehviewer.client.data.GalleryVersionRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryInfoFieldsTest {
    private fun detail() = GalleryDetail(galleryInfo = BaseGalleryInfo(gid = 123, token = "000000007b", title = "HTML title", pages = 10), tagGroups = emptyList(), comments = GalleryCommentList(emptyList(), false), previewList = emptyList())
    private val links = GalleryInfoLinks("https://e-hentai.org/g/123/000000007b/", null, null, "https://e-hentai.org/gallerytorrents.php?gid=123&t=000000007b")

    @Test
    fun allOriginalFieldsRemainPresentEvenWhenTheirValuesAreMissing() {
        val fields = galleryInfoFields(detail(), null, GalleryVersionLink.Unknown, links, null).associateBy { it.key.name }
        assertEquals(setOf("GID", "TOKEN", "URL", "TITLE", "TITLE_JPN", "THUMB", "CATEGORY", "UPLOADER", "POSTED", "PARENT", "VISIBLE", "LANGUAGE", "PAGES", "SIZE", "FAVORITE_COUNT", "FAVORITED", "RATING_COUNT", "RATING", "TORRENT_COUNT", "TORRENT_URL", "FAVORITE_NAME"), fields.keys)
        assertEquals("Null", fields.getValue("TITLE_JPN").value.text)
        assertEquals("Null", fields.getValue("UPLOADER").value.text)
        assertEquals("Null", fields.getValue("PARENT").value.text)
        assertEquals("Null", fields.getValue("SIZE").value.text)
        assertEquals("None", fields.getValue("FAVORITE_NAME").value.text)
        assertEquals("False", fields.getValue("FAVORITED").value.text)
        assertEquals("0", fields.getValue("FAVORITE_COUNT").value.raw)
    }

    @Test
    fun parentNoneAndUnknownStayDifferentAndBothKeepTheParentField() {
        val detail = detail()
        val none = galleryInfoFields(detail, null, GalleryVersionLink.None, links, null).single { it.key == GalleryInfoFieldKey.PARENT }
        val unknown = galleryInfoFields(detail, null, GalleryVersionLink.to(GalleryVersionRef(12, null)), links, null).single { it.key == GalleryInfoFieldKey.PARENT }
        assertEquals("None", none.value.text)
        assertEquals("Null", unknown.value.text)
        assertNull(none.value.raw)
        assertNull(unknown.value.raw)
    }

    @Test
    fun apiSupplementsCannotOverwriteHtmlOrModifyTheGalleryRecord() {
        val detail = detail().apply {
            size = "12 MB (12582912 bytes)"
            favoriteCount = 7
            favoriteSlot = 0
            favoriteName = "Mine"
            ratingCount = 3
            rating = 4.5f
        }
        val node = GalleryVersionNode(GalleryVersionRef(123, "000000007b"), title = "API title", titleJpn = "日本語", pages = 100, fileSizeBytes = 5_000_000_000)
        val fields = galleryInfoFields(detail, node, GalleryVersionLink.None, links, "manga").associateBy { it.key }
        assertEquals("HTML title", fields.getValue(GalleryInfoFieldKey.TITLE).value.raw)
        assertEquals("10", fields.getValue(GalleryInfoFieldKey.PAGES).value.raw)
        assertEquals("12 MB (12582912 bytes)", fields.getValue(GalleryInfoFieldKey.SIZE).value.raw)
        assertEquals("日本語", fields.getValue(GalleryInfoFieldKey.TITLE_JPN).value.raw)
        assertEquals("7", fields.getValue(GalleryInfoFieldKey.FAVORITE_COUNT).value.raw)
        assertEquals("True", fields.getValue(GalleryInfoFieldKey.FAVORITED).value.raw)
        assertEquals("Mine", fields.getValue(GalleryInfoFieldKey.FAVORITE_NAME).value.raw)
        assertEquals(10, detail.pages)
        assertEquals("HTML title", detail.title)
        assertNull(detail.titleJpn)
        assertEquals(7, detail.favoriteCount)
    }

    @Test
    fun knownBytesSupplementUnknownSizeAndNumericCopiesKeepTheirRawValues() {
        val detail = detail().apply {
            favoriteCount = 12345
            favoriteSlot = 0
        }
        val node = GalleryVersionNode(GalleryVersionRef(123, "000000007b"), fileSizeBytes = 5_000_000_000)
        val fields = galleryInfoFields(detail, node, GalleryVersionLink.None, links, null).associateBy { it.key }
        assertEquals("5000000000", fields.getValue(GalleryInfoFieldKey.SIZE).value.raw)
        assertEquals(GalleryInfoFormat.BYTES, fields.getValue(GalleryInfoFieldKey.SIZE).value.format)
        assertEquals("12345", fields.getValue(GalleryInfoFieldKey.FAVORITE_COUNT).value.raw)
        assertEquals("Null", fields.getValue(GalleryInfoFieldKey.FAVORITE_NAME).value.text)
        assertTrue(fields.getValue(GalleryInfoFieldKey.URL).isGalleryLink)
        assertTrue(fields.getValue(GalleryInfoFieldKey.PARENT).isGalleryLink)
    }
}
