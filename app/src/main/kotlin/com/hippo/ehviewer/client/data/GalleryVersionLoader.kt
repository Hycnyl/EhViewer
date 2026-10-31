package com.hippo.ehviewer.client.data

import com.hippo.ehviewer.client.MAX_REQUEST_SIZE
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Memory-only state for one information sheet. Successful metadata is never written to EhDB. */
class GalleryVersionLoader(
    private val seed: GalleryVersionSeed,
    private val fetch: suspend (List<GalleryVersionRef>) -> List<GalleryVersionMetadata>,
    private val requestBudget: Int = 50,
) {
    private val mutex = Mutex()
    private val nodes = (seed.newer + seed.viewed).associateBy { it.ref.gid }.toMutableMap()
    private val attempts = mutableMapOf<GalleryVersionRef, GalleryVersionMetadata>()
    private var first = if (seed.viewed.parent == GalleryVersionLink.None) GalleryVersionLink.to(seed.viewed.ref) else GalleryVersionLink.Unknown
    private var parent = seed.viewed.parent
    private var latest = GalleryVersionLink.Unknown
    private val upstream = mutableSetOf<Long>()
    private val downstream = seed.newer.mapTo(mutableSetOf()) { it.ref.gid }
    private var loading = false
    private var expansionAttempted = false
    private var traced = false
    private var remaining = 0
    private val mutableState = MutableStateFlow(snapshot())
    val state = mutableState.asStateFlow()

    suspend fun loadKeys(retry: Boolean = false) = load(retry, expand = false)
    suspend fun expand(retry: Boolean = false) = load(retry, expand = true)

    private suspend fun load(retry: Boolean, expand: Boolean) = mutex.withLock {
        if (retry) {
            // Only explicit retry can repeat unavailable/inconsistent responses; walks use the cache.
            val cycle = mutableState.value.hasCycle
            val retryable = attempts.filterValues { value ->
                cycle || value.node == null || listOf(value.node.parent, value.first, value.latest).any { !it.known || it.ref?.canRequest == false }
            }.keys
            retryable.forEach { ref ->
                attempts.remove(ref)
                nodes[ref.gid]?.let { nodes[ref.gid] = it.copy(metadataLoaded = false) }
            }
        }
        remaining = requestBudget
        loading = true
        if (expand) expansionAttempted = true
        publish()
        try {
            ensure(listOf(seed.viewed.ref))
            attempts[seed.viewed.ref]?.takeIf { it.node != null }?.let {
                first = it.first.takeIf { link -> link.known } ?: first
                parent = it.node!!.parent.takeIf { link -> link.known } ?: parent
                latest = it.latest.takeIf { link -> link.known } ?: latest
            }
            ensure(listOfNotNull(first.ref, parent.ref, latest.ref))
            val parentMetadata = attempts.values.firstOrNull { it.gid == parent.ref?.gid && it.node != null }
            if (!first.known && parentMetadata?.first?.ref != null) first = parentMetadata.first
            if (!latest.known && parentMetadata?.latest?.ref?.gid?.let { it != parent.ref?.gid } == true) latest = parentMetadata.latest
            enrichKnownTokens()
            ensure(listOfNotNull(first.ref, parent.ref, latest.ref))
            if (expand) {
                ensure(seed.newer.map { it.ref })
                // HTML establishes that these are newer versions, even if the viewed token no longer works.
                if (!latest.known) {
                    val candidates = seed.newer.mapNotNull { n -> attempts[n.ref]?.takeIf { it.node != null }?.latest?.ref }.distinctBy { it.gid }
                    if (candidates.size == 1) latest = GalleryVersionLink.to(candidates.single())
                }
                if (!first.known) {
                    val candidates = seed.newer.mapNotNull { n -> attempts[n.ref]?.takeIf { it.node != null }?.first?.ref }.distinctBy { it.gid }
                    if (candidates.size == 1) first = GalleryVersionLink.to(candidates.single())
                }
                enrichKnownTokens()
                ensure(listOfNotNull(first.ref, parent.ref, latest.ref))
                val upPath = mutableSetOf<Long>()
                val downPath = mutableSetOf<Long>()
                val upComplete = walk(seed.viewed.ref, first.ref?.gid, upPath, upstreamWalk = true)
                val downComplete = latest.ref?.let { walk(it, seed.viewed.ref.gid, downPath, upstreamWalk = false) } ?: false
                upstream.addAll(upPath)
                downstream.addAll(downPath)
                val pathNodes = upPath + downPath + seed.viewed.ref.gid
                traced = upComplete && downComplete && seed.newer.all { it.ref.gid in pathNodes && nodes[it.ref.gid]?.metadataLoaded == true }
            }
        } finally {
            loading = false
            publish()
        }
    }

    private suspend fun walk(start: GalleryVersionRef, target: Long?, path: MutableSet<Long>, upstreamWalk: Boolean): Boolean {
        var ref = start
        val visited = mutableSetOf<Long>()
        while (visited.add(ref.gid)) {
            currentCoroutineContext().ensureActive()
            if (ref.gid != seed.viewed.ref.gid) path.add(ref.gid)
            ensure(listOf(ref))
            if (ref.gid == target) return true
            val link = nodes[ref.gid]?.parent ?: return false
            if (!link.known) return false
            if (link.ref == null) {
                if (upstreamWalk && target == null) {
                    first = GalleryVersionLink.to(ref)
                    return true
                }
                return false
            }
            ref = link.ref
        }
        return false
    }

    private fun enrichKnownTokens() {
        val known = buildList {
            nodes.values.forEach { add(it.ref) }
            attempts.values.forEach { metadata ->
                addAll(listOfNotNull(metadata.first.ref, metadata.latest.ref, metadata.node?.parent?.ref))
            }
        }
        fun enrich(link: GalleryVersionLink): GalleryVersionLink {
            val ref = link.ref ?: return link
            if (ref.canRequest) return link
            val supplied = known.firstOrNull { it.gid == ref.gid && it.canRequest } ?: return link
            return GalleryVersionLink.to(supplied)
        }
        first = enrich(first)
        parent = enrich(parent)
        latest = enrich(latest)
    }

    private suspend fun ensure(refs: List<GalleryVersionRef>) {
        refs.forEach { ref ->
            if (ref.gid > 0 && ref.gid !in nodes) {
                nodes[ref.gid] = GalleryVersionNode(ref)
            } else if (ref.canRequest && nodes[ref.gid]?.ref?.canRequest == false) {
                nodes[ref.gid] = nodes.getValue(ref.gid).copy(ref = ref)
            }
        }
        val missing = refs.map { ref -> if (ref.canRequest) ref else nodes[ref.gid]?.ref ?: ref }
            .filter { it.canRequest && nodes[it.gid]?.metadataLoaded != true && it !in attempts }.distinctBy { it.gid }
        for (batch in missing.chunked(MAX_REQUEST_SIZE)) {
            currentCoroutineContext().ensureActive()
            if (remaining <= 0) break
            remaining--
            val result = try {
                fetch(batch)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
            for (ref in batch) {
                val metadata = result.find { it.gid == ref.gid } ?: GalleryVersionMetadata(ref.gid)
                attempts[ref] = metadata
                metadata.node?.let { incoming ->
                    val previous = nodes[ref.gid]
                    val viewed = ref.gid == seed.viewed.ref.gid
                    nodes[ref.gid] = incoming.copy(
                        title = if (viewed) seed.viewed.title ?: incoming.title ?: previous?.title else incoming.title ?: previous?.title,
                        titleJpn = if (viewed) seed.viewed.titleJpn ?: incoming.titleJpn ?: previous?.titleJpn else incoming.titleJpn ?: previous?.titleJpn,
                        posted = if (viewed) seed.viewed.posted ?: incoming.posted ?: previous?.posted else incoming.posted ?: previous?.posted,
                        pages = if (viewed) seed.viewed.pages ?: incoming.pages ?: previous?.pages else incoming.pages ?: previous?.pages,
                        fileSizeBytes = incoming.fileSizeBytes ?: previous?.fileSizeBytes,
                        sizeText = previous?.sizeText ?: incoming.sizeText,
                        uploader = previous?.uploader ?: incoming.uploader,
                        parent = incoming.parent.takeIf { it.known } ?: previous?.parent ?: GalleryVersionLink.Unknown,
                        expunged = incoming.expunged ?: previous?.expunged,
                    )
                }
            }
            publish()
        }
    }

    private fun snapshot(): GalleryVersionChain {
        val keyRefs = listOfNotNull(first.ref, parent.ref, seed.viewed.ref, latest.ref).distinctBy { it.gid }
        keyRefs.forEach { ref -> nodes.putIfAbsent(ref.gid, GalleryVersionNode(ref)) }
        val visible = if (expansionAttempted) nodes.toMap() else nodes.filterKeys { gid -> keyRefs.any { it.gid == gid } }
        val (order, cycle) = orderGalleryVersions(visible, seed.viewed.ref.gid, first.ref?.gid, latest.ref?.gid, upstream, downstream)
        val middle = if (cycle) null else versionMiddleState(seed.viewed.ref.gid, first, parent, latest, nodes, seed.newer.mapTo(mutableSetOf()) { it.ref.gid })
        val completeKeys = first.known && parent.known && latest.known && middle != null && keyRefs.all { nodes[it.gid]?.metadataLoaded == true } && !cycle
        return GalleryVersionChain(
            viewedGid = seed.viewed.ref.gid,
            first = first,
            parent = parent,
            latest = latest,
            nodes = nodes.toMap(),
            orderedGids = order,
            hasMiddleVersions = middle,
            isLoading = loading,
            keyInfoComplete = completeKeys,
            expansionAttempted = expansionAttempted,
            traceComplete = traced && !cycle,
            hasCycle = cycle,
        )
    }

    private fun publish() {
        mutableState.value = snapshot()
    }
}
