package com.angussoftware.letta.env

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source contract: the agentctl listener MUST bind the FILESYSTEM namespace.
 *
 * History (v0.4.25–v0.4.29): AndroidSocketBinder used
 * `LocalServerSocket(path)`, whose single-name constructor binds the
 * ABSTRACT namespace — no socket file was ever created (leading '@' in
 * /proc/net/unix on both phone and watch), so the filesystem client
 * (nc -U <path>) always got ENOENT. agentctl was dead on every install
 * while bind logs looked healthy. This test fails the build if anyone
 * reintroduces an abstract-namespace bind.
 */
class SocketNamespaceContractTest {

    private fun binderSource(): String {
        val src = java.io.File("src/main/java/com/angussoftware/letta/env/SocketLifecycle.kt")
            .takeIf { it.exists() }
            ?: java.io.File("../src/main/java/com/angussoftware/letta/env/SocketLifecycle.kt")
        check(src.exists()) { "SocketLifecycle.kt not found from test working dir" }
        return src.readText()
    }

    @Test
    fun `bind must use the FILESYSTEM namespace explicitly`() {
        val text = binderSource()
        val bindBlock = text.substringAfter("class AndroidSocketBinder")
        assertTrue(
            "AndroidSocketBinder.bind() must bind LocalSocketAddress.Namespace.FILESYSTEM — " +
                "LocalServerSocket(String) binds the ABSTRACT namespace and creates no file, " +
                "leaving agentctl dead with healthy-looking logs (v0.4.25–v0.4.29 bug).",
            bindBlock.contains("LocalSocketAddress.Namespace.FILESYSTEM"),
        )
    }

    @Test
    fun `bind must not use the single-name LocalServerSocket constructor`() {
        val text = binderSource()
        val bindBlock = text.substringAfter("class AndroidSocketBinder")
        assertFalse(
            "AndroidSocketBinder must not call LocalServerSocket(path) — that constructor " +
                "binds the ABSTRACT namespace. Use LocalSocket.bind(FILESYSTEM) + " +
                "LocalServerSocket(FileDescriptor) instead.",
            Regex("""LocalServerSocket\(\s*path\s*\)""").containsMatchIn(bindBlock),
        )
    }

    @Test
    fun `name holder must be pinned against garbage collection`() {
        val text = binderSource()
        val binderBlock = text.substringAfter("class AndroidSocketBinder")
        assertTrue(
            "The LocalSocket that binds the filesystem name must be held in a field for the " +
                "server's lifetime — LocalServerSocket(FileDescriptor) shares the fd, and the " +
                "holder's finalizer would close the live server's fd if it were GC'd.",
            binderBlock.contains("nameHolder"),
        )
        assertTrue(
            "nameHolder must be cleared only after the server fd is closed (in ServerHandle.close).",
            binderBlock.contains("nameHolder = null"),
        )
    }
}
