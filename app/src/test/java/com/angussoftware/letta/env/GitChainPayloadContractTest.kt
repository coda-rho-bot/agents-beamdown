package com.angussoftware.letta.env

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source contract: the git chain must tolerate a missing/corrupt payload at
 * rootfs/usr/bin/git — and must NEVER install a shell script there.
 *
 * History (Oct 7, live-diagnosed on both devices): git-elf execs
 * rootfs/usr/bin/git as a GLIBC PROGRAM (loader invocation). Three failure
 * shapes observed: stale v0.2.7 ELF (cannot load), payload absent after
 * reinstall (rootfs.tar never carries it), and a #! wrapper left by a field
 * hotfix ("invalid ELF header"). The harness shared-memory sync execs git by
 * this ABSOLUTE path, so any bad state gated ALL Bash/Monitor calls in
 * on-device sessions. PR #112's delegation approach was closed as
 * wrong-shaped: a script at that path breaks git-elf's exec.
 */
class GitChainPayloadContractTest {

    private fun src(path: String): String {
        val f = java.io.File(path).takeIf { it.exists() }
            ?: java.io.File("../$path").takeIf { it.exists() }
        check(f != null) { "$path not found from test working dir" }
        return f.readText()
    }

    @Test
    fun `installSupportFiles restores a non-ELF or missing payload from the staged tarball`() {
        val t = src("src/main/java/com/angussoftware/letta/env/LettaEnvironmentService.kt")
        assertTrue(
            "installSupportFiles must check the payload at rootfs/usr/bin/git and extract the " +
                "staged git-arm64.tar.gz when it is missing or not a real ELF (three live " +
                "failure shapes, Oct 7).",
            t.contains("git payload resilience") && t.contains("git-arm64.tar.gz"),
        )
    }

    @Test
    fun `git dot sh primary path checks payload presence before exec-ing git-elf`() {
        val t = src("src/main/assets/git.sh")
        val primary = t.substringAfter("# --- primary: static ELF chain")
            .substringBefore("# --- fallback: proot rootfs")
        assertTrue(
            "git.sh PRIMARY path must verify the payload ELF exists (magic check) and extract " +
                "the staged tarball before exec-ing git-elf — the old primary only checked " +
                "git-elf/gc-shim presence and fell into a misleading loader error on a wiped " +
                "payload (watch session, Oct 7).",
            primary.contains("is_elf") && primary.contains("git-arm64.tar.gz") &&
                primary.contains("exec \"\$DX_FILES/bin/git-elf\""),
        )
    }

    @Test
    fun `no code path may write a shell script to rootfs usr bin git`() {
        for (path in listOf(
            "src/main/java/com/angussoftware/letta/env/LettaEnvironmentService.kt",
            "src/main/assets/git.sh",
        )) {
            val t = src(path)
            assertFalse(
                "Nothing may write a #! script at rootfs/usr/bin/git — the glibc loader " +
                "cannot exec scripts; git-elf needs the real Debian ELF there (PR #112 " +
                "closed on exactly this). Offender: $path",
                Regex("""usr/bin/git[^"\n]*"\s*\n?\s*"[^"]*#!/""").containsMatchIn(t) ||
                Regex("""writeText\([^)]*#!/system/bin/sh[^)]*usr/bin/git""").containsMatchIn(t),
            )
        }
    }
}
