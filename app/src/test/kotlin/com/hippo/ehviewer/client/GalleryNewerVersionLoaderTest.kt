package com.hippo.ehviewer.client

import com.hippo.ehviewer.client.data.GalleryNewerVersionLoader
import com.hippo.ehviewer.client.data.GalleryVersionIssueCode
import com.hippo.ehviewer.client.data.GalleryVersionLink
import com.hippo.ehviewer.client.data.GalleryVersionMetadata
import com.hippo.ehviewer.client.data.GalleryVersionNode
import com.hippo.ehviewer.client.data.GalleryVersionRef
import com.hippo.ehviewer.client.data.GalleryVersionRowStatus
import com.hippo.ehviewer.client.data.GalleryVersionStage
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryNewerVersionLoaderTest {
    private fun ref(gid: Long) = GalleryVersionRef(gid, gid.toString(16).padStart(10, '0'))
    private fun link(gid: Long?) = gid?.let { GalleryVersionLink.to(ref(it)) } ?: GalleryVersionLink.None
    private fun candidates(ids: Iterable<Long>) = ids.map { GalleryVersionNode(ref(it), title = "GND " + it) }
    private fun meta(gid: Long, parent: Long? = if (gid == 1L) null else gid - 1, latest: Long = 6) = GalleryVersionMetadata(
        gid,
        GalleryVersionNode(ref(gid), title = "API " + gid, posted = "2024-01-01", pages = 10, fileSizeBytes = 1024, parent = link(parent), expunged = false, metadataLoaded = true),
        first = link(1),
        latest = link(latest),
    )

    @Test
    fun currentIsImmediatelyVisibleAndGndNeverSuppliesVerifiedEdges() = runBlocking {
        var calls = 0
        val loader = GalleryNewerVersionLoader(
            GalleryVersionNode(ref(3), title = "HTML", pages = 99),
            listOf(GalleryVersionNode(ref(4), parent = link(3), metadataLoaded = true)),
            fetch = { refs ->
                calls++
                refs.map { meta(it.gid, latest = 4) }
            },
        )
        assertEquals(0, calls)
        assertEquals(listOf(3L), loader.state.value.orderedGids)
        assertEquals("HTML", loader.state.value.current.title)
        assertFalse(loader.state.value.nodes.getValue(4).metadataLoaded)
        loader.load()
        assertEquals(listOf(3L, 4L), loader.state.value.orderedGids)
        assertEquals("HTML", loader.state.value.current.title)
        assertEquals(99, loader.state.value.current.pages)
        assertTrue(loader.state.value.isComplete)
    }

    @Test
    fun parentEdgesOrderReversedNonConsecutiveIdsWithoutRequestingFirstOrOlderParents() = runBlocking {
        val rows = listOf(meta(100, 101, 5), meta(90, 100, 5), meta(70, 90, 5), meta(5, 70, 5)).associateBy { it.gid }
        val requested = mutableListOf<Long>()
        val loader = GalleryNewerVersionLoader(GalleryVersionNode(ref(100)), candidates(listOf(5, 70, 90)), fetch = { refs ->
            requested.addAll(refs.map { it.gid })
            refs.map { rows.getValue(it.gid) }
        })
        loader.load()
        assertEquals(listOf(100L, 90L, 70L, 5L), loader.state.value.orderedGids)
        assertEquals(setOf(100L, 90L, 70L, 5L), requested.toSet())
        assertEquals(4, requested.size)
        assertTrue(loader.state.value.isDirectParent(90, 70))
        assertEquals(5L, loader.state.value.latestGid)
        assertTrue(loader.state.value.isComplete)
        assertTrue(loader.state.value.diagnostics.issues.isEmpty())
    }

    @Test
    fun repeatedCurrentAndCandidateEntriesAreDeduplicated() = runBlocking {
        val requested = mutableListOf<Long>()
        val loader = GalleryNewerVersionLoader(GalleryVersionNode(ref(3), title = "Current"), candidates(listOf(3, 4, 4, 3, 5, 6)), fetch = { refs ->
            requested.addAll(refs.map { it.gid })
            refs.map { meta(it.gid) }
        })
        loader.load()
        assertEquals((3L..6L).toList(), loader.state.value.orderedGids)
        assertEquals(4, requested.size)
        assertEquals(4, requested.distinct().size)
        assertEquals("Current", loader.state.value.current.title)
    }

    @Test
    fun oldVersionsBranchesOrphansAndTheirFailuresDoNotPolluteTheTimeline() = runBlocking {
        for (kind in 0..5) {
            val requested = mutableMapOf<Long, Int>()
            val extras = if (kind == 1) listOf(50L, 51L) else listOf(50L)
            val supplied = candidates(listOf(1L, 2L, 4L, 5L, 6L) + extras).map {
                if (kind == 5 && it.ref.gid == 50L) it.copy(ref = GalleryVersionRef(50, null)) else it
            }
            val loader = GalleryNewerVersionLoader(GalleryVersionNode(ref(3)), supplied, fetch = { refs ->
                refs.map {
                    requested[it.gid] = requested.getOrDefault(it.gid, 0) + 1
                    when {
                        it.gid == 51L -> meta(51, 50)
                        it.gid != 50L -> meta(it.gid)
                        kind == 0 -> meta(50, 3)
                        kind == 1 -> meta(50, 51)
                        kind == 2 -> GalleryVersionMetadata(50, rowStatus = GalleryVersionRowStatus.API_ERROR)
                        kind == 3 -> GalleryVersionMetadata(50, rowStatus = GalleryVersionRowStatus.TOKEN_INVALID)
                        else -> GalleryVersionMetadata(50)
                    }
                }
            })
            loader.load()
            assertEquals((3L..6L).toList(), loader.state.value.orderedGids)
            assertTrue(loader.state.value.isComplete)
            assertTrue(loader.state.value.diagnostics.issues.isEmpty())
            assertFalse(49L in requested)
            val before = requested.toMap()
            loader.load(retry = true)
            assertEquals(before, requested)
            assertTrue(loader.state.value.diagnostics.issues.isEmpty())
        }
    }

    @Test
    fun verifiedLatestSelectsItsOwnBranchWhileAnUnknownLatestCannotChooseABranch() = runBlocking {
        for (known in listOf(false, true)) {
            val loader = GalleryNewerVersionLoader(GalleryVersionNode(ref(1)), candidates(2L..3L), fetch = { refs ->
                refs.map {
                    meta(it.gid, if (it.gid == 1L) null else 1, latest = 3).let { row -> if (known) row else row.copy(latest = GalleryVersionLink.Unknown) }
                }
            })
            loader.load()
            if (known) {
                assertEquals(listOf(1L, 3L), loader.state.value.orderedGids)
                assertTrue(loader.state.value.isComplete)
                assertTrue(loader.state.value.diagnostics.issues.isEmpty())
            } else {
                assertEquals(listOf(1L), loader.state.value.orderedGids)
                assertFalse(loader.state.value.isComplete)
                assertNull(loader.state.value.latestGid)
                assertTrue(loader.state.value.diagnostics.issues.any { it.code == GalleryVersionIssueCode.ROLE_UNRESOLVED })
            }
        }
    }

    @Test
    fun unknownParentOnTheNeededPathCanBeRetriedWithoutRefetchingSuccess() = runBlocking {
        var fail = true
        val counts = mutableMapOf<Long, Int>()
        val loader = GalleryNewerVersionLoader(GalleryVersionNode(ref(1)), candidates(2L..3L), fetch = { refs ->
            refs.map {
                counts[it.gid] = counts.getOrDefault(it.gid, 0) + 1
                val row = meta(it.gid, latest = 3)
                if (it.gid == 3L && fail) row.copy(node = row.node!!.copy(parent = GalleryVersionLink.Unknown, parentVerified = false)) else row
            }
        })
        loader.load()
        assertEquals(listOf(1L, 2L), loader.state.value.orderedGids)
        assertFalse(loader.state.value.isComplete)
        assertTrue(loader.state.value.diagnostics.issues.any { it.code == GalleryVersionIssueCode.PARENT_UNKNOWN && it.affectedGids == listOf(3L) })
        fail = false
        loader.load(retry = true)
        assertTrue(loader.state.value.isComplete)
        assertEquals((1L..3L).toList(), loader.state.value.orderedGids)
        assertEquals(mapOf(1L to 1, 2L to 1, 3L to 2), counts)
        assertTrue(loader.state.value.diagnostics.issues.isEmpty())
    }

    @Test
    fun aMissingMainNodeIsNotShownAndItsMetadataFailureIsReported() = runBlocking {
        val loader = GalleryNewerVersionLoader(GalleryVersionNode(ref(1)), candidates(2L..3L), fetch = { refs -> refs.map { if (it.gid == 2L) GalleryVersionMetadata(2) else meta(it.gid, latest = 3) } })
        loader.load()
        assertEquals(listOf(1L), loader.state.value.orderedGids)
        assertFalse(loader.state.value.isComplete)
        assertTrue(loader.state.value.diagnostics.issues.any { it.code == GalleryVersionIssueCode.METADATA_MISSING && it.affectedGids == listOf(2L) })
    }

    @Test
    fun requestsAreBatchedAndACompletePathStopsUnnecessaryCandidateQueries() = runBlocking {
        for (extra in listOf(false, true)) {
            val batches = mutableListOf<List<Long>>()
            val ids = (2L..70L).toList() + if (extra) (1000L..1200L).toList() else emptyList()
            val loader = GalleryNewerVersionLoader(GalleryVersionNode(ref(1)), candidates(ids), fetch = { refs ->
                batches.add(refs.map { it.gid })
                refs.map { meta(it.gid, latest = 70) }
            })
            loader.load()
            assertEquals(3, batches.size)
            assertTrue(batches.all { it.size <= 25 })
            assertEquals((1L..70L).toList(), loader.state.value.orderedGids)
            assertTrue(loader.state.value.isComplete)
            assertTrue(loader.state.value.diagnostics.issues.isEmpty())
            loader.load(retry = true)
            assertEquals(3, batches.size)
        }
    }

    @Test
    fun batchesPublishAConfirmedPrefixBeforeTheFinalRequestFinishes() = runBlocking {
        val pending = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var requests = 0
        val loader = GalleryNewerVersionLoader(GalleryVersionNode(ref(1)), candidates(2L..40L), fetch = { refs ->
            requests++
            if (requests == 2) {
                pending.complete(Unit)
                release.await()
            }
            refs.map { meta(it.gid, latest = 40) }
        })
        val job = launch { loader.load() }
        withTimeout(5_000) { pending.await() }
        assertTrue(loader.state.value.isLoading)
        assertEquals((1L..25L).toList(), loader.state.value.orderedGids)
        release.complete(Unit)
        job.join()
        assertEquals((1L..40L).toList(), loader.state.value.orderedGids)
        assertTrue(loader.state.value.isComplete)
    }

    @Test
    fun cancellationKeepsSuccessfulBatchesAndClearsLoadingWithoutRecordingAnError() = runBlocking {
        val pending = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val counts = mutableMapOf<Long, Int>()
        val loader = GalleryNewerVersionLoader(GalleryVersionNode(ref(1)), candidates(2L..40L), fetch = { refs ->
            refs.forEach { counts[it.gid] = counts.getOrDefault(it.gid, 0) + 1 }
            if (refs.any { it.gid == 26L }) {
                pending.complete(Unit)
                release.await()
            }
            refs.map { meta(it.gid, latest = 40) }
        })
        val job = launch { loader.load() }
        withTimeout(5_000) { pending.await() }
        job.cancelAndJoin()
        assertFalse(loader.state.value.isLoading)
        assertNull(loader.state.value.diagnostics.currentStage)
        assertTrue(loader.state.value.diagnostics.issues.isEmpty())
        assertEquals((1L..25L).toList(), loader.state.value.orderedGids)
        release.complete(Unit)
        loader.load(retry = true)
        assertTrue(loader.state.value.isComplete)
        assertTrue(counts.filterKeys { it <= 25L }.values.all { it == 1 })
        assertTrue(counts.filterKeys { it > 25L }.values.all { it == 2 })
    }

    @Test
    fun directAndWrappedCancellationsAreNeverConvertedIntoNetworkFailures() = runBlocking {
        for (wrapped in listOf(false, true)) {
            val loader = GalleryNewerVersionLoader(GalleryVersionNode(ref(1)), candidates(2L..3L), fetch = {
                val cancelled = CancellationException("cancelled")
                if (wrapped) throw IllegalStateException("wrapped", cancelled) else throw cancelled
            })
            var cancelled = false
            try {
                loader.load()
            } catch (_: CancellationException) {
                cancelled = true
            }
            assertTrue(cancelled)
            assertFalse(loader.state.value.isLoading)
            assertTrue(loader.state.value.diagnostics.issues.isEmpty())
        }
    }

    @Test
    fun requestBudgetLimitsEachAttemptAndRetriesContinueFromCachedBatches() = runBlocking {
        val counts = mutableMapOf<Long, Int>()
        val loader = GalleryNewerVersionLoader(GalleryVersionNode(ref(1)), candidates(2L..70L), fetch = { refs ->
            refs.map {
                counts[it.gid] = counts.getOrDefault(it.gid, 0) + 1
                meta(it.gid, latest = 70)
            }
        }, requestBudget = 1)
        loader.load()
        assertFalse(loader.state.value.isComplete)
        assertEquals(1, loader.state.value.diagnostics.requestsUsed)
        assertTrue(loader.state.value.diagnostics.issues.any { it.code == GalleryVersionIssueCode.REQUEST_BUDGET_EXHAUSTED })
        assertTrue(loader.state.value.diagnostics.issues.none { it.code == GalleryVersionIssueCode.NETWORK_ERROR || it.code == GalleryVersionIssueCode.API_ROW_ERROR })
        loader.load(retry = true)
        assertFalse(loader.state.value.isComplete)
        loader.load(retry = true)
        assertTrue(loader.state.value.isComplete)
        assertEquals((1L..70L).toList(), loader.state.value.orderedGids)
        assertTrue(counts.values.all { it == 1 })
    }

    @Test
    fun apiSuppliedTokensRepairGndTokensAndRemainValidNavigationTargets() = runBlocking {
        val requested = mutableListOf<GalleryVersionRef>()
        val loader = GalleryNewerVersionLoader(GalleryVersionNode(ref(1)), listOf(GalleryVersionNode(ref(2)), GalleryVersionNode(GalleryVersionRef(3, null))), fetch = { refs ->
            requested.addAll(refs)
            refs.map { meta(it.gid, latest = 3) }
        })
        loader.load()
        assertTrue(loader.state.value.isComplete)
        assertEquals((1L..3L).toList(), loader.state.value.orderedGids)
        assertTrue(requested.all { it.canRequest })
        assertEquals(ref(3), loader.state.value.nodes.getValue(3).ref)
        assertTrue(loader.state.value.diagnostics.issues.isEmpty())
    }

    @Test
    fun unavailableTokensNeverCreateRequestsOrNavigableFakeNodes() = runBlocking {
        val requested = mutableListOf<Long>()
        val loader = GalleryNewerVersionLoader(GalleryVersionNode(ref(1)), listOf(GalleryVersionNode(ref(2)), GalleryVersionNode(GalleryVersionRef(3, null))), fetch = { refs ->
            requested.addAll(refs.map { it.gid })
            refs.map { meta(it.gid, latest = 3).copy(latest = GalleryVersionLink.to(GalleryVersionRef(3, null))) }
        })
        loader.load()
        assertFalse(3L in requested)
        assertFalse(3L in loader.state.value.orderedGids)
        assertFalse(loader.state.value.nodes.getValue(3).ref.canRequest)
        assertFalse(loader.state.value.isComplete)
        assertTrue(loader.state.value.diagnostics.issues.any { it.code == GalleryVersionIssueCode.TOKEN_INVALID })
        val before = requested.toList()
        loader.load(retry = true)
        assertEquals(before, requested)
    }

    @Test
    fun cyclesOnTheLatestPathAreDiagnosedAndOnlyCycleMembersAreRetried() = runBlocking {
        var cycle = true
        val counts = mutableMapOf<Long, Int>()
        val loader = GalleryNewerVersionLoader(GalleryVersionNode(ref(1)), candidates(2L..3L), fetch = { refs ->
            refs.map {
                counts[it.gid] = counts.getOrDefault(it.gid, 0) + 1
                meta(
                    it.gid,
                    if (cycle && it.gid == 2L) {
                        3
                    } else if (it.gid == 1L) {
                        null
                    } else {
                        it.gid - 1
                    },
                    latest = 3,
                )
            }
        })
        loader.load()
        assertFalse(loader.state.value.isComplete)
        assertEquals(listOf(1L), loader.state.value.orderedGids)
        assertEquals(setOf(2L, 3L), loader.state.value.diagnostics.issues.single { it.code == GalleryVersionIssueCode.CYCLE_DETECTED }.affectedGids.toSet())
        cycle = false
        loader.load(retry = true)
        assertTrue(loader.state.value.isComplete)
        assertEquals(mapOf(1L to 1, 2L to 2, 3L to 2), counts)
        assertTrue(loader.state.value.diagnostics.issues.isEmpty())
    }

    @Test
    fun failedBatchesRetainTheirCauseAndRecoveryReusesEarlierSuccess() = runBlocking {
        var fail = true
        val counts = mutableMapOf<Long, Int>()
        val loader = GalleryNewerVersionLoader(GalleryVersionNode(ref(1)), candidates(2L..40L), fetch = { refs ->
            refs.forEach { counts[it.gid] = counts.getOrDefault(it.gid, 0) + 1 }
            if (refs.any { it.gid == 26L } && fail) throw IOException("not exposed")
            refs.map { meta(it.gid, latest = 40) }
        })
        loader.load()
        assertTrue(loader.state.value.diagnostics.issues.any { it.stage == GalleryVersionStage.CANDIDATE_METADATA && it.code == GalleryVersionIssueCode.NETWORK_ERROR && 40L in it.affectedGids })
        assertFalse(loader.state.value.diagnostics.toString().contains("not exposed"))
        assertFalse(loader.state.value.isComplete)
        fail = false
        loader.load(retry = true)
        assertTrue(loader.state.value.isComplete)
        assertTrue(counts.filterKeys { it <= 25L }.values.all { it == 1 })
        assertTrue(counts.filterKeys { it > 25L }.values.all { it == 2 })
    }

    @Test
    fun apiRowErrorsAreDeduplicatedAcrossRetries() = runBlocking {
        val loader = GalleryNewerVersionLoader(GalleryVersionNode(ref(1)), candidates(2L..3L), fetch = { refs -> refs.map { if (it.gid == 2L) GalleryVersionMetadata(2, rowStatus = GalleryVersionRowStatus.API_ERROR) else meta(it.gid, latest = 3) } })
        loader.load()
        val count = loader.state.value.diagnostics.issues.size
        repeat(3) {
            loader.load(retry = true)
            val issues = loader.state.value.diagnostics.issues
            assertEquals(count, issues.size)
            assertEquals(issues.size, issues.map { it.stage to it.code }.distinct().size)
            assertTrue(issues.any { it.code == GalleryVersionIssueCode.API_ROW_ERROR })
        }
    }

    @Test
    fun currentApiFailureDoesNotHideConfirmedNewerNodesAndOnlyCurrentNeedsRetry() = runBlocking {
        var fail = true
        val counts = mutableMapOf<Long, Int>()
        val loader = GalleryNewerVersionLoader(GalleryVersionNode(ref(1), title = "HTML"), candidates(2L..3L), fetch = { refs ->
            refs.map {
                counts[it.gid] = counts.getOrDefault(it.gid, 0) + 1
                if (it.gid == 1L && fail) GalleryVersionMetadata(1) else meta(it.gid, latest = 3)
            }
        })
        loader.load()
        assertEquals((1L..3L).toList(), loader.state.value.orderedGids)
        assertFalse(loader.state.value.isComplete)
        assertTrue(loader.state.value.diagnostics.issues.any { it.stage == GalleryVersionStage.KEY_METADATA && it.code == GalleryVersionIssueCode.METADATA_MISSING })
        fail = false
        loader.load(retry = true)
        assertTrue(loader.state.value.isComplete)
        assertEquals(mapOf(1L to 2, 2L to 1, 3L to 1), counts)
        assertEquals("HTML", loader.state.value.current.title)
    }

    @Test
    fun conflictingLatestMetadataIsRetriedWithoutQueryingUnrelatedVersions() = runBlocking {
        var conflict = true
        val requested = mutableMapOf<Long, Int>()
        val loader = GalleryNewerVersionLoader(GalleryVersionNode(ref(1)), candidates(2L..3L), fetch = { refs ->
            refs.map {
                requested[it.gid] = requested.getOrDefault(it.gid, 0) + 1
                meta(it.gid, latest = if (conflict && it.gid == 3L) 4 else 3)
            }
        })
        loader.load()
        assertFalse(loader.state.value.isComplete)
        assertTrue(loader.state.value.diagnostics.issues.any { it.code == GalleryVersionIssueCode.ROLE_UNRESOLVED })
        assertFalse(4L in requested)
        conflict = false
        loader.load(retry = true)
        assertTrue(loader.state.value.isComplete)
        assertEquals(mapOf(1L to 2, 2L to 1, 3L to 2), requested)
        assertTrue(loader.state.value.diagnostics.issues.isEmpty())
    }
}
