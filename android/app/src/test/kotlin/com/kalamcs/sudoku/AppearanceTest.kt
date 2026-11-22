package com.kalamcs.sudoku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppearanceTest {
    @Test fun defaultIsOceanBlueLightEvenOnADarkDevice() {
        val settings = AppearanceSettings.fromKeys(null, null)
        assertEquals(GameTheme.OCEAN, settings.theme)
        assertEquals(AppearanceMode.LIGHT, settings.mode)
        assertFalse(settings.palette(systemDark = true).dark)
        assertEquals(settings, AppearanceSettings.fromKeys("unknown", "unknown"))
    }

    @Test fun systemModeFollowsDeviceAndDarkOverrideIsIndependent() {
        val system = AppearanceSettings.fromKeys("system", "purple")
        assertEquals(GameTheme.PURPLE, system.theme)
        assertFalse(system.palette(systemDark = false).dark)
        assertTrue(system.palette(systemDark = true).dark)
        assertNotEquals(system.palette(systemDark = false).surface, system.palette(systemDark = true).surface)
        assertTrue(system.copy(mode = AppearanceMode.DARK).palette(systemDark = false).dark)
    }

    @Test fun removedPaletteKeysFallBackWithoutChangingModeOrScreenTimeout() {
        for (key in listOf("fluent", "nord", "catppuccin", "material")) {
            assertEquals(AppearanceSettings(AppearanceMode.DARK, GameTheme.OCEAN, ScreenTimeout.THREE_MINUTES),
                AppearanceSettings.fromKeys("dark", key, "three_minutes"))
        }
    }

    @Test fun pencilMarksAndControlsStayReadableInEveryPalette() {
        for (theme in GameTheme.entries) {
            for (dark in listOf(false, true)) {
                val palette = theme.palette(dark)
                val label = "${theme.label} (${if (dark) "dark" else "light"})"
                assertTrue("$label foreground", colorContrast(palette.ink, palette.surface) >= 4.5)
                assertTrue("$label filled button", colorContrast(palette.onAccent, palette.accent) >= 4.5)
                assertTrue("$label emphasized matching digit", colorContrast(palette.matchingCellInk, palette.matchingCell) >= 4.5)
                assertTrue("$label matching outline", colorContrast(palette.accent, palette.relatedCell) >= 2.0)
                assertTrue("$label completed keypad digit", colorContrast(palette.muted, palette.completedKey) >= 4.5)
                assertTrue("$label selected notes", colorContrast(palette.ink, palette.selectedCell) >= 4.5)
                assertTrue("$label matching notes", colorContrast(palette.ink, palette.noteMarker) >= 4.5)
                assertTrue("$label ordinary notes", colorContrast(palette.muted, palette.relatedCell) >= 4.5)
                assertTrue("$label error notes", colorContrast(palette.error, palette.errorSurface) >= 4.5)
                assertTrue("$label active control", colorContrast(palette.accentInk, palette.selectedCell) >= 4.5)
                assertTrue("$label entered digit", colorContrast(palette.accentInk, palette.relatedCell) >= 4.5)
            }
        }
    }

}
