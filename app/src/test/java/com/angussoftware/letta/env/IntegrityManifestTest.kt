package com.angussoftware.letta.env

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavioral + source-contract tests for the APK-asset integrity manifest
 * (review 2731, PR #118).
 *
 * The manifest closes two review blockers: (1) addon verification was
 * length-only — now every protected file is sha256-compared (chunked
 * stream); (2) the "trusted rootfs source" for restores was itself
 * agent-writable (laundering path) — now the APK ASSET manifest is the
 * authority and rootfs copies are untrusted cache; restore bytes come
 * from APK assets (direct or rootfs.tar members).
 */
class IntegrityManifestTest {

    private fun src(path: String): String {
        val f = java.io.File(path).takeIf { it.exists() }
            ?: java.io.File("../$path").takeIf { it.exists() }
        check(f != null) { "$path not found from test working dir" }
        return f.readText()
    }

    // ---------- behavioral: parser ----------

    @Test
    fun `parser reads dest sha source member and optional columns`() {
        val text = """
            # header comment
            ${"libs/libc.so.6"}${'\t'}${"43d1729f81d7772b8d046886593c8917fa0d2050ccc4d7e2d45fabe42f0adfb2"}${'\t'}${"rootfs:usr/lib/aarch64-linux-gnu/libc.so.6"}
            ${"libs/libstdc++.so.6"}${'\t'}${"532f3a12d7b8fef6cc7a064ea106b6c4ab2365674b73e743e4a385d4ffb6c8ab"}${'\t'}${"rootfs:usr/lib/aarch64-linux-gnu/libstdc++.so.6"}${'\t'}${"usr/lib/aarch64-linux-gnu/libstdc++.so.6.0.30"}
            ${"libs/sharp-linux-arm64.node"}${'\t'}${"95406e4cc71bf1d9f5b6f13c3e6db7bab5b7e71a7f003ccce27efc7a2780ff0b"}${'\t'}${"rootfs:usr/local/lib/x"}${'\t'}${"optional"}
            ${"dns-shim.js"}${'\t'}${"d0afa6671e11b7d240546ec6f15c9eb51f5350e43745976b5961a311b815b1b2"}${'\t'}${"asset:dns-shim.js"}
        """.trimIndent()
        val entries = LettaEnvironmentService.parseIntegrityManifest(text)
        assertEquals(4, entries.size)
        val libc = entries.first { it.dest == "libs/libc.so.6" }
        assertEquals("rootfs:usr/lib/aarch64-linux-gnu/libc.so.6", libc.source)
        assertNull(libc.member)
        assertFalse(libc.optional)
        val libstdcpp = entries.first { it.dest == "libs/libstdc++.so.6" }
        assertEquals("usr/lib/aarch64-linux-gnu/libstdc++.so.6.0.30", libstdcpp.member)
        val sharp = entries.first { it.dest == "libs/sharp-linux-arm64.node" }
        assertTrue(sharp.optional)
        val shim = entries.first { it.dest == "dns-shim.js" }
        assertEquals("asset:dns-shim.js", shim.source)
    }

    @Test
    fun `parser skips malformed lines and bad hashes`() {
        val text = """
            notaentry
            libs/x.so.6	ZZZ	rootfs:whatever
            libs/y.so.6	${"a".repeat(64)}	bogus:src
            libs/ok.so.6	${"b".repeat(64)}	asset:ok
        """.trimIndent()
        val entries = LettaEnvironmentService.parseIntegrityManifest(text)
        assertEquals(1, entries.size)
        assertEquals("libs/ok.so.6", entries[0].dest)
    }

    @Test
    fun `parser is case-insensitive on hash hex`() {
        val upper = "A".repeat(64)
        val e = LettaEnvironmentService.parseIntegrityManifest("libs/z	$upper	asset:z")[0]
        assertEquals(upper.lowercase(), e.sha256)
    }

    @Test
    fun `sha256Of streams correctly for known vector`() {
        val tmp = java.io.File.createTempFile("integrity", "bin")
        try {
            tmp.writeBytes(ByteArray(0))
            assertEquals(
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                LettaEnvironmentService.sha256Of(tmp),
            )
            tmp.writeBytes("abc".toByteArray())
            assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                LettaEnvironmentService.sha256Of(tmp),
            )
        } finally {
            tmp.delete()
        }
    }

    // ---------- source contract: authority wiring ----------

    @Test
    fun `boot gate derives expected set from manifest not a literal`() {
        val t = src("src/main/java/com/angussoftware/letta/env/LettaEnvironmentService.kt")
        assertFalse(
            "The boot gate's expected files/libs set must derive from the integrity manifest — " +
                "a hand-copied hashSetOf literal is the exact defect review 2731 flagged " +
                "(literal drifts from reality, laundered entries look legitimate).",
            Regex("""hashSetOf\(\s*""" + """"libc\.so\.6"""").containsMatchIn(t),
        )
        assertTrue(
            "integrityGate must consult the manifest (readIntegrityManifest/parseIntegrityManifest).",
            t.contains("integrityGate") && t.contains("readIntegrityManifest()"),
        )
    }

    @Test
    fun `restore path never trusts the on-disk rootfs copy without hash check`() {
        val t = src("src/main/java/com/angussoftware/letta/env/LettaEnvironmentService.kt")
        assertTrue(
            "Rootfs-sourced restores must go through extractRootfsTarMembers (APK asset " +
                "authority) or a HASH-VERIFIED rootfs cache copy — the review's laundering " +
                "blocker was restoring from agent-writable rootfs bytes as if trusted.",
            t.contains("extractRootfsTarMembers") && t.contains("sha256Of(untrusted) == e.sha256"),
        )
        assertFalse(
            "No restore path may copy from rootfs based on length/exists alone.",
            Regex("""\.length\(\)\s*!=\s*\w+\.length\(\)""").containsMatchIn(t),
        )
    }

    @Test
    fun `manifest is parsed from the apk asset never the filesdir copy`() {
        val t = src("src/main/java/com/angussoftware/letta/env/LettaEnvironmentService.kt")
        assertTrue(
            "readIntegrityManifest must open assets.open(\"integrity-manifest.txt\") — the " +
                "filesDir copy is attacker-writable and diagnostics-only.",
            Regex("""assets\.open\("integrity-manifest\.txt"\)""").containsMatchIn(t),
        )
        assertFalse(
            "Nothing may PARSE the filesDir manifest copy for decisions.",
            Regex("""parseIntegrityManifest\(\s*File\(filesDir[^)]*\)\.readText\(\)""").containsMatchIn(t),
        )
    }

    // ---------- generated asset contract ----------

    private fun manifestAsset(): String = src("src/main/assets/integrity-manifest.txt")

    @Test
    fun `generated manifest asset parses and covers the protected set`() {
        val entries = LettaEnvironmentService.parseIntegrityManifest(manifestAsset())
        val dests = entries.map { it.dest }.toSet()
        val required = setOf(
            "dns-shim.js", "fs-shim.js",
            "libs/dnsport.so",
            "libs/libc.so.6", "libs/libm.so.6", "libs/libdl.so.2", "libs/libpthread.so.0",
            "libs/libgcc_s.so.1", "libs/libstdc++.so.6", "libs/libtinfo.so.6", "libs/libresolv.so.2",
            "libs/pty.node",
        )
        val missing = required - dests
        assertTrue(
            "Manifest must cover every protected runtime file (missing: $missing). " +
                "Regenerate with tools/integrity/generate-manifest.py.",
            missing.isEmpty(),
        )
        assertTrue(entries.any { it.dest == "libs/sharp-linux-arm64.node" && it.optional })
        assertTrue(entries.any { it.dest == "libs/libvips-cpp.so.8.17.3" && it.optional })
        assertFalse(dests.any { it.contains("git-arm64") })
    }

    @Test
    fun `generated manifest hashes match the assets it pins`() {
        val entries = LettaEnvironmentService.parseIntegrityManifest(manifestAsset())
        for (e in entries.filter { it.source.startsWith("asset:") }) {
            val f = java.io.File("src/main/assets/${e.source.removePrefix("asset:")}")
                .takeIf { it.exists() }
                ?: java.io.File("../src/main/assets/${e.source.removePrefix("asset:")}")
            assertTrue("asset for ${e.dest} missing", f != null && f.exists())
            assertEquals(
                "asset ${e.dest} drifted from manifest — regenerate the manifest",
                e.sha256,
                LettaEnvironmentService.sha256Of(f!!),
            )
        }
    }

    @Test
    fun `generator script and asset stay in sync`() {
        val gen = src("../tools/integrity/generate-manifest.py")
        assertTrue(
            "Generator must exist and hash from APK assets/rootfs.tar/swap tarballs.",
            "ROOTFS_LIBS" in gen && "SWAP_STAGED" in gen && "ASSET_FILES" in gen,
        )
    }
}
