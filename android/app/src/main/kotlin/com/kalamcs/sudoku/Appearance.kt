// Copyright (c) 2026 Kalam Shah
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.kalamcs.sudoku

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import kotlin.math.pow
import kotlin.math.roundToInt

internal enum class AppearanceMode(val key: String, val label: String, val nightMode: Int) {
    LIGHT("light", "Light", AppCompatDelegate.MODE_NIGHT_NO),
    DARK("dark", "Dark", AppCompatDelegate.MODE_NIGHT_YES),
    SYSTEM("system", "System", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);

    fun isDark(systemDark: Boolean) = when (this) {
        LIGHT -> false
        DARK -> true
        SYSTEM -> systemDark
    }
}

internal enum class ScreenTimeout(val key: String, val label: String, val holdMillis: Long? = null) {
    SYSTEM("system", "Use Android setting"),
    TWO_MINUTES("two_minutes", "At least 2 minutes", 120_000),
    THREE_MINUTES("three_minutes", "At least 3 minutes", 180_000),
    WHILE_PLAYING("while_playing", "Keep on while playing");
}

internal enum class KeypadLayout(val key: String, val label: String) {
    AUTO("auto", "Auto"),
    GRID("grid", "3×3"),
    ROW("row", "1×9"),
}

internal data class AppearanceSettings(
    val mode: AppearanceMode = AppearanceMode.LIGHT,
    val theme: GameTheme = GameTheme.OCEAN,
    val screenTimeout: ScreenTimeout = ScreenTimeout.SYSTEM,
    val keypadLayout: KeypadLayout = KeypadLayout.AUTO,
    val entryOrder: EntryOrder = EntryOrder.CELL_FIRST,
) {
    fun palette(systemDark: Boolean) = theme.palette(mode.isDark(systemDark))

    companion object {
        fun fromKeys(
            mode: String?, theme: String?, screenTimeout: String? = null, keypadLayout: String? = null, entryOrder: String? = null,
        ) = AppearanceSettings(
            mode = AppearanceMode.entries.firstOrNull { it.key == mode } ?: AppearanceMode.LIGHT,
            theme = GameTheme.entries.firstOrNull { it.key == theme } ?: GameTheme.OCEAN,
            screenTimeout = ScreenTimeout.entries.firstOrNull { it.key == screenTimeout } ?: ScreenTimeout.SYSTEM,
            keypadLayout = KeypadLayout.entries.firstOrNull { it.key == keypadLayout } ?: KeypadLayout.AUTO,
            entryOrder = EntryOrder.entries.firstOrNull { it.key == entryOrder } ?: EntryOrder.CELL_FIRST,
        )
    }
}

internal class AppearanceStore(context: Context) {
    private val preferences = context.getSharedPreferences("sudoku_appearance", Context.MODE_PRIVATE)

    fun load() = AppearanceSettings.fromKeys(
        preferences.getString("mode", null),
        preferences.getString("theme", null),
        preferences.getString("screenTimeout", null),
        preferences.getString("keypadLayout", null),
        preferences.getString("entryOrder", null),
    )

    fun save(settings: AppearanceSettings) {
        preferences.edit()
            .putString("mode", settings.mode.key)
            .putString("theme", settings.theme.key)
            .putString("screenTimeout", settings.screenTimeout.key)
            .putString("keypadLayout", settings.keypadLayout.key)
            .putString("entryOrder", settings.entryOrder.key)
            .apply()
    }
}

internal enum class GameTheme(val key: String, val label: String, private val light: GamePalette, private val dark: GamePalette) {
    OCEAN("ocean", "Ocean Blue",
        colors(false, 0xf7fafe, 0xffffff, 0x2563eb, 0xdbeafe, 0x1e293b),
        colors(true, 0x0b1220, 0x172338, 0x60a5fa, 0x233f62, 0xf1f5f9)),
    FOREST("forest", "Forest Green",
        colors(false, 0xf6faf7, 0xffffff, 0x23815c, 0xddf3e5, 0x20352c),
        colors(true, 0x0d1915, 0x192b23, 0x6ed5a1, 0x244c3b, 0xedf8f1)),
    PURPLE("purple", "Royal Purple",
        colors(false, 0xfaf8ff, 0xffffff, 0x7c3aed, 0xede3ff, 0x302441),
        colors(true, 0x171121, 0x251c34, 0xb69aff, 0x493467, 0xf5f0ff)),
    SAND("sand", "Warm Sand",
        colors(false, 0xfcf8f1, 0xfffdf8, 0xb66a28, 0xf6e6cb, 0x47392c),
        colors(true, 0x201a16, 0x30261f, 0xe6ad6a, 0x55402c, 0xfaeedd)),
    ROSE("rose", "Rose",
        colors(false, 0xfff8fa, 0xffffff, 0xc44278, 0xfce1eb, 0x452838),
        colors(true, 0x21141c, 0x33202c, 0xf18cb5, 0x593047, 0xfff0f6)),
    SLATE("slate", "Slate",
        colors(false, 0xf5f7f9, 0xffffff, 0x475569, 0xe2e8f0, 0x1e293b),
        colors(true, 0x101419, 0x1e252e, 0xa3b3c7, 0x344252, 0xf1f5f9));

    fun palette(dark: Boolean) = if (dark) this.dark else light
}

internal class GamePalette(
    val dark: Boolean,
    val page: Int,
    val surface: Int,
    val accent: Int,
    val relatedCell: Int,
    val ink: Int,
) {
    val cardRadius = 12
    val muted = blend(ink, surface, .20f)
    val border = blend(surface, ink, if (dark) .42f else .20f)
    val onAccent = highContrastInk(accent)
    val toolbarSurface = blend(surface, accent, if (dark) .08f else .05f)
    val selectedCell = blend(surface, accent, if (dark) .18f else .12f)
    val matchingCell = blend(relatedCell, accent, .22f)
    val accentInk = (0..10).map { blend(accent, ink, it / 10f) }.first { color ->
        listOf(page, surface, relatedCell, selectedCell, matchingCell).all { colorContrast(color, it) >= 4.5 }
    }
    val givenDigit = ink
    val enteredDigit = accentInk
    val pencilMark = muted
    val noteMarker = blend(relatedCell, accent, if (dark) .24f else .32f)
    val completedKey = blend(surface, ink, if (dark) .17f else .13f)
    val completedKeyInk = (5 downTo 0).map { blend(ink, completedKey, it / 10f) }
        .first { colorContrast(it, completedKey) >= 3 }
    val error = opaque(if (dark) 0xffa6b4 else 0xa12241)
    val errorSurface = blend(surface, error, if (dark) .16f else .1f)
    val successSurface = opaque(if (dark) 0x244c3b else 0xddf3e5)
    val successInk = opaque(if (dark) 0x9ae7bd else 0x20583f)
    val gridLine = blend(surface, ink, if (dark) .28f else .26f)
    val boxBorder = blend(surface, ink, .89f)

    fun difficultyAccent(difficulty: Difficulty) = opaque(when (difficulty) {
        Difficulty.EASY -> if (dark) 0x52dac1 else 0x22b9a0
        Difficulty.MEDIUM -> if (dark) 0x65b4ff else 0x258cf0
        Difficulty.HARD -> if (dark) 0xb289ff else 0x8956eb
        Difficulty.EXTREME -> if (dark) 0xff89b8 else 0xe4508c
    })
}

private fun colors(dark: Boolean, page: Int, board: Int, accent: Int, related: Int, ink: Int) =
    GamePalette(dark, opaque(page), opaque(board), opaque(accent), opaque(related), opaque(ink))

private fun opaque(rgb: Int) = rgb or 0xff000000.toInt()

private fun highContrastInk(background: Int) = listOf(opaque(0), opaque(0xffffff)).maxBy { colorContrast(it, background) }

internal fun colorContrast(first: Int, second: Int): Double {
    fun luminance(color: Int): Double {
        fun channel(shift: Int): Double {
            val value = ((color shr shift) and 0xff) / 255.0
            return if (value <= .04045) value / 12.92 else ((value + .055) / 1.055).pow(2.4)
        }
        return .2126 * channel(16) + .7152 * channel(8) + .0722 * channel(0)
    }
    val a = luminance(first)
    val b = luminance(second)
    return (maxOf(a, b) + .05) / (minOf(a, b) + .05)
}

internal fun blend(base: Int, overlay: Int, fraction: Float): Int {
    fun channel(shift: Int): Int {
        val a = (base shr shift) and 0xff
        val b = (overlay shr shift) and 0xff
        return (a + (b - a) * fraction).roundToInt() shl shift
    }
    return opaque(channel(16) or channel(8) or channel(0))
}
