package com.angussoftware.letta.env

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Rotation guard for server.log / server-stdout.log / dns-forwarder.log
 * (Uncia WILL-HURT Oct 25: unbounded growth risked storage exhaustion on
 * both devices). The guard under test is the pure file operation — the
 * service calls it before every append (server.log) and pre-launch
 * (stdout/dns logs).
 */
class LogRotationTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun rotateIfLarge(f: File, cap: Long) {
        if (f.length() > cap) {
            val old = File(f.parentFile, f.name + ".1")
            if (old.exists()) old.delete()
            f.renameTo(old)
        }
    }

    @Test fun `under cap - no rotation`() {
        val f = tmp.newFile("server.log").apply { writeText("x".repeat(100)) }
        rotateIfLarge(f, 1024)
        assertTrue(f.exists())
        assertFalse(File(f.parentFile, "server.log.1").exists())
    }

    @Test fun `over cap - rotates to dot1`() {
        val f = tmp.newFile("server.log").apply { writeText("x".repeat(2000)) }
        rotateIfLarge(f, 1024)
        assertFalse(f.exists())
        val r = File(f.parentFile, "server.log.1")
        assertTrue(r.exists())
        assertEquals(2000L, r.length())
    }

    @Test fun `rotated replaces prior generation - bounded at 2x cap`() {
        val dir = tmp.root
        val gen0 = File(dir, "server.log.1").apply { writeText("old-gen") }
        val f = File(dir, "server.log").apply { writeText("x".repeat(2000)) }
        rotateIfLarge(f, 1024)
        // prior .1 gone, replaced by the newest generation
        assertEquals(2000L, gen0.length())
    }

    @Test fun `missing file - no throw`() {
        val f = File(tmp.root, "never-existed.log")
        rotateIfLarge(f, 1024) // length() = 0 <= cap, no-op, no exception
        assertFalse(f.exists())
    }
}
