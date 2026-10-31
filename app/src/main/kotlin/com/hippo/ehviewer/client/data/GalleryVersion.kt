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
)

data class GalleryVersionMetadata(
    val gid: Long,
    val node: GalleryVersionNode? = null,
    val first: GalleryVersionLink = GalleryVersionLink.Unknown,
    val latest: GalleryVersionLink = GalleryVersionLink.Unknown,
)

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

    fun isDirectParent(older: Long, newer: Long) = !hasCycle && nodes[newer]?.parent?.let { it.known && it.ref?.gid == older } == true
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

/** Parent edges determine order. Role/HTML anchors order gaps without asserting direct ancestry. */
internal fun orderGalleryVersions(
    nodes: Map<Long, GalleryVersionNode>,
    viewedGid: Long,
    firstGid: Long?,
    latestGid: Long?,
    upstream: Set<Long>,
    downstream: Set<Long>,
): Pair<List<Long>, Boolean> {
    val edges = nodes.keys.associateWith { mutableSetOf<Long>() }
    fun edge(a: Long?, b: Long?) {
        if (a != null && b != null && a != b && a in nodes && b in nodes) edges.getValue(a).add(b)
    }
    nodes.values.forEach { edge(it.parent.ref?.gid, it.ref.gid) }
    nodes.keys.forEach {
        edge(firstGid, it)
        edge(it, latestGid)
    }
    upstream.forEach { edge(it, viewedGid) }
    downstream.forEach { edge(viewedGid, it) }
    val degrees = nodes.keys.associateWith { 0 }.toMutableMap()
    edges.values.forEach { children -> children.forEach { degrees[it] = degrees.getValue(it) + 1 } }
    val ready = java.util.PriorityQueue<Long>()
    degrees.filterValues { it == 0 }.keys.forEach(ready::add)
    val order = mutableListOf<Long>()
    while (ready.isNotEmpty()) {
        val gid = ready.remove()
        order.add(gid)
        edges.getValue(gid).forEach {
            degrees[it] = degrees.getValue(it) - 1
            if (degrees[it] == 0) ready.add(it)
        }
    }
    val cyclic = order.size != nodes.size || nodes.values.any { it.parent.ref?.gid == it.ref.gid }
    if (cyclic) order.addAll(nodes.keys.minus(order.toSet()).sorted())
    return order to cyclic
}
