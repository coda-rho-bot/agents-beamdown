package com.angussoftware.letta.env

import org.junit.Test
import org.junit.Assert.*
import java.io.File

/**
 * Docs-sync guard: ACCESS.md and docs/agentctl.md must mention every
 * registry command (the docs are allowed to say more, never less).
 * Keeps the human-facing trust docs from drifting behind the code.
 */
class DocsSyncTest {

    private fun repoFile(path: String): File {
        // unit tests run with CWD = app module dir
        val f = File(path).takeIf { it.exists() } ?: File("../$path")
        assertTrue("docs file not found: $path (CWD=${File(".").absolutePath})", f.exists())
        return f
    }

    @Test
    fun `ACCESS_md documents every command`() {
        val docs = repoFile("ACCESS.md").readText()
        AgentAccessibilityService.REGISTRY.forEach { spec ->
            val shown = spec.clientAlias ?: spec.name
            assertTrue("ACCESS.md is missing command '$shown' (wire: ${spec.name}) — update the consent-class table",
                docs.contains(shown))
        }
    }

    @Test
    fun `agentctl doc mentions every command`() {
        val docs = repoFile("docs/agentctl.md").readText()
        AgentAccessibilityService.REGISTRY.forEach { spec ->
            val shown = spec.clientAlias ?: spec.name
            assertTrue("docs/agentctl.md is missing command '$shown' (wire: ${spec.name}) — update the protocol doc",
                docs.contains(shown))
        }
    }

    @Test
    fun `ACCESS_md states the two trust doors`() {
        val docs = repoFile("ACCESS.md").readText()
        assertTrue("ACCESS.md must describe the shell door", docs.contains("shell door"))
        assertTrue("ACCESS.md must describe the consent-gated door", docs.contains("consent-gated"))
    }
}
