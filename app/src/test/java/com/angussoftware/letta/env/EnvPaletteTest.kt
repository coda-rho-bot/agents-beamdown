package com.angussoftware.letta.env

import androidx.compose.ui.graphics.toArgb
import com.angussoftware.theming.compose.ui.theme.primaryDark
import com.angussoftware.theming.compose.ui.theme.primaryLight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * EnvPalette contract: every rendered color comes from an Angus Software
 * Theming token, dark and light are distinct sets, and withAlpha keeps
 * the token's RGB intact (tint varies, hue never leaves the theme).
 */
class EnvPaletteTest {

    @Test
    fun darkAndLightDiffer() {
        val dark = EnvPalette.forMode(night = true)
        val light = EnvPalette.forMode(night = false)
        assertNotEquals(dark, light)
        assertNotEquals(dark.bg, light.bg)
        assertNotEquals(dark.surface, light.surface)
        assertNotEquals(dark.textPrimary, light.textPrimary)
        assertNotEquals(dark.error, light.error)
    }

    @Test
    fun okIsPrimaryTokenBothModes() {
        // healthy anchor = brand primary in both modes (design-system doctrine)
        assertEquals(primaryDark.toArgb(), EnvPalette.forMode(night = true).ok)
        assertEquals(primaryLight.toArgb(), EnvPalette.forMode(night = false).ok)
    }

    @Test
    fun withAlphaPreservesRgb() {
        val p = EnvPalette.forMode(night = true)
        val tinted = p.error.withAlpha(0x26)
        assertEquals(0x26, (tinted ushr 24) and 0xFF)
        assertEquals(p.error and 0x00FFFFFF, tinted and 0x00FFFFFF)
    }

    @Test
    fun noZeroColors() {
        // a slot left unwired would be transparent black — visible defect
        for (night in listOf(true, false)) {
            val p = EnvPalette.forMode(night)
            listOf(p.bg, p.surface, p.textPrimary, p.textSecondary,
                p.ok, p.warn, p.error, p.accent, p.outline)
                .forEach { assertNotEquals("night=$night slot unwired", 0, it) }
        }
    }
}
