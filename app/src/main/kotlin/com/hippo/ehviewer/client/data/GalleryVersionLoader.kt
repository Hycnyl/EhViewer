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
    private val metadataByGid = mutableMapOf<Long, GalleryVersionMetadata>()
    private val metadataIssues = mutableMapOf<Long, List<GalleryVersionIssue>>()
    private val pageIssues = mutableMapOf<Long, GalleryVersionIssue>()
    private val phaseIssues = mutableListOf<GalleryVersionIssue>()
    private var currentStage: GalleryVersionStage? = null
    private var keysLoadedOnce = false
    private var validationResult: ChainValidationResult? = null
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
    private var remaining = requestBudget
    private val mutableState = MutableStateFlow(snapshot())
    val state = mutableState.asStateFlow()

    suspend fun loadKeys(retry: Boolean = false) = load(retry, expand = false)
    suspend fun expand(retry: Boolean = false) = load(retry, expand = true)

    suspend fun retry(expanded: Boolean) {
        if (state.value.needsFullRetry(expanded)) expand(retry = true) else loadKeys(retry = true)
    }

    private suspend fun load(retry: Boolean, expand: Boolean) = mutex.withLock {
        if (retry) invalidateFailedAttempts()
        remaining = requestBudget
        phaseIssues.clear()
        validationResult = null
        currentStage = GalleryVersionStage.KEY_METADATA
        loading = true
        if (expand) expansionAttempted = true
        publish()
        try {
            loadKeyMetadata()
            keysLoadedOnce = true
            if (expand) {
                enterStage(GalleryVersionStage.FIRST_HTML)
                val found = loadCandidates()
                enterStage(GalleryVersionStage.CANDIDATE_METADATA)
                loadCandidateMetadata()
                inferMissingRoles()
                enrichKnownTokens()
                ensure(listOfNotNull(first.ref, parent.ref, latest.ref))
                traced = found && finishValidation()
                if (!traced) traceWithExistingWalk()
            }
        } finally {
            currentStage = null
            loading = false
            publish()
        }
    }

    private fun invalidateFailedAttempts() {
        val cycles = if (mutableState.value.hasCycle) cycleGids().toSet() else emptySet()
        val rolesUnknown = !first.known || first.ref == null || !latest.known || latest.ref == null
        val keys = listOfNotNull(seed.viewed.ref.gid, parent.ref?.gid, first.ref?.gid, latest.ref?.gid)
        // The ordering flag also covers contradictory key roles, without a Parent cycle.
        val conflictingKeys = if (mutableState.value.hasCycle && cycles.isEmpty()) keys.toSet() else emptySet()
        val retryable = attempts.filterValues { value ->
            val effective = nodes[value.gid]
            value.gid in cycles || value.gid in conflictingKeys || value.node == null || value.rowStatus != GalleryVersionRowStatus.VALID ||
                effective?.metadataLoaded != true || !effective.parentVerified ||
                (rolesUnknown && value.gid in keys && (!value.first.known || !value.latest.known)) ||
                listOf(value.node.parent, value.first, value.latest).any { it.ref?.let(::resolveRef)?.canRequest == false }
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
        metadataByGid[seed.viewed.ref.gid]?.let {
            first = it.first.takeIf { link -> link.known } ?: first
            parent = it.node!!.parent.takeIf { link -> link.known } ?: parent
            latest = it.latest.takeIf { link -> link.known } ?: latest
        }
        ensure(listOfNotNull(first.ref, parent.ref, latest.ref))
        val parentMetadata = parent.ref?.gid?.let(metadataByGid::get)
        if (!first.known && parentMetadata?.first?.ref != null) first = parentMetadata.first
        if (!latest.known && parentMetadata?.latest?.ref?.gid?.let { it != parent.ref?.gid } == true) latest = parentMetadata.latest
        enrichKnownTokens()
        ensure(listOfNotNull(first.ref, parent.ref, latest.ref))
    }

    private suspend fun loadCandidates(): Boolean {
        val ref = first.ref?.let(::resolveRef)
        val result = when {
            ref?.gid == seed.viewed.ref.gid -> GalleryVersionCandidates(true, seed.newer)
            ref == null -> GalleryVersionCandidates(false)
            !ref.canRequest -> {
                phaseIssues.add(issue(GalleryVersionStage.FIRST_HTML, GalleryVersionIssueCode.TOKEN_INVALID, listOf(ref.gid)))
                GalleryVersionCandidates(false)
            }
            ref in candidatePages -> candidatePages.getValue(ref)
            fetchCandidates == null -> GalleryVersionCandidates(false)
            remaining <= 0 -> {
                phaseIssues.add(issue(GalleryVersionStage.FIRST_HTML, GalleryVersionIssueCode.REQUEST_BUDGET_EXHAUSTED, listOf(ref.gid)))
                GalleryVersionCandidates(false)
            }
            else -> {
                remaining--
                var failure: GalleryVersionIssueCode? = null
                val fetched = try {
                    fetchCandidates.invoke(ref)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failure = galleryVersionFailureCode(e)
                    GalleryVersionCandidates(false)
                }
                candidatePages[ref] = fetched
                if (fetched.found) {
                    pageIssues.remove(ref.gid)
                } else {
                    pageIssues[ref.gid] = issue(GalleryVersionStage.FIRST_HTML, failure ?: GalleryVersionIssueCode.GND_MISSING, listOf(ref.gid))
                }
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

    private suspend fun loadCandidateMetadata() = ensure(candidates.mapNotNull { nodes[it]?.ref }, GalleryVersionStage.CANDIDATE_METADATA)

    private fun inferMissingRoles() {
        val known = metadataByGid.values
        if (!first.known) {
            val refs = known.mapNotNull { it.first.ref }.distinctBy { it.gid }
            if (refs.size == 1) first = GalleryVersionLink.to(refs.single())
        }
        if (!latest.known) {
            val refs = known.mapNotNull { it.latest.ref }.distinctBy { it.gid }
            if (refs.size == 1) latest = GalleryVersionLink.to(refs.single())
        }
    }

    /** Completeness is still proved by the same Parent path; diagnostics explain each failed check. */
    private fun validateCandidateChain(): ChainValidationResult {
        fun incomplete(code: GalleryVersionIssueCode, gids: List<Long>, retryable: Boolean = code.retryRecommended) = ChainValidationResult.Incomplete(code, gids, retryable)
        if (mutableState.value.hasCycle) {
            val cycles = cycleGids()
            return if (cycles.isNotEmpty()) {
                incomplete(GalleryVersionIssueCode.CYCLE_DETECTED, cycles)
            } else {
                incomplete(GalleryVersionIssueCode.ROLE_UNRESOLVED, listOf(seed.viewed.ref.gid), false)
            }
        }
        val firstGid = first.ref?.gid ?: return incomplete(GalleryVersionIssueCode.ROLE_UNRESOLVED, listOf(seed.viewed.ref.gid))
        var gid = latest.ref?.gid ?: return incomplete(GalleryVersionIssueCode.ROLE_UNRESOLVED, listOf(seed.viewed.ref.gid))
        if (!parent.known) return incomplete(GalleryVersionIssueCode.PARENT_UNKNOWN, listOf(seed.viewed.ref.gid))
        val path = mutableSetOf<Long>()
        while (path.add(gid)) {
            val node = nodes[gid]
            if (node?.metadataLoaded != true) {
                val budgetStopped = remaining <= 0 && attempts.keys.none { it.gid == gid }
                return incomplete(if (budgetStopped) GalleryVersionIssueCode.REQUEST_BUDGET_EXHAUSTED else GalleryVersionIssueCode.METADATA_MISSING, listOf(gid))
            }
            if (!node.parentVerified || !node.parent.known) return incomplete(GalleryVersionIssueCode.PARENT_UNKNOWN, listOf(gid))
            val metadata = metadataByGid[gid]
            if (metadata?.first?.known == true && metadata.first.ref?.gid != firstGid) return incomplete(GalleryVersionIssueCode.ROLE_UNRESOLVED, listOf(gid), false)
            if (metadata?.latest?.known == true && metadata.latest.ref?.gid != latest.ref?.gid) return incomplete(GalleryVersionIssueCode.ROLE_UNRESOLVED, listOf(gid), false)
            if (gid == firstGid) {
                if (node.parent != GalleryVersionLink.None) return incomplete(GalleryVersionIssueCode.ROLE_UNRESOLVED, listOf(gid), false)
                val expected = candidates + listOfNotNull(first.ref?.gid, parent.ref?.gid, seed.viewed.ref.gid, latest.ref?.gid)
                val unlinked = expected.filter { it !in path }
                if (unlinked.isNotEmpty()) return incomplete(GalleryVersionIssueCode.CANDIDATE_UNLINKED, unlinked)
                if (nodes[seed.viewed.ref.gid]?.parent?.let { it.known && it.ref?.gid == parent.ref?.gid } != true) return incomplete(GalleryVersionIssueCode.ROLE_UNRESOLVED, listOf(seed.viewed.ref.gid), false)
                return ChainValidationResult.Complete
            }
            gid = node.parent.ref?.gid ?: return incomplete(GalleryVersionIssueCode.ROLE_UNRESOLVED, listOf(node.ref.gid, firstGid), false)
        }
        return incomplete(GalleryVersionIssueCode.CYCLE_DETECTED, listOf(gid))
    }

    private fun finishValidation(): Boolean {
        enterStage(GalleryVersionStage.CHAIN_VALIDATION)
        validationResult = validateCandidateChain()
        val complete = validationResult == ChainValidationResult.Complete
        if (complete) {
            // A successful fallback has recovered the information that the First page could not supply.
            first.ref?.gid?.let(pageIssues::remove)
            phaseIssues.removeAll { it.stage == GalleryVersionStage.PARENT_TRACE || it.code == GalleryVersionIssueCode.REQUEST_BUDGET_EXHAUSTED }
        }
        return complete
    }

    private suspend fun traceWithExistingWalk() {
        enterStage(GalleryVersionStage.PARENT_TRACE)
        walk(seed.viewed.ref, first.ref?.gid, upstreamWalk = true)?.let(phaseIssues::add)
        val latestRef = latest.ref
        if (latestRef != null) {
            walk(latestRef, seed.viewed.ref.gid, upstreamWalk = false)?.let(phaseIssues::add)
        } else {
            phaseIssues.add(issue(GalleryVersionStage.PARENT_TRACE, GalleryVersionIssueCode.ROLE_UNRESOLVED, listOf(seed.viewed.ref.gid)))
        }
        traced = finishValidation()
    }

    private suspend fun walk(start: GalleryVersionRef, target: Long?, upstreamWalk: Boolean): GalleryVersionIssue? {
        var ref = start
        val visited = linkedSetOf<Long>()
        fun stopped(code: GalleryVersionIssueCode, gids: List<Long> = listOf(ref.gid)) = issue(GalleryVersionStage.PARENT_TRACE, code, gids)
        while (visited.add(ref.gid)) {
            currentCoroutineContext().ensureActive()
            ref = resolveRef(ref)
            val cachedFailure = ref in attempts && (nodes[ref.gid]?.metadataLoaded != true || nodes[ref.gid]?.parentVerified != true)
            ensure(listOf(ref), GalleryVersionStage.PARENT_TRACE)
            ref = resolveRef(ref)
            if (remaining > 0 && cachedFailure && fallbackRetried.add(ref)) {
                attempts.remove(ref)
                nodes[ref.gid]?.let { nodes[ref.gid] = it.copy(metadataLoaded = false) }
                ensure(listOf(ref), GalleryVersionStage.PARENT_TRACE)
            }
            if (ref.gid == target) return null
            val link = nodes[ref.gid]?.parent ?: return stopped(GalleryVersionIssueCode.METADATA_MISSING)
            if (!link.known) return stopped(if (!ref.canRequest) GalleryVersionIssueCode.TOKEN_INVALID else GalleryVersionIssueCode.PARENT_UNKNOWN)
            if (link.ref == null) {
                if (upstreamWalk && target == null && nodes[ref.gid]?.parentVerified == true) {
                    first = GalleryVersionLink.to(resolveRef(ref))
                    publish()
                    return null
                }
                return stopped(GalleryVersionIssueCode.ROLE_UNRESOLVED)
            }
            ref = link.ref
        }
        return stopped(GalleryVersionIssueCode.CYCLE_DETECTED, visited.dropWhile { it != ref.gid })
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

    private suspend fun ensure(refs: List<GalleryVersionRef>, stage: GalleryVersionStage = GalleryVersionStage.KEY_METADATA) {
        refs.forEach(::rememberRef)
        val resolved = refs.map(::resolveRef).distinctBy { it.gid }
        val invalid = resolved.filter { !it.canRequest && nodes[it.gid]?.metadataLoaded != true }.map { it.gid }
        if (invalid.isNotEmpty()) phaseIssues.add(issue(stage, GalleryVersionIssueCode.TOKEN_INVALID, invalid))
        val missing = resolved.filter { it.canRequest && nodes[it.gid]?.metadataLoaded != true && it !in attempts }
        for ((index, batch) in missing.chunked(MAX_REQUEST_SIZE).withIndex()) {
            currentCoroutineContext().ensureActive()
            if (remaining <= 0) {
                phaseIssues.add(issue(stage, GalleryVersionIssueCode.REQUEST_BUDGET_EXHAUSTED, missing.drop(index * MAX_REQUEST_SIZE).map { it.gid }))
                break
            }
            enterStage(stage)
            remaining--
            var failure: GalleryVersionIssueCode? = null
            val result = try {
                fetch(batch)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = galleryVersionFailureCode(e)
                emptyList()
            }
            for (ref in batch) {
                val metadata = result.find { it.gid == ref.gid } ?: GalleryVersionMetadata(ref.gid)
                attempts[ref] = metadata
                metadata.node?.let { incoming ->
                    metadataByGid[ref.gid] = metadata
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
                recordMetadataIssues(stage, ref.gid, metadata, failure)
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
            diagnostics = diagnostics(),
        )
    }

    private fun issue(stage: GalleryVersionStage, code: GalleryVersionIssueCode, gids: List<Long>, retryable: Boolean = code.retryRecommended) = GalleryVersionIssue(stage, code, gids.distinct(), retryable)

    private fun enterStage(stage: GalleryVersionStage) {
        if (currentStage != stage) {
            currentStage = stage
            publish()
        }
    }

    private fun recordMetadataIssues(stage: GalleryVersionStage, gid: Long, metadata: GalleryVersionMetadata, failure: GalleryVersionIssueCode?) {
        val codes = mutableSetOf<GalleryVersionIssueCode>()
        if (failure != null) {
            codes.add(failure)
        } else {
            when (metadata.rowStatus) {
                GalleryVersionRowStatus.VALID -> if (metadata.node == null) codes.add(GalleryVersionIssueCode.METADATA_MISSING)
                GalleryVersionRowStatus.API_ERROR -> codes.add(GalleryVersionIssueCode.API_ROW_ERROR)
                GalleryVersionRowStatus.METADATA_MISSING -> codes.add(GalleryVersionIssueCode.METADATA_MISSING)
                GalleryVersionRowStatus.TOKEN_INVALID -> codes.add(GalleryVersionIssueCode.TOKEN_INVALID)
            }
            metadata.errorCode?.let(codes::add)
            if (metadata.node != null && nodes[gid]?.parentVerified != true) codes.add(GalleryVersionIssueCode.PARENT_UNKNOWN)
        }
        if (codes.isEmpty()) {
            metadataIssues.remove(gid)
        } else {
            metadataIssues[gid] = codes.map { issue(stage, it, listOf(gid)) }
        }
    }

    /** Only inspect cycles when the existing ordering pass has detected a cycle/role conflict. */
    private fun cycleGids(): List<Long> {
        val checked = mutableSetOf<Long>()
        val cycles = mutableSetOf<Long>()
        for (gid in nodes.keys) {
            if (gid in checked) continue
            val path = linkedMapOf<Long, Int>()
            var current: Long? = gid
            while (current != null && current !in checked && current in nodes) {
                val previous = path[current]
                if (previous != null) {
                    cycles.addAll(path.keys.drop(previous))
                    break
                }
                path[current] = path.size
                current = nodes[current]?.takeIf { it.parentVerified }?.parent?.ref?.gid
            }
            checked.addAll(path.keys)
        }
        return cycles.toList()
    }

    private fun diagnostics(): GalleryVersionDiagnostics {
        val active = mutableListOf<GalleryVersionIssue>()
        active.addAll(metadataIssues.values.flatten())
        first.ref?.gid?.let { pageIssues[it]?.let(active::add) }
        for (problem in phaseIssues) {
            val gids = when (problem.code) {
                GalleryVersionIssueCode.TOKEN_INVALID -> problem.affectedGids.filter { nodes[it]?.ref?.let(::resolveRef)?.canRequest != true }
                GalleryVersionIssueCode.PARENT_UNKNOWN -> problem.affectedGids.filter { nodes[it]?.parentVerified != true }
                else -> problem.affectedGids
            }
            if (gids.isNotEmpty() || problem.affectedGids.isEmpty()) active.add(problem.copy(affectedGids = gids))
        }
        if (keysLoadedOnce) {
            if (!first.known || first.ref == null || !latest.known || latest.ref == null) active.add(issue(GalleryVersionStage.KEY_METADATA, GalleryVersionIssueCode.ROLE_UNRESOLVED, listOf(seed.viewed.ref.gid)))
            if (!parent.known) active.add(issue(GalleryVersionStage.KEY_METADATA, GalleryVersionIssueCode.PARENT_UNKNOWN, listOf(seed.viewed.ref.gid)))
        }
        (validationResult as? ChainValidationResult.Incomplete)?.let {
            val gids = when (it.reason) {
                GalleryVersionIssueCode.METADATA_MISSING, GalleryVersionIssueCode.REQUEST_BUDGET_EXHAUSTED -> it.affectedGids.filter { gid -> nodes[gid]?.metadataLoaded != true }
                GalleryVersionIssueCode.PARENT_UNKNOWN -> it.affectedGids.filter { gid -> nodes[gid]?.parentVerified != true }
                else -> it.affectedGids
            }
            if (gids.isNotEmpty() || it.affectedGids.isEmpty()) active.add(issue(GalleryVersionStage.CHAIN_VALIDATION, it.reason, gids, it.retryable))
        }
        val issues = mergeGalleryVersionIssues(active)
        val pending = if (issues.any { it.code == GalleryVersionIssueCode.REQUEST_BUDGET_EXHAUSTED }) nodes.values.count { !it.metadataLoaded } else null
        return GalleryVersionDiagnostics(currentStage, issues, requestBudget, requestBudget - remaining, pending)
    }

    private fun publish() {
        mutableState.value = snapshot()
    }
}
