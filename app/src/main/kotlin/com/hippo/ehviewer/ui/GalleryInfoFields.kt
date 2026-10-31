package com.hippo.ehviewer.ui

import androidx.annotation.StringRes
import com.ehviewer.core.i18n.R
import com.ehviewer.core.model.GalleryDetail
import com.ehviewer.core.model.GalleryInfo.Companion.LOCAL_FAVORITED
import com.hippo.ehviewer.client.data.GalleryVersionLink
import com.hippo.ehviewer.client.data.GalleryVersionNode

enum class GalleryInfoGroup { OVERVIEW, CORE, BASIC, FAVORITES, RESOURCES }

enum class GalleryInfoFieldKey(@StringRes val label: Int, val group: GalleryInfoGroup) {
    GID(R.string.key_gid, GalleryInfoGroup.RESOURCES),
    TOKEN(R.string.key_token, GalleryInfoGroup.RESOURCES),
    URL(R.string.key_url, GalleryInfoGroup.RESOURCES),
    TITLE(R.string.key_title, GalleryInfoGroup.OVERVIEW),
    TITLE_JPN(R.string.key_title_jpn, GalleryInfoGroup.OVERVIEW),
    THUMB(R.string.key_thumb, GalleryInfoGroup.RESOURCES),
    CATEGORY(R.string.key_category, GalleryInfoGroup.OVERVIEW),
    UPLOADER(R.string.key_uploader, GalleryInfoGroup.BASIC),
    POSTED(R.string.key_posted, GalleryInfoGroup.BASIC),
    PARENT(R.string.key_parent, GalleryInfoGroup.BASIC),
    VISIBLE(R.string.key_visible, GalleryInfoGroup.BASIC),
    LANGUAGE(R.string.key_language, GalleryInfoGroup.BASIC),
    PAGES(R.string.key_pages, GalleryInfoGroup.CORE),
    SIZE(R.string.key_size, GalleryInfoGroup.CORE),
    FAVORITE_COUNT(R.string.key_favorite_count, GalleryInfoGroup.CORE),
    FAVORITED(R.string.key_favorited, GalleryInfoGroup.FAVORITES),
    RATING_COUNT(R.string.key_rating_count, GalleryInfoGroup.CORE),
    RATING(R.string.key_rating, GalleryInfoGroup.CORE),
    TORRENT_COUNT(R.string.key_torrents, GalleryInfoGroup.RESOURCES),
    TORRENT_URL(R.string.key_torrent_url, GalleryInfoGroup.RESOURCES),
    FAVORITE_NAME(R.string.favorite_name, GalleryInfoGroup.FAVORITES),
}

enum class GalleryInfoFormat { TEXT, INTEGER, DECIMAL, BYTES }

data class GalleryInfoValue(val raw: String?, val absent: Boolean = false, val format: GalleryInfoFormat = GalleryInfoFormat.TEXT) {
    val text get() = raw ?: if (absent) "None" else "Null"

    companion object {
        fun text(value: String?) = GalleryInfoValue(value?.takeIf(String::isNotBlank))
        fun number(value: Number?) = GalleryInfoValue(value?.toString(), format = GalleryInfoFormat.INTEGER)
        fun bytes(value: Long?) = GalleryInfoValue(value?.toString(), format = GalleryInfoFormat.BYTES)
    }
}

data class GalleryInfoField(val key: GalleryInfoFieldKey, val value: GalleryInfoValue) {
    val isGalleryLink get() = key == GalleryInfoFieldKey.URL || key == GalleryInfoFieldKey.PARENT
}

data class GalleryInfoLinks(val gallery: String?, val thumb: String?, val parent: String?, val torrent: String?)

/** All 21 fields are unconditional. API supplements are presentation-only and never mutate detail. */
fun galleryInfoFields(
    detail: GalleryDetail,
    viewed: GalleryVersionNode?,
    parent: GalleryVersionLink,
    links: GalleryInfoLinks,
    category: String?,
): List<GalleryInfoField> {
    val favorited = detail.favoriteSlot > LOCAL_FAVORITED
    val sizeText = detail.size?.takeIf(String::isNotBlank) ?: viewed?.sizeText?.takeIf(String::isNotBlank)
    val values = mapOf(
        GalleryInfoFieldKey.GID to GalleryInfoValue.number(detail.gid.takeIf { it > 0 }).copy(format = GalleryInfoFormat.TEXT),
        GalleryInfoFieldKey.TOKEN to GalleryInfoValue.text(detail.token),
        GalleryInfoFieldKey.URL to GalleryInfoValue.text(links.gallery),
        GalleryInfoFieldKey.TITLE to GalleryInfoValue.text(detail.title?.takeIf(String::isNotBlank) ?: viewed?.title),
        GalleryInfoFieldKey.TITLE_JPN to GalleryInfoValue.text(detail.titleJpn?.takeIf(String::isNotBlank) ?: viewed?.titleJpn),
        GalleryInfoFieldKey.THUMB to GalleryInfoValue.text(links.thumb),
        GalleryInfoFieldKey.CATEGORY to GalleryInfoValue.text(category ?: viewed?.category),
        GalleryInfoFieldKey.UPLOADER to GalleryInfoValue.text(detail.uploader?.takeIf(String::isNotBlank) ?: viewed?.uploader),
        GalleryInfoFieldKey.POSTED to GalleryInfoValue.text(detail.posted?.takeIf(String::isNotBlank) ?: viewed?.posted),
        GalleryInfoFieldKey.PARENT to GalleryInfoValue(links.parent?.takeIf(String::isNotBlank), absent = parent == GalleryVersionLink.None),
        GalleryInfoFieldKey.VISIBLE to GalleryInfoValue.text(detail.visible),
        GalleryInfoFieldKey.LANGUAGE to GalleryInfoValue.text(detail.language),
        GalleryInfoFieldKey.PAGES to GalleryInfoValue.number(detail.pages.takeIf { it > 0 } ?: viewed?.pages),
        GalleryInfoFieldKey.SIZE to (sizeText?.let(GalleryInfoValue::text) ?: GalleryInfoValue.bytes(viewed?.fileSizeBytes)),
        GalleryInfoFieldKey.FAVORITE_COUNT to GalleryInfoValue.number(detail.favoriteCount.takeIf { it >= 0 }),
        GalleryInfoFieldKey.FAVORITED to GalleryInfoValue(if (favorited) "True" else "False"),
        GalleryInfoFieldKey.RATING_COUNT to GalleryInfoValue.number(detail.ratingCount.takeIf { it >= 0 }),
        GalleryInfoFieldKey.RATING to GalleryInfoValue(detail.rating.takeIf { it.isFinite() && it >= 0 }?.toString(), format = GalleryInfoFormat.DECIMAL),
        GalleryInfoFieldKey.TORRENT_COUNT to GalleryInfoValue.number(detail.torrentCount.takeIf { it >= 0 }),
        GalleryInfoFieldKey.TORRENT_URL to GalleryInfoValue.text(links.torrent),
        GalleryInfoFieldKey.FAVORITE_NAME to GalleryInfoValue(detail.favoriteName?.takeIf(String::isNotBlank), absent = !favorited),
    )
    return GalleryInfoFieldKey.entries.map { GalleryInfoField(it, values.getValue(it)) }
}
