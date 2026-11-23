package com.kalamcs.sudoku

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper
import kotlin.math.hypot
import kotlin.math.roundToInt

internal class BoardViewport {
    var scale = 1f
        private set
    var left = 0f
        private set
    var top = 0f
        private set

    fun zoom(factor: Float, focusX: Float, focusY: Float, size: Float) {
        val next = (scale * factor).coerceIn(1f, 3f)
        val ratio = next / scale
        left = focusX - (focusX - left) * ratio
        top = focusY - (focusY - top) * ratio
        scale = next
        constrain(size)
    }

    fun pan(dx: Float, dy: Float, size: Float) {
        left += dx
        top += dy
        constrain(size)
    }

    private fun constrain(size: Float) {
        val limit = size * (1f - scale)
        left = left.coerceIn(limit, 0f)
        top = top.coerceIn(limit, 0f)
    }

    fun reset() {
        scale = 1f
        left = 0f
        top = 0f
    }

    fun cellAt(x: Float, y: Float, size: Float): Int? {
        if (size <= 0 || x < 0 || y < 0 || x >= size || y >= size) return null
        val extent = size * scale
        val col = ((x - left) * 9 / extent).toInt().coerceIn(0, 8)
        val row = ((y - top) * 9 / extent).toInt().coerceIn(0, 8)
        return row * 9 + col
    }
}

internal data class Diagram(
    val board: IntArray,
    val notes: IntArray = IntArray(81),
    val group: Set<Int> = emptySet(),
    val focus: Set<Int> = emptySet(),
    val crossed: Set<Int> = emptySet(),
)

internal class BoardView(
    context: Context,
    private val palette: GamePalette,
    private val onCell: ((Int) -> Unit)? = null,
) : View(context) {
    private val viewport = BoardViewport()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val outline = Path()
    private val outlineRadius = 6f * resources.displayMetrics.density
    private val outlineStroke = 1.3f * resources.displayMetrics.density
    private val bold = Typeface.create("sans-serif", Typeface.BOLD)
    private val regular = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val digitText = Array(10) { it.toString() }
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var game: Game? = null
    private var diagram: Diagram? = null
    private var hidden = false
    private var pointer = INVALID_POINTER_ID
    private var downX = 0f
    private var downY = 0f
    private var previousX = 0f
    private var previousY = 0f
    private var dragged = false
    private var multiTouch = false
    private var tappedCell: Int? = null
    private var scaleSpan = 0f
    private var scaleFocusX = 0f
    private var scaleFocusY = 0f
    var onZoomChanged: ((Boolean) -> Unit)? = null
    private val size get() = minOf(width, height).toFloat()

    private val accessibility = if (onCell != null) object : ExploreByTouchHelper(this) {
        override fun getVirtualViewAt(x: Float, y: Float) = if (hidden) INVALID_ID else viewport.cellAt(x, y, size) ?: INVALID_ID

        override fun getVisibleVirtualViews(ids: MutableList<Int>) {
            if (hidden || game == null) return
            val side = size.toInt()
            for (cell in 0..80) if (cellBounds(cell).intersects(0, 0, side, side)) ids.add(cell)
        }

        override fun onPopulateNodeForVirtualView(id: Int, node: AccessibilityNodeInfoCompat) {
            val board = game
            val position = Sudoku.name(id).replaceFirstChar(Char::uppercaseChar)
            val value = board?.values?.get(id) ?: 0
            val notes = board?.notes?.get(id) ?: 0
            val content = when {
                value != 0 && board?.puzzle?.givens?.get(id) != 0 -> "given $value"
                value != 0 && board?.conflicts?.get(id) == true -> "$value, duplicate"
                value != 0 && board?.markedErrors?.contains(id) == true -> "$value, incorrect"
                value != 0 -> "$value"
                notes != 0 -> {
                    val matching = board?.matchingNote(id) ?: 0
                    val list = "notes ${Sudoku.digitsIn(notes).joinToString()}"
                    if (matching == 0) list else "$list, highlighted $matching"
                }
                else -> "empty"
            }
            node.contentDescription = "$position, $content"
            node.className = "android.widget.Button"
            node.isSelected = board?.selected == id
            node.isClickable = true
            val bounds = cellBounds(id)
            val side = size.toInt()
            bounds.intersect(0, 0, side, side)
            setBoundsInScreenFromBoundsInParent(node, bounds)
            node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
        }

        override fun onPerformActionForVirtualView(id: Int, action: Int, arguments: Bundle?): Boolean {
            if (hidden || action != AccessibilityNodeInfo.ACTION_CLICK) return false
            onCell.invoke(id)
            return true
        }
    } else null

    init {
        layoutDirection = LAYOUT_DIRECTION_LTR
        if (accessibility != null) ViewCompat.setAccessibilityDelegate(this, accessibility)
    }

    fun render(game: Game, hidden: Boolean = false) {
        this.game = game
        this.hidden = hidden
        invalidate()
        accessibility?.invalidateRoot()
    }

    fun render(diagram: Diagram, description: String) {
        this.diagram = diagram
        contentDescription = description
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        invalidate()
    }

    fun resetZoom() {
        viewport.reset()
        viewportChanged()
    }

    private fun viewportChanged() {
        onZoomChanged?.invoke(viewport.scale > 1f)
        invalidate()
        accessibility?.invalidateRoot()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val preferredSide = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            maxOf(suggestedMinimumWidth, suggestedMinimumHeight, MeasureSpec.getSize(heightMeasureSpec))
        } else MeasureSpec.getSize(widthMeasureSpec)
        val measuredHeight = resolveSize(preferredSide, heightMeasureSpec)
        setMeasuredDimension(resolveSize(measuredHeight, widthMeasureSpec), measuredHeight)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        outline.reset()
        outline.addRoundRect(0f, 0f, size, size, outlineRadius, outlineRadius, Path.Direction.CW)
        val oldSide = minOf(oldw, oldh)
        if (oldSide != 0 && minOf(w, h) != oldSide) resetZoom()
    }

    override fun dispatchHoverEvent(event: MotionEvent): Boolean =
        accessibility?.dispatchHoverEvent(event) == true || super.dispatchHoverEvent(event)

    override fun performClick(): Boolean {
        super.performClick()
        val cell = tappedCell ?: return false
        onCell?.invoke(cell)
        tappedCell = null
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (onCell == null || hidden) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pointer = event.getPointerId(0)
                downX = event.x
                downY = event.y
                previousX = downX
                previousY = downY
                dragged = false
                multiTouch = false
                if (viewport.scale > 1f) parent.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                multiTouch = true
                parent.requestDisallowInterceptTouchEvent(true)
                trackPinch(event, rebase = true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount > 1) {
                    trackPinch(event)
                    return true
                }
                val index = event.findPointerIndex(pointer)
                if (index < 0) return true
                val x = event.getX(index)
                val y = event.getY(index)
                if (hypot(x - downX, y - downY) > touchSlop) dragged = true
                if (viewport.scale > 1f && (dragged || multiTouch)) {
                    viewport.pan(x - previousX, y - previousY, size)
                    viewportChanged()
                }
                previousX = x
                previousY = y
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.pointerCount > 2) {
                    trackPinch(event, rebase = true, skip = event.actionIndex)
                    return true
                }
                val remaining = if (event.actionIndex == 0) 1 else 0
                pointer = event.getPointerId(remaining)
                previousX = event.getX(remaining)
                previousY = event.getY(remaining)
            }
            MotionEvent.ACTION_UP -> {
                if (!dragged && !multiTouch) {
                    tappedCell = viewport.cellAt(event.x, event.y, size)
                    if (tappedCell != null) performClick()
                }
                pointer = INVALID_POINTER_ID
                parent.requestDisallowInterceptTouchEvent(false)
            }
            MotionEvent.ACTION_CANCEL -> {
                pointer = INVALID_POINTER_ID
                parent.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    private fun trackPinch(event: MotionEvent, rebase: Boolean = false, skip: Int = -1) {
        val first = if (skip == 0) 1 else 0
        val second = if (skip == 0 || skip == 1) 2 else 1
        val x1 = event.getX(first)
        val y1 = event.getY(first)
        val x2 = event.getX(second)
        val y2 = event.getY(second)
        val x = (x1 + x2) / 2
        val y = (y1 + y2) / 2
        val span = hypot(x2 - x1, y2 - y1).coerceAtLeast(1f)
        if (!rebase && scaleSpan > 0) {
            viewport.pan(x - scaleFocusX, y - scaleFocusY, size)
            viewport.zoom(span / scaleSpan, x, y, size)
            viewportChanged()
        }
        scaleFocusX = x
        scaleFocusY = y
        scaleSpan = span
    }

    private fun cellBounds(cell: Int): Rect {
        val step = size * viewport.scale / 9
        val x = viewport.left + (cell % 9) * step
        val y = viewport.top + (cell / 9) * step
        return Rect(x.roundToInt(), y.roundToInt(), (x + step).roundToInt(), (y + step).roundToInt())
    }

    fun reveal(cell: Int) {
        if (viewport.scale > 1f) {
            val bounds = cellBounds(cell)
            viewport.pan(size / 2 - bounds.exactCenterX(), size / 2 - bounds.exactCenterY(), size)
            viewportChanged()
        }
        requestRectangleOnScreen(cellBounds(cell), true)
    }

    override fun onDraw(canvas: Canvas) {
        val active = game
        val example = diagram
        val values = active?.values ?: example?.board ?: return
        val notes = active?.notes ?: example?.notes ?: return
        val gameBoard = active != null
        val saved = canvas.save()
        if (gameBoard) canvas.clipPath(outline) else canvas.clipRect(0f, 0f, size, size)
        canvas.drawColor(palette.surface)
        if (hidden) {
            canvas.restoreToCount(saved)
            return
        }
        canvas.translate(viewport.left, viewport.top)
        canvas.scale(viewport.scale, viewport.scale)
        val step = size / 9
        val selection = active?.selected ?: -1
        val match = active?.matchingDigit ?: 0
        for (cell in values.indices) {
            val x = (cell % 9) * step
            val y = (cell / 9) * step
            val value = values[cell]
            val selected = selection == cell
            val matching = match != 0 && value == match
            val incorrect = active?.markedErrors?.contains(cell) == true
            val conflicting = active?.conflicts?.get(cell) == true
            val errorHighlight = incorrect || ((selected || matching) && conflicting)
            val focus = example?.focus?.contains(cell) == true
            val crossed = example?.crossed?.contains(cell) == true
            val background = when {
                errorHighlight || crossed -> palette.errorSurface
                selected -> palette.selectedCell
                matching -> palette.matchingCell
                focus -> palette.selectedCell
                selection >= 0 && cell in Sudoku.peers[selection] || example?.group?.contains(cell) == true -> palette.relatedCell
                else -> 0
            }
            if (background != 0) {
                paint.color = background
                canvas.drawRect(x, y, x + step, y + step, paint)
            }
            if (value != 0) {
                val isGiven = active == null || active.puzzle.givens[cell] != 0
                paint.color = when {
                    incorrect || conflicting -> palette.error
                    matching -> palette.accentInk
                    isGiven -> palette.givenDigit
                    else -> palette.enteredDigit
                }
                numeral(canvas, value, x + step / 2, y + step / 2, step * .58f, if (isGiven) bold else regular)
            } else if (notes[cell] != 0) {
                val matchingNote = active?.matchingNote(cell) ?: 0
                val noteColor = when {
                    crossed -> palette.error
                    selected || focus -> palette.ink
                    else -> palette.pencilMark
                }
                for (digit in 1..9) {
                    if (notes[cell] and Sudoku.bit(digit) == 0) continue
                    val noteX = x + ((digit - 1) % 3 + .5f) * step / 3
                    val noteY = y + ((digit - 1) / 3 + .5f) * step / 3
                    val marked = digit == matchingNote
                    if (marked) {
                        paint.color = palette.noteMarker
                        val half = step * .157f
                        val radius = step * .063f
                        canvas.drawRoundRect(noteX - half, noteY - half, noteX + half, noteY + half, radius, radius, paint)
                    }
                    paint.color = if (marked) palette.ink else noteColor
                    numeral(canvas, digit, noteX, noteY, step * .26f, if (selected || focus || marked) bold else regular)
                }
            }
            if (selected || focus || crossed) {
                paint.color = if (errorHighlight || crossed) palette.error else palette.accent
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = step * .065f
                val inset = step * .045f
                val radius = if (selected) step * .12f else 0f
                canvas.drawRoundRect(x + inset, y + inset, x + step - inset, y + step - inset, radius, radius, paint)
                paint.style = Paint.Style.FILL
            }
        }
        for (i in if (gameBoard) 1..8 else 0..9) {
            val thick = i % 3 == 0
            paint.color = if (thick) palette.boxBorder else palette.gridLine
            paint.strokeWidth = if (thick) step * .045f else step * .018f
            val position = (i * step).coerceIn(paint.strokeWidth / 2, size - paint.strokeWidth / 2)
            canvas.drawLine(position, 0f, position, size, paint)
            canvas.drawLine(0f, position, size, position, paint)
        }
        canvas.restoreToCount(saved)
        if (gameBoard) {
            val inset = outlineStroke / 2
            paint.color = palette.boxBorder
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = outlineStroke
            canvas.drawRoundRect(inset, inset, size - inset, size - inset, outlineRadius, outlineRadius, paint)
            paint.style = Paint.Style.FILL
        }
    }

    private fun numeral(canvas: Canvas, value: Int, x: Float, y: Float, textSize: Float, typeface: Typeface) {
        paint.textSize = textSize
        paint.typeface = typeface
        canvas.drawText(digitText[value], x, y - (paint.ascent() + paint.descent()) / 2, paint)
    }

    private companion object {
        const val INVALID_POINTER_ID = -1
    }
}
