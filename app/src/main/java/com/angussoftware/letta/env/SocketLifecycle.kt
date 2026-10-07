package com.angussoftware.letta.env

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Process
import android.system.Os
import java.io.File

/**
 * Single-owner lifecycle for the agentctl unix listener.
 *
 * Oct 7 live finding (v0.4.26): a11y service reconnects during an app update
 * fire onServiceConnected() repeatedly; each reconnect spawned a bind attempt,
 * and two racing threads could both be inside bind/unlink — the loser's
 * cleanup unlinked the WINNER's filesystem entry, leaving the winner's socket
 * bound in-kernel with no name. Every later bind then hit EADDRINUSE against
 * an invisible inode, forever; clients got ENOENT. The retry loop could not
 * recover because the holder was in-process and unnamed.
 *
 * Contract enforced here:
 *  - ONE binder at a time (synchronized; rebind closes the old server first)
 *  - only the owner unlinks (a losing/failed bind never deletes a name it
 *    did not successfully create)
 *  - EADDRINUSE is diagnosed, not just retried: if the path CONNECTS, a live
 *    listener owns the name (adopt = stand down); if connect gets
 *    ENOENT/ECONNREFUSED, any in-process holder is torn down and rebound
 *  - [binder] is injectable so JVM tests can simulate the exact race
 */
class SocketLifecycle(
    private val binder: Binder,
    private val log: (String) -> Unit,
) {
    /** Socket ops, injectable for tests. Production impl = Android LocalServerSocket. */
    interface Binder {
        /** @return the bound server (impl-specific type, e.g. LocalServerSocket), or throws (e.g. EADDRINUSE). Must NOT unlink on failure. */
        fun bind(path: String): Any
        /** Wrap an already-bound raw server as a closable handle for lifecycle bookkeeping. */
        fun bindHandle(raw: Any): ServerHandle
        /** Active-connect probe: true = a live listener answers at path. */
        fun probeConnect(path: String): Boolean
        /** Best-effort name removal — called ONLY on a name this lifecycle owns. */
        fun unlink(path: String)
        /** Set 0600 on the socket file (production only; no-op allowed in tests). */
        fun chmod600(sockFile: File)
    }

    /** Uniform server handle across implementations. */
    interface ServerHandle { fun close() }

    val lock = Any()
    var server: ServerHandle? = null
        private set

    /**
     * Bind or take over the listener at [sockFile]. Idempotent while alive:
     * if this lifecycle already holds a server, it is closed (and its name
     * unlinked) FIRST, so a service reconnect lands as close→rebind rather
     * than a second racing bind.
     *
     * @param onBound called exactly once per successful bind with the raw
     *        server (typed by the binder's production impl) to start the
     *        accept loop; runs on the caller's thread.
     * @param isAlive service-liveness check; when false, recovery loops stop.
     */
    fun <T> bindOrTakeOver(sockFile: File, onBound: (T) -> Unit, isAlive: () -> Boolean) {
        val path = sockFile.absolutePath
        while (isAlive()) {
            // Single-owner section: tear down any previous owner of THIS
            // lifecycle before creating a new one.
            val previous = synchronized(lock) {
                val prev = server
                server = null
                prev
            }
            previous?.let { prev ->
                runCatching { prev.close() }
                // close() unlinks the name for LocalServerSocket — but be
                // explicit in case close is overridden/no-op in a handle impl.
                runCatching { binder.unlink(path) }
                log("agentctl socket: previous owner closed before rebind")
            }
            try {
                @Suppress("UNCHECKED_CAST")
                val bound = binder.bind(path) as T
                synchronized(lock) { server = binder.bindHandle(bound as Any) }
                runCatching { binder.chmod600(sockFile) }
                log("agentctl socket up (unix): $path")
                onBound(bound)
                return
            } catch (e: Exception) {
                if (!isAlive()) return
                val boundElsewhere = runCatching { binder.probeConnect(path) }.getOrDefault(false)
                if (boundElsewhere) {
                    // A live listener answers — the name is healthy; we adopt
                    // it instead of fighting it (this happens when the system
                    // re-delivers onServiceConnected to a process that already
                    // serves). Standing down is correct: exactly one server.
                    log("agentctl socket: $path already served by a live listener — adopting, standing down")
                    return
                }
                // Orphaned holder (our own dead zombie or another process's
                // corpse): the name is bound but nobody answers. Force-unlink
                // and retry — the in-kernel inode dies when we unlink it and
                // no accept-loop holds it (we are the only live contender
                // because adopt-vs-standdown above resolves live contention).
                log("agentctl socket bind failed (${e.javaClass.simpleName}: ${e.message}) — orphaned holder, unlinking and retrying")
                runCatching { binder.unlink(path) }
                runCatching { Thread.sleep(500) }
            }
        }
    }

    /** Stop serving: close the owner and remove the name. Safe to call twice. */
    fun shutdown(sockFile: File) {
        val prev = synchronized(lock) {
            val p = server
            server = null
            p
        }
        prev?.let { runCatching { it.close() } }
        runCatching { binder.unlink(sockFile.absolutePath) }
    }
}

/** Production binder: Android LocalServerSocket + peer-uid probe. */
class AndroidSocketBinder : SocketLifecycle.Binder {
    override fun bind(path: String): Any {
        // NOTE: no pre-delete here. Deleting before bind is what created the
        // zombie-inode race (loser's delete unlinked winner's entry). Bind
        // straight; SocketLifecycle's recovery paths own unlinking.
        return LocalServerSocket(path)
    }

    override fun bindHandle(raw: Any): SocketLifecycle.ServerHandle {
        val ss = raw as LocalServerSocket
        return object : SocketLifecycle.ServerHandle {
            override fun close() {
                runCatching { ss.close() } // LocalServerSocket.close() unlinks
            }
        }
    }

    override fun probeConnect(path: String): Boolean {
        return try {
            val probe = LocalSocket()
            probe.connect(java.io.File(path).toLocalSocketAddress())
            runCatching { probe.close() }
            true // something accepted/held the connect → live listener
        } catch (_: Exception) {
            false
        }
    }

    override fun unlink(path: String) {
        runCatching { File(path).delete() }
    }

    override fun chmod600(sockFile: File) {
        runCatching { Os.chmod(sockFile.absolutePath, 384 /* 0600 octal */) }
    }
}

private fun File.toLocalSocketAddress(): android.net.LocalSocketAddress =
    android.net.LocalSocketAddress(absolutePath, android.net.LocalSocketAddress.Namespace.FILESYSTEM)

/** Peer-uid gate (defense in depth on top of the 0600 socket file). */
fun LocalSocket.peerUidMatches(): Boolean = runCatching {
    peerCredentials?.uid == Process.myUid()
}.getOrDefault(false)
