package com.angussoftware.letta.env

import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SocketLifecycle race regression tests — the Oct 7 v0.4.26 live incident:
 * concurrent onServiceConnected() binds where the losing thread's cleanup
 * unlinked the winner's entry, leaving a bound-but-unnamed inode and
 * permanent EADDRINUSE (agentctl ENOENT until reinstall).
 */
class SocketLifecycleTest {

    /** Fake binder simulating a unix socket namespace. */
    private class FakeBinder : SocketLifecycle.Binder {
        val names = Collections.synchronizedMap(mutableMapOf<String, Any>()) // path -> bound marker
        val liveListeners = Collections.synchronizedSet(mutableSetOf<String>()) // paths with a REAL accept loop
        val unlinks = AtomicInteger(0)
        val bindCalls = AtomicInteger(0)

        override fun bind(path: String): Any {
            bindCalls.incrementAndGet()
            synchronized(names) {
                if (names.containsKey(path)) throw RuntimeException("bind failed (Address already in use)")
                names[path] = Any()
            }
            return names[path]!!
        }

        override fun bindHandle(raw: Any): SocketLifecycle.ServerHandle =
            object : SocketLifecycle.ServerHandle {
                override fun close() { /* unlink handled below */ }
            }

        /** Connect probe succeeds ONLY for names with a live accept loop. */
        override fun probeConnect(path: String): Boolean = liveListeners.contains(path)

        override fun unlink(path: String) {
            unlinks.incrementAndGet()
            names.remove(path)
            liveListeners.remove(path)
        }

        override fun chmod600(sockFile: File) { /* no-op in tests */ }
    }

    private fun lifecycle(binder: FakeBinder, logs: MutableList<String>): SocketLifecycle =
        SocketLifecycle(binder, { logs.add(it) })

    @Test
    fun `second connect while first serves adopts instead of racing`() {
        val binder = FakeBinder()
        val logs = mutableListOf<String>()
        val lc = lifecycle(binder, logs)
        val alive = AtomicBoolean(true)

        // First bind: live listener with accept loop registered.
        lc.bindOrTakeOver<Any>(File("/x/agentctl.sock"), onBound = { binder.liveListeners.add("/x/agentctl.sock") }, isAlive = { alive.get() })

        // System re-delivers onServiceConnected: second bindOrTakeOver while
        // the first listener is LIVE. The OLD code raced binds; the fix must
        // close the previous owner, rebind cleanly, and end with exactly one.
        lc.bindOrTakeOver<Any>(File("/x/agentctl.sock"), onBound = { binder.liveListeners.add("/x/agentctl.sock") }, isAlive = { alive.get() })

        // Exactly one name, one live listener, and the previous owner was closed.
        assertEquals(1, binder.names.size)
        assertTrue(binder.liveListeners.contains("/x/agentctl.sock"))
        assertTrue(logs.any { it.contains("previous owner closed") })
    }

    @Test
    fun `orphaned zombie inode is unlinked and rebound`() {
        val binder = FakeBinder()
        val logs = mutableListOf<String>()
        val lc = lifecycle(binder, logs)
        val alive = AtomicBoolean(true)

        // Simulate the incident state: name bound in "kernel" (names map) but
        // NO live listener (bound-but-unlinked zombie's mirror image: nobody
        // answers the connect probe).
        binder.names["/x/agentctl.sock"] = Any()

        var bound = false
        lc.bindOrTakeOver<Any>(File("/x/agentctl.sock"), onBound = { bound = true; binder.liveListeners.add("/x/agentctl.sock") }, isAlive = { alive.get() })

        // The orphan was detected, unlinked, and we own the name now.
        assertTrue("should have recovered via unlink+rebind", bound)
        assertTrue(logs.any { it.contains("orphaned holder") })
        assertTrue(binder.liveListeners.contains("/x/agentctl.sock"))
    }

    @Test
    @Throws(Exception::class)
    fun `concurrent binds never double-own the name`() {
        val binder = FakeBinder()
        val logs = Collections.synchronizedList(mutableListOf<String>())
        val lc = lifecycle(binder, logs)
        val alive = AtomicBoolean(true)
        val starts = CountDownLatch(4)
        val done = CountDownLatch(4)

        // Four threads storm bindOrTakeOver simultaneously (the incident's
        // three-way race, plus one). All must converge to ONE owner.
        repeat(4) {
            Thread {
                starts.countDown()
                starts.await()
                runCatching {
                    lc.bindOrTakeOver<Any>(File("/x/agentctl.sock"),
                        onBound = { binder.liveListeners.add("/x/agentctl.sock") },
                        isAlive = { alive.get() })
                }
                done.countDown()
            }.start()
        }
        assertTrue(done.await(10, java.util.concurrent.TimeUnit.SECONDS))

        // Invariant: at most one live listener at the name; namespace consistent.
        assertTrue("exactly one live listener expected, got ${binder.liveListeners.size}",
            binder.liveListeners.size <= 1)
        assertTrue("name must end bound", binder.names.containsKey("/x/agentctl.sock"))
    }

    @Test
    fun `shutdown closes owner and clears name`() {
        val binder = FakeBinder()
        val lc = lifecycle(binder, mutableListOf())
        val alive = AtomicBoolean(true)
        lc.bindOrTakeOver<Any>(File("/x/agentctl.sock"), onBound = { binder.liveListeners.add("/x/agentctl.sock") }, isAlive = { alive.get() })
        assertTrue(binder.names.isNotEmpty())

        lc.shutdown(File("/x/agentctl.sock"))
        assertTrue(binder.names.isEmpty())
        assertNull(lc.server)
    }

    @Test
    fun `dead service stops retrying`() {
        val binder = FakeBinder()
        val lc = lifecycle(binder, mutableListOf())
        val alive = AtomicBoolean(false) // service already dead
        var bound = false
        lc.bindOrTakeOver<Any>(File("/x/agentctl.sock"), onBound = { bound = true }, isAlive = { alive.get() })
        assertFalse("no bind when service is dead", bound)
        assertEquals(0, binder.bindCalls.get())
    }
}
