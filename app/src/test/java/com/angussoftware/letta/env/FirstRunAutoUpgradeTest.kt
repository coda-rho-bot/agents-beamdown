package com.angussoftware.letta.env

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * First-run letta auto-upgrade (0.4.34 item a): pins the two pure
 * decisions that gate the auto-upgrade path — the version-compare that
 * decides "newer" and the pref-stamp trigger that decides "this
 * generation still needs a check".
 *
 * The version compare is the load-bearing one: the baked pin ages between
 * release builds (0.34.4 baked while npm serves 0.34.3x), and the string
 * comparison "0.34.31" < "0.34.4" reads the WRONG direction — only the
 * part-wise numeric compare gets fresh installs onto current letta-code.
 */
class FirstRunAutoUpgradeTest {

    // ---- isNewerVersion: the pin case ----

    @Test
    fun latest_034_31_is_newer_than_baked_034_4() {
        // THE pin case (Coda, design review): "0.34.31" vs "0.34.4" is
        // newer part-wise ([0,34,31] > [0,34,4]) despite the longer
        // string reading "smaller". String compare would strand fresh
        // installs on the baked pin forever within a minor series.
        assertTrue(LettaEnvironmentService.isNewerVersion("0.34.31", "0.34.4"))
    }

    @Test
    fun baked_034_4_is_not_newer_than_latest_034_31() {
        assertFalse(LettaEnvironmentService.isNewerVersion("0.34.4", "0.34.31"))
    }

    // ---- isNewerVersion: ordinary semver movement ----

    @Test
    fun patch_bump_is_newer() {
        assertTrue(LettaEnvironmentService.isNewerVersion("0.34.5", "0.34.4"))
    }

    @Test
    fun minor_bump_is_newer() {
        assertTrue(LettaEnvironmentService.isNewerVersion("0.35.0", "0.34.31"))
    }

    @Test
    fun multi_digit_major_is_newer() {
        assertTrue(LettaEnvironmentService.isNewerVersion("10.0.0", "9.9.9"))
    }

    @Test
    fun older_series_is_not_newer() {
        assertFalse(LettaEnvironmentService.isNewerVersion("0.33.9", "0.34.4"))
    }

    @Test
    fun same_version_is_not_newer() {
        assertFalse(LettaEnvironmentService.isNewerVersion("0.34.4", "0.34.4"))
    }

    // ---- isNewerVersion: malformed inputs never upgrade (fail-safe) ----

    @Test
    fun prerelease_candidate_is_never_newer() {
        // Auto-upgrade only installs clean semver releases; a prerelease
        // tag on the candidate is not an upgrade target.
        assertFalse(LettaEnvironmentService.isNewerVersion("0.34.4-rc1", "0.34.4"))
    }

    @Test
    fun unknown_baked_is_never_newer() {
        // pkg.json unreadable -> baked "unknown": the compare is
        // meaningless, keep whatever is installed.
        assertFalse(LettaEnvironmentService.isNewerVersion("0.34.31", "unknown"))
    }

    @Test
    fun unknown_candidate_is_never_newer() {
        assertFalse(LettaEnvironmentService.isNewerVersion("unknown", "0.34.4"))
    }

    @Test
    fun partial_version_is_never_newer() {
        assertFalse(LettaEnvironmentService.isNewerVersion("0.34", "0.34.4"))
    }

    @Test
    fun four_part_version_is_never_newer() {
        assertFalse(LettaEnvironmentService.isNewerVersion("0.34.4.1", "0.34.4"))
    }

    @Test
    fun prerelease_baked_is_never_newer_against() {
        // Malformed CURRENT also blocks: both sides must be clean semver.
        assertFalse(LettaEnvironmentService.isNewerVersion("0.34.31", "0.34.4-beta"))
    }

    // ---- shouldAutoUpgradeCheck: per-versionCode trigger ----

    @Test
    fun fresh_install_needs_check() {
        assertTrue(LettaEnvironmentService.shouldAutoUpgradeCheck(-1, 46))
    }

    @Test
    fun stamped_current_generation_does_not_recheck() {
        assertFalse(LettaEnvironmentService.shouldAutoUpgradeCheck(46, 46))
    }

    @Test
    fun apk_update_re_arms_check() {
        // The composition with the keyed marker: a new versionCode means a
        // fresh baked rootfs — exactly when the pin has aged.
        assertTrue(LettaEnvironmentService.shouldAutoUpgradeCheck(46, 47))
    }

    @Test
    fun downgrade_install_also_re_arms_check() {
        assertTrue(LettaEnvironmentService.shouldAutoUpgradeCheck(47, 46))
    }
}
