package com.hippo.ehviewer.client

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
        assertEquals(1, count)
        loader.loadKeys(retry = true)
        assertEquals(2, count)
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
}
