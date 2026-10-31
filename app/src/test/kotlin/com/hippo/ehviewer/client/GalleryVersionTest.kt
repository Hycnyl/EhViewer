package com.hippo.ehviewer.client

import com.hippo.ehviewer.client.data.GalleryVersionCandidates
import com.hippo.ehviewer.client.data.GalleryVersionLink
import com.hippo.ehviewer.client.data.GalleryVersionLoader
import com.hippo.ehviewer.client.data.GalleryVersionMetadata
import com.hippo.ehviewer.client.data.GalleryVersionNode
import com.hippo.ehviewer.client.data.GalleryVersionRef
import com.hippo.ehviewer.client.data.GalleryVersionRole
import com.hippo.ehviewer.client.data.GalleryVersionSeed
import com.hippo.ehviewer.client.parser.GalleryApiParser
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

class GalleryVersionTest {
    private fun ref(id: Long) = GalleryVersionRef(id, id.toString(16).padStart(10, '0'))
    private fun link(id: Long?) = id?.let { GalleryVersionLink.to(ref(it)) } ?: GalleryVersionLink.None
    private fun metadata(id: Long, parent: Long?, first: Long = 1, latest: Long = 5) = GalleryVersionMetadata(
        id,
        GalleryVersionNode(ref(id), title = "API $id", posted = "2024-01-01", pages = 10, fileSizeBytes = 5_000_000_000, parent = link(parent), expunged = false, metadataLoaded = true),
        first = link(first),
        latest = link(latest),
    )

    @Test
    fun parserKeepsLongSizesAndSeparatesLatestFromViewed() {
        val result = GalleryApiParser.parseVersionMetadata("""{"gmetadata":[{"gid":3,"token":"0000000003","title":"A&amp;B","posted":"1700000000","filecount":"42","filesize":5000000000,"category":"Manga","expunged":false,"parent_gid":"2","parent_key":"0000000002","first_gid":"1","first_key":"0000000001","current_gid":"5","current_key":"0000000005"}]}""").single()
        val node = checkNotNull(result.node)
        assertEquals(3L, node.ref.gid)
        assertEquals(5L, result.latest.ref!!.gid)
        assertEquals(1L, result.first.ref!!.gid)
        assertEquals(2L, node.parent.ref!!.gid)
        assertEquals(5_000_000_000L, node.fileSizeBytes)
        assertEquals("A&B", node.title)
        assertEquals(false, node.expunged)
    }

    @Test
    fun omittedRelationsOnACompleteRecordIdentifyASingleVersion() {
        val result = GalleryApiParser.parseVersionMetadata("""{"gmetadata":[{"gid":1,"token":"0000000001","title":"Only","category":"Manga","posted":"1700000000","filecount":"1"}]}""").single()
        val node = checkNotNull(result.node)
        assertEquals(GalleryVersionLink.None, node.parent)
        assertEquals(1L, result.first.ref!!.gid)
        assertEquals(1L, result.latest.ref!!.gid)
        assertNull(node.expunged)
        assertNull(node.fileSizeBytes)
    }

    @Test
    fun errorAndPartialRowsDoNotInventAbsentRelationsOrFalseBooleans() {
        val rows = GalleryApiParser.parseVersionMetadata("""{"gmetadata":[{"gid":1,"error":"Key missing, or incorrect key provided."},{"gid":2,"token":"0000000002","title":"Partial"},{"gid":3,"token":"0000000003","title":"Malformed","category":"Manga","posted":1,"filecount":2,"parent_gid":"unknown"}]}""")
        assertNull(rows[0].node)
        assertFalse(rows[0].first.known)
        val partial = checkNotNull(rows[1].node)
        assertFalse(partial.parent.known)
        assertFalse(rows[1].latest.known)
        assertNull(partial.expunged)
        assertFalse(rows[2].node!!.parent.known)
    }

    @Test
    fun knownParentWithoutTokenKeepsTheGidWithoutMakingARequestableUrl() {
        val row = GalleryApiParser.parseVersionMetadata("""{"gmetadata":[{"gid":2,"token":"0000000002","title":"Child","category":"Manga","posted":1,"filecount":2,"parent_gid":"1"}]}""").single()
        val parent = checkNotNull(row.node).parent
        val parentRef = checkNotNull(parent.ref)
        assertTrue(parent.known)
        assertEquals(1L, parentRef.gid)
        assertFalse(parentRef.canRequest)
    }

    @Test
    fun defaultLoadFetchesOnlyDeduplicatedKeysAndExpansionFillsBothDirections() = runBlocking {
        val data = listOf(metadata(1, null, latest = 6), metadata(2, 1, latest = 6), metadata(3, 2, latest = 6), metadata(4, 3, latest = 6), metadata(5, 4, latest = 6), metadata(6, 5, latest = 6)).associateBy { it.gid }
        val calls = mutableListOf<List<Long>>()
        val loader = GalleryVersionLoader(GalleryVersionSeed(GalleryVersionNode(ref(4), title = "HTML title", pages = 99, parent = link(3)), listOf(GalleryVersionNode(ref(5)), GalleryVersionNode(ref(6)))), fetch = { refs ->
            calls.add(refs.map { it.gid })
            refs.mapNotNull { data[it.gid] }
        })
        loader.loadKeys()
        assertEquals(listOf(listOf(4L), listOf(1L, 3L, 6L)), calls)
        assertEquals(listOf(1L, 3L, 4L, 6L), loader.state.value.visibleNodes(false).map { it.ref.gid })
        assertEquals(true, loader.state.value.hasMiddleVersions)
        assertFalse(loader.state.value.isDirectParent(1, 3))
        assertTrue(loader.state.value.isDirectParent(3, 4))
        assertEquals("HTML title", loader.state.value.nodes.getValue(4).title)
        assertEquals(99, loader.state.value.nodes.getValue(4).pages)
        loader.expand()
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 6L), loader.state.value.visibleNodes(true).map { it.ref.gid })
        assertTrue(loader.state.value.isComplete)
        assertTrue(loader.state.value.isDirectParent(1, 2))
        val count = calls.size
        loader.expand()
        loader.loadKeys()
        assertEquals(count, calls.size)
    }

    @Test
    fun differentFirstAndParentAndDifferentLatestAndViewedDoNotProveMiddleVersions() = runBlocking {
        val data = listOf(metadata(1, null, latest = 4), metadata(2, 1, latest = 4), metadata(3, 2, latest = 4), metadata(4, 3, latest = 4)).associateBy { it.gid }
        val loader = GalleryVersionLoader(GalleryVersionSeed(GalleryVersionNode(ref(3), parent = link(2))), fetch = { refs -> refs.mapNotNull { data[it.gid] } })
        loader.loadKeys()
        assertEquals(false, loader.state.value.hasMiddleVersions)
        assertTrue(loader.state.value.isComplete)
        assertTrue(loader.state.value.isDirectParent(1, 2))
        assertTrue(loader.state.value.isDirectParent(3, 4))
    }

    @Test
    fun rolesMergeAndKeyQueriesNeverRepeatTheViewedOrParentFirstGid() = runBlocking {
        val calls = mutableListOf<List<Long>>()
        val loader = GalleryVersionLoader(GalleryVersionSeed(GalleryVersionNode(ref(2), parent = link(1))), fetch = { refs ->
            calls.add(refs.map { it.gid })
            refs.map { metadata(it.gid, if (it.gid == 1L) null else 1, latest = 2) }
        })
        loader.loadKeys()
        assertEquals(listOf(listOf(2L), listOf(1L)), calls)
        assertEquals(setOf(GalleryVersionRole.FIRST, GalleryVersionRole.PARENT), loader.state.value.roles(1).toSet())
        assertEquals(setOf(GalleryVersionRole.VIEWED, GalleryVersionRole.LATEST), loader.state.value.roles(2).toSet())
        assertEquals(2, loader.state.value.visibleNodes(false).size)
    }

    @Test
    fun singleVersionStillHasTheViewedNodeAndParentNone() = runBlocking {
        val loader = GalleryVersionLoader(GalleryVersionSeed(GalleryVersionNode(ref(1))), fetch = { listOf(metadata(1, null, latest = 1)) })
        loader.loadKeys()
        assertEquals(GalleryVersionLink.None, loader.state.value.parent)
        assertEquals(false, loader.state.value.hasMiddleVersions)
        assertEquals(1, loader.state.value.visibleNodes(false).size)
        assertEquals(3, loader.state.value.roles(1).size)
    }

    @Test
    fun failuresStayUnknownAndExplicitRetryRetainsSuccessfulMetadata() = runBlocking {
        var fail = true
        val calls = mutableListOf<List<Long>>()
        val loader = GalleryVersionLoader(GalleryVersionSeed(GalleryVersionNode(ref(3), parent = link(2))), fetch = { refs ->
            calls.add(refs.map { it.gid })
            refs.mapNotNull { if (it.gid == 2L && fail) null else metadata(it.gid, if (it.gid == 1L) null else it.gid - 1, latest = 3) }
        })
        loader.loadKeys()
        assertNull(loader.state.value.hasMiddleVersions)
        assertFalse(loader.state.value.isComplete)
        assertNull(loader.state.value.nodes.getValue(2).expunged)
        fail = false
        loader.loadKeys(retry = true)
        assertEquals(listOf(2L), calls.last())
        assertEquals(false, loader.state.value.hasMiddleVersions)
        assertTrue(loader.state.value.isComplete)
    }

    @Test
    fun anInvalidTokenDoesNotCauseRepeatedAutomaticRequests() = runBlocking {
        var count = 0
        val loader = GalleryVersionLoader(GalleryVersionSeed(GalleryVersionNode(ref(1))), fetch = {
            count++
            listOf(GalleryVersionMetadata(1))
        })
        loader.loadKeys()
        loader.expand()
        loader.loadKeys()
        assertEquals(2, count) // One cached failure may be retried once by the fallback walk.
        loader.expand()
        assertEquals(2, count)
        loader.loadKeys(retry = true)
        assertEquals(3, count)
        assertNull(loader.state.value.hasMiddleVersions)
        assertNull(loader.state.value.nodes.getValue(1).expunged)
    }

    @Test
    fun parentCyclesCannotBecomeSolidLinesOrUnboundedQueries() = runBlocking {
        var count = 0
        val data = listOf(metadata(1, 2, latest = 3), metadata(2, 1, latest = 3), metadata(3, 2, latest = 3)).associateBy { it.gid }
        val loader = GalleryVersionLoader(GalleryVersionSeed(GalleryVersionNode(ref(3), parent = link(2))), fetch = { refs ->
            count++
            refs.mapNotNull { data[it.gid] }
        })
        loader.loadKeys()
        loader.expand()
        assertTrue(loader.state.value.hasCycle)
        assertFalse(loader.state.value.isComplete)
        assertFalse(loader.state.value.isDirectParent(1, 2))
        assertTrue(count <= 2)
    }

    @Test
    fun requestBudgetBoundsTracingAndAnExplicitRetryContinuesUsingTheCache() = runBlocking {
        var calls = 0
        val loader = GalleryVersionLoader(GalleryVersionSeed(GalleryVersionNode(ref(10), parent = link(9))), fetch = { refs ->
            calls++
            refs.map { metadata(it.gid, if (it.gid == 1L) null else it.gid - 1, latest = 10) }
        }, requestBudget = 3)
        loader.loadKeys()
        loader.expand()
        assertFalse(loader.state.value.traceComplete)
        val before = calls
        loader.expand(retry = true)
        assertTrue(calls - before <= 3)
        loader.expand(retry = true)
        assertTrue(loader.state.value.traceComplete)
    }

    @Test
    fun htmlCandidatesAreFetchedInBatchesOfAtMostTwentyFive() = runBlocking {
        val batches = mutableListOf<Int>()
        val newer = (2L..60L).map { GalleryVersionNode(ref(it)) }
        val loader = GalleryVersionLoader(GalleryVersionSeed(GalleryVersionNode(ref(1)), newer), fetch = { refs ->
            batches.add(refs.size)
            refs.map { metadata(it.gid, if (it.gid == 1L) null else it.gid - 1, latest = 60) }
        })
        loader.loadKeys()
        assertEquals(2, batches.size)
        loader.expand()
        assertTrue(batches.all { it <= 25 })
        assertTrue(loader.state.value.traceComplete)
    }

    @Test
    fun cancellationStopsTheActiveFetchAndResetsLoadingState() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val loader = GalleryVersionLoader(GalleryVersionSeed(GalleryVersionNode(ref(1))), fetch = {
            calls++
            started.complete(Unit)
            release.await()
            emptyList()
        })
        val job = launch { loader.loadKeys() }
        started.await()
        job.cancelAndJoin()
        assertEquals(1, calls)
        assertFalse(loader.state.value.isLoading)
    }

    @Test
    fun parentRelationsControlOrderEvenWhenIdsAndHtmlCandidatesAreReversed() = runBlocking {
        val data = listOf(metadata(100, null, 100, 5), metadata(90, 100, 100, 5), metadata(70, 90, 100, 5), metadata(20, 70, 100, 5), metadata(10, 20, 100, 5), metadata(5, 10, 100, 5)).associateBy { it.gid }
        val loader = GalleryVersionLoader(GalleryVersionSeed(GalleryVersionNode(ref(20), parent = link(70)), listOf(GalleryVersionNode(ref(5)), GalleryVersionNode(ref(10)))), fetch = { refs -> refs.mapNotNull { data[it.gid] } })
        loader.loadKeys()
        loader.expand()
        assertEquals(listOf(100L, 90L, 70L, 20L, 10L, 5L), loader.state.value.visibleNodes(true).map { it.ref.gid })
        assertTrue(loader.state.value.isComplete)
    }

    @Test
    fun htmlTokensAreReusedWhenTheApiGivesOnlyTheParentId() = runBlocking {
        val calls = mutableListOf<List<GalleryVersionRef>>()
        val child = metadata(2, 1, latest = 2).let { it.copy(node = it.node!!.copy(parent = GalleryVersionLink.to(GalleryVersionRef(1, null))), first = GalleryVersionLink.to(GalleryVersionRef(1, null))) }
        val loader = GalleryVersionLoader(GalleryVersionSeed(GalleryVersionNode(ref(2), parent = link(1))), fetch = { refs ->
            calls.add(refs)
            refs.map { if (it.gid == 2L) child else metadata(1, null, latest = 2) }
        })
        loader.loadKeys()
        assertEquals(listOf(ref(1)), calls.last())
        assertTrue(loader.state.value.nodes.getValue(1).ref.canRequest)
    }

    @Test
    fun incompleteRelationshipsCanBeRetriedWithoutStickingToOldApiOnlyValues() = runBlocking {
        var calls = 0
        val partial = GalleryVersionMetadata(1, node = GalleryVersionNode(ref(1), title = "Partial", metadataLoaded = true))
        val loader = GalleryVersionLoader(GalleryVersionSeed(GalleryVersionNode(ref(1))), fetch = {
            calls++
            listOf(if (calls == 1) partial else metadata(1, null, latest = 1))
        })
        loader.loadKeys()
        assertNull(loader.state.value.hasMiddleVersions)
        loader.loadKeys(retry = true)
        assertEquals(2, calls)
        assertEquals("API 1", loader.state.value.nodes.getValue(1).title)
        assertTrue(loader.state.value.isComplete)
    }

    @Test
    fun anotherKeyNodeCanSupplyAMissingFirstTokenWithoutGuessing() = runBlocking {
        val calls = mutableListOf<List<Long>>()
        val viewed = metadata(3, 2, latest = 3).copy(first = GalleryVersionLink.to(GalleryVersionRef(1, null)))
        val loader = GalleryVersionLoader(GalleryVersionSeed(GalleryVersionNode(ref(3))), fetch = { refs ->
            calls.add(refs.map { it.gid })
            refs.map { if (it.gid == 3L) viewed else metadata(it.gid, if (it.gid == 1L) null else 1, latest = 3) }
        })
        loader.loadKeys()
        assertEquals(listOf(listOf(3L), listOf(2L), listOf(1L)), calls)
        assertTrue(loader.state.value.first.ref!!.canRequest)
        assertTrue(loader.state.value.isComplete)
    }

    @Test
    fun anExplicitRetryCanRecoverAMissingRelationshipToken() = runBlocking {
        var rootRequests = 0
        val missing = metadata(3, 2, latest = 3).let { it.copy(node = it.node!!.copy(parent = GalleryVersionLink.to(GalleryVersionRef(2, null)))) }
        val loader = GalleryVersionLoader(GalleryVersionSeed(GalleryVersionNode(ref(3))), fetch = { refs ->
            refs.map {
                if (it.gid == 3L && ++rootRequests == 1) missing else metadata(it.gid, if (it.gid == 1L) null else it.gid - 1, latest = 3)
            }
        })
        loader.loadKeys()
        assertFalse(loader.state.value.isComplete)
        loader.loadKeys(retry = true)
        assertEquals(2, rootRequests)
        assertTrue(loader.state.value.parent.ref!!.canRequest)
        assertTrue(loader.state.value.isComplete)
    }

    @Test
    fun ordinaryListDecodingStillIgnoresSupplementaryVersionFields() {
        val item = """{"gmetadata":[{"gid":1,"title":"Original","title_jpn":"","category":"Manga","thumb":"https://example.org/a.jpg","uploader":null,"posted":"1","filecount":"2","rating":"4.5","tags":[],"parent_gid":{"unexpected":"shape"}}]}""".parseAs<GalleryApiParser.Result>().items.single()
        assertEquals("Original", item.title)
        assertEquals(2, item.pages)
    }

    private fun gnd(ids: Iterable<Long>) = GalleryVersionCandidates(true, ids.map { GalleryVersionNode(ref(it), title = "GND $it", posted = "2024-01-01") })

    @Test
    fun firstGndAndOneBatchReplaceSerialParentRequestsRegardlessOfCandidateOrder() = runBlocking {
        val ids = listOf(100L, 90L, 70L, 20L, 10L, 5L)
        val data = ids.mapIndexed { index, gid -> metadata(gid, ids.getOrNull(index - 1), 100, 5) }.associateBy { it.gid }
        val calls = mutableListOf<List<Long>>()
        var htmlCalls = 0
        val loader = GalleryVersionLoader(
            GalleryVersionSeed(GalleryVersionNode(ref(20), title = "Viewed HTML", pages = 99, parent = link(70)), listOf(GalleryVersionNode(ref(5)))),
            fetch = { refs ->
                calls.add(refs.map { it.gid })
                refs.map { data.getValue(it.gid) }.reversed()
            },
            fetchCandidates = {
                htmlCalls++
                assertEquals(ref(100), it)
                // Fake HTML edges must not participate in ordering or validation.
                GalleryVersionCandidates(true, listOf(5L, 90L, 10L, 70L, 20L).map { id -> GalleryVersionNode(ref(id), parent = link(5), metadataLoaded = true) })
            },
        )
        loader.loadKeys()
        val before = calls.size
        loader.expand()
        assertEquals(1, htmlCalls)
        assertEquals(listOf(listOf(90L, 10L)), calls.drop(before))
        assertEquals(ids, loader.state.value.visibleNodes(true).map { it.ref.gid })
        assertEquals("Viewed HTML", loader.state.value.nodes.getValue(20).title)
        assertEquals(99, loader.state.value.nodes.getValue(20).pages)
        assertTrue(loader.state.value.traceComplete)
        val count = calls.size
        loader.expand()
        assertEquals(count, calls.size)
        assertEquals(1, htmlCalls)
    }

    @Test
    fun viewedFirstReusesItsHtmlAndAllMetadataBatchesStayWithinTwentyFive() = runBlocking {
        val calls = mutableListOf<List<Long>>()
        var htmlCalls = 0
        val loader = GalleryVersionLoader(
            GalleryVersionSeed(GalleryVersionNode(ref(1)), (2L..60L).reversed().map { GalleryVersionNode(ref(it)) }),
            fetch = { refs ->
                calls.add(refs.map { it.gid })
                refs.map { metadata(it.gid, if (it.gid == 1L) null else it.gid - 1, latest = 60) }
            },
            fetchCandidates = {
                htmlCalls++
                error("First HTML is already in the seed")
            },
        )
        loader.loadKeys()
        loader.expand()
        assertEquals(0, htmlCalls)
        assertTrue(calls.all { it.size <= 25 })
        assertEquals((1L..60L).toList(), loader.state.value.visibleNodes(true).map { it.ref.gid })
        assertTrue(loader.state.value.traceComplete)
    }

    @Test
    fun firstGndAndViewedCandidatesAreMergedAndDeduplicatedByGid() = runBlocking {
        val calls = mutableListOf<List<Long>>()
        val loader = GalleryVersionLoader(
            GalleryVersionSeed(GalleryVersionNode(ref(3)), listOf(GalleryVersionNode(ref(4)), GalleryVersionNode(ref(5)), GalleryVersionNode(ref(6)))),
            fetch = { refs ->
                calls.add(refs.map { it.gid })
                refs.map { metadata(it.gid, if (it.gid == 1L) null else it.gid - 1, latest = 6) }
            },
            fetchCandidates = { gnd(listOf(2, 4, 5, 4)) },
        )
        loader.loadKeys()
        loader.expand()
        assertEquals(listOf(4L, 5L), calls.last())
        assertTrue(calls.flatten().groupingBy { it }.eachCount().values.all { it == 1 })
        assertEquals((1L..6L).toList(), loader.state.value.visibleNodes(true).map { it.ref.gid })
        assertTrue(loader.state.value.traceComplete)
    }

    @Test
    fun aValidEmptyGndPageIsCachedEvenAcrossExplicitRetry() = runBlocking {
        var htmlCalls = 0
        var apiCalls = 0
        val loader = GalleryVersionLoader(
            GalleryVersionSeed(GalleryVersionNode(ref(2))),
            fetch = { refs ->
                apiCalls++
                refs.map { metadata(it.gid, if (it.gid == 1L) null else it.gid - 1, latest = 3) }
            },
            fetchCandidates = {
                htmlCalls++
                GalleryVersionCandidates(true)
            },
        )
        loader.loadKeys()
        val before = apiCalls
        loader.expand()
        loader.expand(retry = true)
        assertEquals(1, htmlCalls)
        assertEquals(before, apiCalls)
        assertTrue(loader.state.value.traceComplete)
    }

    @Test
    fun aMissingGndPageFallsBackToBothParentWalksAndReusesSuccessfulMetadata() = runBlocking {
        val calls = mutableListOf<Long>()
        var htmlCalls = 0
        val loader = GalleryVersionLoader(
            GalleryVersionSeed(GalleryVersionNode(ref(5)), listOf(GalleryVersionNode(ref(7)), GalleryVersionNode(ref(9)))),
            fetch = { refs ->
                calls.addAll(refs.map { it.gid })
                refs.map { metadata(it.gid, if (it.gid == 1L) null else it.gid - 1, latest = 9) }
            },
            fetchCandidates = {
                htmlCalls++
                if (htmlCalls == 1) error("First HTML unavailable")
                GalleryVersionCandidates(false)
            },
        )
        loader.loadKeys()
        loader.expand()
        assertEquals((1L..9L).toSet(), calls.toSet())
        assertTrue(calls.groupingBy { it }.eachCount().values.all { it == 1 })
        assertEquals((1L..9L).toList(), loader.state.value.visibleNodes(true).map { it.ref.gid })
        assertTrue(loader.state.value.traceComplete)
        loader.expand()
        assertEquals(1, htmlCalls)
        loader.expand(retry = true)
        assertEquals(2, htmlCalls)
        assertTrue(calls.groupingBy { it }.eachCount().values.all { it == 1 })
    }

    @Test
    fun fallbackRetriesOnlyTheFailedBatchNodeOnceAndKeepsSuccessfulNodes() = runBlocking {
        val counts = mutableMapOf<Long, Int>()
        val loader = GalleryVersionLoader(
            GalleryVersionSeed(GalleryVersionNode(ref(3))),
            fetch = { refs ->
                refs.mapNotNull {
                    val count = counts.getOrDefault(it.gid, 0) + 1
                    counts[it.gid] = count
                    if (it.gid == 5L && count == 1) null else metadata(it.gid, if (it.gid == 1L) null else it.gid - 1, latest = 6)
                }
            },
            fetchCandidates = { gnd(2L..6L) },
        )
        loader.loadKeys()
        loader.expand()
        assertEquals(2, counts[5])
        assertTrue(counts.filterKeys { it != 5L }.values.all { it == 1 })
        assertEquals((1L..6L).toList(), loader.state.value.visibleNodes(true).map { it.ref.gid })
        assertTrue(loader.state.value.traceComplete)
    }

    @Test
    fun failedUnlocatedNodesStayHiddenUntilExplicitRetryAndDoNotLoop() = runBlocking {
        var fail = true
        val counts = mutableMapOf<Long, Int>()
        val loader = GalleryVersionLoader(
            GalleryVersionSeed(GalleryVersionNode(ref(3))),
            fetch = { refs ->
                refs.mapNotNull {
                    counts[it.gid] = counts.getOrDefault(it.gid, 0) + 1
                    if (it.gid == 5L && fail) null else metadata(it.gid, if (it.gid == 1L) null else it.gid - 1, latest = 6)
                }
            },
            fetchCandidates = { gnd(2L..6L) },
        )
        loader.loadKeys()
        loader.expand()
        val partial = loader.state.value.visibleNodes(true).map { it.ref.gid }
        assertTrue(4L in partial)
        assertFalse(5L in partial)
        assertTrue(6L in partial)
        assertFalse(loader.state.value.isDirectParent(4, 6))
        assertFalse(loader.state.value.traceComplete)
        loader.expand()
        assertEquals(2, counts[5])
        fail = false
        loader.expand(retry = true)
        assertEquals(3, counts[5])
        assertTrue(partial.all { it in loader.state.value.orderedGids })
        assertTrue(loader.state.value.traceComplete)
    }

    @Test
    fun gndOrphansAreHiddenAndDoNotTriggerUnrelatedParentQueries() = runBlocking {
        val calls = mutableListOf<Long>()
        val loader = GalleryVersionLoader(
            GalleryVersionSeed(GalleryVersionNode(ref(3))),
            fetch = { refs ->
                calls.addAll(refs.map { it.gid })
                refs.map {
                    metadata(
                        it.gid,
                        when (it.gid) {
                            1L -> null
                            50L -> 49
                            else -> it.gid - 1
                        },
                        latest = 6,
                    )
                }
            },
            fetchCandidates = { gnd((2L..6L).toList() + 50) },
        )
        loader.loadKeys()
        loader.expand()
        assertFalse(49L in calls)
        assertTrue(50L in loader.state.value.nodes)
        assertFalse(50L in loader.state.value.orderedGids)
        assertFalse(49L in loader.state.value.orderedGids)
        assertEquals((1L..6L).toList(), loader.state.value.visibleNodes(true).map { it.ref.gid })
        assertFalse(loader.state.value.traceComplete)
    }

    @Test
    fun branchesAndCyclesCannotBeReportedAsACompleteSingleChain() = runBlocking {
        for (cycle in listOf(false, true)) {
            val loader = GalleryVersionLoader(
                GalleryVersionSeed(GalleryVersionNode(ref(3))),
                fetch = { refs ->
                    refs.map {
                        metadata(
                            it.gid,
                            when (it.gid) {
                                1L -> null
                                50L -> if (cycle) 51 else 3
                                51L -> 50
                                else -> it.gid - 1
                            },
                            latest = 6,
                        )
                    }
                },
                fetchCandidates = { gnd((2L..6L).toList() + if (cycle) listOf(50L, 51L) else listOf(50L)) },
            )
            loader.loadKeys()
            loader.expand()
            assertFalse(loader.state.value.traceComplete)
            assertFalse(50L in loader.state.value.orderedGids)
            assertEquals(cycle, loader.state.value.hasCycle)
            if (cycle) assertFalse(loader.state.value.isDirectParent(3, 4))
        }
    }

    @Test
    fun candidateBatchSnapshotsAreVisibleBeforeLaterBatchesComplete() = runBlocking {
        val secondStarted = CompletableDeferred<Unit>()
        val allowSecond = CompletableDeferred<Unit>()
        val thirdStarted = CompletableDeferred<Unit>()
        val allowThird = CompletableDeferred<Unit>()
        lateinit var loader: GalleryVersionLoader
        loader = GalleryVersionLoader(
            GalleryVersionSeed(GalleryVersionNode(ref(4))),
            fetch = { refs ->
                if (refs.size > 1 && refs.first().gid == 2L) {
                    assertTrue((2L..60L).all { it in loader.state.value.nodes })
                    assertTrue(loader.state.value.isLoading)
                }
                if (refs.first().gid == 29L) {
                    secondStarted.complete(Unit)
                    allowSecond.await()
                }
                if (refs.first().gid == 54L) {
                    thirdStarted.complete(Unit)
                    allowThird.await()
                }
                refs.map { metadata(it.gid, if (it.gid == 1L) null else it.gid - 1, latest = 60) }
            },
            fetchCandidates = { gnd(2L..60L) },
        )
        loader.loadKeys()
        val job = launch { loader.expand() }
        withTimeout(5_000) { secondStarted.await() }
        val first = loader.state.value.visibleNodes(true).map { it.ref.gid }
        assertTrue(2L in first && 28L in first)
        assertFalse(29L in first)
        assertTrue(loader.state.value.nodes.getValue(28).metadataLoaded)
        assertTrue(loader.state.value.isLoading)
        allowSecond.complete(Unit)
        withTimeout(5_000) { thirdStarted.await() }
        val second = loader.state.value.visibleNodes(true).map { it.ref.gid }
        assertTrue(first.all { it in second })
        assertTrue(53L in second)
        assertFalse(54L in second)
        assertTrue(loader.state.value.isLoading)
        allowThird.complete(Unit)
        job.join()
        assertEquals((1L..60L).toList(), loader.state.value.visibleNodes(true).map { it.ref.gid })
        assertTrue(loader.state.value.traceComplete)
        assertFalse(loader.state.value.isLoading)
    }

    @Test
    fun cancellingABatchKeepsPublishedNodesAndResumesWithoutRepeatingSuccessfulRequests() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val counts = mutableMapOf<Long, Int>()
        var htmlCalls = 0
        val loader = GalleryVersionLoader(
            GalleryVersionSeed(GalleryVersionNode(ref(4))),
            fetch = { refs ->
                refs.forEach { counts[it.gid] = counts.getOrDefault(it.gid, 0) + 1 }
                if (refs.first().gid == 29L) {
                    if (!started.isCompleted) started.complete(Unit)
                    release.await()
                }
                refs.map { metadata(it.gid, if (it.gid == 1L) null else it.gid - 1, latest = 40) }
            },
            fetchCandidates = {
                htmlCalls++
                gnd(2L..40L)
            },
        )
        loader.loadKeys()
        val job = launch { loader.expand() }
        withTimeout(5_000) { started.await() }
        val partial = loader.state.value.orderedGids
        assertTrue(28L in partial)
        job.cancelAndJoin()
        assertFalse(loader.state.value.isLoading)
        assertEquals(partial, loader.state.value.orderedGids)
        release.complete(Unit)
        loader.expand()
        assertEquals(1, htmlCalls)
        assertEquals(1, counts[28])
        assertEquals(2, counts[29])
        assertTrue(loader.state.value.traceComplete)
    }

    @Test
    fun cancellingFirstHtmlDoesNotCacheAFailureOrLeaveLoadingSet() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var htmlCalls = 0
        val loader = GalleryVersionLoader(
            GalleryVersionSeed(GalleryVersionNode(ref(3))),
            fetch = { refs -> refs.map { metadata(it.gid, if (it.gid == 1L) null else it.gid - 1, latest = 6) } },
            fetchCandidates = {
                htmlCalls++
                if (!started.isCompleted) started.complete(Unit)
                release.await()
                gnd(2L..6L)
            },
        )
        loader.loadKeys()
        val job = launch { loader.expand() }
        withTimeout(5_000) { started.await() }
        val keys = loader.state.value.visibleNodes(false).map { it.ref.gid }
        job.cancelAndJoin()
        assertFalse(loader.state.value.isLoading)
        assertEquals(keys, loader.state.value.visibleNodes(false).map { it.ref.gid })
        release.complete(Unit)
        loader.expand()
        assertEquals(2, htmlCalls)
        assertTrue(loader.state.value.traceComplete)
    }

    @Test
    fun htmlAndMetadataShareTheBudgetAndRetriesReuseTheSuccessfulHtmlPage() = runBlocking {
        var htmlCalls = 0
        var apiCalls = 0
        val loader = GalleryVersionLoader(
            GalleryVersionSeed(GalleryVersionNode(ref(10))),
            fetch = { refs ->
                apiCalls++
                refs.map { metadata(it.gid, if (it.gid == 1L) null else it.gid - 1, latest = 10) }
            },
            requestBudget = 2,
            fetchCandidates = {
                htmlCalls++
                gnd(listOf(5, 6))
            },
        )
        loader.loadKeys()
        val before = apiCalls
        loader.expand()
        assertEquals(1, htmlCalls)
        assertEquals(1, apiCalls - before)
        assertFalse(loader.state.value.traceComplete)
        repeat(5) {
            val count = apiCalls
            loader.expand(retry = true)
            assertTrue(apiCalls - count <= 2)
        }
        assertEquals(1, htmlCalls)
        assertTrue(loader.state.value.traceComplete)
    }

    @Test
    fun aMissingFirstTokenCannotBeGuessedForHtmlOrMetadataRequests() = runBlocking {
        var htmlCalls = 0
        val calls = mutableListOf<Long>()
        val loader = GalleryVersionLoader(
            GalleryVersionSeed(GalleryVersionNode(ref(3))),
            fetch = { refs ->
                calls.addAll(refs.map { it.gid })
                refs.map {
                    val item = metadata(it.gid, if (it.gid == 1L) null else it.gid - 1, latest = 3)
                    item.copy(first = GalleryVersionLink.to(GalleryVersionRef(1, null)), node = item.node!!.let { n -> if (it.gid == 2L) n.copy(parent = GalleryVersionLink.to(GalleryVersionRef(1, null))) else n })
                }
            },
            fetchCandidates = {
                htmlCalls++
                error("Missing token must not be used")
            },
        )
        loader.loadKeys()
        loader.expand()
        assertEquals(0, htmlCalls)
        assertFalse(1L in calls)
        assertFalse(loader.state.value.traceComplete)
    }

    @Test
    fun aLaterPartialResponseDoesNotEraseAnAlreadyVerifiedParent() = runBlocking {
        var partial = false
        val loader = GalleryVersionLoader(
            GalleryVersionSeed(GalleryVersionNode(ref(3), title = "HTML")),
            fetch = { refs ->
                refs.map {
                    val item = metadata(it.gid, if (it.gid == 1L) null else it.gid - 1, latest = 6)
                    if (it.gid == 3L) item.copy(first = GalleryVersionLink.Unknown, node = item.node!!.let { n -> if (partial) n.copy(parent = GalleryVersionLink.Unknown, parentVerified = false) else n }) else item
                }
            },
            fetchCandidates = { gnd(2L..6L) },
        )
        loader.loadKeys()
        loader.expand()
        assertTrue(loader.state.value.isDirectParent(2, 3))
        val before = loader.state.value.orderedGids
        partial = true
        loader.expand(retry = true)
        assertTrue(loader.state.value.isDirectParent(2, 3))
        assertEquals(before, loader.state.value.orderedGids)
        assertEquals("HTML", loader.state.value.nodes.getValue(3).title)
    }

    @Test
    fun anHtmlParentAloneDoesNotTurnIntoAnApiVerifiedSolidLine() = runBlocking {
        val loader = GalleryVersionLoader(GalleryVersionSeed(GalleryVersionNode(ref(3), parent = link(2))), fetch = { emptyList() })
        loader.loadKeys()
        assertTrue(loader.state.value.parent.known)
        assertFalse(loader.state.value.isDirectParent(2, 3))
    }

    @Test
    fun apiTokensTakePriorityOverStaleFirstAndViewedHtmlCandidates() = runBlocking {
        val queried = mutableListOf<GalleryVersionRef>()
        val stale = GalleryVersionNode(GalleryVersionRef(5, "aaaaaaaaaa"))
        val loader = GalleryVersionLoader(
            GalleryVersionSeed(GalleryVersionNode(ref(3)), listOf(stale)),
            fetch = { refs ->
                queried.addAll(refs)
                refs.mapNotNull { if (it.gid == 5L && it != ref(5)) null else metadata(it.gid, if (it.gid == 1L) null else it.gid - 1, latest = 6) }
            },
            fetchCandidates = { GalleryVersionCandidates(true, (2L..6L).map { if (it == 5L) GalleryVersionNode(GalleryVersionRef(5, "bbbbbbbbbb")) else GalleryVersionNode(ref(it)) }) },
        )
        loader.loadKeys()
        loader.expand()
        assertEquals(listOf(ref(5)), queried.filter { it.gid == 5L })
        assertEquals(ref(5), loader.state.value.nodes.getValue(5).ref)
        assertTrue(loader.state.value.traceComplete)
    }
}
