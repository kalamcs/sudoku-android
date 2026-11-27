// Copyright (c) 2026 Kalam Shah
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.kalamcs.sudoku

internal data class PrunedNotes(val cell: Int, val peers: List<Int>)

internal data class BoardSnapshot(val values: IntArray, val notes: IntArray, val prunedNotes: PrunedNotes?)
internal data class EditHistory(val undo: List<BoardSnapshot> = emptyList(), val redo: List<BoardSnapshot> = emptyList())

internal enum class EntryOrder(val key: String, val label: String) {
    CELL_FIRST("cell_first", "Cell first"), DIGIT_FIRST("digit_first", "Digit first"),
}

internal class Game(
    val puzzle: Puzzle,
    values: IntArray = puzzle.givens.copyOf(),
    notes: IntArray = IntArray(Sudoku.COUNT),
    var elapsedMs: Long = 0,
    var paused: Boolean = false,
    var selected: Int = values.indexOfFirst { it == 0 }.coerceAtLeast(0),
    prunedNotes: PrunedNotes? = null,
    recordedWin: Boolean = false,
    history: EditHistory = EditHistory(),
    entryOrder: EntryOrder = EntryOrder.CELL_FIRST,
    pickedDigit: Int = 0,
) {
    var values = values
        private set
    var notes = notes
        private set
    var prunedNotes = prunedNotes
        private set
    var notesMode = false
    var entryOrder = entryOrder
        private set
    var pickedDigit = pickedDigit.takeIf { entryOrder == EntryOrder.DIGIT_FIRST && it in 1..9 } ?: 0
        private set
    var highlightedDigit = 0
        private set
    val matchingDigit get() = when {
        pickedDigit != 0 -> pickedDigit
        highlightedDigit != 0 -> highlightedDigit
        else -> values[selected]
    }
    var finished = false
        private set
    var recordedWin = recordedWin
        private set
    var markedErrors = emptySet<Int>()
        private set
    private var startedAt: Long? = null
    private val undo = ArrayDeque(history.undo)
    private val redo = ArrayDeque(history.redo)
    val conflicts = BooleanArray(Sudoku.COUNT)
    val counts = IntArray(10)
    val canUndo get() = undo.isNotEmpty() && !paused && !finished
    val canRedo get() = redo.isNotEmpty() && !paused && !finished
    val hasBlank get() = 0 in values
    val hasProgress get() = !finished && (!values.contentEquals(puzzle.givens) || notes.any { it != 0 })

    fun canContinue(now: Long) = !finished &&
        (elapsed(now) > 10_000L || hasProgress || undo.isNotEmpty() || redo.isNotEmpty())

    fun remaining(digit: Int) = (Sudoku.SIZE - counts[digit]).coerceAtLeast(0)

    init { updateCounts() }

    fun select(cell: Int) {
        if (cell !in values.indices) return
        selected = cell
        highlightedDigit = 0
    }

    fun canEnter(digit: Int) = !paused && !finished &&
        (puzzle.givens[selected] != 0 || counts[digit] < Sudoku.SIZE || (!notesMode && values[selected] == digit))

    fun changeEntryOrder(order: EntryOrder): Boolean {
        if (entryOrder == order) return false
        entryOrder = order
        pickedDigit = 0
        highlightedDigit = 0
        return true
    }

    fun canTapDigit(digit: Int) = if (entryOrder == EntryOrder.CELL_FIRST) canEnter(digit)
        else !paused && !finished && (digit == pickedDigit || counts[digit] < 9)

    fun tapDigit(digit: Int): Boolean {
        if (entryOrder == EntryOrder.CELL_FIRST) return enter(digit)
        if (digit !in 1..9 || !canTapDigit(digit)) return false
        pickedDigit = if (pickedDigit == digit) 0 else digit
        highlightedDigit = 0
        return false
    }

    fun tapCell(cell: Int): Boolean {
        if (paused || cell !in values.indices) return false
        select(cell)
        val digit = pickedDigit
        if (digit == 0 || puzzle.givens[cell] != 0 || !notesMode && values[cell] == digit) return false
        return enter(digit)
    }

    fun matchingNote(cell: Int): Int {
        val digit = matchingDigit
        return if (digit != 0 && values[cell] == 0 && notes[cell] and Sudoku.bit(digit) != 0) digit else 0
    }

    fun enter(digit: Int): Boolean {
        if (digit !in 1..9 || !canEnter(digit)) return false
        val cell = selected
        if (puzzle.givens[cell] != 0) {
            highlightedDigit = digit
            return false
        }
        if (notesMode && values[cell] != 0) return false
        if (notesMode) {
            edit()
            notes[cell] = notes[cell] xor Sudoku.bit(digit)
            highlightedDigit = digit
        } else {
            highlightedDigit = 0
            replaceValue(cell, if (values[cell] == digit) 0 else digit)
        }
        updateCounts()
        return true
    }

    fun erase(): Boolean {
        val cell = selected
        if (paused || finished || puzzle.givens[cell] != 0 || (values[cell] == 0 && notes[cell] == 0)) return false
        replaceValue(cell, 0)
        updateCounts()
        return true
    }

    fun autoNotes(): Boolean {
        if (paused || finished) return false
        val next = IntArray(Sudoku.COUNT) { if (values[it] == 0) Sudoku.candidates(values, it) else 0 }
        if (next.contentEquals(notes)) return false
        edit()
        notes = next
        return true
    }

    private fun replaceValue(cell: Int, value: Int) {
        val previousPruning = prunedNotes
        edit()
        if (previousPruning?.cell == cell) {
            val previousDigit = Sudoku.bit(values[cell])
            for (peer in previousPruning.peers) notes[peer] = notes[peer] or previousDigit
        }
        values[cell] = value
        notes[cell] = 0
        if (value == 0) return

        val digit = Sudoku.bit(value)
        val affected = Sudoku.peers[cell].filter { notes[it] and digit != 0 }
        for (peer in affected) notes[peer] = notes[peer] and digit.inv()
        if (affected.isNotEmpty()) prunedNotes = PrunedNotes(cell, affected)
    }

    fun editHistory() = EditHistory(undo.toList(), redo.toList())

    fun crossedOffNotes(): IntArray {
        val crossedOff = IntArray(Sudoku.COUNT)
        var previous = undo.firstOrNull() ?: return crossedOff

        fun applyEdit(nextValues: IntArray, nextNotes: IntArray) {
            val notesOnly = previous.values.contentEquals(nextValues)
            for (cell in nextValues.indices) {
                crossedOff[cell] = if (nextValues[cell] == 0 && previous.values[cell] == 0) {
                    val removed = if (notesOnly) previous.notes[cell] and nextNotes[cell].inv() else 0
                    // Auto Notes can only remove marks that are already illegal on this grid.
                    val legalRemovals = if (removed != 0) removed and Sudoku.candidates(nextValues, cell) else 0
                    (crossedOff[cell] or legalRemovals) and nextNotes[cell].inv()
                } else 0
            }
        }

        // Use the saved undo path, so upgrades and Undo/Redo see the same evidence.
        for (next in undo.asSequence().drop(1)) {
            applyEdit(next.values, next.notes)
            previous = next
        }
        applyEdit(values, notes)
        return crossedOff
    }

    private fun snapshot() = BoardSnapshot(values.copyOf(), notes.copyOf(), prunedNotes)

    private fun edit() {
        undo.addLast(snapshot())
        redo.clear()
        markedErrors = emptySet()
        prunedNotes = null
    }

    fun undo(): Boolean = restoreHistory(undo, redo, canUndo)
    fun redo(): Boolean = restoreHistory(redo, undo, canRedo)

    private fun restoreHistory(from: ArrayDeque<BoardSnapshot>, to: ArrayDeque<BoardSnapshot>, allowed: Boolean): Boolean {
        if (!allowed) return false
        to.addLast(snapshot())
        val previous = from.removeLast()
        values = previous.values
        notes = previous.notes
        prunedNotes = previous.prunedNotes
        markedErrors = emptySet()
        updateCounts()
        return true
    }

    fun mistakes(): List<Int> = values.indices.filter { values[it] != 0 && values[it] != puzzle.solution[it] }

    fun markMistakes() { markedErrors = mistakes().toSet() }

    private fun updateCounts() {
        counts.fill(0)
        for (cell in values.indices) {
            val digit = values[cell]
            val conflict = digit != 0 && puzzle.givens[cell] == 0 && Sudoku.peers[cell].any { values[it] == digit }
            conflicts[cell] = conflict
            if (!conflict && digit != 0) counts[digit]++
        }
    }

    fun elapsed(now: Long) = elapsedMs + (startedAt?.let { (now - it).coerceAtLeast(0) } ?: 0)

    fun start(now: Long) {
        if (startedAt == null && !finished && !paused) startedAt = now
    }

    fun stop(now: Long) {
        elapsedMs = elapsed(now)
        startedAt = null
    }

    fun finish(now: Long): Boolean {
        val firstWin = !recordedWin
        stop(now)
        finished = true
        recordedWin = true
        notesMode = false
        markedErrors = emptySet()
        highlightedDigit = 0
        pickedDigit = 0
        return firstWin
    }

    fun reset(now: Long) {
        values = puzzle.givens.copyOf()
        notes = IntArray(Sudoku.COUNT)
        notesMode = false
        pickedDigit = 0
        prunedNotes = null
        markedErrors = emptySet()
        undo.clear()
        redo.clear()
        select(values.indexOf(0))
        elapsedMs = 0
        startedAt = now
        paused = false
        finished = false
        updateCounts()
    }
}

internal data class Record(
    var best: Long? = null,
    var bestAt: Long? = null,
    var wins: Long = 0,
    var totalTime: Long = 0,
    var timedWins: Long = 0,
) {
    val average get() = if (timedWins > 0) totalTime / timedWins else null

    fun win(seconds: Long, now: Long): Boolean {
        wins++
        totalTime += seconds
        timedWins++
        if (best?.let { seconds >= it } == true) return false
        best = seconds
        bestAt = now
        return true
    }
}
