// Copyright (c) 2026 Kalam Shah
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.kalamcs.sudoku

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import kotlin.math.roundToInt

internal class HomePanels(
    context: Context,
    private val ui: Ui,
    private val puzzles: View,
    private val stats: View,
    private val actions: View,
    private val hasContinueCard: Boolean,
) : ViewGroup(context) {
    private val gutter = ui.dp(12)
    private var sideBySide = false

    init {
        listOf(puzzles, stats, actions).forEach { addView(it, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)) }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val configuration = resources.configuration
        val minimum = ui.dp(550) * maxOf(1f, configuration.fontScale)
        sideBySide = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE && width >= minimum
        val leftWidth = if (sideBySide) ((width - gutter) * .52f).roundToInt() else width
        val rightWidth = if (sideBySide) width - gutter - leftWidth else width
        fun measure(view: View, available: Int) = view.measure(
            MeasureSpec.makeMeasureSpec(available, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
        )
        measure(puzzles, leftWidth)
        measure(stats, rightWidth)
        measure(actions, leftWidth)
        val leftHeight = puzzles.measuredHeight + gutter + actions.measuredHeight
        val height = if (sideBySide) maxOf(leftHeight, stats.measuredHeight) else leftHeight + ui.dp(18) + stats.measuredHeight
        setMeasuredDimension(width, resolveSize(height, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        fun place(view: View, x: Int, y: Int) = view.layout(x, y, x + view.measuredWidth, y + view.measuredHeight)
        place(puzzles, 0, 0)
        if (sideBySide) {
            place(stats, puzzles.measuredWidth + gutter, 0)
            val belowPuzzles = puzzles.bottom + gutter
            val actionsTop = if (hasContinueCard) maxOf(belowPuzzles, stats.bottom - actions.measuredHeight) else belowPuzzles
            place(actions, 0, actionsTop)
        } else {
            place(stats, 0, puzzles.measuredHeight + ui.dp(18))
            place(actions, 0, stats.bottom + gutter)
        }
    }
}

internal class HomeWaves(context: Context, private val palette: GamePalette) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private var farWave: Shader? = null
    private var nearWave: Shader? = null

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (h == 0) return
        val color = palette.accent and 0xffffff
        farWave = LinearGradient(0f, 0f, 0f, h.toFloat(), color or 0x42000000, color, Shader.TileMode.CLAMP)
        nearWave = LinearGradient(0f, 0f, 0f, h.toFloat(), color or 0x59000000, color, Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        path.rewind()
        path.moveTo(0f, .48f * h)
        path.cubicTo(.21f * w, .08f * h, .33f * w, .83f * h, .51f * w, .53f * h)
        path.cubicTo(.68f * w, .24f * h, .74f * w, -.10f * h, w, .43f * h)
        path.lineTo(w, h)
        path.lineTo(0f, h)
        path.close()
        paint.shader = farWave
        canvas.drawPath(path, paint)

        path.rewind()
        path.moveTo(0f, .72f * h)
        path.cubicTo(.17f * w, .15f * h, .30f * w, .30f * h, .45f * w, .65f * h)
        path.cubicTo(.70f * w, 1.08f * h, .82f * w, .28f * h, w, .34f * h)
        path.lineTo(w, h)
        path.lineTo(0f, h)
        path.close()
        paint.shader = nearWave
        canvas.drawPath(path, paint)
    }
}

internal class SavedPuzzlePreview(context: Context, private val palette: GamePalette, private val game: Game) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val outline = Path()

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        outline.rewind()
        val side = minOf(w, h).toFloat()
        outline.addRoundRect(0f, 0f, side, side, 9 * density, 9 * density, Path.Direction.CW)
    }

    override fun onDraw(canvas: Canvas) {
        val side = minOf(width, height).toFloat()
        val cellSide = side / 3f
        val start = game.selected / 27 * 27 + game.selected % 9 / 3 * 3
        val saved = canvas.save()
        canvas.clipPath(outline)
        canvas.drawColor(palette.surface)
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textSize = cellSide * .70f
        for (row in 0..2) for (column in 0..2) {
            val index = start + row * 9 + column
            val left = column * cellSide
            val top = row * cellSide
            if (index == game.selected) {
                paint.color = palette.matchingCell
                canvas.drawRect(left, top, left + cellSide, top + cellSide, paint)
            }
            val digit = game.values[index]
            if (digit != 0) {
                paint.color = if (game.puzzle.givens[index] != 0) palette.givenDigit else palette.enteredDigit
                val baseline = top + cellSide / 2 - (paint.ascent() + paint.descent()) / 2
                canvas.drawText(digit.toString(), left + cellSide / 2, baseline, paint)
            }
        }
        paint.color = palette.gridLine
        paint.strokeWidth = density
        for (index in 1..2) {
            val at = index * cellSide
            canvas.drawLine(at, 0f, at, side, paint)
            canvas.drawLine(0f, at, side, at, paint)
        }
        canvas.restoreToCount(saved)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.3f * density
        paint.color = palette.border
        val inset = paint.strokeWidth / 2
        canvas.drawRoundRect(inset, inset, side - inset, side - inset, 9 * density, 9 * density, paint)
        paint.style = Paint.Style.FILL
    }
}
