// Copyright (c) 2026 Kalam Shah
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.kalamcs.sudoku

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import androidx.appcompat.widget.AppCompatButton
import androidx.core.view.ViewCompat
import kotlin.math.ceil
import kotlin.math.roundToInt

internal class DigitKey(context: Context, private val palette: GamePalette, digit: Int) : AppCompatButton(context) {
    private val marker = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11f, resources.displayMetrics)
        strokeWidth = dp(1.5f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val check = Path()
    private var remaining = -1

    var stackedCount = false
        set(value) {
            if (field == value) return
            field = value
            updatePadding()
        }

    init {
        text = digit.toString()
        textSize = 23f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        isAllCaps = false
        gravity = Gravity.CENTER
        includeFontPadding = false
        stateListAnimator = null
        minWidth = 0
        minimumWidth = 0
        minHeight = dp(44f).roundToInt()
        minimumHeight = minHeight
        updatePadding()
    }

    fun showRemaining(count: Int) {
        if (remaining == count) return
        remaining = count
        ViewCompat.setStateDescription(this, when (count) {
            0 -> "All nine placed"
            1 -> "1 placement remaining"
            else -> "$count placements remaining"
        })
        invalidate()
    }

    private fun dp(value: Float) = value * resources.displayMetrics.density

    private fun updatePadding() {
        val inset = dp(3f).roundToInt()
        val countSpace = if (stackedCount) ceil(marker.descent() - marker.ascent()).toInt() else 0
        setPadding(0, inset, 0, inset + countSpace)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (remaining < 0) return
        val inset = dp(5f)
        val completed = remaining == 0
        marker.color = when {
            completed -> currentTextColor
            isSelected -> palette.onAccent
            else -> palette.muted
        }
        val baseline = if (stackedCount) height - dp(3f) - marker.descent() else dp(3f) - marker.ascent()
        val right = if (stackedCount) width / 2f else width - inset
        if (completed) {
            val size = marker.textSize * .8f
            val x = if (stackedCount) right - size / 2 else right - size
            val y = baseline - size * .4f
            check.rewind()
            check.moveTo(x, y)
            check.lineTo(x + size * .35f, y + size * .35f)
            check.lineTo(x + size, y - size * .35f)
            marker.style = Paint.Style.STROKE
            canvas.drawPath(check, marker)
        } else {
            marker.style = Paint.Style.FILL
            marker.textAlign = if (stackedCount) Paint.Align.CENTER else Paint.Align.RIGHT
            canvas.drawText(remaining.toString(), right, baseline, marker)
        }
    }
}
