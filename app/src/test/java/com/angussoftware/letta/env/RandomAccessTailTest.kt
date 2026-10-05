package com.angussoftware.letta.env

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * RandomAccessTail semantics for the agentctl logtail command (v0.4.8+):
 * the a11y service reads filesDir/server.log in-process (release run-as is
 * denied, a11y text caps truncate the 8000-char body). logtail requests up
 * to 16384 bytes — the default 8000-char cap would silently clip that, so
 * tail() takes maxChars and logtail passes maxChars = n.
 */
class RandomAccessTailTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun writeLog(lines: Int): java.io.File =
        tmp.newFile("server.log").apply {
            writeText((1..lines).joinToString("") { "line $it\n" })
        }

    @Test fun returnsWholeFileWhenUnderCap() {
        val f = writeLog(10)
        assertEquals((1..10).joinToString("") { "line $it\n" }, RandomAccessTail.tail(f, 4096))
    }

    @Test fun returnsLastNBytesOnly() {
        val f = writeLog(1000)
        val tail = RandomAccessTail.tail(f, 4096)
        assertTrue("tail should be shorter than the file", tail.length < 4096 + 100)
        assertTrue("tail must end at the last line", tail.endsWith("line 1000\n"))
        assertTrue("tail should not contain early lines", !tail.contains("line 1\n"))
    }

    @Test fun dropsPartialLeadingLineWhenSeekingMidFile() {
        // Big enough that the 4096-byte window starts mid-line: the first
        // fragment in the window is partial and must be dropped.
        val f = tmp.newFile("server.log").apply {
            writeText((1..2000).joinToString("") { "line $it padded to a fixed width 0000\n" })
        }
        val tail = RandomAccessTail.tail(f, 4096)
        assertTrue("first returned line must be complete", tail.startsWith("line "))
        assertTrue("partial leading fragment must be dropped", !tail.startsWith("ed to a fixed"))
        assertTrue(tail.endsWith("line 2000 padded to a fixed width 0000\n"))
    }

    @Test fun keepsFirstLineWhenWholeFileFits() {
        val f = tmp.newFile("server.log").apply { writeText("first line\nsecond line\n") }
        assertEquals("first line\nsecond line\n", RandomAccessTail.tail(f, 4096))
    }

    @Test fun maxCharsDefaultClipsTo8000() {
        val f = writeLog(10_000)
        assertEquals(8000, RandomAccessTail.tail(f, 64 * 1024).length)
    }

    @Test fun maxCharsOverrideServesAgentRequestsUncipped() {
        val f = writeLog(10_000)
        val tail = RandomAccessTail.tail(f, 16384, maxChars = 16384)
        assertTrue("logtail's 16384-byte request must not be clipped to 8000", tail.length > 8000)
        assertTrue(tail.endsWith("line 10000\n"))
    }

    @Test fun emptyFileYieldsEmptyTail() {
        val f = tmp.newFile("server.log")
        assertEquals("", RandomAccessTail.tail(f, 4096))
    }
}
