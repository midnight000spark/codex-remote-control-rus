package com.haochen.codexremote

import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * Best-effort JSON cache writer.
 *
 * Content is serialized and written on one background thread, and only the
 * latest pending value per file is kept, so a burst of snapshots cannot queue
 * an unbounded number of writes behind the UI thread.
 *
 * Clearing the cache bumps an epoch: pending writes are dropped and a write
 * already in flight is skipped, so deleted cache files cannot reappear.
 */
internal class SessionCacheWriter(name: String = "session-cache-writer") {
    private class Pending(val epoch: Int, val content: () -> String)

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, name).apply { isDaemon = true }
    }
    private val lock = Any()
    private val pending = LinkedHashMap<File, Pending>()
    private var epoch = 0
    private var draining = false

    fun submit(file: File, content: () -> String) {
        synchronized(lock) {
            pending[file] = Pending(epoch, content)
            if (draining) return
            draining = true
        }
        try {
            executor.execute { drain() }
        } catch (_: RejectedExecutionException) {
            // The activity is shutting down; drop the write.
            synchronized(lock) { draining = false }
        }
    }

    fun clearPending() {
        synchronized(lock) {
            pending.clear()
            epoch += 1
        }
    }

    private fun drain() {
        while (true) {
            val next = synchronized(lock) {
                val entry = pending.entries.firstOrNull()
                if (entry == null) {
                    draining = false
                    return
                }
                pending.remove(entry.key)
                entry
            }
            val current = synchronized(lock) { next.value.epoch == epoch }
            if (!current) continue
            try {
                next.key.parentFile?.mkdirs()
                next.key.writeText(next.value.content())
            } catch (_: Exception) {
                // Cache failures must not affect the bridge connection or chat flow.
            }
        }
    }

    fun shutdown() {
        // Queued writes still run; the process is going away anyway.
        executor.shutdown()
    }
}