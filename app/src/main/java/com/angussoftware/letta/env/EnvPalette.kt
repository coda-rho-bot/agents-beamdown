package com.angussoftware.letta.env

import androidx.compose.ui.graphics.toArgb
import com.angussoftware.theming.compose.ui.theme.backgroundDark
import com.angussoftware.theming.compose.ui.theme.backgroundLight
import com.angussoftware.theming.compose.ui.theme.errorDark
import com.angussoftware.theming.compose.ui.theme.errorLight
import com.angussoftware.theming.compose.ui.theme.onSurfaceDark
import com.angussoftware.theming.compose.ui.theme.onSurfaceLight
import com.angussoftware.theming.compose.ui.theme.onSurfaceVariantDark
import com.angussoftware.theming.compose.ui.theme.onSurfaceVariantLight
import com.angussoftware.theming.compose.ui.theme.outlineVariantDark
import com.angussoftware.theming.compose.ui.theme.outlineVariantLight
import com.angussoftware.theming.compose.ui.theme.primaryDark
import com.angussoftware.theming.compose.ui.theme.primaryLight
import com.angussoftware.theming.compose.ui.theme.surfaceContainerDark
import com.angussoftware.theming.compose.ui.theme.surfaceContainerLowestLight
import com.angussoftware.theming.compose.ui.theme.tertiaryDark
import com.angussoftware.theming.compose.ui.theme.tertiaryLight

/**
 * Design-system palette bridge for the View-based UI.
 *
 * Every color the activity renders comes from the Angus Software Theming
 * tokens (com.angussoftware.theming:theming-compose) — no hardcoded ramps.
 * Semantic mapping (same doctrine as the fuel-dashboard gauge work):
 *
 *  - `ok`     → primary   (brand teal = the healthy anchor)
 *  - `warn`   → tertiary   (in-progress is drift, not alarm — calm blue)
 *  - `error`  → error      (muted rose)
 *  - `accent` → primary    (single accent: the brand)
 *
 * Pure function of the ui mode so the mapping is unit-testable without an
 * Activity. [withAlpha] derives translucent tints from token RGB — the
 * alpha varies, the hue never leaves the theme.
 */
/** Keep the token's RGB, replace the alpha channel (tint varies, hue stays). */
fun Int.withAlpha(alpha: Int): Int = (alpha shl 24) or (this and 0x00FFFFFF)

object EnvPalette {

    /** Token-derived palette for one ui mode. All values are ARGB ints. */
    data class Mode(
        val bg: Int,
        val surface: Int,
        val textPrimary: Int,
        val textSecondary: Int,
        val ok: Int,
        val warn: Int,
        val error: Int,
        val accent: Int,
        val outline: Int,
    )

    fun forMode(night: Boolean): Mode = if (night) {
        Mode(
            bg = backgroundDark.toArgb(),
            surface = surfaceContainerDark.toArgb(),
            textPrimary = onSurfaceDark.toArgb(),
            textSecondary = onSurfaceVariantDark.toArgb(),
            ok = primaryDark.toArgb(),
            warn = tertiaryDark.toArgb(),
            error = errorDark.toArgb(),
            accent = primaryDark.toArgb(),
            outline = outlineVariantDark.toArgb(),
        )
    } else {
        Mode(
            bg = backgroundLight.toArgb(),
            surface = surfaceContainerLowestLight.toArgb(),
            textPrimary = onSurfaceLight.toArgb(),
            textSecondary = onSurfaceVariantLight.toArgb(),
            ok = primaryLight.toArgb(),
            warn = tertiaryLight.toArgb(),
            error = errorLight.toArgb(),
            accent = primaryLight.toArgb(),
            outline = outlineVariantLight.toArgb(),
        )
    }
}
