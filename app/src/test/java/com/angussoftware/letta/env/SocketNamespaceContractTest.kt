package com.angussoftware.letta.env

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source contract: the agentctl listener MUST bind the FILESYSTEM namespace.
 *
 * History (v0.4.25–v0.4.29): AndroidSocketBinder used
 * `LocalServerSocket(path)`, whose single-name constructor binds an
 * abstract-family namespace (default Namespace.ABSTRACT) — no socket file was ever
 * created (leading '@' in /proc/net/unix on both phone and watch), so the
 * filesystem client (nc -U <path>) always got ENOENT. agentctl was dead on
 * every install while bind logs looked healthy.
 *
 * Assertions run on a SLICE: only AndroidSocketBinder's bind() body, with
 * comments stripped. Whole-file matching is vacuous (file-bottom helpers
 * legitimately mention Namespace.FILESYSTEM), and comment text must neither
 * satisfy nor trip the assertions.
 */
class SocketNamespaceContractTest {

    private fun binderSource(): String {
        val src = java.io.File("src/main/java/com/angussoftware/letta/env/SocketLifecycle.kt")
            .takeIf { it.exists() }
            ?: java.io.File("../src/main/java/com/angussoftware/letta/env/SocketLifecycle.kt")
        check(src.exists()) { "SocketLifecycle.kt not found from test working dir" }
        return src.readText()
    }

    private fun binderClass(text: String): String =
        text.substringAfter("class AndroidSocketBinder")

    /** JUST the bind() body — where a reintroduced abstract bind would live. */
    private fun bindBody(text: String): String =
        text.substringAfter("override fun bind(").substringBefore("override fun bindHandle")

    /** Strip block (non-nesting) then line comments so explanatory prose can't satisfy or trip assertions. */
    private fun codeOnly(text: String): String =
        text.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""//[^\n]*"""), "")

    /** Balanced-paren argument text for every call site of [callee] in [code]. */
    private fun callArgs(code: String, callee: String): List<String> {
        val args = mutableListOf<String>()
        var i = code.indexOf(callee)
        while (i >= 0) {
            val open = i + callee.length
            if (open < code.length && code[open] == '(') {
                var depth = 0
                var j = open
                while (j < code.length) {
                    when (code[j]) {
                        '(' -> depth++
                        ')' -> { depth--; if (depth == 0) break }
                    }
                    j++
                }
                if (depth == 0 && j < code.length) args.add(code.substring(open + 1, j))
            }
            i = code.indexOf(callee, i + 1)
        }
        return args
    }

    @Test
    fun `bind must use the FILESYSTEM namespace explicitly`() {
        val body = codeOnly(bindBody(binderSource()))
        val calls = callArgs(body, "LocalSocketAddress")
        assertTrue(
            "bind() must construct a LocalSocketAddress at all (no bind, no socket).",
            calls.isNotEmpty(),
        )
        assertTrue(
            "AndroidSocketBinder.bind() must construct LocalSocketAddress with an EXPLICIT " +
                "Namespace.FILESYSTEM argument — got args: $calls",
            calls.any { it.contains("Namespace.FILESYSTEM") },
        )
    }

    @Test
    fun `bind must not construct abstract-family sockets`() {
        val body = codeOnly(bindBody(binderSource()))
        val serverCalls = callArgs(body, "LocalServerSocket")
        assertTrue(
            "bind() must create the LocalServerSocket (over its FileDescriptor).",
            serverCalls.isNotEmpty(),
        )
        assertTrue(
            "Every LocalServerSocket in bind() must be constructed from a FileDescriptor — " +
                "the String-name constructor binds an abstract-family namespace (no file " +
                "created). Got args: $serverCalls",
            serverCalls.all { it.contains("FileDescriptor") },
        )
        val addrCalls = callArgs(body, "LocalSocketAddress")
        assertTrue(
            "Every LocalSocketAddress in bind() must carry an EXPLICIT namespace — the " +
                "single-name constructor defaults to Namespace.ABSTRACT (abstract family), which " +
                "recreates the exact no-file symptom. Got args: $addrCalls",
            addrCalls.isNotEmpty() && addrCalls.all { it.contains("Namespace.") },
        )
    }

    @Test
    fun `name holder must be pinned against garbage collection`() {
        val cls = binderClass(binderSource())
        val body = codeOnly(bindBody(cls))
        assertTrue(
            "bind() must pin the name-holding LocalSocket into the nameHolder field " +
                "(nameHolder = holder) — LocalServerSocket(FileDescriptor) SHARES the fd, and " +
                "an unpinned holder's finalizer closes the live server's fd on GC. This exact " +
                "missing-pin class shipped dead sockets v0.4.25–v0.4.29.",
            body.contains("nameHolder = holder"),
        )
        assertTrue(
            "The nameHolder field must be declared (@Volatile private var nameHolder).",
            Regex("""@Volatile\s+private\s+var\s+nameHolder""").containsMatchIn(cls),
        )
        assertTrue(
            "nameHolder must be cleared only after the server fd is closed " +
                "(nameHolder = null in ServerHandle.close, after ss.close()).",
            Regex("""ss\.close\(\)[\s\S]*?nameHolder = null""").containsMatchIn(cls),
        )
    }
}
