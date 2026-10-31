/*
 * Copyright 2016 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.hippo.ehviewer.client.parser

import com.ehviewer.core.model.GalleryInfo
import com.ehviewer.core.util.unescapeXml
import com.hippo.ehviewer.client.EhUtils.getCategory
import com.hippo.ehviewer.client.data.GalleryVersionIssueCode
import com.hippo.ehviewer.client.data.GalleryVersionLink
import com.hippo.ehviewer.client.data.GalleryVersionMetadata
import com.hippo.ehviewer.client.data.GalleryVersionNode
import com.hippo.ehviewer.client.data.GalleryVersionRef
import com.hippo.ehviewer.client.data.GalleryVersionRowStatus
import com.hippo.ehviewer.client.getThumbKey
import com.hippo.ehviewer.client.json
import com.hippo.ehviewer.client.parseAs
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

object GalleryApiParser {
    /** Read-only supplementary metadata; error/partial rows must not enter the list-update parser. */
    fun parseVersionMetadata(body: String): List<GalleryVersionMetadata> {
        val response = json.parseToJsonElement(body) as? JsonObject
        val rows = response?.get("gmetadata") as? JsonArray ?: throw SerializationException("Missing gallery metadata")
        return rows.mapNotNull { value ->
            val row = value as? JsonObject ?: return@mapNotNull null
            fun text(key: String) = (row[key] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
            fun number(key: String) = (row[key] as? JsonPrimitive)?.longOrNull
            val gid = number("gid")?.takeIf { it > 0 } ?: return@mapNotNull null
            val error = text("error")
            val ref = GalleryVersionRef(gid, text("token"))
            if (error != null) {
                val invalidToken = (error.contains("key", ignoreCase = true) || error.contains("token", ignoreCase = true)) &&
                    listOf("missing", "incorrect", "invalid").any { error.contains(it, ignoreCase = true) }
                return@mapNotNull GalleryVersionMetadata(gid, rowStatus = GalleryVersionRowStatus.API_ERROR, errorCode = if (invalidToken) GalleryVersionIssueCode.TOKEN_INVALID else GalleryVersionIssueCode.API_ROW_ERROR)
            }
            if (!ref.canRequest) {
                return@mapNotNull GalleryVersionMetadata(gid, rowStatus = GalleryVersionRowStatus.TOKEN_INVALID)
            }
            if (listOf("title", "posted", "filecount", "filesize", "category").none(row::containsKey)) {
                return@mapNotNull GalleryVersionMetadata(gid)
            }
            val completeRecord = text("category") != null && number("posted") != null && number("filecount") != null
            fun relation(gidKey: String, tokenKey: String): GalleryVersionLink {
                val id = number(gidKey)
                val key = text(tokenKey)
                // The API omits absent relations on first/latest galleries. A malformed ID is unknown.
                return when {
                    (!row.containsKey(gidKey) || row[gidKey] == JsonNull || id == 0L) && key == null -> if (completeRecord) GalleryVersionLink.None else GalleryVersionLink.Unknown
                    id != null && id > 0 -> GalleryVersionLink.to(GalleryVersionRef(id, key ?: ref.token.takeIf { id == gid }))
                    else -> GalleryVersionLink.Unknown
                }
            }
            val parent = relation("parent_gid", "parent_key")
            val first = relation("first_gid", "first_key").let {
                if (it == GalleryVersionLink.None) {
                    if (parent == GalleryVersionLink.None) GalleryVersionLink.to(ref) else GalleryVersionLink.Unknown
                } else {
                    it
                }
            }
            val latest = relation("current_gid", "current_key").let { if (it == GalleryVersionLink.None) GalleryVersionLink.to(ref) else it }
            val posted = number("posted")?.takeIf { it in 0..Long.MAX_VALUE / 1000 }?.let {
                runCatching { ParserUtils.formatDate(it * 1000) }.getOrNull()
            }
            GalleryVersionMetadata(
                gid = gid,
                node = GalleryVersionNode(
                    ref = ref,
                    title = text("title")?.unescapeXml(),
                    titleJpn = text("title_jpn")?.unescapeXml(),
                    posted = posted,
                    pages = number("filecount")?.takeIf { it in 0L..Int.MAX_VALUE.toLong() }?.toInt(),
                    fileSizeBytes = number("filesize")?.takeIf { it >= 0 },
                    uploader = text("uploader")?.unescapeXml(),
                    category = text("category"),
                    parent = parent,
                    expunged = (row["expunged"] as? JsonPrimitive)?.booleanOrNull,
                    metadataLoaded = true,
                ),
                first = first,
                latest = latest,
                rowStatus = if (completeRecord) GalleryVersionRowStatus.VALID else GalleryVersionRowStatus.METADATA_MISSING,
            )
        }
    }

    fun parse(body: String, galleryInfoList: List<GalleryInfo>) {
        body.parseAs<Result>().items.forEach { item ->
            val gi = galleryInfoList.find { it.gid == item.gid } ?: return@forEach
            gi.apply {
                title = item.title.unescapeXml()
                titleJpn = item.titleJpn.unescapeXml()
                category = getCategory(item.category)
                thumbKey = getThumbKey(item.thumb)
                uploader = item.uploader?.unescapeXml()
                posted = ParserUtils.formatDate(item.posted * 1000)
                rating = item.rating
                simpleTags = item.tags
                pages = item.pages
                generateSLang()
            }
        }
    }

    @Serializable
    data class Result(@SerialName("gmetadata") val items: List<Item>)

    @Serializable
    data class Item(
        val gid: Long,
        val title: String,
        @SerialName("title_jpn")
        val titleJpn: String,
        val category: String,
        val thumb: String,
        // Null in some old galleries
        val uploader: String?,
        val posted: Long,
        @SerialName("filecount")
        val pages: Int,
        val rating: Float,
        val tags: List<String>,
    )
}
