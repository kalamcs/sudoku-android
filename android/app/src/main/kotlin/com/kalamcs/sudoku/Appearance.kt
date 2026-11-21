package com.kalamcs.sudoku

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
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

internal data class AppearanceSettings(
    val mode: AppearanceMode = AppearanceMode.LIGHT,
    val theme: GameTheme = GameTheme.FLUENT,
    val screenTimeout: ScreenTimeout = ScreenTimeout.SYSTEM,
) {
    fun palette(systemDark: Boolean) = theme.palette(mode.isDark(systemDark))

    companion object {
        fun fromKeys(mode: String?, theme: String?, screenTimeout: String? = null) = AppearanceSettings(
            mode = AppearanceMode.entries.firstOrNull { it.key == mode } ?: AppearanceMode.LIGHT,
            theme = GameTheme.entries.firstOrNull { it.key == theme } ?: GameTheme.FLUENT,
            screenTimeout = ScreenTimeout.entries.firstOrNull { it.key == screenTimeout } ?: ScreenTimeout.SYSTEM,
        )
    }
}

internal class AppearanceStore(context: Context) {
    private val preferences = context.getSharedPreferences("sudoku_appearance", Context.MODE_PRIVATE)

    fun load() = AppearanceSettings.fromKeys(
        preferences.getString("mode", null),
        preferences.getString("theme", null),
        preferences.getString("screenTimeout", null),
    )

    fun save(settings: AppearanceSettings) {
        preferences.edit()
            .putString("mode", settings.mode.key)
            .putString("theme", settings.theme.key)
            .putString("screenTimeout", settings.screenTimeout.key)
            .apply()
    }
}

internal enum class GameTheme(val key: String, private val title: String, val cardRadius: Int) {
    FLUENT("fluent", "Fluent 2", 12),
    NORD("nord", "Nord", 10),
    CATPPUCCIN("catppuccin", "Catppuccin", 18),
    MATERIAL("material", "Google Material", 24);

    fun label(dark: Boolean) =
        if (this == CATPPUCCIN) "Catppuccin ${if (dark) "Mocha" else "Latte"}" else title

    fun palette(dark: Boolean): GamePalette = when (this) {
        FLUENT -> if (dark) {
            colors(true, 0x171717, 0x242424, 0xf5f5f5, 0xc7c7c7, 0x686868, 0x77b7ec, 0x152537, 0xffa1a8)
        } else {
            colors(false, 0xf5f5f5, 0xffffff, 0x242424, 0x505050, 0xc7c7c7, 0x0f6cbd, 0xffffff, 0xa4262c)
        }
        NORD -> if (dark) {
            colors(true, 0x2e3440, 0x3b4252, 0xeceff4, 0xd8dee9, 0x718098, 0xb6e0eb, 0x222733, 0xffc2c7)
        } else {
            colors(false, 0xe5e9f0, 0xf8fafc, 0x2e3440, 0x4c566a, 0xb7c2d4, 0x386782, 0xffffff, 0x922a3c)
        }
        CATPPUCCIN -> if (dark) {
            colors(true, 0x181825, 0x1e1e2e, 0xcdd6f4, 0xbac2de, 0x666a84, 0xcba6f7, 0x1e1e2e, 0xf38ba8)
        } else {
            colors(false, 0xe6e9ef, 0xeff1f5, 0x4c4f69, 0x5c5f77, 0xacb0be, 0x7427c6, 0xffffff, 0xa51c39)
        }
        MATERIAL -> if (dark) {
            colors(true, 0x14121c, 0x201e2b, 0xe8e3f5, 0xcbc3db, 0x77718a, 0xc9bfff, 0x2f2663, 0xffb4ab)
        } else {
            colors(false, 0xf7f4fd, 0xfffbff, 0x231e35, 0x514b61, 0xc5bed2, 0x494197, 0xffffff, 0xa12328)
        }
    }

    private fun colors(
        dark: Boolean,
        page: Int,
        surface: Int,
        ink: Int,
        muted: Int,
        border: Int,
        accent: Int,
        onAccent: Int,
        error: Int,
    ) = GamePalette(
        dark, cardRadius, opaque(page), opaque(surface), opaque(ink), opaque(muted),
        opaque(border), opaque(accent), opaque(onAccent), opaque(error),
    )
}

internal data class GamePalette(
    val dark: Boolean,
    val cardRadius: Int,
    val page: Int,
    val surface: Int,
    val ink: Int,
    val muted: Int,
    val border: Int,
    val accent: Int,
    val onAccent: Int,
    val error: Int,
) {
    val relatedCell = blend(surface, accent, if (dark) .07f else .045f)
    val selectedCell = blend(surface, accent, if (dark) .18f else .12f)
    val matchingCell = blend(surface, accent, .33f)
    val matchingCellInk = opaque(if (dark) 0xffffff else 0x202020)
    val noteMarker = blend(surface, accent, if (dark) .27f else .24f)
    val completedKey = blend(surface, ink, if (dark) .17f else .13f)
    val errorSurface = blend(surface, error, if (dark) .16f else .1f)
    val gridLine = blend(surface, ink, if (dark) .28f else .26f)
}

private fun opaque(rgb: Int) = rgb or 0xff000000.toInt()

internal fun blend(base: Int, overlay: Int, fraction: Float): Int {
    fun channel(shift: Int): Int {
        val a = (base shr shift) and 0xff
        val b = (overlay shr shift) and 0xff
        return (a + (b - a) * fraction).roundToInt() shl shift
    }
    return opaque(channel(16) or channel(8) or channel(0))
}
