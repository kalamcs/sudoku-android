package com.kalamcs.sudoku

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppearanceTest {
    @Test fun defaultIsFluentLightEvenOnADarkDevice() {
        val settings = AppearanceSettings.fromKeys(null, null)
        assertEquals(GameTheme.FLUENT, settings.theme)
        assertEquals(AppearanceMode.LIGHT, settings.mode)
        assertFalse(settings.palette(systemDark = true).dark)
        assertEquals(settings, AppearanceSettings.fromKeys("unknown", "unknown"))
    }

    @Test fun systemModeFollowsDeviceAndDarkOverrideIsIndependent() {
        val system = AppearanceSettings.fromKeys("system", "catppuccin")
        assertEquals("Catppuccin Latte", system.theme.label(system.palette(systemDark = false).dark))
        assertEquals("Catppuccin Mocha", system.theme.label(system.palette(systemDark = true).dark))
        assertNotEquals(system.palette(systemDark = false).surface, system.palette(systemDark = true).surface)
        assertTrue(system.copy(mode = AppearanceMode.DARK).palette(systemDark = false).dark)
    }

    @Test fun pencilMarksAndControlsStayReadableInEveryPalette() {
        for (theme in GameTheme.entries) {
            for (dark in listOf(false, true)) {
                val palette = theme.palette(dark)
                val label = theme.label(dark)
                assertTrue("$label foreground", contrast(palette.ink, palette.surface) >= 4.5)
                assertTrue("$label filled button", contrast(palette.onAccent, palette.accent) >= 4.5)
                assertTrue("$label emphasized matching digit", contrast(palette.matchingCellInk, palette.matchingCell) >= 4.5)
                assertTrue("$label matching cell separation", contrast(palette.matchingCell, palette.relatedCell) >= 1.5)
                assertTrue("$label completed keypad digit", contrast(palette.muted, palette.completedKey) >= 4.5)
                assertTrue("$label selected notes", contrast(palette.ink, palette.selectedCell) >= 4.5)
                assertTrue("$label matching notes", contrast(palette.ink, palette.noteMarker) >= 4.5)
                assertTrue("$label ordinary notes", contrast(palette.muted, palette.relatedCell) >= 4.5)
                assertTrue("$label error notes", contrast(palette.error, palette.errorSurface) >= 4.5)
                assertTrue("$label active control", contrast(palette.accent, palette.selectedCell) >= 4.5)
            }
        }
    }

    private fun contrast(first: Int, second: Int): Double {
        val a = luminance(first)
        val b = luminance(second)
        return (max(a, b) + .05) / (min(a, b) + .05)
    }

    private fun luminance(color: Int): Double {
        fun channel(shift: Int): Double {
            val value = ((color shr shift) and 0xff) / 255.0
            return if (value <= .04045) value / 12.92 else ((value + .055) / 1.055).pow(2.4)
        }
        return .2126 * channel(16) + .7152 * channel(8) + .0722 * channel(0)
    }
}
