package com.hippo.ehviewer.client

import com.hippo.ehviewer.client.data.GalleryNewerVersionLoader
import com.hippo.ehviewer.client.data.GalleryVersionCandidates
import com.hippo.ehviewer.client.data.GalleryVersionIssueCode
import com.hippo.ehviewer.client.data.GalleryVersionLink
import com.hippo.ehviewer.client.data.GalleryVersionLoadStrategy
import com.hippo.ehviewer.client.data.GalleryVersionLoader
import com.hippo.ehviewer.client.data.GalleryVersionMetadata
import com.hippo.ehviewer.client.data.GalleryVersionNode
import com.hippo.ehviewer.client.data.GalleryVersionRef
import com.hippo.ehviewer.client.data.GalleryVersionSeed
import com.hippo.ehviewer.client.data.GalleryVersionStage
import com.hippo.ehviewer.client.json
import com.hippo.ehviewer.client.parser.GalleryApiParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryVersionTipTest {
    @Test
    fun real146NodeChainAcceptsAnEarlierTipProvedByItsParentPath() = runBlocking {
        // Real API/GND relationship snapshot; gallery titles, tags and image URLs are omitted.
        val fixture = checkNotNull(javaClass.getResourceAsStream("/gallery-version-4289727.json")).bufferedReader().use { it.readText() }
        val data = json.parseToJsonElement(fixture).jsonObject
        val rows = GalleryApiParser.parseVersionMetadata(fixture).associateBy { it.gid }
        val viewed = rows.getValue(4289727).node!!
        val candidates = data.getValue("gnd").jsonArray.map { GalleryVersionNode(rows.getValue(it.jsonPrimitive.long).node!!.ref) }
        val newer = data.getValue("currentGnd").jsonArray.map { GalleryVersionNode(rows.getValue(it.jsonPrimitive.long).node!!.ref) }
        assertEquals(4291748L, rows.getValue(4249632).latest.ref!!.gid)
        assertEquals(4291748L, rows.getValue(4294055).node!!.parent.ref!!.gid)
        for (strategy in GalleryVersionLoadStrategy.entries) {
            val requests = mutableListOf<List<Long>>()
            var html = 0
            val loader = GalleryVersionLoader(
                GalleryVersionSeed(GalleryVersionNode(viewed.ref, parent = viewed.parent), newer),
                fetch = { refs ->
                    requests.add(refs.map { it.gid })
                    refs.map { rows.getValue(it.gid) }
                },
                fetchCandidates = { ref ->
                    assertEquals(3929832L, ref.gid)
                    html++
                    GalleryVersionCandidates(true, candidates)
                },
                strategy = strategy,
            )
            val stages = mutableSetOf<GalleryVersionStage>()
            val observer = launch(Dispatchers.Unconfined) { loader.state.collect { it.diagnostics.currentStage?.let(stages::add) } }
            loader.loadKeys()
            loader.expand()
            repeat(4) {
                if (!loader.state.value.isComplete && loader.state.value.diagnostics.issues.any { it.code == GalleryVersionIssueCode.REQUEST_BUDGET_EXHAUSTED }) {
                    loader.retry(expanded = false)
                    assertTrue(loader.state.value.diagnostics.requestsUsed <= 50)
                }
            }
            observer.cancelAndJoin()
            assertEquals(strategy == GalleryVersionLoadStrategy.PARENT_WALK_FIRST, GalleryVersionStage.PARENT_TRACE in stages)
            val chain = loader.state.value
            assertTrue(chain.diagnostics.toString(), chain.isComplete)
            assertTrue(chain.traceComplete)
            assertEquals(146, chain.orderedGids.size)
            assertEquals(3929832L, chain.first.ref!!.gid)
            assertEquals(4287215L, chain.parent.ref!!.gid)
            assertEquals(4289727L, chain.viewedGid)
            assertEquals(4294055L, chain.latest.ref!!.gid)
            assertEquals(listOf(4289727L, 4291748L, 4294055L), chain.orderedGids.takeLast(3))
            chain.orderedGids.zipWithNext().forEach { (older, newerGid) -> assertTrue(chain.isDirectParent(older, newerGid)) }
            assertTrue(chain.diagnostics.issues.isEmpty())
            assertFalse(chain.hasCycle)
            assertTrue(requests.all { it.size <= 25 })
            assertEquals(146, requests.flatten().size)
            assertEquals(146, requests.flatten().distinct().size)
            assertEquals(if (strategy == GalleryVersionLoadStrategy.GND_FIRST) 1 else 0, html)
            val count = requests.size
            loader.retry(expanded = false)
            assertEquals(count, requests.size)
            assertTrue(loader.state.value.isComplete)
        }
    }
    private fun ref(gid: Long) = GalleryVersionRef(gid, gid.toString(16).padStart(10, '0'))
    private fun link(gid: Long) = GalleryVersionLink.to(ref(gid))
    private fun row(gid: Long, hint: Long = 6) = GalleryVersionMetadata(
        gid,
        GalleryVersionNode(ref(gid), parent = if (gid == 1L) GalleryVersionLink.None else link(gid - 1), metadataLoaded = true),
        first = link(1),
        latest = link(hint),
    )

    @Test
    fun cachedSelfAndDescendantTipsAreAcceptedWithoutInventingDirectParentEdges() = runBlocking {
        for (hint in listOf(4L, 5L)) {
            val rows = (1L..6L).associateWith {
                row(
                    it,
                    if (it == 4L) {
                        hint
                    } else if (it == 2L) {
                        4
                    } else {
                        6
                    },
                )
            }
            val gnd = (2L..6L).map { GalleryVersionNode(ref(it)) }
            val full = GalleryVersionLoader(
                GalleryVersionSeed(GalleryVersionNode(ref(3))),
                fetch = { refs -> refs.map { rows.getValue(it.gid) } },
                fetchCandidates = { GalleryVersionCandidates(true, gnd) },
            )
            full.loadKeys()
            full.expand()
            assertTrue(full.state.value.isComplete)
            assertEquals((1L..6L).toList(), full.state.value.orderedGids)
            assertTrue(full.state.value.diagnostics.issues.isEmpty())
            assertFalse(full.state.value.isDirectParent(2, 4))
            val requested = mutableListOf<Long>()
            val newer = GalleryNewerVersionLoader(GalleryVersionNode(ref(3)), (4L..6L).map { GalleryVersionNode(ref(it)) }, fetch = { refs ->
                requested.addAll(refs.map { it.gid })
                refs.map { rows.getValue(it.gid) }
            })
            newer.load()
            assertTrue(newer.state.value.isComplete)
            assertEquals((3L..6L).toList(), newer.state.value.orderedGids)
            assertTrue(newer.state.value.diagnostics.issues.isEmpty())
            assertEquals(setOf(3L, 4L, 5L, 6L), requested.toSet())
            assertFalse(newer.state.value.isDirectParent(4, 6))
        }
    }

    @Test
    fun ancestorAndUnlinkedTipsStillReportARelationshipConflict() = runBlocking {
        for (hint in listOf(2L, 99L)) {
            val rows = (1L..6L).associateWith { row(it, if (it == 4L) hint else 6) }
            val gnd = (4L..6L).map { GalleryVersionNode(ref(it)) }
            val requested = mutableSetOf<Long>()
            val full = GalleryVersionLoader(
                GalleryVersionSeed(GalleryVersionNode(ref(3)), gnd),
                fetch = { refs ->
                    requested.addAll(refs.map { it.gid })
                    refs.map { rows.getValue(it.gid) }
                },
                fetchCandidates = { GalleryVersionCandidates(true, (2L..6L).map { GalleryVersionNode(ref(it)) }) },
            )
            full.loadKeys()
            full.expand()
            assertFalse(full.state.value.traceComplete)
            assertTrue(full.state.value.diagnostics.issues.any { it.code == GalleryVersionIssueCode.ROLE_UNRESOLVED && 4L in it.affectedGids })
            assertFalse(99L in requested)
            requested.clear()
            val newer = GalleryNewerVersionLoader(GalleryVersionNode(ref(3)), (4L..6L).map { GalleryVersionNode(ref(it)) }, fetch = { refs ->
                requested.addAll(refs.map { it.gid })
                refs.map { rows.getValue(it.gid) }
            })
            newer.load()
            assertFalse(newer.state.value.isComplete)
            assertTrue(newer.state.value.diagnostics.issues.any { it.code == GalleryVersionIssueCode.ROLE_UNRESOLVED && 4L in it.affectedGids })
            assertEquals(setOf(3L, 4L, 5L, 6L), requested)
        }
    }
}
