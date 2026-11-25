// Copyright (c) 2026 Kalam Shah
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.kalamcs.sudoku

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ScrollView
import androidx.appcompat.widget.AppCompatButton

internal class SolvingLayout(
    context: Context,
    ui: Ui,
    header: View,
    status: View,
    board: BoardView,
    toolbar: View,
    digits: List<DigitKey>,
    shortcuts: List<AppCompatButton>,
    preference: KeypadLayout,
) : ScrollView(context) {
    private val content = SolvingContent(context, ui, header, status, board, toolbar, digits, shortcuts, preference)

    internal val keypadColumns get() = content.keypadColumns
    internal val sideBySide get() = content.sideBySide

    init {
        id = R.id.page_scroll
        isVerticalScrollBarEnabled = false
        addView(content, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        content.viewportHeight = (MeasureSpec.getSize(heightMeasureSpec) - paddingTop - paddingBottom).coerceAtLeast(1)
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}

private class SolvingContent(
    context: Context,
    private val ui: Ui,
    private val header: View,
    private val status: View,
    private val board: BoardView,
    private val toolbar: View,
    digits: List<DigitKey>,
    shortcutButtons: List<AppCompatButton>,
    private val preference: KeypadLayout,
) : ViewGroup(context) {
    private val edge = ui.dp(8)
    private val gap = ui.dp(6)
    private val compactGap = ui.dp(3)
    private val paneGap = ui.dp(10)
    private val verticalEdge = ui.dp(4)
    private val minimumBoard = ui.dp(144)
    private val keyTarget = ui.dp(44)
    private val minimumPanel = 5 * keyTarget + 4 * ui.dp(4)
    private val waves = HomeWaves(context, ui.palette)
    private val keypad = KeypadGrid(context, ui, digits)
    private val shortcuts = PuzzleShortcuts(context, ui, shortcutButtons)

    var viewportHeight = 1
        set(value) {
            if (field == value) return
            field = value
            forceLayout()
        }

    private data class Plan(
        val columns: Int,
        val boardSize: Int,
        val panelWidth: Int,
        val keypadWidth: Int,
        val shortcutWidth: Int,
        val splitFooter: Boolean,
        val compactFooter: Boolean,
        val height: Int,
    )

    private lateinit var plan: Plan
    private var waveHeight = 0
    val keypadColumns get() = if (::plan.isInitialized) plan.columns else 3
    var sideBySide = false
        private set

    init {
        id = R.id.page_content
        for (child in listOf(header, waves, status, board, toolbar, keypad, shortcuts)) {
            addView(child, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(1)
        val inner = (width - 2 * edge).coerceAtLeast(1)
        measureHeight(header, width)
        sideBySide = width > viewportHeight && inner >= minimumBoard + paneGap + minimumPanel
        val grid = planFor(3, inner, keypad.minimumWidth(3, keyTarget))
        val row = if (preference == KeypadLayout.GRID) null else {
            val target = if (preference == KeypadLayout.ROW) ui.dp(36) else keyTarget
            val rowWidth = keypad.minimumWidth(9, target)
            val available = if (sideBySide) inner - paneGap - minimumBoard else inner
            if (available >= rowWidth) planFor(9, inner, rowWidth) else null
        }
        val boardDifference = maxOf(ui.dp(12), grid.boardSize / 20)
        plan = when (preference) {
            KeypadLayout.GRID -> grid
            KeypadLayout.ROW -> row ?: grid
            KeypadLayout.AUTO -> if (row != null && row.height <= viewportHeight &&
                (grid.height > viewportHeight || row.boardSize >= grid.boardSize + boardDifference ||
                    sideBySide && row.boardSize >= grid.boardSize - keyTarget)) row else grid
        }
        measureControls(plan)
        exact(board, plan.boardSize, plan.boardSize)
        waveHeight = if (sideBySide) 0 else (viewportHeight - plan.height).coerceIn(0, ui.dp(20))
        exact(waves, width, waveHeight)
        setMeasuredDimension(width, plan.height + waveHeight)
    }

    private fun planFor(columns: Int, width: Int, minimumKeypadWidth: Int): Plan {
        val headerHeight = header.measuredHeight
        if (sideBySide) {
            val boardSize = minOf(viewportHeight - 2 * verticalEdge, width - paneGap - maxOf(minimumPanel, minimumKeypadWidth))
                .coerceAtLeast(1)
            val panelWidth = width - paneGap - boardSize
            val regular = panelPlan(columns, boardSize, panelWidth, panelWidth, panelWidth, split = false, compact = false)
            if (regular.height <= viewportHeight) return regular
            val compact = panelPlan(columns, boardSize, panelWidth, panelWidth, panelWidth, split = false, compact = true)
            if (compact.height <= viewportHeight || columns == 9) return compact
            val footerWidth = shortcuts.columnWidth
            val keypadWidth = panelWidth - footerWidth - gap
            if (keypadWidth < minimumKeypadWidth) return compact
            return panelPlan(columns, boardSize, panelWidth, keypadWidth, footerWidth, split = true, compact = true)
                .takeIf { it.height < compact.height } ?: compact
        }

        fun portrait(compact: Boolean): Plan {
            val sizes = Plan(columns, 0, width, width, width, splitFooter = false, compactFooter = compact, height = 0)
            measureControls(sizes)
            val spacing = if (compact) compactGap else gap
            val fixedHeight = headerHeight + status.measuredHeight + toolbar.measuredHeight + keypad.measuredHeight +
                shortcuts.measuredHeight + 5 * spacing + verticalEdge
            val boardSize = (viewportHeight - fixedHeight).coerceIn(minOf(minimumBoard, width), width)
            return sizes.copy(boardSize = boardSize, height = fixedHeight + boardSize)
        }
        val regular = portrait(compact = false)
        if (regular.boardSize == width && regular.height <= viewportHeight) return regular
        val compact = portrait(compact = true)
        return if (compact.boardSize > regular.boardSize || compact.height < regular.height) compact else regular
    }

    private fun panelPlan(
        columns: Int, boardSize: Int, panelWidth: Int, keypadWidth: Int, footerWidth: Int, split: Boolean, compact: Boolean,
    ): Plan {
        val sizes = Plan(columns, boardSize, panelWidth, keypadWidth, footerWidth, split, compact, height = 0)
        measureControls(sizes)
        val spacing = if (compact) compactGap else gap
        val lower = if (split) maxOf(keypad.measuredHeight, shortcuts.measuredHeight)
            else keypad.measuredHeight + spacing + shortcuts.measuredHeight
        val panelHeight = header.measuredHeight + status.measuredHeight + toolbar.measuredHeight + 3 * spacing + lower
        return sizes.copy(height = 2 * verticalEdge + maxOf(boardSize, panelHeight))
    }

    private fun measureControls(sizes: Plan) {
        if (sideBySide) measureHeight(header, sizes.panelWidth)
        measureHeight(status, sizes.panelWidth)
        measureHeight(toolbar, sizes.panelWidth)
        keypad.measureAt(sizes.keypadWidth, sizes.columns)
        shortcuts.measureAt(sizes.shortcutWidth, sizes.splitFooter, sizes.compactFooter)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        if (sideBySide) {
            val spacing = if (plan.compactFooter) compactGap else gap
            waves.place(0, 0)
            board.place(edge, verticalEdge)
            val panelLeft = edge + plan.boardSize + paneGap
            val statusTop = header.place(panelLeft, verticalEdge) + spacing
            val toolbarTop = status.place(panelLeft, statusTop) + spacing
            val keypadTop = toolbar.place(panelLeft, toolbarTop) + spacing
            val keypadBottom = keypad.place(panelLeft, keypadTop)
            if (plan.splitFooter) shortcuts.place(panelLeft + plan.keypadWidth + gap, keypadTop)
            else shortcuts.place(panelLeft, keypadBottom + spacing)
        } else {
            header.place(0, 0)
            waves.place(0, header.measuredHeight)
            val spacing = if (plan.compactFooter) compactGap else gap
            val boardTop = status.place(edge, header.measuredHeight + waveHeight + spacing) + spacing
            val toolbarTop = board.place((measuredWidth - plan.boardSize) / 2, boardTop) + spacing
            val keypadTop = toolbar.place(edge, toolbarTop) + spacing
            shortcuts.place(edge, keypad.place(edge, keypadTop) + spacing)
        }
    }
}

private class KeypadGrid(
    context: Context,
    ui: Ui,
    private val keys: List<DigitKey>,
) : ViewGroup(context) {
    private val gap = ui.dp(2)
    private val rowGap = ui.dp(3)
    private var columns = 3
    private var keyHeight = 0

    init { keys.forEach { addView(it, LayoutParams(MATCH_PARENT, WRAP_CONTENT)) } }

    fun minimumWidth(columns: Int, target: Int): Int {
        val keyWidth = maxOf(target, keys.maxOf { it.paint.measureText(it.text.toString()).toInt() + it.paddingLeft + it.paddingRight + 1 })
        return keyWidth * columns + gap * (columns - 1)
    }

    fun measureAt(width: Int, columns: Int) {
        if (this.columns != columns) {
            this.columns = columns
            keys.forEach { it.stackedCount = columns == 9 }
            forceLayout()
        }
        measureHeight(this, width)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val usable = (width - gap * (columns - 1)).coerceAtLeast(columns)
        keyHeight = keys.mapIndexed { index, key ->
            val column = index % columns
            measureHeight(key, usable * (column + 1) / columns - usable * column / columns)
        }.max()
        keys.forEach { exact(it, it.measuredWidth, keyHeight) }
        val rows = keys.size / columns
        setMeasuredDimension(width, rows * keyHeight + (rows - 1) * rowGap)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val usable = measuredWidth - gap * (columns - 1)
        keys.forEachIndexed { index, key ->
            val column = index % columns
            key.place(usable * column / columns + gap * column, index / columns * (keyHeight + rowGap))
        }
    }
}

private class PuzzleShortcuts(
    context: Context,
    private val ui: Ui,
    private val actions: List<AppCompatButton>,
) : ViewGroup(context) {
    private val icons = actions.map { it.compoundDrawablesRelative[1] }
    private val line = Paint().apply {
        color = ui.palette.border
        strokeWidth = ui.dp(1).toFloat()
    }
    private var stacked = false
    private var compact = false
    private var actionHeight = 0
    val columnWidth: Int
        get() = maxOf(ui.dp(88), actions.maxOf { button ->
            button.text.lineSequence().maxOf { button.paint.measureText(it).toInt() } + ui.dp(25)
        })

    init {
        background = ui.shape(ui.palette.toolbarSurface, 12, ui.palette.border)
        setWillNotDraw(false)
        actions.forEach { addView(it, LayoutParams(MATCH_PARENT, WRAP_CONTENT)) }
    }

    fun measureAt(width: Int, stacked: Boolean, compact: Boolean) {
        if (this.stacked != stacked || this.compact != compact) {
            this.stacked = stacked
            this.compact = compact
            actions.zip(icons).forEach { (button, icon) ->
                val size = ui.dp(if (compact) 15 else 21)
                icon?.setBounds(0, 0, size, size)
                button.compoundDrawablePadding = ui.dp(if (compact) 3 else 4)
                button.setCompoundDrawablesRelative(if (compact) icon else null, if (compact) null else icon, null, null)
            }
            forceLayout()
        }
        measureHeight(this, width)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        actionHeight = actions.mapIndexed { index, button ->
            measureHeight(button, if (stacked) width else width * (index + 1) / actions.size - width * index / actions.size)
        }.max()
        actions.forEach { exact(it, it.measuredWidth, actionHeight) }
        setMeasuredDimension(width, if (stacked) actions.size * actionHeight else actionHeight)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        actions.forEachIndexed { index, button ->
            button.place(if (stacked) 0 else measuredWidth * index / actions.size, if (stacked) actionHeight * index else 0)
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        for (index in 1 until actions.size) {
            if (stacked) {
                val y = (index * actionHeight).toFloat()
                canvas.drawLine(ui.dp(8).toFloat(), y, width - ui.dp(8).toFloat(), y, line)
            } else {
                val x = (width * index / actions.size).toFloat()
                canvas.drawLine(x, height * .2f, x, height * .8f, line)
            }
        }
    }
}

private fun measureHeight(view: View, width: Int): Int {
    view.measure(View.MeasureSpec.makeMeasureSpec(width.coerceAtLeast(1), View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
    return view.measuredHeight
}

private fun exact(view: View, width: Int, height: Int) {
    view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
}

private fun View.place(x: Int, y: Int): Int {
    layout(x, y, x + measuredWidth, y + measuredHeight)
    return y + measuredHeight
}
