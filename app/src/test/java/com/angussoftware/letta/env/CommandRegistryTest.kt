package com.angussoftware.letta.env

import org.junit.Test
import org.junit.Assert.*
import java.io.File

/**
 * Command registry invariants. The REGISTRY is the single source of truth:
 * the consent gate enforces from it and the commands index renders from it.
 * These tests keep the other surfaces (agentctl.sh client, docs) honest.
 */
class CommandRegistryTest {

    private val registry = AgentAccessibilityService.REGISTRY

    @Test
    fun `no duplicate wire names or aliases`() {
        val names = registry.map { it.name }
        val aliases = registry.mapNotNull { it.clientAlias }
        assertEquals("duplicate wire names", names.size, names.toSet().size)
        assertEquals("duplicate aliases", aliases.size, aliases.toSet().size)
    }

    @Test
    fun `every command the agentctl client can send exists in the registry`() {
        // Parse the shipped client script: every '"cmd":"X"' it can emit
        // must be a registry wire name or client alias. If someone adds a
        // command to the client but not the registry (or renames one side),
        // this fails the build — docs/enforcement drift becomes a test
        // failure instead of a runtime lie.
        val script = File("src/main/assets/agentctl.sh").readText()
        // Match both literal sends ('{"cmd":"x"') and escaped-quote sends
        // ("{\"cmd\":\"x\"") — the client uses both forms.
        val sent = Regex("""\\?"?cmd\\?"?\s*:\s*\\?"([a-zA-Z-]+)\\?"""")
            .findAll(script).map { it.groupValues[1] }.toSet()
        assertTrue("agentctl.sh not found or unreadable", sent.isNotEmpty())
        val known = registry.flatMap { listOfNotNull(it.name, it.clientAlias) }.toSet()
        val unknown = sent - known
        assertTrue("client sends commands missing from REGISTRY: $unknown", unknown.isEmpty())
    }

    @Test
    fun `client-local commands are never socket-dispatchable lies`() {
        // CLIENT_LOCAL entries exist for documentation completeness only;
        // their wire names must NOT collide with socket commands that would
        // dispatch them into the gated path.
        val socketNames = registry.filter { it.consent != AgentAccessibilityService.ConsentClass.CLIENT_LOCAL }.map { it.name }.toSet()
        registry.filter { it.consent == AgentAccessibilityService.ConsentClass.CLIENT_LOCAL }.forEach {
            assertFalse("client-local ${it.name} collides with a socket command", it.name in socketNames)
        }
    }

    @Test
    fun `every spec carries its metadata`() {
        registry.forEach { s ->
            assertTrue("blank name", s.name.isNotBlank())
            assertTrue("blank does for ${s.name}", s.does.isNotBlank())
        }
    }

    @Test
    fun `consent classes are coherent`() {
        // The privacy-bearing and device-control set stays gated no matter
        // how the table is edited. back/home/notifications and am/pm/keyevent
        // joined this set Oct 8 (audit R7-6/R7-2): navigation can dismiss
        // security dialogs; the passthroughs expose the same capabilities as
        // their gated named twins.
        val gated = registry.filter { it.consent == AgentAccessibilityService.ConsentClass.SESSION_GATED }.map { it.name }.toSet()
        listOf("screenshot-text", "click", "clickId", "text", "tap", "longPress", "swipe",
               "key", "launch", "notiflist", "hcrequest",
               "back", "home", "notifications", "am", "pm", "keyevent").forEach {
            assertTrue("$it must remain SESSION_GATED", it in gated)
        }
        // Introspection and app-own diagnostics stay ungated.
        listOf("ping", "commands", "capabilities", "status", "session",
               "notifstatus", "logtail").forEach {
            assertTrue("$it must remain UNGATED (found ${registry.firstOrNull { r -> r.name == it }?.consent})",
                registry.firstOrNull { r -> r.name == it }?.consent == AgentAccessibilityService.ConsentClass.UNGATED)
        }
        // Permission-scoped telemetry: the grant is the consent.
        listOf("health", "location").forEach {
            assertEquals("$it must stay GRANT_IS_CONSENT",
                AgentAccessibilityService.ConsentClass.GRANT_IS_CONSENT,
                registry.first { r -> r.name == it }.consent)
        }
    }
}
