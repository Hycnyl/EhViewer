package com.hippo.ehviewer.client.data

import com.ehviewer.core.model.GalleryDetail
import com.hippo.ehviewer.client.parser.GalleryDetailUrlParser

private val versionTokenPattern = Regex("[0-9a-f]{10}")

data class GalleryVersionRef(val gid: Long, val token: String?) {
    val canRequest get() = gid > 0 && token != null && versionTokenPattern.matches(token)
}

/** A known null GID means no relation; an unknown link must never be shown as None. */
data class GalleryVersionLink(val known: Boolean = false, val ref: GalleryVersionRef? = null) {
    companion object {
        val Unknown = GalleryVersionLink()
        val None = GalleryVersionLink(known = true)
        fun to(ref: GalleryVersionRef) = GalleryVersionLink(true, ref)
    }
}

data class GalleryVersionNode(
    val ref: GalleryVersionRef,
    val title: String? = null,
    val titleJpn: String? = null,
    val posted: String? = null,
    val pages: Int? = null,
    val fileSizeBytes: Long? = null,
    val sizeText: String? = null,
    val uploader: String? = null,
    val category: String? = null,
    val parent: GalleryVersionLink = GalleryVersionLink.Unknown,
    val expunged: Boolean? = null,
    val metadataLoaded: Boolean = false,
    val parentVerified: Boolean = metadataLoaded && parent.known,
)

data class GalleryVersionMetadata(
    val gid: Long,
    val node: GalleryVersionNode? = null,
    val first: GalleryVersionLink = GalleryVersionLink.Unknown,
    val latest: GalleryVersionLink = GalleryVersionLink.Unknown,
)

data class GalleryVersionCandidates(val found: Boolean, val items: List<GalleryVersionNode> = emptyList())

data class GalleryVersionSeed(val viewed: GalleryVersionNode, val newer: List<GalleryVersionNode> = emptyList()) {
    companion object {
        fun from(detail: GalleryDetail): GalleryVersionSeed {
            val parent = when {
                // The existing model does not record whether a missing HTML field was parsed.
                detail.parent == null -> GalleryVersionLink.Unknown
                else -> GalleryDetailUrlParser.parse(detail.parent)?.let {
                    GalleryVersionLink.to(GalleryVersionRef(it.gid, it.token))
                } ?: GalleryVersionLink.Unknown
            }
            return GalleryVersionSeed(
                viewed = GalleryVersionNode(
                    ref = GalleryVersionRef(detail.gid, detail.token),
                    title = detail.title?.takeIf(String::isNotBlank),
                    titleJpn = detail.titleJpn?.takeIf(String::isNotBlank),
                    posted = detail.posted?.takeIf(String::isNotBlank),
                    pages = detail.pages.takeIf { it > 0 },
                    sizeText = detail.size?.takeIf(String::isNotBlank),
                    uploader = detail.uploader?.takeIf(String::isNotBlank),
                    parent = parent,
                ),
                newer = detail.newerVersions.distinctBy { it.gid }.filter { it.gid != detail.gid }.map {
                    GalleryVersionNode(GalleryVersionRef(it.gid, it.token), title = it.title, posted = it.posted)
                },
            )
        }
    }
}

enum class GalleryVersionRole { FIRST, PARENT, VIEWED, LATEST }

data class GalleryVersionChain(
    val viewedGid: Long,
    val first: GalleryVersionLink,
    val parent: GalleryVersionLink,
    val latest: GalleryVersionLink,
    val nodes: Map<Long, GalleryVersionNode>,
    val orderedGids: List<Long>,
    val hasMiddleVersions: Boolean?,
    val isLoading: Boolean = false,
    val keyInfoComplete: Boolean = false,
    val expansionAttempted: Boolean = false,
    val traceComplete: Boolean = false,
    val hasCycle: Boolean = false,
) {
    val isComplete get() = keyInfoComplete && (!expansionAttempted || traceComplete)

    fun roles(gid: Long) = buildList {
        if (first.ref?.gid == gid) add(GalleryVersionRole.FIRST)
        if (parent.ref?.gid == gid) add(GalleryVersionRole.PARENT)
        if (viewedGid == gid) add(GalleryVersionRole.VIEWED)
        if (latest.ref?.gid == gid) add(GalleryVersionRole.LATEST)
    }

    fun visibleNodes(expanded: Boolean): List<GalleryVersionNode> {
        val gids = if (expanded) orderedGids else listOfNotNull(first.ref?.gid, parent.ref?.gid, viewedGid, latest.ref?.gid).distinct()
        return gids.mapNotNull(nodes::get)
    }

    fun isDirectParent(older: Long, newer: Long) = !hasCycle && nodes[newer]?.let { it.parentVerified && it.parent.ref?.gid == older } == true
}

internal fun versionMiddleState(
    viewedGid: Long,
    first: GalleryVersionLink,
    parent: GalleryVersionLink,
    latest: GalleryVersionLink,
    nodes: Map<Long, GalleryVersionNode>,
    newerGids: Set<Long>,
): Boolean? {
    val up = when {
        parent.known && parent.ref == null -> false
        first.ref == null || parent.ref == null -> null
        first.ref.gid == parent.ref.gid -> false
        else -> nodes[parent.ref.gid]?.parent?.let {
            when {
                !it.known || it.ref == null -> null
                it.ref.gid == first.ref.gid -> false
                it.ref.gid == parent.ref.gid || it.ref.gid == viewedGid -> null
                else -> true
            }
        }
    }
    val down = when {
        latest.ref == null -> null
        latest.ref.gid == viewedGid -> false
        else -> nodes[latest.ref.gid]?.parent?.let {
            when {
                !it.known || it.ref == null -> null
                it.ref.gid == viewedGid -> false
                it.ref.gid == latest.ref.gid -> null
                else -> true
            }
        }
    }
    val htmlHasMiddle = if (latest.ref != null) newerGids.any { it != viewedGid && it != latest.ref.gid } else newerGids.size > 1
    return when {
        up == true || down == true || htmlHasMiddle -> true
        up == false && down == false -> false
        else -> null
    }
}

/** Only API parent edges locate candidates; anchors keep their established relative roles. */
internal fun orderGalleryVersions(
    nodes: Map<Long, GalleryVersionNode>,
    viewedGid: Long,
    firstGid: Long?,
    parentGid: Long?,
    latestGid: Long?,
): Pair<List<Long>, Boolean> {
    val anchors = listOfNotNull(firstGid, parentGid, viewedGid, latestGid).distinct().filter { it in nodes }
    fun parent(gid: Long) = nodes[gid]?.takeIf { it.parentVerified }?.parent?.ref?.gid?.takeIf { it in nodes }
    val checked = mutableSetOf<Long>()
    for (gid in nodes.keys) {
        val path = mutableSetOf<Long>()
        var current: Long? = gid
        while (current != null && current !in checked) {
            if (!path.add(current)) return anchors to true
            current = parent(current)
        }
        checked.addAll(path)
    }
    fun ancestors(gid: Long?): List<Long> = buildList {
        var current = gid
        while (current != null && current in nodes) {
            add(current)
            current = parent(current)
        }
    }
    val viewedPath = ancestors(viewedGid)
    val parentPath = ancestors(parentGid)
    if ((latestGid != viewedGid && latestGid in viewedPath) ||
        (firstGid in viewedPath && viewedPath.lastOrNull() != firstGid) ||
        (parentGid != viewedGid && viewedGid in parentPath)
    ) {
        return anchors to true
    }

    val order = mutableListOf<Long>()
    val included = mutableSetOf<Long>()
    fun append(gid: Long?) {
        if (gid != null && gid in nodes && included.add(gid)) order.add(gid)
    }
    append(firstGid)
    if (parentGid !in viewedPath) parentPath.asReversed().forEach(::append)
    viewedPath.asReversed().forEach(::append)

    val latestPath = ancestors(latestGid)
    if (viewedGid in latestPath) {
        latestPath.takeWhile { it != viewedGid }.asReversed().forEach(::append)
    } else {
        // A unique continuation is ordered by verified parent edges, never by GND/GID order.
        val children = nodes.values.filter { it.parentVerified && it.parent.ref != null }.groupBy { it.parent.ref!!.gid }
        var current = viewedGid
        while (current != latestGid) {
            val next = children[current]?.singleOrNull()?.ref?.gid ?: break
            if (next in included) break
            append(next)
            current = next
        }
        append(latestGid)
    }
    return order to false
}
