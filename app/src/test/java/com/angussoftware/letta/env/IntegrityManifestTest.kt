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
    fun `pin-version header parses and rejects garbage`() {
        assertEquals(
            "0.34.4",
            LettaEnvironmentService.parseManifestPinVersion(
                "# some header\n# letta.js pin-version: 0.34.4\n# more",
            ),
        )
        assertEquals(null, LettaEnvironmentService.parseManifestPinVersion("# no header here"))
        assertEquals(null, LettaEnvironmentService.parseManifestPinVersion("# letta.js pin-version: not-a-version"))
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

    // ---------- source contract: pin ordering + fail-closed (Oct 8 set-3) ----------

    @Test
    fun `lettajs pin check runs before patching - gate then patch ordering`() {
        val t = src("src/main/java/com/angussoftware/letta/env/LettaEnvironmentService.kt")
        val runEnv = t.substringAfter("private fun runEnvironment(")
        val pinAt = runEnv.indexOf("verifyLettaJsPin(")
        val patchAt = runEnv.indexOf("applyLettaJsPatches(")
        assertTrue(
            "runEnvironment must call verifyLettaJsPin BEFORE applyLettaJsPatches — the pin " +
                "covers PRE-PATCH member bytes; verifying after patching would compare patched " +
                "bytes against the pre-patch pin and false-block every pinned boot.",
            pinAt in 0 until patchAt,
        )
        // The pin's failure must BLOCK before patching runs.
        val blockAt = runEnv.indexOf("letta.js pre-patch pin failed")
        assertTrue(
            "verifyLettaJsPin false must return/block before any patching (block log line missing or misplaced)",
            blockAt in 0 until patchAt,
        )
    }

    @Test
    fun `upgrade path fails closed on zero patch sites`() {
        val t = src("src/main/java/com/angussoftware/letta/env/LettaEnvironmentService.kt")
        // upgradeLetta must be Boolean; false = do not boot the fresh install.
        assertTrue(
            "upgradeLetta must return Boolean (false = freshly installed state must not boot)",
            "private fun upgradeLetta(): Boolean" in t,
        )
        val upg = t.substringAfter("private fun upgradeLetta(): Boolean")
        val zeroBranch = upg.indexOf("0 patch sites in freshly installed letta.js")
        val zeroReturn = upg.indexOf("return false", zeroBranch)
        assertTrue(
            "0-patch-sites branch on a FRESH npm install must return false (fail-closed), not warn",
            zeroBranch >= 0 && zeroReturn in zeroBranch until zeroBranch + 2000,
        )
        val mismatchBranch = upg.indexOf("!= pinned")
        val mismatchReturn = upg.indexOf("return false", mismatchBranch)
        assertTrue(
            "installed-version != pinned-resolution branch must return false (do not boot registry-swapped content)",
            mismatchBranch >= 0 && mismatchReturn in mismatchBranch until mismatchBranch + 1000,
        )
        // Worker must honor false by NOT falling through to runEnvironment.
        val worker = t.substringAfter("private fun spawnWorker(")
        assertTrue(
            "worker must skip launch when upgradeLetta returns false (if (!upgradeLetta()) break) — " +
                "a bare call would boot the unpatchable install anyway",
            "if (!upgradeLetta())" in worker,
        )
    }

    // ---------- generated asset contract ----------

    private fun manifestAsset(): String = src("src/main/assets/integrity-manifest.txt")

    @Test
    fun `generated manifest asset parses and covers the protected set`() {
        val entries = LettaEnvironmentService.parseIntegrityManifest(manifestAsset())
        val dests = entries.map { it.dest }.toSet()
        val required = setOf(
            "dns-shim.js", "fs-shim.js", "dns-forwarder.js",
            "libs/dnsport.so",
            "libs/libc.so.6", "libs/libm.so.6", "libs/libdl.so.2", "libs/libpthread.so.0",
            "libs/libgcc_s.so.1", "libs/libstdc++.so.6", "libs/libtinfo.so.6", "libs/libresolv.so.2",
            "libs/pty.node",
            "npm/letta.js",
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
    fun `lettajs pin entry carries pin-version header and real tar member`() {
        val text = manifestAsset()
        val entries = LettaEnvironmentService.parseIntegrityManifest(text)
        val pin = entries.firstOrNull { it.dest == "npm/letta.js" }
        assertTrue(
            "npm/letta.js manifest entry required (letta.js is executed with full app " +
                "privileges on every boot — pre-patch member pin, Oct 8 follow-up).",
            pin != null,
        )
        val pinVersion = LettaEnvironmentService.parseManifestPinVersion(text)
        assertTrue(
            "manifest must carry '# letta.js pin-version: X.Y.Z' — the runtime pins " +
                "letta.js only while the installed version equals it (upgrades replace the file)",
            pinVersion != null,
        )
        assertTrue(
            "npm/letta.js entry must be rootfs-sourced with an explicit tar member",
            pin!!.source.startsWith("rootfs:") && !pin.member.isNullOrEmpty(),
        )
        assertTrue("letta.js pin must not be optional", !pin.optional)
    }

    @Test
    fun `lettajs pin hash matches the pinned rootfs tar member pre-patch bytes`() {
        // The manifest's npm/letta.js hash must equal the PRE-PATCH member
        // bytes inside the authority rootfs.tar. Runs in CI (build.yml
        // fetches rootfs.tar before tests); skipped when the tarball is
        // absent locally (gitignored, ~194MB).
        val tar = sequenceOf("src/main/assets/rootfs.tar", "../src/main/assets/rootfs.tar")
            .map { java.io.File(it) }.firstOrNull { it.exists() }
            ?: return
        val entries = LettaEnvironmentService.parseIntegrityManifest(manifestAsset())
        val pin = entries.first { it.dest == "npm/letta.js" }
        val memberHash = java.security.MessageDigest.getInstance("SHA-256")
        var inMember = false
        java.util.zip.GZIPInputStream(tar.inputStream(), 1 shl 16).use { gz ->
            val header = ByteArray(512)
            val name = ByteArray(100)
            val size = ByteArray(12)
            val buf = ByteArray(1 shl 16)
            outer@ while (true) {
                var off = 0
                while (off < 512) {
                    val n = gz.read(header, off, 512 - off)
                    if (n < 0) break@outer
                    off += n
                }
                if (header.all { it == 0.toByte() }) break
                System.arraycopy(header, 0, name, 0, 100)
                System.arraycopy(header, 124, size, 0, 12)
                val memberName = String(name).trimEnd('\u0000')
                val memberSize = size.takeWhile { it != 0.toByte() }
                    .fold(0L) { acc, b -> acc * 8 + (b - '0'.code.toByte()) }
                val padded = (memberSize + 511) / 512 * 512
                val wanted = "./" + (pin.member ?: "")
                if (memberName == wanted) {
                    var rem = memberSize
                    while (rem > 0) {
                        val n = gz.read(buf, 0, minOf(buf.size.toLong(), rem).toInt())
                        if (n < 0) break@outer
                        memberHash.update(buf, 0, n)
                        rem -= n
                    }
                    inMember = true
                    break@outer
                }
                var skip = padded
                while (skip > 0) {
                    val n = gz.read(buf, 0, minOf(buf.size.toLong(), skip).toInt())
                    if (n < 0) break@outer
                    skip -= n
                }
            }
        }
        assertTrue("rootfs.tar member ${pin.member} not found in stream scan", inMember)
        val hex = memberHash.digest().joinToString("") { "%02x".format(it) }
        assertEquals(
            "npm/letta.js manifest hash != PRE-PATCH bytes of rootfs.tar member — " +
                "regenerate with tools/integrity/generate-manifest.py (hash must be of " +
                "as-extracted member bytes, before applyLettaJsPatches)",
            pin.sha256,
            hex,
        )
    }

    @Test
    fun `manifest rootfs authority sha stays lockstep with release pipeline pin`() {
        // EXPECTED_SHA lockstep: the manifest header's rootfs.tar sha and the
        // release pipeline's EXPECTED_SHA must name the SAME authority build.
        // Drift either way = two "authorities" disagreeing about what the
        // runtime restores — the exact single-authority invariant #118 built.
        val manifestSha = Regex("rootfs\\.tar authority sha256 at generation:\\s*([0-9a-f]{64})")
            .find(manifestAsset())?.groupValues?.get(1)
        val rel = src("../.woodpecker/release.yml")
        val ciSha = Regex("EXPECTED_SHA\\s*=\\s*'([0-9a-f]{64})'").find(rel)?.groupValues?.get(1)
        assertTrue("manifest header must record the generation rootfs.tar sha256", manifestSha != null)
        assertTrue("release.yml must pin EXPECTED_SHA", ciSha != null)
        assertEquals(
            "rootfs.tar authority split-brain: integrity manifest and release.yml " +
                "EXPECTED_SHA name different builds. Update BOTH (regenerate manifest " +
                "against the new tarball AND bump EXPECTED_SHA) whenever the rootfs is " +
                "re-pinned — e.g. the next rootfs rebuild folding in the arm64 sharp swap.",
            manifestSha,
            ciSha,
        )
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
