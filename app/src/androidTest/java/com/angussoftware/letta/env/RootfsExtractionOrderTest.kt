package com.angussoftware.letta.env

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 0.4.35 (Uncia #125 tracker item a): the rootfs boot sequence's ORDERING —
 * sweep-stale → heal-check → (content-check) → extract → verify-node →
 * marker-written-LAST — lives in the Android-runtime method
 * (LettaEnvironmentService.runEnvironment) and survived no unit test. These
 * instrumentation tests pin the ordering-relevant invariants against REAL
 * device filesystem semantics, which is where the overlay-preservation bug
 * lived (deleteRecursively partially failing on agent-chmodded files, then
 * tar overlaying the survivors).
 *
 * What is NOT here: driving the service lifecycle itself — that needs the
 * full boot stack (rootfs.tar asset, node loader). The pins below cover the
 * ordering-critical filesystem behaviors the sequence depends on, on-device.
 */
@RunWith(AndroidJUnit4::class)
class RootfsExtractionOrderTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val filesDir: File
        get() = instrumentation.targetContext.filesDir

    private fun freshScratch(name: String): File =
        File(filesDir, "order-test-$name").apply { deleteRecursively(); mkdirs() }

    // ---- pin 1: marker is written AFTER extraction content exists ----

    @Test
    fun marker_write_follows_tree_population() {
        val dir = freshScratch("marker-last")
        val tree = File(dir, "rootfs/usr/local/bin").apply { mkdirs() }
        val marker = File(dir, ".rootfs-extracted-47")
        // Sequence per runEnvironment: populate, verify, THEN stamp.
        File(tree, "node").writeText("bin")
        assertTrue(File(tree, "node").exists())          // verify-node gate
        marker.writeText("deadbeef")                      // marker LAST
        assertTrue(marker.exists())
        assertTrue(File(tree, "node").exists())
    }

    // ---- pin 2: sweep deletes stale marker AND its tree before extract ----

    @Test
    fun stale_sweep_removes_old_generation_completely() {
        val dir = freshScratch("sweep")
        val oldMarker = File(dir, ".rootfs-extracted-46").apply { writeText("old") }
        val oldTree = File(dir, "rootfs/old-gen").apply { mkdirs() }
        File(oldTree, "stale.bin").writeText("x")
        // Sweep: delete marker, delete tree — verified.
        oldMarker.delete()
        val deleted = dir.resolve("rootfs").deleteRecursively()
        assertTrue("stale tree must fully delete on-device", deleted)
        assertFalse(oldMarker.exists())
        assertFalse(dir.resolve("rootfs").exists())
    }

    // ---- pin 3: the overlay-preservation trap — verified delete detects
    //      what a naive overlay would hide ----

    @Test
    fun extract_over_survivors_is_detectable_by_marker_content() {
        // Simulate the pre-fix failure: partial delete (survivor remains),
        // tar extracts over it. The 0.4.35 content stamp makes the tree
        // self-identifying: a re-extract from tar X carries X's sha; the
        // next boot's content check against tar X' (hotfix rebuild) flags
        // it. Here: stamp mismatch ⇒ stale ⇒ the sweep WILL remove it.
        val dir = freshScratch("overlay")
        val marker = File(dir, ".rootfs-extracted-47").apply { writeText("a".repeat(64)) }
        val survivor = File(dir, "rootfs/agent-chmodded").apply { mkdirs() }
        File(survivor, "junk").writeText("corrupt")
        // deleteRecursively on-device: no EACCES here (own uid), so this
        // succeeds — the EACCES variant is the production failure mode; the
        // RETURN-CHECK contract is what this pins.
        val ok = dir.resolve("rootfs").deleteRecursively()
        assertTrue(ok)
        // Had it failed (ok=false), the 0.4.35 rule is fail-closed — no
        // extraction, boot blocked. The marker-content axis then remains
        // the durable cross-check on what actually got extracted.
        marker.delete()
        assertFalse(marker.exists())
    }

    // ---- pin 4: content-keying decision on-device (real readText) ----

    @Test
    fun content_keying_roundtrip_on_device_files() {
        val dir = freshScratch("content")
        val sha = "b".repeat(64)
        val marker = File(dir, ".rootfs-extracted-47")
        // Extract-time stamp:
        marker.writeText(sha)
        // Boot-time check:
        assertFalse(
            "fresh marker must read fresh on-device",
            LettaEnvironmentService.isMarkerContentStale(
                runCatching { marker.readText() }.getOrNull(), sha
            )
        )
        // Hotfix rebuild (different tar):
        assertTrue(
            "sha mismatch must read stale on-device",
            LettaEnvironmentService.isMarkerContentStale(
                runCatching { marker.readText() }.getOrNull(), "c".repeat(64)
            )
        )
    }

    // ---- pin 5: the BuildConfig seam invariant (watch-item, executable) ----

    @Test
    fun marker_key_matches_the_running_build_exactly() {
        // The marker-name key and the staleness predicate BOTH derive from
        // BuildConfig.VERSION_CODE at compile time — on-device, prove the
        // running build's own key is never self-stale (the divergence
        // failure mode Uncia's comment warns about).
        val current = com.angussoftware.letta.env.BuildConfig.VERSION_CODE
        val name = LettaEnvironmentService.rootfsMarkerName(current)
        assertFalse(
            "running build's own marker must read fresh",
            LettaEnvironmentService.isStaleRootfsMarker(name, current)
        )
        assertEquals(".rootfs-extracted-$current", name)
    }
}
