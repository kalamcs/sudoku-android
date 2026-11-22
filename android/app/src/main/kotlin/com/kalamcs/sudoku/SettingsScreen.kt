package com.kalamcs.sudoku

import android.content.Context
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.accessibility.AccessibilityNodeInfo.CHECKED_STATE_FALSE
import android.view.accessibility.AccessibilityNodeInfo.CHECKED_STATE_TRUE
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.annotation.DrawableRes
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat

internal class SettingsScreen(private val context: Context, private val ui: Ui) {
    fun settings(
        page: LinearLayout,
        appearance: AppearanceSettings,
        back: () -> Unit,
        setMode: (AppearanceMode) -> Unit,
        openPalette: () -> Unit,
        selectTimeout: () -> Unit,
        openGuide: () -> Unit,
        openFeedback: () -> Unit,
        openAbout: () -> Unit,
    ) {
        ui.add(page, ui.pageHeading("Settings", back))
        val looks = section("Appearance", R.drawable.ic_palette)
        val modes = settingRow("Theme", R.drawable.ic_theme)
        modes.addView(ui.segments(AppearanceMode.entries, appearance.mode, AppearanceMode::label, onChange = setMode),
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 2.2f))
        ui.add(looks, modes)
        val palettes = settingRow("Colour Palette", R.drawable.ic_palette)
        GameTheme.entries.forEach { theme ->
            palettes.addView(chip(theme.palette(ui.palette.dark).accent, theme == appearance.theme),
                LinearLayout.LayoutParams(0, ui.dp(21), .31f).apply { leftMargin = ui.dp(3) })
        }
        addChevron(palettes)
        makeAction(palettes, "Colour Palette, ${appearance.theme.label}", openPalette)
        ui.add(looks, palettes)
        ui.add(page, looks, 8)

        val gameplay = section("Gameplay", R.drawable.ic_gamepad)
        val timeout = settingRow("Screen Timeout", R.drawable.ic_screen_timeout, "(for dimming)")
        val timeoutLabel = if (appearance.screenTimeout == ScreenTimeout.SYSTEM) "Use System" else appearance.screenTimeout.label
        timeout.addView(ui.button(timeoutLabel, onClick = selectTimeout).apply {
            letterSpacing = 0f
            textSize = 12f
            setCompoundDrawablesRelative(null, null, ui.icon(R.drawable.ic_chevron_down, 15), null)
            compoundDrawablePadding = ui.dp(5)
            background = ui.dashboardPress(ui.dashboardSurface(10))
            maxWidth = ui.dp(148)
            contentDescription = "Screen Timeout, $timeoutLabel"
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        ui.add(gameplay, timeout)
        ui.add(page, gameplay, 12)

        val learn = section("Learn", R.drawable.ic_guide)
        val guide = settingRow("How to Play", R.drawable.ic_guide, "Rules and solving strategies")
        addChevron(guide)
        makeAction(guide, "How to Play", openGuide)
        ui.add(learn, guide)
        ui.add(page, learn, 12)

        val about = section("About", R.drawable.ic_info)
        val feedback = settingRow("Send Feedback", R.drawable.ic_feedback, "Share suggestions, report issues, or ask questions")
        addChevron(feedback)
        makeAction(feedback, "Send Feedback", openFeedback)
        ui.add(about, feedback)
        val details = settingRow("About", R.drawable.ic_info, "App version and open-source licenses")
        addChevron(details)
        makeAction(details, "About Sudoku", openAbout)
        ui.add(about, details)
        ui.add(page, about, 12)
    }

    fun about(page: LinearLayout, back: () -> Unit, licenses: () -> Unit) {
        ui.add(page, ui.pageHeading("About", back))
        val app = section("Sudoku", R.drawable.ic_auto_notes)
        ui.add(app, settingRow("Version ${BuildConfig.VERSION_NAME}", R.drawable.ic_info))
        val notices = settingRow("Open-source licenses", R.drawable.ic_guide)
        addChevron(notices)
        makeAction(notices, "Open-source licenses", licenses)
        ui.add(app, notices)
        ui.add(page, app, 8)
    }

    fun palette(page: LinearLayout, selected: GameTheme, back: () -> Unit, select: (GameTheme) -> Unit) {
        ui.add(page, ui.pageHeading("Colour Palette", back))
        ui.add(page, ui.text(
            "Compare each palette in Light and Dark with a selected number and matching candidate notes.",
            14, ui.palette.muted,
        ).apply { gravity = Gravity.CENTER }, 3)
        for (theme in GameTheme.entries) ui.add(page, paletteRow(theme, theme == selected) { select(theme) }, 8)
    }

    private fun paletteRow(theme: GameTheme, selected: Boolean, choose: () -> Unit) = ui.row().apply {
        background = ui.dashboardPress(if (selected) ui.shape(ui.palette.selectedCell, 14, ui.palette.accent)
            else ui.dashboardSurface(14, .035f))
        elevation = ui.dp(1).toFloat()
        setPadding(ui.dp(7), ui.dp(8), ui.dp(7), ui.dp(8))
        val indicator = ImageView(context).apply {
            setImageDrawable(ui.icon(if (selected) R.drawable.ic_radio_selected else R.drawable.ic_radio_empty, 18))
            imageTintList = ColorStateList.valueOf(if (selected) ui.palette.accentInk else ui.palette.muted)
        }
        addView(indicator, LinearLayout.LayoutParams(ui.dp(19), ui.dp(22)))
        val name = ui.column()
        ui.add(name, ui.heading(theme.label, 14))
        val light = theme.palette(dark = false)
        val swatches = ui.row()
        listOf(light.accent, light.noteMarker, light.relatedCell, light.surface).forEach { color ->
            swatches.addView(chip(color), LinearLayout.LayoutParams(ui.dp(14), ui.dp(14)).apply { rightMargin = ui.dp(3) })
        }
        ui.add(name, swatches, 3)
        addView(name, LinearLayout.LayoutParams(0, WRAP_CONTENT, .89f).apply {
            leftMargin = ui.dp(5)
            rightMargin = ui.dp(5)
        })
        ui.equal(this, preview(light))
        ui.equal(this, preview(theme.palette(dark = true)), 5)
        isSelected = selected
        makeAction(this, theme.label, choose)
        ViewCompat.setAccessibilityDelegate(this, object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = "android.widget.RadioButton"
                info.isCheckable = true
                info.setChecked(if (selected) CHECKED_STATE_TRUE else CHECKED_STATE_FALSE)
            }
        })
    }

    private fun preview(palette: GamePalette) = ui.column().apply {
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(ui.dp(3), ui.dp(3), ui.dp(3), ui.dp(3))
        background = ui.shape(palette.selectedCell, 8)
        val icon = if (palette.dark) R.drawable.ic_moon else R.drawable.ic_sun
        val label = ui.text(if (palette.dark) "Dark" else "Light", 11, palette.ink, bold = true).apply {
            background = ui.shape(palette.surface, 10, palette.gridLine)
            setPadding(ui.dp(6), ui.dp(1), ui.dp(6), ui.dp(1))
            setCompoundDrawablesRelative(ui.icon(icon, 12), null, null, null)
            compoundDrawableTintList = ColorStateList.valueOf(palette.ink)
            compoundDrawablePadding = ui.dp(3)
        }
        ui.add(this, label, width = WRAP_CONTENT)
        ui.add(this, BoardSampleView(context, palette, BoardSample.PALETTE), 3)
    }

    private fun section(title: String, @DrawableRes icon: Int) = ui.column().apply {
        background = ui.dashboardSurface(13, .035f)
        clipToOutline = true
        elevation = ui.dp(2).toFloat()
        val heading = settingRow(title, icon, heading = true).apply {
            setBackgroundColor(ui.palette.toolbarSurface)
            minimumHeight = ui.dp(42)
        }
        ui.add(this, heading)
    }

    private fun settingRow(label: String, @DrawableRes icon: Int, subtitle: String? = null, heading: Boolean = false) =
        ui.row().apply {
            minimumHeight = ui.dp(57)
            setPadding(ui.dp(12), ui.dp(4), ui.dp(12), ui.dp(4))
            addView(iconView(icon, ui.palette.accentInk), LinearLayout.LayoutParams(ui.dp(21), ui.dp(21)))
            val words = ui.column()
            ui.add(words, if (heading) ui.heading(label, 17) else ui.text(label, 14, bold = true))
            if (subtitle != null) ui.add(words, ui.text(subtitle, 12, ui.palette.muted))
            addView(words, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
                leftMargin = ui.dp(9)
                rightMargin = ui.dp(6)
            })
        }

    private fun chip(color: Int, selected: Boolean = false) = ImageView(context).apply {
        background = ui.shape(color, 5, blend(color, ui.palette.ink, .12f))
        if (selected) {
            setImageDrawable(ui.icon(R.drawable.ic_check, 12))
            imageTintList = ColorStateList.valueOf(
                if (colorContrast(color, 0xff000000.toInt()) > colorContrast(color, 0xffffffff.toInt()))
                    0xff000000.toInt() else 0xffffffff.toInt(),
            )
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
    }

    private fun addChevron(row: LinearLayout) {
        row.addView(iconView(R.drawable.ic_chevron_right, ui.palette.ink),
            LinearLayout.LayoutParams(ui.dp(17), ui.dp(17)).apply { leftMargin = ui.dp(4) })
    }

    private fun iconView(@DrawableRes icon: Int, color: Int) = ImageView(context).apply {
        setImageDrawable(ui.icon(icon, 21))
        imageTintList = ColorStateList.valueOf(color)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun makeAction(row: LinearLayout, description: String, action: () -> Unit) {
        row.isFocusable = true
        row.contentDescription = description
        ViewCompat.setScreenReaderFocusable(row, true)
        for (index in 0 until row.childCount) {
            row.getChildAt(index).importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        row.setOnClickListener { action() }
    }
}
