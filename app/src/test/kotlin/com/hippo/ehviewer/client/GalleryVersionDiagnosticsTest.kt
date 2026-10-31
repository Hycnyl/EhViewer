package com.hippo.ehviewer.client

import com.hippo.ehviewer.client.data.GalleryVersionCandidates
import com.hippo.ehviewer.client.data.GalleryVersionIssueCode
import com.hippo.ehviewer.client.data.GalleryVersionLink
import com.hippo.ehviewer.client.data.GalleryVersionLoader
import com.hippo.ehviewer.client.data.GalleryVersionMetadata
import com.hippo.ehviewer.client.data.GalleryVersionNode
import com.hippo.ehviewer.client.data.GalleryVersionRef
import com.hippo.ehviewer.client.data.GalleryVersionRequestException
import com.hippo.ehviewer.client.data.GalleryVersionRowStatus
import com.hippo.ehviewer.client.data.GalleryVersionSeed
import com.hippo.ehviewer.client.data.GalleryVersionStage
import com.hippo.ehviewer.client.data.galleryVersionHttpFailure
import com.hippo.ehviewer.client.exception.IpBannedException
import com.hippo.ehviewer.client.exception.ParseException
import com.hippo.ehviewer.client.parser.GalleryApiParser
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryVersionDiagnosticsTest {
    private fun ref(gid: Long) = GalleryVersionRef(gid, gid.toString(16).padStart(10, '0'))
    private fun link(gid: Long?) = gid?.let { GalleryVersionLink.to(ref(it)) } ?: GalleryVersionLink.None
    private fun meta(gid: Long, parent: Long? = if (gid == 1L) null else gid - 1, latest: Long = 6) = GalleryVersionMetadata(
        gid,
        GalleryVersionNode(ref(gid), title = "API $gid", posted = "2024-01-01", pages = 10, parent = link(parent), metadataLoaded = true),
        first = link(1),
        latest = link(latest),
    )
    private fun gnd(ids: Iterable<Long>) = GalleryVersionCandidates(true, ids.map { GalleryVersionNode(ref(it)) })
    private fun has(loader: GalleryVersionLoader, stage: GalleryVersionStage, code: GalleryVersionIssueCode) = loader.state.value.diagnostics.issues.any { it.stage == stage && it.code == code }

    @Test
    fun collapsedLongChainRetryResumesFullLoadingEvenWhenMiddleStateIsUnknown() = runBlocking {
        var cycle = true
        var html = 0
        val counts = mutableMapOf<Long, Int>()
        val loader = GalleryVersionLoader(
            GalleryVersionSeed(GalleryVersionNode(ref(10))),
            fetch = { refs ->
                refs.map {
                    counts[it.gid] = counts.getOrDefault(it.gid, 0) + 1
                    meta(
                        it.gid,
                        if (cycle && it.gid == 40L) {
                            41
                        } else if (it.gid == 1L) {
                            null
                        } else {
                            it.gid - 1
                        },
                        latest = 60,
                    )
                }
            },
            fetchCandidates = {
                html++
                gnd(2L..60L)
            },
        )
        loader.loadKeys()
        loader.expand()
        assertTrue(loader.state.value.expansionAttempted)
        assertNull(loader.state.value.hasMiddleVersions)
        assertFalse(loader.state.value.traceComplete)
        assertTrue(has(loader, GalleryVersionStage.CHAIN_VALIDATION, GalleryVersionIssueCode.CYCLE_DETECTED))
        val affected = loader.state.value.diagnostics.issues.first { it.code == GalleryVersionIssueCode.CYCLE_DETECTED }.affectedGids
        assertEquals(setOf(40L, 41L), affected.toSet())
        cycle = false
        loader.retry(expanded = false)
        assertTrue(loader.state.value.traceComplete)
        assertEquals((1L..60L).toList(), loader.state.value.orderedGids)
        assertEquals(1, html)
        assertEquals(2, counts[40])
        assertEquals(2, counts[41])
        assertTrue(counts.filterKeys { it != 40L && it != 41L }.values.all { it == 1 })
        assertTrue(loader.state.value.diagnostics.issues.isEmpty())
    }

    @Test
    fun collapsedRetryStillFetchesCandidatesWhenThereAreNoMiddleVersions() = runBlocking {
        var extraRequests = 0
        var html = 0
        val loader = GalleryVersionLoader(
            GalleryVersionSeed(GalleryVersionNode(ref(2))),
            fetch = { refs ->
                refs.map {
                    if (it.gid == 4L) {
                        extraRequests++
                        GalleryVersionMetadata(4)
                    } else {
                        meta(it.gid, latest = 3)
                    }
                }
            },
            fetchCandidates = {
                html++
                gnd(listOf(2, 3, 4))
            },
        )
        loader.loadKeys()
        loader.expand()
        assertEquals(false, loader.state.value.hasMiddleVersions)
        assertFalse(loader.state.value.traceComplete)
        assertEquals(1, extraRequests)
        loader.retry(expanded = false)
        assertEquals(2, extraRequests)
        assertEquals(1, html)
    }

    @Test
    fun collapsedRetryRepeatsFailedFirstHtmlAndContinuesParentWalkUsingSuccessfulCache() = runBlocking {
        var html = 0
        val counts = mutableMapOf<Long, Int>()
        val loader = GalleryVersionLoader(
            GalleryVersionSeed(GalleryVersionNode(ref(5))),
            fetch = { refs ->
                refs.map {
                    counts[it.gid] = counts.getOrDefault(it.gid, 0) + 1
                    meta(it.gid, latest = 10)
                }
            },
            requestBudget = 4,
            fetchCandidates = {
                html++
                if (html == 1) throw IOException("first unavailable")
                GalleryVersionCandidates(false)
            },
        )
        loader.loadKeys()
        loader.expand()
        assertFalse(loader.state.value.traceComplete)
        assertTrue(has(loader, GalleryVersionStage.FIRST_HTML, GalleryVersionIssueCode.NETWORK_ERROR))
        loader.retry(expanded = false)
        assertEquals(2, html)
        assertTrue(loader.state.value.traceComplete)
        assertEquals((1L..10L).toList(), loader.state.value.orderedGids)
        assertTrue(counts.values.all { it == 1 })
        assertTrue(loader.state.value.diagnostics.issues.isEmpty())
    }

    @Test
    fun retryBeforeAnyExpansionOnlyReloadsFailedKeyMetadata() = runBlocking {
        var fail = true
        var html = 0
        val counts = mutableMapOf<Long, Int>()
        val loader = GalleryVersionLoader(
            GalleryVersionSeed(GalleryVersionNode(ref(3), parent = link(2))),
            fetch = { refs ->
                refs.map {
                    counts[it.gid] = counts.getOrDefault(it.gid, 0) + 1
                    if (it.gid == 3L && fail) throw IOException("key failed")
                    meta(it.gid)
                }
            },
            fetchCandidates = {
                html++
                gnd(2L..6L)
            },
        )
        loader.loadKeys()
        assertFalse(loader.state.value.expansionAttempted)
        fail = false
        loader.retry(expanded = false)
        assertFalse(loader.state.value.expansionAttempted)
        assertTrue(loader.state.value.keyInfoComplete)
        assertEquals(0, html)
        assertEquals(2, counts[3])
        assertTrue(counts.filterKeys { it != 3L }.values.all { it == 1 })
        assertTrue(loader.state.value.diagnostics.issues.isEmpty())
    }

    @Test
    fun completeTraceWithPartialCandidateDataStillRetriesTheCandidateAfterCollapse() = runBlocking {
        var partial = true
        var candidateRequests = 0
        var html = 0
        val loader = GalleryVersionLoader(
            GalleryVersionSeed(GalleryVersionNode(ref(3))),
            fetch = { refs ->
                refs.map {
                    val row = meta(it.gid)
                    if (it.gid == 4L) {
                        candidateRequests++
                        if (partial) row.copy(rowStatus = GalleryVersionRowStatus.METADATA_MISSING) else row
                    } else {
                        row
                    }
                }
            },
            fetchCandidates = {
                html++
                gnd(2L..6L)
            },
        )
        loader.loadKeys()
        loader.expand()
        assertTrue(loader.state.value.traceComplete)
        assertTrue(has(loader, GalleryVersionStage.CANDIDATE_METADATA, GalleryVersionIssueCode.METADATA_MISSING))
        partial = false
        loader.retry(expanded = false)
        assertEquals(2, candidateRequests)
        assertEquals(1, html)
        assertTrue(loader.state.value.traceComplete)
        assertTrue(loader.state.value.diagnostics.issues.isEmpty())
    }

    @Test
    fun keyFailuresRetainClassifiedCausesAndNeverStoreExceptionTextInUiState() = runBlocking {
        val cases = listOf(
            IOException("secret network response") to GalleryVersionIssueCode.NETWORK_ERROR,
            GalleryVersionRequestException(checkNotNull(galleryVersionHttpFailure(503, false, false))) to GalleryVersionIssueCode.HTTP_ERROR,
            GalleryVersionRequestException(checkNotNull(galleryVersionHttpFailure(401, false, false))) to GalleryVersionIssueCode.LOGIN_REQUIRED,
            GalleryVersionRequestException(checkNotNull(galleryVersionHttpFailure(200, true, false))) to GalleryVersionIssueCode.LOGIN_REQUIRED,
            IpBannedException("secret restriction") to GalleryVersionIssueCode.IP_BANNED,
            GalleryVersionRequestException(checkNotNull(galleryVersionHttpFailure(403, false, true))) to GalleryVersionIssueCode.CLOUDFLARE_CHALLENGE,
            ParseException("secret HTML") to GalleryVersionIssueCode.PARSE_ERROR,
            SerializationException("secret JSON") to GalleryVersionIssueCode.PARSE_ERROR,
            IllegalStateException("secret unexpected response") to GalleryVersionIssueCode.UNKNOWN_ERROR,
        )
        for ((error, code) in cases) {
            val loader = GalleryVersionLoader(GalleryVersionSeed(GalleryVersionNode(ref(3))), fetch = { throw error })
            loader.loadKeys()
            val issue = loader.state.value.diagnostics.issues.first { it.code == code }
            assertEquals(GalleryVersionStage.KEY_METADATA, issue.stage)
            assertEquals(listOf(3L), issue.affectedGids)
            assertFalse(loader.state.value.diagnostics.toString().contains("secret"))
            assertEquals(1, loader.state.value.diagnostics.requestsUsed)
            assertNull(loader.state.value.diagnostics.currentStage)
        }
        assertNull(galleryVersionHttpFailure(200, false, false))
    }

    @Test
    fun firstHtmlFailuresAreDistinctFromMissingAndValidEmptyGnd() = runBlocking {
        for (kind in 0..3) {
            val loader = GalleryVersionLoader(
                GalleryVersionSeed(GalleryVersionNode(ref(3)), (4L..6L).map { GalleryVersionNode(ref(it)) }),
                fetch = { refs -> refs.map { if (it.gid == 5L) GalleryVersionMetadata(5) else meta(it.gid) } },
                fetchCandidates = {
                    when (kind) {
                        0 -> throw IOException("First HTML failed")
                        1 -> GalleryVersionCandidates(false)
                        3 -> throw ParseException("First HTML parse failed")
                        else -> GalleryVersionCandidates(true)
                    }
                },
            )
            loader.loadKeys()
            loader.expand()
            assertFalse(loader.state.value.traceComplete)
            assertEquals(kind == 0, has(loader, GalleryVersionStage.FIRST_HTML, GalleryVersionIssueCode.NETWORK_ERROR))
            assertEquals(kind == 1, has(loader, GalleryVersionStage.FIRST_HTML, GalleryVersionIssueCode.GND_MISSING))
            assertEquals(kind == 3, has(loader, GalleryVersionStage.FIRST_HTML, GalleryVersionIssueCode.PARSE_ERROR))
            if (kind == 2) assertTrue(loader.state.value.diagnostics.issues.none { it.stage == GalleryVersionStage.FIRST_HTML })
        }
    }

    @Test
    fun failedCandidateBatchesAreNotCollapsedIntoMissingMetadata() = runBlocking {
        val loader = GalleryVersionLoader(
            GalleryVersionSeed(GalleryVersionNode(ref(3))),
            fetch = { refs -> if (refs.any { it.gid == 4L || it.gid == 5L }) throw IOException("batch failed") else refs.map { meta(it.gid) } },
            fetchCandidates = { gnd(2L..6L) },
        )
        loader.loadKeys()
        loader.expand()
        assertTrue(has(loader, GalleryVersionStage.CANDIDATE_METADATA, GalleryVersionIssueCode.NETWORK_ERROR))
        assertTrue(has(loader, GalleryVersionStage.PARENT_TRACE, GalleryVersionIssueCode.NETWORK_ERROR))
        assertFalse(has(loader, GalleryVersionStage.CANDIDATE_METADATA, GalleryVersionIssueCode.METADATA_MISSING))
    }

    @Test
    fun parserRetainsApiErrorsTokenRejectionAndPartialRecordStatusWithoutRawMessages() {
        val rows = GalleryApiParser.parseVersionMetadata("""{"gmetadata":[{"gid":4,"error":"secret gallery error"},{"gid":5,"error":"secret incorrect key provided"},{"gid":6,"token":"bad","title":"Missing token"},{"gid":7,"token":"0000000007","title":"Partial"}]}""")
        assertEquals(GalleryVersionRowStatus.API_ERROR, rows[0].rowStatus)
        assertEquals(GalleryVersionIssueCode.API_ROW_ERROR, rows[0].errorCode)
        assertEquals(GalleryVersionRowStatus.API_ERROR, rows[1].rowStatus)
        assertEquals(GalleryVersionIssueCode.TOKEN_INVALID, rows[1].errorCode)
        assertEquals(GalleryVersionRowStatus.TOKEN_INVALID, rows[2].rowStatus)
        assertEquals(GalleryVersionRowStatus.METADATA_MISSING, rows[3].rowStatus)
        assertFalse(rows.toString().contains("secret"))
    }

    @Test
    fun apiRowErrorsAndAbsentRowsHaveDifferentDiagnostics() = runBlocking {
        val loader = GalleryVersionLoader(
            GalleryVersionSeed(GalleryVersionNode(ref(3))),
            fetch = { refs ->
                refs.map {
                    if (it.gid == 4L) {
                        GalleryApiParser.parseVersionMetadata("""{"gmetadata":[{"gid":4,"error":"Gallery unavailable"}]}""").single()
                    } else if (it.gid == 5L) {
                        GalleryVersionMetadata(5)
                    } else {
                        meta(it.gid)
                    }
                }
            },
            fetchCandidates = { gnd(2L..6L) },
        )
        loader.loadKeys()
        loader.expand()
        assertTrue(has(loader, GalleryVersionStage.CANDIDATE_METADATA, GalleryVersionIssueCode.API_ROW_ERROR))
        assertTrue(has(loader, GalleryVersionStage.PARENT_TRACE, GalleryVersionIssueCode.METADATA_MISSING))
        assertTrue(loader.state.value.diagnostics.issues.none { it.code == GalleryVersionIssueCode.NETWORK_ERROR })
    }

    @Test
    fun missingTokensAreReportedWithoutIssuingRequests() = runBlocking {
        var requests = 0
        val loader = GalleryVersionLoader(GalleryVersionSeed(GalleryVersionNode(GalleryVersionRef(3, null))), fetch = {
            requests++
            emptyList()
        })
        loader.loadKeys()
        val token = loader.state.value.diagnostics.issues.first { it.code == GalleryVersionIssueCode.TOKEN_INVALID }
        assertEquals(GalleryVersionStage.KEY_METADATA, token.stage)
        assertEquals(listOf(3L), token.affectedGids)
        assertFalse(token.retryable)
        assertEquals(0, requests)
        assertEquals(0, loader.state.value.diagnostics.requestsUsed)
    }

    @Test
    fun unknownOrUnverifiedParentsGetRelationshipDiagnostics() = runBlocking {
        val loader = GalleryVersionLoader(
            GalleryVersionSeed(GalleryVersionNode(ref(3), parent = link(2))),
            fetch = { refs -> refs.map { if (it.gid == 3L) meta(3).let { row -> row.copy(node = row.node!!.copy(parent = GalleryVersionLink.Unknown, parentVerified = false)) } else meta(it.gid) } },
            fetchCandidates = { gnd(2L..6L) },
        )
        loader.loadKeys()
        loader.expand()
        assertTrue(has(loader, GalleryVersionStage.PARENT_TRACE, GalleryVersionIssueCode.PARENT_UNKNOWN))
        assertTrue(has(loader, GalleryVersionStage.CHAIN_VALIDATION, GalleryVersionIssueCode.PARENT_UNKNOWN))
        assertFalse(loader.state.value.isDirectParent(2, 3))
    }

    @Test
    fun exhaustedSharedBudgetCountsUnrequestedNodesWithoutInventingApiFailures() = runBlocking {
        var html = 0
        var api = 0
        val loader = GalleryVersionLoader(
            GalleryVersionSeed(GalleryVersionNode(ref(4))),
            fetch = { refs ->
                api++
                refs.map { meta(it.gid, latest = 60) }
            },
            requestBudget = 2,
            fetchCandidates = {
                html++
                gnd(2L..60L)
            },
        )
        loader.loadKeys()
        val before = api
        loader.expand()
        val diagnostics = loader.state.value.diagnostics
        assertEquals(1, html)
        assertEquals(1, api - before)
        assertEquals(2, diagnostics.requestBudget)
        assertEquals(2, diagnostics.requestsUsed)
        assertEquals(31, diagnostics.pendingMetadataCount)
        val stopped = diagnostics.issues.first { it.stage == GalleryVersionStage.CANDIDATE_METADATA && it.code == GalleryVersionIssueCode.REQUEST_BUDGET_EXHAUSTED }
        assertEquals(31, stopped.affectedGids.size)
        assertTrue(stopped.retryable)
        assertTrue(diagnostics.issues.none { it.code == GalleryVersionIssueCode.API_ROW_ERROR || it.code == GalleryVersionIssueCode.METADATA_MISSING || it.code == GalleryVersionIssueCode.NETWORK_ERROR })
    }

    @Test
    fun unlinkedCandidatesAreDiagnosedWithoutQueryingUnrelatedParents() = runBlocking {
        val queried = mutableListOf<Long>()
        val loader = GalleryVersionLoader(
            GalleryVersionSeed(GalleryVersionNode(ref(3))),
            fetch = { refs ->
                queried.addAll(refs.map { it.gid })
                refs.map {
                    meta(
                        it.gid,
                        if (it.gid == 50L) {
                            49
                        } else if (it.gid == 1L) {
                            null
                        } else {
                            it.gid - 1
                        },
                    )
                }
            },
            fetchCandidates = { gnd((2L..6L).toList() + 50) },
        )
        loader.loadKeys()
        loader.expand()
        val issue = loader.state.value.diagnostics.issues.single { it.stage == GalleryVersionStage.CHAIN_VALIDATION && it.code == GalleryVersionIssueCode.CANDIDATE_UNLINKED }
        assertEquals(listOf(50L), issue.affectedGids)
        assertFalse(issue.retryable)
        assertFalse(49L in queried)
        assertFalse(50L in loader.state.value.orderedGids)
        assertTrue(loader.state.value.diagnostics.issues.none { it.code == GalleryVersionIssueCode.API_ROW_ERROR || it.code == GalleryVersionIssueCode.METADATA_MISSING })
    }

    @Test
    fun unresolvedRolesAndRoleConflictsAreNotMislabelledAsNetworkOrCycleErrors() = runBlocking {
        for (conflict in listOf(false, true)) {
            var repaired = false
            val loader = GalleryVersionLoader(
                GalleryVersionSeed(GalleryVersionNode(ref(3))),
                fetch = { refs ->
                    refs.map {
                        if (conflict) {
                            meta(
                                it.gid,
                                if (it.gid == 1L) {
                                    if (repaired) null else 50
                                } else {
                                    it.gid - 1
                                },
                                latest = 3,
                            )
                        } else {
                            meta(it.gid).copy(first = GalleryVersionLink.Unknown, latest = GalleryVersionLink.Unknown, node = meta(it.gid).node!!.copy(parent = GalleryVersionLink.None))
                        }
                    }
                },
                fetchCandidates = { gnd(2L..3L) },
            )
            loader.loadKeys()
            loader.expand()
            assertTrue(has(loader, GalleryVersionStage.CHAIN_VALIDATION, GalleryVersionIssueCode.ROLE_UNRESOLVED))
            assertTrue(loader.state.value.diagnostics.issues.none { it.code == GalleryVersionIssueCode.NETWORK_ERROR || it.code == GalleryVersionIssueCode.CYCLE_DETECTED })
            if (conflict) {
                repaired = true
                loader.retry(expanded = false)
                assertTrue(loader.state.value.traceComplete)
                assertTrue(loader.state.value.diagnostics.issues.isEmpty())
            }
        }
    }

    @Test
    fun repeatedRetriesReplaceFailuresInsteadOfAccumulatingDuplicateIssues() = runBlocking {
        val loader = GalleryVersionLoader(GalleryVersionSeed(GalleryVersionNode(ref(3))), fetch = { throw IOException("offline") })
        loader.loadKeys()
        val count = loader.state.value.diagnostics.issues.size
        repeat(4) {
            loader.retry(expanded = false)
            val issues = loader.state.value.diagnostics.issues
            assertEquals(count, issues.size)
            assertEquals(issues.size, issues.map { it.stage to it.code }.distinct().size)
            assertTrue(issues.all { it.affectedGids == it.affectedGids.distinct() })
        }
    }

    @Test
    fun cancellationIsNotRecordedAsAnOrdinaryLoadingFailure() = runBlocking {
        for (wrapped in listOf(false, true)) {
            val loader = GalleryVersionLoader(GalleryVersionSeed(GalleryVersionNode(ref(3))), fetch = {
                val cancellation = CancellationException("cancelled")
                if (wrapped) throw IllegalStateException("wrapped", cancellation) else throw cancellation
            })
            var cancelled = false
            try {
                loader.loadKeys()
            } catch (_: CancellationException) {
                cancelled = true
            }
            assertTrue(cancelled)
            assertFalse(loader.state.value.isLoading)
            assertNull(loader.state.value.diagnostics.currentStage)
            assertTrue(loader.state.value.diagnostics.issues.isEmpty())
        }
    }

    @Test
    fun stagesArePublishedDuringExistingRequestsAndClearedAfterCancellation() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        lateinit var loader: GalleryVersionLoader
        loader = GalleryVersionLoader(
            GalleryVersionSeed(GalleryVersionNode(ref(3))),
            fetch = { refs ->
                assertEquals(GalleryVersionStage.KEY_METADATA, loader.state.value.diagnostics.currentStage)
                refs.map { meta(it.gid) }
            },
            fetchCandidates = {
                assertEquals(GalleryVersionStage.FIRST_HTML, loader.state.value.diagnostics.currentStage)
                started.complete(Unit)
                release.await()
                gnd(2L..6L)
            },
        )
        loader.loadKeys()
        val job = launch { loader.expand() }
        withTimeout(5_000) { started.await() }
        job.cancelAndJoin()
        assertFalse(loader.state.value.isLoading)
        assertNull(loader.state.value.diagnostics.currentStage)
        assertTrue(loader.state.value.diagnostics.issues.isEmpty())
    }

    @Test
    fun longSuccessfulChainsKeepBatchCountsOrderingAndCachesWithoutDiagnostics() = runBlocking {
        val batches = mutableListOf<List<Long>>()
        var html = 0
        val loader = GalleryVersionLoader(
            GalleryVersionSeed(GalleryVersionNode(ref(400)), (401L..1200L).map { GalleryVersionNode(ref(it)) }),
            fetch = { refs ->
                batches.add(refs.map { it.gid })
                refs.map { meta(it.gid, latest = 1200) }
            },
            fetchCandidates = {
                html++
                gnd((2L..1200L).reversed())
            },
        )
        loader.loadKeys()
        val initial = batches.size
        loader.expand()
        assertEquals(48, batches.size - initial)
        assertTrue(batches.all { it.size <= 25 })
        assertEquals(1, html)
        assertEquals(49, loader.state.value.diagnostics.requestsUsed)
        assertEquals((1L..1200L).toList(), loader.state.value.orderedGids)
        assertTrue(loader.state.value.traceComplete)
        assertTrue(loader.state.value.diagnostics.issues.isEmpty())
        loader.retry(expanded = false)
        assertEquals(initial + 48, batches.size)
        assertEquals(1, html)
        assertEquals(0, loader.state.value.diagnostics.requestsUsed)
        assertTrue(loader.state.value.diagnostics.issues.isEmpty())
    }
}
