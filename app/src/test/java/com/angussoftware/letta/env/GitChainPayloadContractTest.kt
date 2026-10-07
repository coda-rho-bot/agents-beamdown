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

    @Test
    fun `asset git dot sh and tools mirror are byte-identical`() {
        val asset = src("src/main/assets/git.sh")
        val mirror = src("../tools/on-device/git.sh")
        assertTrue(
            "app/src/main/assets/git.sh and tools/on-device/git.sh must be byte-identical " +
                "(the header's own 'keep in sync' contract — drift has bitten before).",
            asset == mirror,
        )
    }

    @Test
    fun `git dot sh defines die before the primary block uses it`() {
        val t = src("src/main/assets/git.sh")
        val dieDef = t.indexOf("die() {")
        val firstUse = t.indexOf("die \"")
        assertTrue(
            "die() must be defined before its first call — shell functions are only known " +
                "after their definition executes; calling it earlier emits 'die: not found' " +
                "(127) and the failure branch silently falls through (review finding 1).",
            dieDef in 0 until firstUse,
        )
    }

    @Test
    fun `corrupt staged tarball is removed on extract failure`() {
        val t = src("src/main/assets/git.sh")
        val primary = t.substringAfter("# --- primary: static ELF chain")
            .substringBefore("# --- fallback: proot rootfs")
        assertTrue(
            "The primary-path corrupt-tarball branch must rm the staged copy before dying, " +
                "else every later run dies identically and the download path is unreachable " +
                "(review finding 2 — port of the fallback section's own pattern).",
            primary.contains("rm -f \"\$DX_FILES/git-arm64.tar.gz\""),
        )
    }

    @Test
    fun `install-time resilience check runs after tarball staging`() {
        val t = src("src/main/java/com/angussoftware/letta/env/LettaEnvironmentService.kt")
        val stagingLoop = t.indexOf("for (payloadName in listOf(\"git-arm64.tar.gz\"")
        val resilience = t.indexOf("git payload resilience (Oct 7)")
        assertTrue(
            "The payload resilience check must run AFTER the tarball staging loop — on a " +
                "fresh reinstall filesDir is wiped, so checking first no-ops the install-time " +
                "restore (review finding 3).",
            stagingLoop != -1 && resilience != -1 && resilience > stagingLoop,
        )
    }

    /** Behavioral: run git.sh's primary block under /bin/sh with a fixture tree. */
    @Test
    fun `git dot sh primary failure branches actually stop`() {
        val fixture = kotlin.io.path.createTempDirectory("gitsh-fixture")
        try {
            val files = fixture.toFile()
            // chain present + good, payload = shell script (non-ELF), staged tarball = garbage
            java.io.File(files, "bin/git-elf").apply { parentFile.mkdirs(); writeText("stub"); setExecutable(true) }
            java.io.File(files, "gc-shim").apply { mkdirs() }
            java.io.File(files, "gc-shim/git-remote-https").apply { writeText("stub"); setExecutable(true) }
            java.io.File(files, "rootfs/usr/bin").apply { mkdirs() }
            java.io.File(files, "rootfs/usr/bin/git").apply { writeText("#!/system/bin/sh\nexit 0\n") }
            java.io.File(files, "git-arm64.tar.gz").apply { writeBytes(byteArrayOf(1, 2, 3)) } // invalid gzip
            val script = src("src/main/assets/git.sh")
            // Cut at the fallback section: we are testing the primary block only.
            val primaryOnly = script.substringBefore("# --- fallback: proot rootfs") +
                "echo \"UNREACHABLE-FELL-THROUGH\"\nexit 42\n"
            val shFile = java.io.File(files, "test-subject.sh").apply { writeText(primaryOnly) }
            val pb = ProcessBuilder("sh", shFile.absolutePath, "--version")
            pb.environment().put("DX_FILES", files.absolutePath)
            pb.redirectErrorStream(true)
            val proc = pb.start()
            val out = proc.inputStream.bufferedReader().readText()
            val rc = proc.waitFor()
            assertFalse(
                "Primary failure branch must STOP (die) on a corrupt staged tarball — got " +
                    "rc=$rc, output: ${out.take(300)}. Falling through re-creates the " +
                    "misleading-loader-error bug the PR exists to kill.",
                rc == 42 || "UNREACHABLE-FELL-THROUGH" in out,
            )
            assertFalse(
                "Corrupt staged tarball must be REMOVED so the next run can download.",
                java.io.File(files, "git-arm64.tar.gz").exists(),
            )
        } finally {
            fixture.toFile().deleteRecursively()
        }
    }
}
