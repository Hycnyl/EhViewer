// Copyright 2026 Hycnyl. SPDX-License-Identifier: GPL-3.0-or-later
package com.hippo.ehviewer.download

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryWorkGateTest {
    @Test
    fun longExportDoesNotHoldOtherGalleriesAndReadersWaitOnlyForTheirGid() = runBlocking {
        val gate = GalleryWorkGate()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val export = async {
            gate.withExport(1, "busy", { true }) {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()
        var otherStarted = false
        assertTrue(withTimeout(500) { gate.withDownload(2) { otherStarted = true } })
        assertTrue(otherStarted)
        assertFalse(gate.withDownload(1) { error("Same GID must not write") })
        val sameReader = async { gate.withReader(1) { true } }
        assertFalse(sameReader.isCompleted)
        release.complete(Unit)
        export.await()
        assertTrue(withTimeout(500) { sameReader.await() })
    }

    @Test
    fun cancelingSpiderIsBusyUntilItsActualCleanupFinishes() = runBlocking {
        val gate = GalleryWorkGate()
        val root = SupervisorJob()
        val started = CompletableDeferred<Unit>()
        val cleaning = CompletableDeferred<Unit>()
        val finishCleanup = CompletableDeferred<Unit>()
        CoroutineScope(Dispatchers.Default + root).launch {
            try {
                started.complete(Unit)
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    cleaning.complete(Unit)
                    finishCleanup.await()
                }
            }
        }
        gate.observe(3, root)
        started.await()
        root.cancel()
        cleaning.await()
        assertFalse(root.isActive)
        assertFalse(root.isCompleted)
        assertTrue(runCatching { gate.withExport(3, "busy", { true }) {} }.exceptionOrNull() is IllegalStateException)
        finishCleanup.complete(Unit)
        root.join()
        gate.withExport(3, "busy", { true }) {}
    }

    @Test
    fun canceledOrFailedExportReleasesReservationsForRetryAndQueue() = runBlocking {
        val gate = GalleryWorkGate()
        val entered = CompletableDeferred<Unit>()
        val export = launch {
            gate.withExport(4, "busy", { true }) {
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        entered.await()
        export.cancelAndJoin()
        assertTrue(gate.withDownload(4) {})
        assertTrue(runCatching { gate.withExport(4, "busy", { true }) { error("disk failure") } }.isFailure)
        assertTrue(gate.withDownload(4) {})
        gate.withExport(4, "busy", { true }) {}
    }
}
