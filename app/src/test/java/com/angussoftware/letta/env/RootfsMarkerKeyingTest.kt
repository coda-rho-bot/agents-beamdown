package com.angussoftware.letta.env

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Shape-(a) durable closure (0.4.34): the rootfs extraction marker is keyed
 * by versionCode, so an APK update that ships a new rootfs.tar re-extracts
 * over the stale tree. These tests pin the marker-name contract and the
 * staleness decision that drives the sweep.
 *
 * The stale-rootfs class bit updates twice before this closure (see the
 * 0.4.32-batch todo, item c): a versionless marker meant every APK update
 * silently kept the PREVIOUS rootfs unless self-heal tripped.
 */
class RootfsMarkerKeyingTest {

    // ---- marker naming ----

    @Test
    fun marker_name_is_keyed_by_version_code() {
        assertEquals(".rootfs-extracted-46", LettaEnvironmentService.rootfsMarkerName(46))
        assertEquals(".rootfs-extracted-47", LettaEnvironmentService.rootfsMarkerName(47))
    }

    // ---- staleness decision ----

    @Test
    fun current_key_marker_is_not_stale() {
        val name = LettaEnvironmentService.rootfsMarkerName(47)
        assertFalse(LettaEnvironmentService.isStaleRootfsMarker(name, 47))
    }

    @Test
    fun older_key_marker_is_stale() {
        // APK updated 46 -> 47: the 46-era marker no longer matches; the
        // 47 rootfs must re-extract.
        assertTrue(LettaEnvironmentService.isStaleRootfsMarker(".rootfs-extracted-46", 47))
    }

    @Test
    fun pre_keying_unversioned_marker_is_stale() {
        // Devices upgrading from a pre-closure install carry the original
        // versionless marker — must read as stale so the first keyed build
        // re-extracts.
        assertTrue(LettaEnvironmentService.isStaleRootfsMarker(".rootfs-extracted", 47))
    }

    @Test
    fun unrelated_files_are_not_markers() {
        // The sweep lists filesDir — anything not marker-prefixed must be
        // left alone.
        assertFalse(LettaEnvironmentService.isStaleRootfsMarker("rootfs", 47))
        assertFalse(LettaEnvironmentService.isStaleRootfsMarker(".some-other-dotfile", 47))
        assertFalse(LettaEnvironmentService.isStaleRootfsMarker("prefs.xml", 47))
    }

    @Test
    fun prefix_collision_is_not_a_marker_match() {
        // A file merely STARTING with the prefix but not a valid marker
        // shape still counts as stale per the prefix rule — but critically
        // the CURRENT key never does, and non-prefixed files never do.
        // This pins the exact predicate.
        assertTrue(LettaEnvironmentService.isStaleRootfsMarker(".rootfs-extracted-", 47))
        assertFalse(LettaEnvironmentService.isStaleRootfsMarker(".rootfs-extracted-47", 47))
    }
}
