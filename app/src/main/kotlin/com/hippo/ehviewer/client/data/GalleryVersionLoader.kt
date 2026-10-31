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
    private val fetchCandidates: (suspend (GalleryVersionRef) -> GalleryVersionCandidates)? = null,
) {
    private val mutex = Mutex()
    private val nodes = (seed.newer + seed.viewed).associateBy { it.ref.gid }.toMutableMap()
    private val attempts = mutableMapOf<GalleryVersionRef, GalleryVersionMetadata>()
    private val apiRefs = mutableMapOf<Long, GalleryVersionRef>()
    private val candidatePages = mutableMapOf<GalleryVersionRef, GalleryVersionCandidates>()
    private val candidates = seed.newer.mapTo(mutableSetOf()) { it.ref.gid }
    private val fallbackRetried = mutableSetOf<GalleryVersionRef>()
    private var first = if (seed.viewed.parent == GalleryVersionLink.None) GalleryVersionLink.to(seed.viewed.ref) else GalleryVersionLink.Unknown
    private var parent = seed.viewed.parent
    private var latest = GalleryVersionLink.Unknown
    private var loading = false
    private var expansionAttempted = false
    private var traced = false
    private var remaining = 0
    private val mutableState = MutableStateFlow(snapshot())
    val state = mutableState.asStateFlow()

    suspend fun loadKeys(retry: Boolean = false) = load(retry, expand = false)
    suspend fun expand(retry: Boolean = false) = load(retry, expand = true)

    private suspend fun load(retry: Boolean, expand: Boolean) = mutex.withLock {
        if (retry) invalidateFailedAttempts()
        remaining = requestBudget
        loading = true
        if (expand) expansionAttempted = true
        publish()
        try {
            loadKeyMetadata()
            if (expand) {
                val found = loadCandidates()
                loadCandidateMetadata()
                inferMissingRoles()
                enrichKnownTokens()
                ensure(listOfNotNull(first.ref, parent.ref, latest.ref))
                traced = found && validateCandidateChain()
                if (!traced) traceWithExistingWalk()
            }
        } finally {
            loading = false
            publish()
        }
    }

    private fun invalidateFailedAttempts() {
        val cycle = mutableState.value.hasCycle
        val retryable = attempts.filterValues { value ->
            cycle || value.node == null || listOf(value.node.parent, value.first, value.latest).any { !it.known || it.ref?.canRequest == false }
        }.keys.toList()
        retryable.forEach { ref ->
            attempts.remove(ref)
            fallbackRetried.remove(ref)
            nodes[ref.gid]?.let { nodes[ref.gid] = it.copy(metadataLoaded = false) }
        }
        candidatePages.entries.removeAll { !it.value.found }
    }

    private suspend fun loadKeyMetadata() {
        ensure(listOf(seed.viewed.ref))
        attempts.values.firstOrNull { it.gid == seed.viewed.ref.gid && it.node != null }?.let {
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
    }

    private suspend fun loadCandidates(): Boolean {
        val ref = first.ref?.let(::resolveRef)
        val result = when {
            ref?.gid == seed.viewed.ref.gid -> GalleryVersionCandidates(true, seed.newer)
            ref == null || !ref.canRequest -> GalleryVersionCandidates(false)
            ref in candidatePages -> candidatePages.getValue(ref)
            fetchCandidates == null || remaining <= 0 -> GalleryVersionCandidates(false)
            else -> {
                remaining--
                val fetched = try {
                    fetchCandidates.invoke(ref)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    GalleryVersionCandidates(false)
                }
                candidatePages[ref] = fetched
                fetched
            }
        }
        result.items.forEach(::mergeCandidate)
        seed.newer.forEach(::mergeCandidate)
        publish()
        return result.found
    }

    private fun mergeCandidate(candidate: GalleryVersionNode) {
        val gid = candidate.ref.gid
        if (gid <= 0) return
        candidates.add(gid)
        val previous = nodes[gid]
        val ref = when {
            previous?.metadataLoaded == true -> previous.ref
            gid in apiRefs -> apiRefs.getValue(gid)
            candidate.ref.canRequest -> candidate.ref
            else -> previous?.ref ?: candidate.ref
        }
        // GND may supply identity/title/date, but never parent edges or API metadata.
        nodes[gid] = previous?.copy(ref = ref, title = previous.title ?: candidate.title, posted = previous.posted ?: candidate.posted)
            ?: GalleryVersionNode(ref, title = candidate.title, posted = candidate.posted)
    }

    private suspend fun loadCandidateMetadata() = ensure(candidates.mapNotNull { nodes[it]?.ref })

    private fun inferMissingRoles() {
        val known = attempts.values.filter { it.node != null }
        if (!first.known) {
            val refs = known.mapNotNull { it.first.ref }.distinctBy { it.gid }
            if (refs.size == 1) first = GalleryVersionLink.to(refs.single())
        }
        if (!latest.known) {
            val refs = known.mapNotNull { it.latest.ref }.distinctBy { it.gid }
            if (refs.size == 1) latest = GalleryVersionLink.to(refs.single())
        }
    }

    /** A complete, unbranched Parent path must cover every expected candidate and all key roles. */
    private fun validateCandidateChain(): Boolean {
        val firstGid = first.ref?.gid ?: return false
        var gid = latest.ref?.gid ?: return false
        if (!parent.known) return false
        val path = mutableSetOf<Long>()
        while (path.add(gid)) {
            val node = nodes[gid] ?: return false
            if (!node.metadataLoaded || !node.parentVerified || !node.parent.known) return false
            val metadata = attempts.values.firstOrNull { it.node?.ref == node.ref }
            if (metadata?.first?.known == true && metadata.first.ref?.gid != firstGid) return false
            if (metadata?.latest?.known == true && metadata.latest.ref?.gid != latest.ref?.gid) return false
            if (gid == firstGid) {
                if (node.parent != GalleryVersionLink.None) return false
                val expected = candidates + listOfNotNull(first.ref?.gid, parent.ref?.gid, seed.viewed.ref.gid, latest.ref?.gid)
                return expected.all { it in path } && nodes[seed.viewed.ref.gid]?.parent?.let { it.known && it.ref?.gid == parent.ref?.gid } == true
            }
            gid = node.parent.ref?.gid ?: return false
        }
        return false
    }

    private suspend fun traceWithExistingWalk() {
        walk(seed.viewed.ref, first.ref?.gid, upstreamWalk = true)
        latest.ref?.let { walk(it, seed.viewed.ref.gid, upstreamWalk = false) }
        traced = validateCandidateChain()
    }

    private suspend fun walk(start: GalleryVersionRef, target: Long?, upstreamWalk: Boolean) {
        var ref = start
        val visited = mutableSetOf<Long>()
        while (visited.add(ref.gid)) {
            currentCoroutineContext().ensureActive()
            ref = resolveRef(ref)
            val cachedFailure = ref in attempts && (nodes[ref.gid]?.metadataLoaded != true || nodes[ref.gid]?.parentVerified != true)
            ensure(listOf(ref))
            ref = resolveRef(ref)
            // A batch failure/partial parent gets one automatic retry, only if this walk needs it.
            if (remaining > 0 && cachedFailure && fallbackRetried.add(ref)) {
                attempts.remove(ref)
                nodes[ref.gid]?.let { nodes[ref.gid] = it.copy(metadataLoaded = false) }
                ensure(listOf(ref))
            }
            if (ref.gid == target) return
            val link = nodes[ref.gid]?.parent ?: return
            if (!link.known) return
            if (link.ref == null) {
                if (upstreamWalk && target == null && nodes[ref.gid]?.parentVerified == true) {
                    first = GalleryVersionLink.to(resolveRef(ref))
                    publish()
                }
                return
            }
            ref = link.ref
        }
    }

    private fun resolveRef(ref: GalleryVersionRef) = apiRefs[ref.gid] ?: nodes[ref.gid]?.ref?.takeIf { it.canRequest } ?: ref

    private fun enrichKnownTokens() {
        val known = buildList {
            nodes.values.forEach { add(it.ref) }
            attempts.values.forEach { metadata ->
                addAll(listOfNotNull(metadata.first.ref, metadata.latest.ref, metadata.node?.parent?.ref))
            }
        }
        fun enrich(link: GalleryVersionLink): GalleryVersionLink {
            val ref = link.ref ?: return link
            apiRefs[ref.gid]?.let { return GalleryVersionLink.to(it) }
            if (ref.canRequest) return link
            val supplied = known.firstOrNull { it.gid == ref.gid && it.canRequest } ?: return link
            return GalleryVersionLink.to(supplied)
        }
        first = enrich(first)
        parent = enrich(parent)
        latest = enrich(latest)
    }

    private fun rememberRef(ref: GalleryVersionRef) {
        if (ref.gid <= 0) return
        val previous = nodes[ref.gid]
        if (previous == null) {
            nodes[ref.gid] = GalleryVersionNode(ref)
        } else if (!previous.ref.canRequest && ref.canRequest) {
            nodes[ref.gid] = previous.copy(ref = ref)
        }
    }

    private fun rememberApiRef(ref: GalleryVersionRef) {
        rememberRef(ref)
        if (!ref.canRequest) return
        val previous = nodes.getValue(ref.gid)
        val supplied = if (previous.metadataLoaded) previous.ref else ref
        apiRefs[ref.gid] = supplied
        if (!previous.metadataLoaded) nodes[ref.gid] = previous.copy(ref = supplied)
    }

    private suspend fun ensure(refs: List<GalleryVersionRef>) {
        refs.forEach(::rememberRef)
        val missing = refs.map(::resolveRef)
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
                        parentVerified = if (incoming.parent.known) incoming.parentVerified else previous?.parentVerified ?: false,
                        expunged = incoming.expunged ?: previous?.expunged,
                    )
                    rememberApiRef(incoming.ref)
                    listOfNotNull(metadata.first.ref, metadata.latest.ref, incoming.parent.ref).forEach(::rememberApiRef)
                }
            }
            publish()
        }
    }

    private fun snapshot(): GalleryVersionChain {
        val keyRefs = listOfNotNull(first.ref, parent.ref, seed.viewed.ref, latest.ref).distinctBy { it.gid }
        keyRefs.forEach(::rememberRef)
        val (order, cycle) = orderGalleryVersions(nodes, seed.viewed.ref.gid, first.ref?.gid, parent.ref?.gid, latest.ref?.gid)
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
