// Copyright 2026 Hycnyl. SPDX-License-Identifier: GPL-3.0-or-later
package com.hippo.ehviewer.download

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job

// The monitor protects bookkeeping only. No I/O, queue callback or suspension runs under
// it. Queue requests for other GIDs never wait for a long export; readers of the same
// gallery wait until its export completes. A cancelling job remains busy until completed.
internal class GalleryWorkGate {
    private class Entry {
        var users = 0
        var export: CompletableDeferred<Unit>? = null
        val jobs = mutableSetOf<Job>()
    }

    private val entries = mutableMapOf<Long, Entry>()

    private fun cleanup(gid: Long, entry: Entry) {
        if (entry.users == 0 && entry.export == null && entry.jobs.isEmpty()) entries.remove(gid)
    }

    fun observe(gid: Long, job: Job) {
        val entry = synchronized(entries) {
            entries.getOrPut(gid, ::Entry).also { if (!it.jobs.add(job)) return }
        }
        job.invokeOnCompletion {
            synchronized(entries) {
                entry.jobs.remove(job)
                cleanup(gid, entry)
            }
        }
    }

    suspend fun withDownload(gid: Long, action: suspend () -> Unit): Boolean {
        val entry = synchronized(entries) {
            val entry = entries.getOrPut(gid, ::Entry)
            if (entry.export != null) return false
            entry.users++
            entry
        }
        try {
            action()
            return true
        } finally {
            synchronized(entries) {
                entry.users--
                cleanup(gid, entry)
            }
        }
    }

    suspend fun <T> withReader(gid: Long, action: () -> T): T {
        while (true) {
            val (entry, pending) = synchronized(entries) {
                val entry = entries.getOrPut(gid, ::Entry)
                val pending = entry.export
                if (pending == null) entry.users++
                entry to pending
            }
            if (pending != null) {
                pending.await()
                continue
            }
            try {
                return action()
            } finally {
                synchronized(entries) {
                    entry.users--
                    cleanup(gid, entry)
                }
            }
        }
    }

    suspend fun <T> withExport(gid: Long, busyMessage: String, isIdle: suspend () -> Boolean, action: suspend () -> T): T {
        val entry = synchronized(entries) {
            val entry = entries.getOrPut(gid, ::Entry)
            check(entry.export == null && entry.users == 0 && entry.jobs.none { !it.isCompleted }) { busyMessage }
            entry.export = CompletableDeferred()
            entry
        }
        try {
            check(isIdle()) { busyMessage }
            synchronized(entries) { check(entry.jobs.none { !it.isCompleted }) { busyMessage } }
            return action()
        } finally {
            val completed = synchronized(entries) {
                val completed = entry.export!!
                entry.export = null
                cleanup(gid, entry)
                completed
            }
            completed.complete(Unit)
        }
    }
}

internal val galleryWorkGate = GalleryWorkGate()
