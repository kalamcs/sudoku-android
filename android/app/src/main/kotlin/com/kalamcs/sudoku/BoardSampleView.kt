// Copyright (c) 2026 Kalam Shah
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.kalamcs.sudoku

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.view.View
import kotlin.math.roundToInt

private const val ELLIPSIS = "…"

internal data class SampleCell(val value: String = "", val notes: List<Int> = emptyList())

internal enum class BoardSample(
    val columns: Int,
    val cells: List<SampleCell>,
    val selected: Int = -1,
    val match: Int = 0,
    val entered: Set<Int> = emptySet(),
) {
    PALETTE(
        5,
        listOf(
            SampleCell("8"), SampleCell("3"), SampleCell(notes = listOf(1, 2, 6)), SampleCell("6"), SampleCell("5"),
            SampleCell(notes = listOf(1, 2, 4)), SampleCell("6"), SampleCell("7"), SampleCell(notes = listOf(1, 4, 6)), SampleCell("8"),
        ),
        selected = 3,
        match = 6,
    ),
    ROW(5, listOf("1", "4", "7", "9", ELLIPSIS).map { SampleCell(it) }, entered = setOf(0)),
    COLUMN(1, listOf("3", "6", "1", ELLIPSIS).map { SampleCell(it) }, entered = setOf(1)),
    BOX(3, listOf("8", "3", "5", "4", "9", "6", "1", "7", "2").map { SampleCell(it) }, entered = setOf(6, 8));

    val rows = cells.size / columns
}

internal class BoardSampleView(
    context: Context,
    private val palette: GamePalette,
    private val sample: BoardSample,
) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val outline = Path()
    private val radius = if (sample == BoardSample.PALETTE) 6 * density else 3 * density

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredWidth = (sample.columns * 24 * density).roundToInt()
        val width = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.AT_MOST) {
            MeasureSpec.getSize(widthMeasureSpec)
        } else resolveSize(desiredWidth, widthMeasureSpec)
        setMeasuredDimension(width, resolveSize(width * sample.rows / sample.columns, heightMeasureSpec))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        outline.rewind()
        outline.addRoundRect(0f, 0f, w.toFloat(), h.toFloat(), radius, radius, Path.Direction.CW)
    }

    override fun onDraw(canvas: Canvas) {
        val cellWidth = width.toFloat() / sample.columns
        val cellHeight = height.toFloat() / sample.rows
        val unit = minOf(cellWidth, cellHeight)
        val save = canvas.save()
        canvas.clipPath(outline)
        canvas.drawColor(palette.surface)
        sample.cells.forEachIndexed { index, cell ->
            val x = index % sample.columns * cellWidth
            val y = index / sample.columns * cellHeight
            val highlighted = sample.match != 0 && cell.value == sample.match.toString()
            paint.color = when {
                index == sample.selected -> palette.selectedCell
                highlighted -> palette.matchingCell
                index in sample.entered -> palette.relatedCell
                else -> palette.surface
            }
            canvas.drawRect(x, y, x + cellWidth, y + cellHeight, paint)
            if (cell.value.isNotEmpty()) {
                paint.color = when {
                    cell.value == ELLIPSIS -> palette.muted
                    highlighted || index in sample.entered -> palette.accentInk
                    else -> palette.ink
                }
                drawText(canvas, cell.value, x + cellWidth / 2, y + cellHeight / 2, unit * .66f, bold = true)
            }
            for (note in cell.notes) {
                val noteX = x + cellWidth * ((note - 1) % 3 + .5f) / 3
                val noteY = y + cellHeight * ((note - 1) / 3 + .5f) / 3
                if (note == sample.match) {
                    val half = unit * .16f
                    paint.color = palette.noteMarker
                    canvas.drawRoundRect(noteX - half, noteY - half, noteX + half, noteY + half,
                        unit * .08f, unit * .08f, paint)
                }
                paint.color = palette.ink
                drawText(canvas, note.toString(), noteX, noteY, unit * .29f, bold = note == sample.match)
            }
        }
        paint.color = palette.gridLine
        paint.strokeWidth = density * .6f
        for (column in 1 until sample.columns) {
            val x = column * cellWidth
            canvas.drawLine(x, 0f, x, height.toFloat(), paint)
        }
        for (row in 1 until sample.rows) {
            val y = row * cellHeight
            canvas.drawLine(0f, y, width.toFloat(), y, paint)
        }
        if (sample.selected >= 0) {
            val x = sample.selected % sample.columns * cellWidth
            val y = sample.selected / sample.columns * cellHeight
            paint.color = palette.accent
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = density * 1.6f
            val inset = density * 1.2f
            canvas.drawRoundRect(x + inset, y + inset, x + cellWidth - inset, y + cellHeight - inset,
                unit * .16f, unit * .16f, paint)
        }
        canvas.restoreToCount(save)
        paint.color = blend(palette.gridLine, palette.accent, .35f)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = density
        val edge = density / 2
        canvas.drawRoundRect(edge, edge, width - edge, height - edge, radius, radius, paint)
        paint.style = Paint.Style.FILL
    }

    private fun drawText(canvas: Canvas, value: String, x: Float, y: Float, size: Float, bold: Boolean) {
        paint.textSize = size
        paint.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        canvas.drawText(value, x, y - (paint.ascent() + paint.descent()) / 2, paint)
    }
}
