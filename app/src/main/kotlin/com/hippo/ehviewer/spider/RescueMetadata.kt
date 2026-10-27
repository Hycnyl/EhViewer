// Copyright 2026 Hycnyl. SPDX-License-Identifier: GPL-3.0-or-later
package com.hippo.ehviewer.spider

import com.ehviewer.core.model.GalleryInfo
import java.net.URI

internal fun SpiderInfo.matchesIdentity(info: GalleryInfo) = gid == info.gid && token == info.token

internal fun ComicInfo.trustedPageCount(info: GalleryInfo): Int? = runCatching {
    val uri = URI(web ?: return null)
    val parts = uri.path.orEmpty().split('/').filter { it.isNotEmpty() }
    pageCount.takeIf {
        it in 1..MAX_RESCUE_PAGES && uri.scheme in setOf("https", "http") &&
            uri.host?.lowercase() in setOf("e-hentai.org", "exhentai.org", "g.e-hentai.org") &&
            parts.size == 3 && parts[0] == "g" && parts[1].toLongOrNull() == info.gid &&
            (info.token.isEmpty() || parts[2] == info.token)
    }
}.getOrNull()
