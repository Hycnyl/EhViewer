package com.hippo.ehviewer.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ComfortColorsTest {
    @Test
    fun lightPresetMaintainsTextAndControlContrast() = checkContrast(ComfortLightColors)

    @Test
    fun darkPresetMaintainsTextAndControlContrast() = checkContrast(ComfortDarkColors)

    @Test
    fun amoledKeepsTextReadableAndContainerHierarchyVisible() {
        val colors = ComfortDarkColors.amoled(true)
        assertEquals(Color.Black, colors.surface)
        assertEquals(Color.Black, colors.background)
        assertEquals(Color.Black, colors.surfaceContainerLowest)
        assertTrue(colors.surfaceContainerHigh != colors.surfaceContainer)
        checkContrast(colors)
    }

    private fun checkContrast(c: ColorScheme) {
        val textPairs = listOf(
            c.onPrimary to c.primary,
            c.onPrimaryContainer to c.primaryContainer,
            c.onSecondary to c.secondary,
            c.onSecondaryContainer to c.secondaryContainer,
            c.onTertiary to c.tertiary,
            c.onTertiaryContainer to c.tertiaryContainer,
            c.onError to c.error,
            c.onErrorContainer to c.errorContainer,
            c.onBackground to c.background,
            c.inverseOnSurface to c.inverseSurface,
            c.onSurfaceVariant to c.surfaceContainerHighest,
            c.onPrimaryFixed to c.primaryFixed,
            c.onPrimaryFixedVariant to c.primaryFixedDim,
            c.onSecondaryFixed to c.secondaryFixed,
            c.onSecondaryFixedVariant to c.secondaryFixedDim,
            c.onTertiaryFixed to c.tertiaryFixed,
            c.onTertiaryFixedVariant to c.tertiaryFixedDim,
        )
        val surfaces = listOf(c.surface, c.surfaceDim, c.surfaceBright, c.surfaceContainerLowest, c.surfaceContainerLow, c.surfaceContainer, c.surfaceContainerHigh, c.surfaceContainerHighest)
        for ((text, background) in textPairs + surfaces.map { c.onSurface to it }) {
            assertTrue("Text contrast ${contrast(text, background)}", contrast(text, background) >= 4.5f)
        }
        for (background in surfaces) {
            assertTrue("Control outline contrast ${contrast(c.outline, background)}", contrast(c.outline, background) >= 3f)
        }
    }

    private fun contrast(a: Color, b: Color): Float {
        val x = a.luminance()
        val y = b.luminance()
        return (maxOf(x, y) + .05f) / (minOf(x, y) + .05f)
    }
}
