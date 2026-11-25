// Copyright (c) 2026 Kalam Shah
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.kalamcs.sudoku

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.ArrayAdapter
import android.widget.CheckedTextView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.EditText
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.AppCompatButton
import androidx.appcompat.widget.AppCompatImageButton
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

    fun pageHeading(title: String, onBack: () -> Unit) = FrameLayout(context).apply {
        setPadding(0, 0, 0, dp(3))
        addView(heading(title, 23).apply { gravity = Gravity.CENTER },
            FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER).apply {
                leftMargin = dp(52)
                rightMargin = dp(52)
            })
        val back = iconButton("Back", R.drawable.ic_back, quiet = true, onClick = onBack).apply {
            background = dashboardPress(shape(palette.page, 8))
        }
        addView(back,
            FrameLayout.LayoutParams(dp(48), dp(48), Gravity.START or Gravity.CENTER_VERTICAL))
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
            minHeight = dp(44)
            minimumHeight = dp(44)
            textSize = 12f
            letterSpacing = 0f
            includeFontPadding = false
            setPadding(dp(2), dp(2), dp(2), dp(2))
            setCompoundDrawablesRelative(null, this@Ui.icon(icon, if (shortcut) 21 else 22), null, null)
            compoundDrawablePadding = dp(3)
            styleGameAction(this, shortcut = shortcut)
        }

    fun dashboardButton(label: String, @DrawableRes icon: Int, onClick: () -> Unit) = row().apply {
        gravity = Gravity.CENTER
        minimumHeight = dp(48)
        setPadding(dp(6), dp(4), dp(6), dp(4))
        addView(ImageView(context).apply {
            setImageDrawable(this@Ui.icon(icon, 23))
            imageTintList = ColorStateList.valueOf(palette.accentInk)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(23), dp(23)))
        addView(text(label, 14, bold = true).apply {
            gravity = Gravity.CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = dp(7) })
        background = dashboardPress(dashboardSurface(12))
        elevation = dp(1).toFloat()
        isFocusable = true
        contentDescription = label
        accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = "android.widget.Button"
            }
        }
        setOnClickListener { onClick() }
    }

    fun icon(@DrawableRes resource: Int, size: Int) = AppCompatResources.getDrawable(context, resource)?.mutate()?.apply {
        setBounds(0, 0, dp(size), dp(size))
    }

    fun styleGameAction(button: AppCompatButton, selected: Boolean = false, shortcut: Boolean = false) {
        styleButton(button, when {
            selected -> palette.matchingCell
            shortcut -> palette.toolbarSurface
            else -> palette.surface
        },
            if (selected) palette.accentInk else palette.ink,
            if (selected || shortcut) 0 else palette.border, radius = 10)
        if (shortcut) button.background = menuSelector()
    }

    fun styleDigit(button: AppCompatButton, selected: Boolean, completed: Boolean) {
        val fill = when {
            completed -> palette.completedKey
            selected -> palette.accent
            else -> palette.toolbarSurface
        }
        val foreground = when {
            completed -> palette.completedKeyInk
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

    fun <T> segments(
        options: List<T>,
        selected: T,
        label: (T) -> String,
        enabled: Boolean = true,
        onChange: (T) -> Unit,
    ) = row().apply {
        background = shape(palette.toolbarSurface, 13, palette.border)
        setPadding(dp(1), dp(1), dp(1), dp(1))
        fun styleSegment(button: AppCompatButton, active: Boolean) {
            button.isSelected = active
            styleButton(button, if (active) palette.accent else palette.toolbarSurface,
                if (active) palette.onAccent else palette.ink, border = 0, radius = 12)
        }
        for ((index, option) in options.withIndex()) {
            equal(this, button(label(option)) {
                for (i in options.indices) styleSegment(getChildAt(i) as AppCompatButton, i == index)
                onChange(option)
            }.apply {
                textSize = 13f
                letterSpacing = 0f
                setPadding(dp(3), 0, dp(3), 0)
                isEnabled = enabled
                styleSegment(this, option == selected)
            })
        }
    }

    fun tintDialog(dialog: AlertDialog) {
        dialog.window?.setBackgroundDrawable(InsetDrawable(shape(palette.surface, palette.cardRadius), dp(8)))
        dialog.findViewById<TextView>(androidx.appcompat.R.id.alertTitle)?.setTextColor(palette.ink)
        dialog.findViewById<TextView>(android.R.id.message)?.setTextColor(palette.ink)
        dialog.listView?.selector = menuSelector()
        for (button in listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL)) {
            dialog.getButton(button)?.apply {
                isAllCaps = false
                setTextColor(palette.accentInk)
                background = menuSelector()
            }
        }
    }

    fun menuSelector() = RippleDrawable(
        ColorStateList.valueOf((palette.accent and 0x00ffffff) or 0x27000000),
        null, shape(palette.surface, 8),
    )

    fun menuItems(labels: List<String>, current: Int) =
        object : ArrayAdapter<String>(context, android.R.layout.simple_list_item_1, labels) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val selected = position == current
                return (convertView as? TextView ?: text("", 15)).apply {
                    text = labels[position]
                    setTextColor(if (selected) palette.accentInk else palette.ink)
                    setTypeface(typeface, if (selected) Typeface.BOLD else Typeface.NORMAL)
                    isSelected = selected
                    ViewCompat.setStateDescription(this, if (selected) "Current destination" else null)
                    minimumHeight = dp(44)
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(14), dp(6), dp(14), dp(6))
                    background = if (selected) shape(palette.selectedCell, 8) else null
                }
            }
        }

    fun choiceItems(labels: List<String>) = object : ArrayAdapter<String>(
        context, android.R.layout.select_dialog_singlechoice, labels,
    ) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            (super.getView(position, convertView, parent) as CheckedTextView).apply {
                setTextColor(palette.ink)
                checkMarkTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(palette.accentInk, palette.muted),
                )
            }
    }

    fun tintTextInput(input: EditText) {
        input.highlightColor = palette.selectedCell
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            input.textCursorDrawable = input.textCursorDrawable?.mutate()?.apply { setTint(palette.accentInk) }
            input.textSelectHandle?.mutate()?.apply { setTint(palette.accentInk) }?.let(input::setTextSelectHandle)
            input.textSelectHandleLeft?.mutate()?.apply { setTint(palette.accentInk) }?.let(input::setTextSelectHandleLeft)
            input.textSelectHandleRight?.mutate()?.apply { setTint(palette.accentInk) }?.let(input::setTextSelectHandleRight)
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
