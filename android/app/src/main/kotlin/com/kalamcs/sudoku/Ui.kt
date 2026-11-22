package com.kalamcs.sudoku

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.AppCompatButton
import androidx.appcompat.widget.AppCompatImageButton
import androidx.appcompat.widget.AppCompatRadioButton
import androidx.core.view.ViewCompat
import kotlin.math.roundToInt

internal class Ui(private val context: Context) {
    var palette = GameTheme.OCEAN.palette(dark = false)

    fun dp(value: Int) = (value * context.resources.displayMetrics.density).roundToInt()

    fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    fun row() = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    fun shape(color: Int, radius: Int, stroke: Int = 0) = GradientDrawable().apply {
        cornerRadius = dp(radius).toFloat()
        setColor(color)
        if (stroke != 0) setStroke(dp(1), stroke)
    }

    fun dashboardSurface(radius: Int, tint: Float = .07f) = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(palette.surface, blend(palette.surface, palette.accent, tint)),
    ).apply {
        cornerRadius = dp(radius).toFloat()
        setStroke(dp(1), blend(palette.border, palette.surface, .28f))
    }

    fun dashboardPress(surface: Drawable) = RippleDrawable(
        ColorStateList.valueOf((palette.accent and 0x00ffffff) or 0x20000000), surface, null,
    )

    fun text(content: String, size: Int = 16, color: Int = palette.ink, bold: Boolean = false) =
        TextView(context).apply {
            text = content
            textSize = size.toFloat()
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setLineSpacing(dp(2).toFloat(), 1f)
        }

    fun heading(content: String, size: Int = 23) = text(content, size, bold = true).apply {
        ViewCompat.setAccessibilityHeading(this, true)
    }

    fun button(content: String, primary: Boolean = false, @DrawableRes icon: Int = 0, onClick: () -> Unit) =
        AppCompatButton(context).apply {
            this.text = content
            isAllCaps = false
            textSize = 14f
            minWidth = 0
            minimumWidth = 0
            minHeight = dp(48)
            minimumHeight = dp(48)
            stateListAnimator = null
            setPadding(dp(8), dp(4), dp(8), dp(4))
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            if (icon != 0) {
                setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0)
                compoundDrawablePadding = dp(4)
            }
            styleButton(this, if (primary) palette.accent else palette.surface,
                if (primary) palette.onAccent else palette.ink, if (primary) 0 else palette.border)
            setOnClickListener { onClick() }
        }

    fun gameAction(content: String, @DrawableRes icon: Int, shortcut: Boolean = false, onClick: () -> Unit) =
        button(content, onClick = onClick).apply {
            textSize = 12f
            letterSpacing = 0f
            includeFontPadding = false
            setPadding(dp(2), dp(5), dp(2), dp(5))
            setCompoundDrawablesRelative(null, this@Ui.icon(icon, if (shortcut) 21 else 23), null, null)
            compoundDrawablePadding = dp(4)
            styleGameAction(this, shortcut = shortcut)
        }

    fun icon(@DrawableRes resource: Int, size: Int) = AppCompatResources.getDrawable(context, resource)?.mutate()?.apply {
        setBounds(0, 0, dp(size), dp(size))
    }

    fun styleGameAction(button: AppCompatButton, selected: Boolean = false, shortcut: Boolean = false) {
        styleButton(button, if (selected) palette.matchingCell else palette.toolbarSurface,
            if (selected) palette.accentInk else palette.ink,
            if (selected || shortcut) 0 else palette.border, radius = 10)
    }

    fun styleDigit(button: AppCompatButton, selected: Boolean, completed: Boolean) {
        val fill = when {
            completed -> palette.completedKey
            selected -> palette.accent
            else -> palette.toolbarSurface
        }
        val foreground = when {
            completed -> palette.muted
            selected -> palette.onAccent
            else -> palette.ink
        }
        styleButton(button, fill, foreground, if (selected) 0 else palette.border, radius = 9, keepDisabledColor = completed)
    }

    private fun styleButton(
        button: AppCompatButton,
        fill: Int,
        foreground: Int,
        border: Int,
        radius: Int = 4,
        keepDisabledColor: Boolean = false,
    ) {
        button.background = buttonBackground(fill, border, foreground, radius)
        val colors = if (keepDisabledColor) ColorStateList.valueOf(foreground) else ColorStateList(
            arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(blend(foreground, fill, .44f), foreground),
        )
        button.setTextColor(colors)
        button.compoundDrawableTintList = colors
    }

    fun iconButton(label: String, @DrawableRes icon: Int, quiet: Boolean = false, onClick: () -> Unit) =
        AppCompatImageButton(context).apply {
            val fill = if (quiet) palette.toolbarSurface else palette.surface
            contentDescription = label
            setImageResource(icon)
            imageTintList = ColorStateList(
                arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
                intArrayOf(blend(palette.ink, fill, .55f), palette.ink),
            )
            minimumWidth = dp(48)
            minimumHeight = dp(48)
            background = buttonBackground(fill, if (quiet) 0 else palette.border, palette.ink)
            setOnClickListener { onClick() }
        }

    private fun buttonBackground(fill: Int, border: Int, foreground: Int, radius: Int = 4) = RippleDrawable(
        ColorStateList.valueOf((foreground and 0x00ffffff) or 0x23000000),
        shape(fill, radius, border),
        null,
    )

    fun <T> choices(
        options: List<T>,
        selected: T,
        label: (T) -> String,
        horizontal: Boolean = false,
        onChange: (T) -> Unit,
    ) = RadioGroup(context).apply {
        orientation = if (horizontal) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        for (option in options) {
            val radio = AppCompatRadioButton(context).apply {
                id = View.generateViewId()
                text = label(option)
                minHeight = dp(48)
                setTextColor(palette.ink)
                buttonTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(palette.accentInk, palette.muted),
                )
                setOnClickListener { onChange(option) }
            }
            addView(radio, RadioGroup.LayoutParams(if (horizontal) 0 else MATCH_PARENT, WRAP_CONTENT, if (horizontal) 1f else 0f))
            if (selected == option) check(radio.id)
        }
    }

    fun tintDialog(dialog: AlertDialog) {
        dialog.window?.setBackgroundDrawable(InsetDrawable(shape(palette.surface, palette.cardRadius), dp(8)))
        dialog.findViewById<TextView>(androidx.appcompat.R.id.alertTitle)?.setTextColor(palette.ink)
        dialog.findViewById<TextView>(android.R.id.message)?.setTextColor(palette.ink)
        for (button in listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL)) {
            dialog.getButton(button)?.apply {
                isAllCaps = false
                setTextColor(palette.accentInk)
            }
        }
    }

    fun card(padding: Int = 16) = column().apply {
        background = shape(palette.surface, palette.cardRadius)
        setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
    }

    fun add(column: LinearLayout, view: View, gap: Int = 0, width: Int = MATCH_PARENT) {
        column.addView(view, LinearLayout.LayoutParams(width, WRAP_CONTENT).apply { topMargin = dp(gap) })
    }

    fun equal(row: LinearLayout, view: View, gap: Int = 0) {
        row.addView(view, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(gap) })
    }
}
