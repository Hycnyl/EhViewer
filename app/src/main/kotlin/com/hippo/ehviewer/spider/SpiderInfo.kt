package com.hippo.ehviewer.spider

import com.ehviewer.core.files.read
import com.ehviewer.core.files.write
import com.ehviewer.core.model.GalleryInfo
import com.ehviewer.core.util.logcat
import com.hippo.ehviewer.coil.edit
import com.hippo.ehviewer.coil.read
import com.hippo.ehviewer.ktbuilder.diskCache
import com.hippo.ehviewer.legacy.readLegacySpiderInfo
import kotlinx.io.readByteArray
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import moe.tarsin.coroutines.runSuspendCatching
import okio.Path
import okio.Path.Companion.toOkioPath
import splitties.init.appCtx

@Serializable
class SpiderInfo(
    val gid: Long,

    val token: String,

    val pages: Int,

    val pTokenMap: MutableMap<Int, String> = hashMapOf(),

    var previewPerPage: Int = -1,

    // Rescue-only reading range, not an authoritative gallery total. Legacy clients see
    // pages=0 on disk, rather than mistaking this bound for a completed gallery.
    val cachedPageCount: Int = 0,
)

private val cbor = Cbor {
    ignoreUnknownKeys = true
}

fun SpiderInfo.write(file: Path) {
    val stored = if (cachedPageCount > 0) SpiderInfo(gid, token, 0, pTokenMap, previewPerPage, cachedPageCount) else this
    file.write { write(cbor.encodeToByteArray(stored)) }
}

internal fun SpiderInfo.forLocalReading(info: GalleryInfo): SpiderInfo {
    if (cachedPageCount == 0) return this
    require(cachedPageCount in 1..MAX_RESCUE_PAGES)
    val verified = info.pages.takeIf { it in cachedPageCount..MAX_RESCUE_PAGES }
    return SpiderInfo(gid, token, verified ?: cachedPageCount, pTokenMap, previewPerPage, if (verified == null) cachedPageCount else 0)
}

fun SpiderInfo.saveToCache() {
    runSuspendCatching {
        spiderInfoCache.edit(gid.toString()) {
            write(data)
        }
    }.onFailure {
        logcat(it)
    }
}

private val spiderInfoCache by lazy {
    diskCache {
        directory(appCtx.cacheDir.toOkioPath() / "spider_info_v2_1")
        maxSizeBytes(20 * 1024 * 1024)
    }
}

fun readFromCache(gid: Long): SpiderInfo? = spiderInfoCache.read(gid.toString()) {
    runCatching {
        data.read { cbor.decodeFromByteArray<SpiderInfo>(readByteArray()) }
    }.onFailure {
        logcat(it)
    }.getOrNull()
}

fun readCompatFromPath(file: Path): SpiderInfo? = runCatching {
    file.read { cbor.decodeFromByteArray<SpiderInfo>(readByteArray()) }
}.getOrNull() ?: runCatching {
    file.readLegacySpiderInfo()
}.getOrNull()
