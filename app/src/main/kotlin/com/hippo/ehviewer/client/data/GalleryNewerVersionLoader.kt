package com.hippo.ehviewer.client.data

import com.hippo.ehviewer.client.MAX_REQUEST_SIZE
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class GalleryNewerVersionState(
    val current: GalleryVersionNode,
    val nodes: Map<Long, GalleryVersionNode>,
    val orderedGids: List<Long>,
    val latestGid: Long? = null,
    val isLoading: Boolean = false,
    val isComplete: Boolean = false,
    val diagnostics: GalleryVersionDiagnostics = GalleryVersionDiagnostics(),
) {
    fun isDirectParent(older: Long, newer: Long) = nodes[newer]?.let { it.parentVerified && it.parent.ref?.gid == older } == true
}

/**
 * A forward timeline from the current page's GND. Only gdata is injected: this loader cannot
 * fetch First HTML, discover unrelated parents, or read the full-chain loading preference.
 */
class GalleryNewerVersionLoader(
    current: GalleryVersionNode,
    candidates: List<GalleryVersionNode>,
    private val fetch: suspend (List<GalleryVersionRef>) -> List<GalleryVersionMetadata>,
    private val requestBudget: Int = 50,
) {
    private val mutex = Mutex()
    private val seedCurrent = current.copy(metadataLoaded = false, parentVerified = false)
    private val viewedGid = current.ref.gid
    private val nodes = (
        candidates.filter { it.ref.gid > 0 && it.ref.gid != viewedGid }.distinctBy { it.ref.gid }.map {
            // GND supplies identity, title and date, never verified Parent edges.
            GalleryVersionNode(it.ref, title = it.title, posted = it.posted)
        } + seedCurrent
        ).associateBy { it.ref.gid }.toMutableMap()
    private val allowedGids = nodes.keys.toSet()
    private val attempts = mutableMapOf<GalleryVersionRef, GalleryVersionMetadata>()
    private val metadata = mutableMapOf<Long, GalleryVersionMetadata>()
    private val apiRefs = mutableMapOf<Long, GalleryVersionRef>()
    private val metadataIssues = mutableMapOf<Long, List<GalleryVersionIssue>>()
    private var remaining = requestBudget
    private var loading = false
    private var validationFinished = false
    private var currentStage: GalleryVersionStage? = null
    private val mutableState = MutableStateFlow(snapshot())
    val state = mutableState.asStateFlow()

    suspend fun load(retry: Boolean = false) = mutex.withLock {
        if (retry) invalidateFailedAttempts()
        remaining = requestBudget
        validationFinished = false
        currentStage = GalleryVersionStage.CANDIDATE_METADATA
        loading = true
        publish()
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                // Once the authoritative Latest path is complete, remaining GND branches are irrelevant.
                if (mutableState.value.isComplete) break
                val missing = (listOf(viewedGid) + allowedGids.filter { it != viewedGid }).mapNotNull { nodes[it]?.ref }
                    .map(::resolveRef).filter { it.canRequest && nodes[it.gid]?.metadataLoaded != true && it !in attempts }
                if (missing.isEmpty() || remaining <= 0) break
                remaining--
                val batch = missing.take(MAX_REQUEST_SIZE)
                var failure: GalleryVersionIssueCode? = null
                val result = try {
                    fetch(batch).associateBy { it.gid }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failure = galleryVersionFailureCode(e)
                    emptyMap()
                }
                batch.forEach { ref ->
                    val row = result[ref.gid] ?: GalleryVersionMetadata(ref.gid)
                    attempts[ref] = row
                    row.node?.let { incoming ->
                        metadata[ref.gid] = row
                        val previous = nodes.getValue(ref.gid)
                        val viewed = ref.gid == viewedGid
                        nodes[ref.gid] = incoming.copy(
                            title = if (viewed) seedCurrent.title ?: incoming.title ?: previous.title else incoming.title ?: previous.title,
                            titleJpn = if (viewed) seedCurrent.titleJpn ?: incoming.titleJpn ?: previous.titleJpn else incoming.titleJpn ?: previous.titleJpn,
                            posted = if (viewed) seedCurrent.posted ?: incoming.posted ?: previous.posted else incoming.posted ?: previous.posted,
                            pages = if (viewed) seedCurrent.pages ?: incoming.pages ?: previous.pages else incoming.pages ?: previous.pages,
                            fileSizeBytes = incoming.fileSizeBytes ?: previous.fileSizeBytes,
                            sizeText = previous.sizeText ?: incoming.sizeText,
                            parent = incoming.parent.takeIf { it.known } ?: previous.parent,
                            parentVerified = if (incoming.parent.known) incoming.parentVerified else previous.parentVerified,
                            expunged = incoming.expunged ?: previous.expunged,
                        )
                        listOfNotNull(incoming.ref, incoming.parent.ref, row.latest.ref).forEach(::rememberApiRef)
                    }
                    recordIssues(ref.gid, row, failure)
                }
                publish()
            }
            currentStage = GalleryVersionStage.CHAIN_VALIDATION
            publish()
            validationFinished = true
        } finally {
            loading = false
            currentStage = null
            publish()
        }
    }

    private fun resolveRef(ref: GalleryVersionRef) = apiRefs[ref.gid] ?: nodes[ref.gid]?.ref?.takeIf { it.canRequest } ?: ref

    private fun rememberApiRef(ref: GalleryVersionRef) {
        if (ref.gid !in allowedGids || !ref.canRequest) return
        val node = nodes.getValue(ref.gid)
        val resolved = if (node.metadataLoaded) node.ref else ref
        apiRefs[ref.gid] = resolved
        if (!node.metadataLoaded) nodes[ref.gid] = node.copy(ref = resolved)
    }

    private fun recordIssues(gid: Long, row: GalleryVersionMetadata, failure: GalleryVersionIssueCode?) {
        val stage = if (gid == viewedGid) GalleryVersionStage.KEY_METADATA else GalleryVersionStage.CANDIDATE_METADATA
        val codes = mutableSetOf<GalleryVersionIssueCode>()
        if (failure != null) {
            codes.add(failure)
        } else {
            when (row.rowStatus) {
                GalleryVersionRowStatus.VALID -> if (row.node == null) codes.add(GalleryVersionIssueCode.METADATA_MISSING)
                GalleryVersionRowStatus.API_ERROR -> codes.add(GalleryVersionIssueCode.API_ROW_ERROR)
                GalleryVersionRowStatus.TOKEN_INVALID -> codes.add(GalleryVersionIssueCode.TOKEN_INVALID)
                GalleryVersionRowStatus.METADATA_MISSING -> codes.add(GalleryVersionIssueCode.METADATA_MISSING)
            }
            row.errorCode?.let(codes::add)
            // The current gallery's older Parent is outside this timeline.
            if (gid != viewedGid && row.node != null && nodes[gid]?.parentVerified != true) codes.add(GalleryVersionIssueCode.PARENT_UNKNOWN)
        }
        if (codes.isEmpty()) metadataIssues.remove(gid) else metadataIssues[gid] = codes.map { GalleryVersionIssue(stage, it, listOf(gid), it.retryRecommended) }
    }

    private fun invalidateFailedAttempts() {
        val timeline = timeline()
        val scope = if (mutableState.value.isComplete) timeline.requiredGids else null
        val conflictingRoles = timeline.issues.filter { it.code == GalleryVersionIssueCode.ROLE_UNRESOLVED }.flatMap { it.affectedGids }.toSet()
        val failed = attempts.filterValues { row ->
            if (scope != null && row.gid !in scope) return@filterValues false
            val node = nodes[row.gid]
            row.gid in timeline.cycleGids || row.gid in conflictingRoles || row.node == null || row.rowStatus != GalleryVersionRowStatus.VALID ||
                node?.metadataLoaded != true || (row.gid != viewedGid && !node.parentVerified) ||
                (row.gid == viewedGid && timeline.latestGid == null)
        }.keys.toList()
        failed.forEach { ref ->
            attempts.remove(ref)
            nodes[ref.gid]?.let { nodes[ref.gid] = it.copy(metadataLoaded = false) }
        }
    }

    private data class Timeline(
        val orderedGids: List<Long>,
        val requiredGids: Set<Long>,
        val latestGid: Long?,
        val complete: Boolean,
        val issues: List<GalleryVersionIssue>,
        val cycleGids: Set<Long>,
    )

    private fun timeline(): Timeline {
        val fromCurrent = metadata[viewedGid]?.latest?.ref
        // Do not infer Latest from a partially loaded candidate batch.
        val latest = fromCurrent ?: if (validationFinished) {
            val reachable = confirmedPrefix().toSet() - viewedGid
            metadata.filterKeys { it in reachable }.values.mapNotNull { it.latest.ref }.distinctBy { it.gid }.singleOrNull()
        } else {
            null
        }
        val required = linkedSetOf(viewedGid)
        val backwards = linkedSetOf<Long>()
        var reachedCurrent = false
        var blocked: GalleryVersionIssue? = null
        var cycles = emptySet<Long>()
        fun problem(code: GalleryVersionIssueCode, gids: List<Long>) = GalleryVersionIssue(GalleryVersionStage.CHAIN_VALIDATION, code, gids, code.retryRecommended)
        fun unavailable(gid: Long): GalleryVersionIssueCode {
            val ref = nodes[gid]?.ref?.let(::resolveRef)
            return when {
                ref != null && !ref.canRequest -> GalleryVersionIssueCode.TOKEN_INVALID
                remaining <= 0 && attempts.keys.none { it.gid == gid } && gid in allowedGids -> GalleryVersionIssueCode.REQUEST_BUDGET_EXHAUSTED
                else -> GalleryVersionIssueCode.METADATA_MISSING
            }
        }
        if (latest != null) {
            var gid = latest.gid
            while (true) {
                required.add(gid)
                if (!backwards.add(gid)) {
                    cycles = backwards.dropWhile { it != gid }.toSet()
                    blocked = problem(GalleryVersionIssueCode.CYCLE_DETECTED, cycles.toList())
                    break
                }
                if (gid == viewedGid) {
                    reachedCurrent = true
                    break
                }
                val node = nodes[gid]
                if (node?.metadataLoaded != true) {
                    blocked = problem(unavailable(gid), listOf(gid))
                    break
                }
                if (!node.parentVerified || !node.parent.known) {
                    blocked = problem(GalleryVersionIssueCode.PARENT_UNKNOWN, listOf(gid))
                    break
                }
                val parent = node.parent.ref?.gid
                if (parent == null) {
                    blocked = problem(GalleryVersionIssueCode.ROLE_UNRESOLVED, listOf(gid))
                    break
                }
                // Stop at the viewed gallery; never follow its older Parent.
                gid = parent
            }
        }
        val order = if (reachedCurrent) {
            backwards.toList().asReversed()
        } else {
            val prefix = confirmedPrefix()
            required.addAll(prefix)
            prefix.toList()
        }
        val issues = mutableListOf<GalleryVersionIssue>()
        val descendants = mutableSetOf<Long>()
        val conflictingRoles = if (reachedCurrent) {
            backwards.filter { gid ->
                descendants.add(gid)
                // A cached tip is consistent when the verified Parent path already reaches it.
                metadata[gid]?.latest?.let { it.known && it.ref?.gid !in descendants } == true
            }
        } else {
            emptyList()
        }
        if (conflictingRoles.isNotEmpty()) issues.add(problem(GalleryVersionIssueCode.ROLE_UNRESOLVED, (conflictingRoles + viewedGid).distinct()))
        if (latest == null) {
            // Failed candidates may still be needed to establish Latest, rather than being proven orphans.
            required.addAll(allowedGids)
            issues.add(problem(GalleryVersionIssueCode.ROLE_UNRESOLVED, listOf(viewedGid)))
        }
        blocked?.let(issues::add)
        if (nodes[viewedGid]?.metadataLoaded != true) issues.add(problem(unavailable(viewedGid), listOf(viewedGid)))
        return Timeline(order, required, latest?.gid, reachedCurrent && conflictingRoles.isEmpty() && nodes[viewedGid]?.metadataLoaded == true, issues, cycles)
    }

    /** A partial prefix is displayed only while each next Parent edge has a unique child. */
    private fun confirmedPrefix(): List<Long> {
        val children = nodes.values.filter { it.ref.gid != viewedGid && it.metadataLoaded && it.parentVerified && it.parent.ref != null }.groupBy { it.parent.ref!!.gid }
        val prefix = linkedSetOf(viewedGid)
        var gid = viewedGid
        while (true) {
            val child = children[gid]?.singleOrNull() ?: break
            if (!prefix.add(child.ref.gid)) break
            gid = child.ref.gid
        }
        return prefix.toList()
    }

    private fun snapshot(): GalleryNewerVersionState {
        val timeline = timeline()
        val active = metadataIssues.filterKeys { it in timeline.requiredGids }.values.flatten().toMutableList()
        if (validationFinished) active.addAll(timeline.issues)
        val issues = mergeGalleryVersionIssues(active)
        val pending = if (issues.any { it.code == GalleryVersionIssueCode.REQUEST_BUDGET_EXHAUSTED }) timeline.requiredGids.count { nodes[it]?.metadataLoaded != true } else null
        return GalleryNewerVersionState(
            current = nodes.getValue(viewedGid),
            nodes = nodes.toMap(),
            orderedGids = timeline.orderedGids,
            latestGid = timeline.latestGid,
            isLoading = loading,
            isComplete = timeline.complete,
            diagnostics = GalleryVersionDiagnostics(currentStage, issues, requestBudget, requestBudget - remaining, pending),
        )
    }

    private fun publish() {
        mutableState.value = snapshot()
    }
}
