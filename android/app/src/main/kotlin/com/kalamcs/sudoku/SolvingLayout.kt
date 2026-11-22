package com.kalamcs.sudoku

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.widget.AppCompatButton

internal class SolvingLayout(
    context: Context,
    private val ui: Ui,
    private val header: View,
    private val status: View,
    private val board: BoardView,
    toolbar: View,
    private val digits: List<AppCompatButton>,
) : ScrollView(context) {
    private val sidePadding = ui.dp(8)
    private val topPadding = ui.dp(2)
    private val bottomPadding = ui.dp(8)
    private val statusGap = ui.dp(2)
    private val bodyGap = ui.dp(6)
    private val boardGap = ui.dp(8)
    private val paneGap = ui.dp(14)

    private val frame = FrameLayout(context).apply { id = R.id.page_content }
    private val content = ui.column().apply {
        layoutDirection = LAYOUT_DIRECTION_LTR
        setPadding(sidePadding, topPadding, sidePadding, bottomPadding)
    }
    private val body = ui.column()
    private val controls = ui.column()
    private val keypad = ui.column()
    private var keypadColumns = 0

    init {
        id = R.id.page_scroll
        isFillViewport = true
        isVerticalScrollBarEnabled = false
        addView(frame, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        frame.addView(content, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        ui.add(content, header)
        ui.add(content, status, 2)
        ui.add(content, body, 6)
        ui.add(body, board)
        ui.add(body, controls, 8)
        ui.add(controls, toolbar)
        ui.add(controls, keypad, 6)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val viewportWidth = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        val viewportHeight = MeasureSpec.getSize(heightMeasureSpec) - paddingTop - paddingBottom
        val sideBySide = viewportWidth >= ui.dp(540) && viewportWidth > viewportHeight
        val pageWidth = viewportWidth.coerceIn(1, ui.dp(if (sideBySide) 840 else 600))
        val contentWidth = (pageWidth - 2 * sidePadding).coerceAtLeast(1)
        val controlsWidth = if (sideBySide) minOf(ui.dp(320), contentWidth * 45 / 100) else contentWidth
        val maxBoardSide = if (sideBySide) contentWidth - controlsWidth - paneGap else contentWidth
        updateKeypad(if ((if (sideBySide) controlsWidth else viewportWidth) <= ui.dp(360)) 3 else 9)
        (keypad.layoutParams as LinearLayout.LayoutParams).topMargin = ui.dp(if (sideBySide) 2 else 6)
        val keyRowGap = ui.dp(if (sideBySide) 2 else 4)
        for (index in 1 until keypad.childCount) {
            (keypad.getChildAt(index).layoutParams as LinearLayout.LayoutParams).topMargin = keyRowGap
        }

        val aboveBody = topPadding + measureHeight(header, contentWidth) + statusGap +
            measureHeight(status, contentWidth) + bodyGap + bottomPadding
        val controlsHeight = measureHeight(controls, controlsWidth)
        val availableBody = viewportHeight - aboveBody
        val availableBoard = if (sideBySide) availableBody else availableBody - boardGap - controlsHeight
        val minimumBoardSide = minOf(ui.dp(180), maxBoardSide)
        val boardSide = availableBoard.coerceIn(minimumBoardSide, maxBoardSide)
        val usedBody = if (sideBySide) maxOf(boardSide, controlsHeight) else boardSide + boardGap + controlsHeight
        val extraSpace = (availableBody - usedBody).coerceAtLeast(0)

        content.layoutParams.width = pageWidth
        body.orientation = if (sideBySide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        body.gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP
        (body.layoutParams as LinearLayout.LayoutParams).topMargin = bodyGap + minOf(extraSpace / 3, ui.dp(28))
        board.layoutParams.width = boardSide
        board.layoutParams.height = boardSide
        (controls.layoutParams as LinearLayout.LayoutParams).apply {
            width = controlsWidth
            topMargin = if (sideBySide) 0 else boardGap
            leftMargin = if (sideBySide) paneGap else 0
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun measureHeight(view: View, width: Int): Int {
        view.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.UNSPECIFIED)
        return view.measuredHeight
    }

    private fun updateKeypad(columns: Int) {
        if (keypadColumns == columns) return
        keypadColumns = columns
        for (index in 0 until keypad.childCount) (keypad.getChildAt(index) as LinearLayout).removeAllViews()
        keypad.removeAllViews()
        digits.chunked(columns).forEach { keys ->
            val row = ui.row()
            keys.forEachIndexed { column, button -> ui.equal(row, button, if (column == 0) 0 else 3) }
            ui.add(keypad, row)
        }
    }
}
