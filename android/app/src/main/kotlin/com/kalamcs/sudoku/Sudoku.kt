// Copyright (c) 2026 Kalam Shah
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.kalamcs.sudoku

import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import kotlin.random.Random

internal enum class Difficulty(val label: String, val seRatings: IntRange) {
    EASY("Easy", 10..25), MEDIUM("Medium", 26..44), HARD("Hard", 45..85), EXTREME("Extreme", 86 until 200);

    val key: String get() = name.lowercase()

    companion object {
        fun fromKey(key: String) = entries.firstOrNull { it.key == key }
    }
}

internal enum class Technique(val title: String) {
    NAKED_SINGLE("Naked single"),
    HIDDEN_SINGLE("Hidden single"),
    LOCKED_CANDIDATES("Locked candidates"),
    NAKED_PAIR("Naked pair"),
    HIDDEN_PAIR("Hidden pair"),
    CONTRADICTION("Proof by contradiction"),
    X_WING("X-Wing"),
    XY_WING("XY-Wing"),
    SWORDFISH("Swordfish"),
}

internal data class Puzzle(val difficulty: Difficulty, val givens: IntArray, val solution: IntArray)

internal data class HintStep(
    val text: String,
    val technique: Technique? = null,
    val heading: String? = null,
    val details: ((HintBudget) -> List<HintStep>)? = null,
)

internal data class CandidateRemoval(val cell: Int, val digit: Int)

internal data class Hint(
    val cell: Int,
    val digit: Int,
    val technique: Technique,
    val steps: List<HintStep>,
    val removals: List<CandidateRemoval> = emptyList(),
    val focus: Map<Int, Int> = emptyMap(),
    val heading: String = technique.title,
)

internal class HintTimedOut : RuntimeException()

internal class HintBudget(timeoutMillis: Long = 1_500, private val nanoTime: () -> Long = System::nanoTime) {
    private val started = nanoTime()
    private val limit = TimeUnit.MILLISECONDS.toNanos(timeoutMillis)

    fun check() {
        if (Thread.currentThread().isInterrupted) throw CancellationException()
        if (nanoTime() - started >= limit) throw HintTimedOut()
    }
}

internal object Sudoku {
    const val SIZE = 9
    const val COUNT = 81
    const val ALL = 0x3fe
    private const val SHORT_PROOF_LIMIT = 256
    private val indexes = 0 until COUNT
    private val digits = 1..SIZE
    private enum class UnitKind(val label: String) { ROW("row"), COLUMN("column"), BOX("box") }
    private data class SudokuUnit(val kind: UnitKind, val index: Int, val cells: List<Int>) {
        val name get() = "${kind.label} ${index + 1}"
    }

    private val units = UnitKind.entries.flatMap { kind ->
        (0 until SIZE).map { index ->
            SudokuUnit(kind, index, indexes.filter {
                when (kind) {
                    UnitKind.ROW -> it / SIZE == index
                    UnitKind.COLUMN -> it % SIZE == index
                    UnitKind.BOX -> box(it) == index
                }
            })
        }
    }
    private val cellUnits = Array(COUNT) { cell -> units.filter { cell in it.cells } }
    val peers = Array(COUNT) { cell ->
        cellUnits[cell].flatMap(SudokuUnit::cells).distinct().filter { it != cell }.toIntArray()
    }

    fun bit(digit: Int) = 1 shl digit
    fun digitsIn(mask: Int) = digits.filter { mask and bit(it) != 0 }
    fun name(cell: Int) = "row ${cell / SIZE + 1}, column ${cell % SIZE + 1}"
    fun decode(text: String): IntArray? =
        if (text.length == COUNT && text.all { it in '0'..'9' }) IntArray(COUNT) { text[it] - '0' } else null

    private fun box(cell: Int) = cell / 27 * 3 + cell % SIZE / 3
    private fun title(text: String) = text.replaceFirstChar(Char::uppercaseChar)
    private fun join(values: Iterable<Any>): String {
        val list = values.toList()
        return when (list.size) {
            0 -> ""
            1 -> list.first().toString()
            2 -> list.joinToString(" and ")
            else -> list.dropLast(1).joinToString(", ") + ", and " + list.last()
        }
    }

    private fun placeIn(unit: SudokuUnit, cell: Int) = when (unit.kind) {
        UnitKind.ROW -> "column ${cell % SIZE + 1}"
        UnitKind.COLUMN -> "row ${cell / SIZE + 1}"
        UnitKind.BOX -> name(cell)
    }

    fun candidates(board: IntArray, cell: Int): Int {
        var mask = ALL
        for (peer in peers[cell]) mask = mask and bit(board[peer]).inv()
        return mask
    }

    private fun candidateBoard(board: IntArray) = IntArray(COUNT) { if (board[it] == 0) candidates(board, it) else 0 }

    fun countSolutions(board: IntArray, limit: Int = 2) = search(board, limit, null)

    fun solve(board: IntArray): IntArray? {
        var solution: IntArray? = null
        search(board, 1, null) { solution = it.copyOf() }
        return solution
    }

    fun uniqueSolution(board: IntArray): IntArray? {
        var solution: IntArray? = null
        val count = search(board, 2, null) { if (solution == null) solution = it.copyOf() }
        return if (count == 1) solution else null
    }

    private fun search(
        grid: IntArray,
        limit: Int,
        random: Random?,
        budget: HintBudget? = null,
        found: ((IntArray) -> Unit)? = null,
    ): Int {
        if (grid.size != COUNT || grid.any { it !in 0..SIZE }) return 0
        val board = grid.copyOf()
        val rows = IntArray(SIZE)
        val columns = IntArray(SIZE)
        val boxes = IntArray(SIZE)
        for (cell in indexes) {
            val value = board[cell]
            if (value == 0) continue
            val r = cell / SIZE
            val c = cell % SIZE
            val b = box(cell)
            val mask = bit(value)
            if ((rows[r] or columns[c] or boxes[b]) and mask != 0) return 0
            rows[r] = rows[r] or mask
            columns[c] = columns[c] or mask
            boxes[b] = boxes[b] or mask
        }

        var solutions = 0
        fun visit() {
            budget?.check()
            var pick = -1
            var options = 0
            var smallest = SIZE + 1
            for (cell in indexes) {
                if (board[cell] != 0) continue
                val mask = ALL and (rows[cell / SIZE] or columns[cell % SIZE] or boxes[box(cell)]).inv()
                val size = Integer.bitCount(mask)
                if (size == 0) return
                if (size < smallest) {
                    smallest = size
                    pick = cell
                    options = mask
                    if (size == 1) break
                }
            }
            if (pick < 0) {
                solutions++
                found?.invoke(board)
                return
            }
            val r = pick / SIZE
            val c = pick % SIZE
            val b = box(pick)
            val choices = digitsIn(options)
            for (digit in if (random == null) choices else choices.shuffled(random)) {
                val mask = bit(digit)
                board[pick] = digit
                rows[r] = rows[r] or mask
                columns[c] = columns[c] or mask
                boxes[b] = boxes[b] or mask
                visit()
                board[pick] = 0
                rows[r] = rows[r] xor mask
                columns[c] = columns[c] xor mask
                boxes[b] = boxes[b] xor mask
                if (solutions >= limit) return
            }
        }
        visit()
        return solutions
    }

    fun generateEasy(random: Random = Random.Default): Puzzle {
        lateinit var solution: IntArray
        search(IntArray(COUNT), 1, random) { solution = it.copyOf() }
        val givens = solution.copyOf()
        val target = random.nextInt(40, 46)
        var clues = COUNT
        // Singles are forced in every solution and all cost at most 2.3 in classic SE.
        for (cell in indexes.shuffled(random)) {
            if (clues == target) break
            givens[cell] = 0
            if (solvesWithSingles(givens)) {
                clues--
            } else {
                givens[cell] = solution[cell]
            }
        }
        return Puzzle(Difficulty.EASY, givens, solution)
    }

    private data class Move(val cell: Int, val digit: Int, val unit: SudokuUnit? = null) {
        val technique get() = if (unit == null) Technique.NAKED_SINGLE else Technique.HIDDEN_SINGLE
    }
    internal data class Elimination(
        val cells: List<Int>, val mask: Int, val needs: List<Int>,
        val reason: HintStep, val focus: List<Int>, val removed: MutableList<Int> = mutableListOf(),
        val focusNotes: Map<Int, Int> = emptyMap(),
    )
    private data class Analysis(val move: Move?, val candidates: IntArray, val eliminations: List<Elimination>)

    private fun places(unit: SudokuUnit, digit: Int, candidates: IntArray) =
        unit.cells.filter { candidates[it] and bit(digit) != 0 }

    private fun single(candidates: IntArray): Move? {
        val naked = indexes.firstOrNull { Integer.bitCount(candidates[it]) == 1 }
        if (naked != null) return Move(naked, Integer.numberOfTrailingZeros(candidates[naked]))
        for (unit in units) for (digit in digits) {
            val places = places(unit, digit, candidates)
            if (places.size == 1) return Move(places.first(), digit, unit)
        }
        return null
    }

    private fun locked(candidates: IntArray): Elimination? {
        for (unit in units) for (digit in digits) {
            val places = places(unit, digit, candidates)
            if (places.size < 2) continue
            for (other in cellUnits[places.first()]) {
                if (other === unit || !places.all { it in other.cells }) continue
                val affected = other.cells.filter { it !in unit.cells && candidates[it] and bit(digit) != 0 }
                if (affected.isEmpty()) continue
                val reason = "In ${unit.name}, ${digit} can only go in ${places.joinToString(" or ") { placeIn(unit, it) }}. " +
                    "Those cells share ${other.name}. Cross off ${digit} elsewhere in ${other.name}, outside ${unit.name}."
                return Elimination(affected, bit(digit), unit.cells.filter { it !in other.cells }.map { it * 10 + digit },
                    HintStep(reason, Technique.LOCKED_CANDIDATES), places)
            }
        }
        return null
    }

    private fun pair(candidates: IntArray): Elimination? {
        for (unit in units) {
            val pairs = unit.cells.filter { Integer.bitCount(candidates[it]) == 2 }.groupBy { candidates[it] }
            for ((mask, cells) in pairs) {
                if (cells.size != 2) continue
                val affected = unit.cells.filter { it !in cells && candidates[it] and mask != 0 }
                if (affected.isEmpty()) continue
                val needs = cells.flatMap { cell -> digitsIn(ALL and mask.inv()).map { cell * 10 + it } }
                val reason = "The cells at ${name(cells[0])} and ${name(cells[1])} must take " +
                    "${join(digitsIn(mask))} in some order. Cross off both digits elsewhere in ${unit.name}."
                return Elimination(affected, mask, needs, HintStep(reason, Technique.NAKED_PAIR), cells)
            }
        }
        return null
    }

    private fun choose(items: List<Int>, size: Int, match: (List<Int>) -> Elimination?): Elimination? {
        val chosen = mutableListOf<Int>()
        fun visit(start: Int): Elimination? {
            val left = size - chosen.size
            if (left == 0) return match(chosen)
            for (index in start..items.size - left) {
                chosen.add(items[index])
                val found = visit(index + 1)
                chosen.removeAt(chosen.lastIndex)
                if (found != null) return found
            }
            return null
        }
        return visit(0)
    }

    private fun nakedSubset(candidates: IntArray, size: Int): Elimination? {
        val heading = if (size == 3) "Naked triple" else "Naked quadruple"
        for (unit in units) {
            val eligible = unit.cells.filter { Integer.bitCount(candidates[it]) in 2..size }
            val step = choose(eligible, size) { cells ->
                val mask = cells.fold(0) { union, cell -> union or candidates[cell] }
                if (Integer.bitCount(mask) != size) return@choose null
                val affected = unit.cells.filter { it !in cells && candidates[it] and mask != 0 }
                if (affected.isEmpty()) return@choose null
                val needs = cells.flatMap { cell -> digitsIn(ALL and mask.inv()).map { cell * 10 + it } }
                val reason = "At ${join(cells.map(::name))}, the only candidates across these cells are ${join(digitsIn(mask))}. " +
                    "These cells need one of each, so cross off those candidates from the other cells in ${unit.name}."
                Elimination(affected, mask, needs, HintStep(reason, Technique.NAKED_PAIR, heading), cells.toList())
            }
            if (step != null) return step
        }
        return null
    }

    private fun hiddenSubset(candidates: IntArray, size: Int): Elimination? {
        val heading = when (size) {
            2 -> Technique.HIDDEN_PAIR.title
            3 -> "Hidden triple"
            else -> "Hidden quadruple"
        }
        for (unit in units) {
            val positions = Array(SIZE + 1) { digit -> places(unit, digit, candidates) }
            val eligible = digits.filter { positions[it].size in 2..size }
            val step = choose(eligible, size) { chosen ->
                val focus = chosen.flatMap { positions[it] }.distinct()
                if (focus.size != size) return@choose null
                val keep = chosen.fold(0) { mask, digit -> mask or bit(digit) }
                val remove = ALL and keep.inv()
                val affected = focus.filter { candidates[it] and remove != 0 }
                if (affected.isEmpty()) return@choose null
                val needs = unit.cells.filter { it !in focus }.flatMap { cell -> chosen.map { cell * 10 + it } }
                val reason = "In ${unit.name}, ${join(chosen)} can go only in ${join(focus.map(::name))}. " +
                    "Keep those ${if (size == 2) "two digits" else "digits"} in these cells and cross off their other candidates."
                Elimination(affected, remove, needs, HintStep(reason, Technique.HIDDEN_PAIR, heading), focus)
            }
            if (step != null) return step
        }
        return null
    }

    internal fun fish(candidates: IntArray, size: Int, budget: HintBudget? = null): Elimination? {
        val technique = if (size == 2) Technique.X_WING else Technique.SWORDFISH
        for (kind in listOf(UnitKind.ROW, UnitKind.COLUMN)) {
            val crossKind = if (kind == UnitKind.ROW) UnitKind.COLUMN else UnitKind.ROW
            val lines = units.filter { it.kind == kind }
            fun cross(cell: Int) = if (kind == UnitKind.ROW) cell % SIZE else cell / SIZE
            for (digit in digits) {
                budget?.check()
                val positions = lines.map { places(it, digit, candidates) }
                val eligible = lines.indices.filter { positions[it].size in 2..size }
                val step = choose(eligible, size) { chosen ->
                    val focus = chosen.flatMap { positions[it] }
                    val crossing = focus.map(::cross).distinct().sorted()
                    if (crossing.size != size) return@choose null
                    val affected = lines.filter { it.index !in chosen }.flatMap { line ->
                        positions[line.index].filter { cross(it) in crossing }
                    }
                    if (affected.isEmpty()) return@choose null
                    val needs = chosen.flatMap { line -> lines[line].cells.filter { cross(it) !in crossing }.map { it * 10 + digit } }
                    val baseNames = "${kind.label}s ${join(chosen.map { it + 1 })}"
                    val crossNames = "${crossKind.label}s ${join(crossing.map { it + 1 })}"
                    val reason = "In $baseNames, the only spots for $digit lie in $crossNames. " +
                        "Each ${kind.label} needs its own $digit, using up all of those crossing lines. " +
                        "Cross off $digit in $crossNames outside $baseNames."
                    Elimination(affected, bit(digit), needs, HintStep(reason, technique), focus)
                }
                if (step != null) return step
            }
        }
        return null
    }

    private fun xyWing(candidates: IntArray): Elimination? {
        for (pivot in indexes) {
            val middle = candidates[pivot]
            if (Integer.bitCount(middle) != 2) continue
            for (first in peers[pivot]) {
                val firstMask = candidates[first]
                val shared = firstMask and middle
                if (Integer.bitCount(firstMask) != 2 || Integer.bitCount(shared) != 1) continue
                val extra = firstMask xor shared
                val other = middle xor shared
                if (shared > other) continue
                for (second in peers[pivot]) {
                    if (candidates[second] != (other or extra)) continue
                    val affected = peers[first].filter { it in peers[second] && candidates[it] and extra != 0 }
                    if (affected.isEmpty()) continue
                    val focus = listOf(pivot, first, second)
                    val needs = focus.flatMap { cell -> digitsIn(ALL and candidates[cell].inv()).map { cell * 10 + it } }
                    val digit = Integer.numberOfTrailingZeros(extra)
                    val reason = "The pivot at ${name(pivot)} can be ${join(digitsIn(middle))}. " +
                        "If it is ${Integer.numberOfTrailingZeros(shared)}, ${name(first)} must be $digit. " +
                        "If it is ${Integer.numberOfTrailingZeros(other)}, ${name(second)} must be $digit. " +
                        "Any cell that shares a row, column, or box with both pincers can cross off $digit."
                    return Elimination(affected, extra, needs, HintStep(reason, Technique.XY_WING), focus)
                }
            }
        }
        return null
    }

    private fun isPending(candidate: Int, crossedOff: IntArray) = crossedOff[candidate / 10] and bit(candidate % 10) == 0

    private fun analyze(board: IntArray, budget: HintBudget? = null, crossedOff: IntArray? = null): Analysis {
        val candidates = candidateBoard(board)
        val eliminations = mutableListOf<Elimination>()
        try {
            while (indexes.none { board[it] == 0 && candidates[it] == 0 }) {
                budget?.check()
                val move = single(candidates)
                if (move != null) return Analysis(move, candidates, eliminations)
                val pattern = locked(candidates) ?: pair(candidates) ?: if (crossedOff != null) {
                    hiddenSubset(candidates, 2) ?: nakedSubset(candidates, 3) ?: hiddenSubset(candidates, 3)
                        ?: nakedSubset(candidates, 4) ?: hiddenSubset(candidates, 4)
                        ?: fish(candidates, 2, budget) ?: xyWing(candidates) ?: fish(candidates, 3, budget)
                } else null
                if (pattern == null) break
                val step = if (crossedOff != null) {
                    pattern.copy(focusNotes = pattern.focus.associateWith { candidates[it] })
                } else pattern
                for (cell in step.cells) {
                    step.removed += digitsIn(candidates[cell] and step.mask).map { cell * 10 + it }
                    candidates[cell] = candidates[cell] and step.mask.inv()
                }
                eliminations += step
            }
        } catch (timeout: HintTimedOut) {
            // A proven elimination that still applies is useful after a placement search times out.
            if (crossedOff == null || eliminations.none { step -> step.removed.any { isPending(it, crossedOff) } }) throw timeout
        }
        return Analysis(null, candidates, eliminations)
    }

    fun solvesWithSingles(board: IntArray): Boolean {
        // Callers start with a valid partial grid.
        val work = board.copyOf()
        val candidates = candidateBoard(work)
        while (0 in work) {
            val move = single(candidates) ?: return false
            work[move.cell] = move.digit
            candidates[move.cell] = 0
            val allowed = bit(move.digit).inv()
            for (peer in peers[move.cell]) candidates[peer] = candidates[peer] and allowed
        }
        return true
    }

    private fun relevant(eliminations: List<Elimination>, needs: MutableSet<Int>): List<HintStep> {
        val steps = mutableListOf<HintStep>()
        for (step in eliminations.asReversed()) {
            if (step.removed.none(needs::contains)) continue
            steps += step.reason
            needs.removeAll(step.removed.toSet())
            needs.addAll(step.needs)
        }
        return steps.asReversed()
    }

    private fun nakedEvidence(board: IntArray, cell: Int, digit: Int): Pair<List<HintStep>, MutableSet<Int>> {
        val missing = digits.filter { it != digit }.toMutableSet()
        val steps = mutableListOf<HintStep>()
        for (unit in cellUnits[cell]) {
            val blocked = unit.cells.map { board[it] }.filter(missing::remove).sorted()
            if (blocked.isNotEmpty()) steps += HintStep("${title(unit.name)} already has ${join(blocked)}.")
        }
        if (missing.isNotEmpty() && digit != 0) {
            steps += HintStep("That leaves ${join((missing + digit).sorted())} to check in this cell.")
        }
        return steps to missing.map { cell * 10 + it }.toMutableSet()
    }

    private fun hiddenEvidence(board: IntArray, cell: Int, digit: Int, unit: SudokuUnit): Pair<List<HintStep>, MutableSet<Int>> {
        val blocked = linkedMapOf<SudokuUnit, MutableList<Int>>()
        val needs = mutableSetOf<Int>()
        for (other in unit.cells) {
            if (other == cell || board[other] != 0) continue
            val cause = cellUnits[other].firstOrNull { scope -> scope.cells.any { board[it] == digit } }
            if (cause == null) needs += other * 10 + digit
            else blocked.getOrPut(cause) { mutableListOf() }.add(other)
        }
        val steps = blocked.map { (scope, cells) ->
            HintStep("${title(scope.name)} already contains ${digit}, ruling it out at ${join(cells.map(::name))}.")
        }
        return if (steps.isEmpty() && needs.isEmpty()) listOf(HintStep("This is the last empty cell in ${unit.name}.")) to needs
        else steps to needs
    }

    private fun evidence(board: IntArray, move: Move) = move.unit?.let {
        hiddenEvidence(board, move.cell, move.digit, it)
    } ?: nakedEvidence(board, move.cell, move.digit)

    private fun mostConstrained(candidates: IntArray) =
        indexes.filter { candidates[it] != 0 }.minByOrNull { Integer.bitCount(candidates[it]) }

    private data class Trial(val moves: List<Move>, val candidates: IntArray, val deadEnd: HintStep? = null)

    private fun trial(board: IntArray): Trial {
        val candidates = candidateBoard(board)
        val moves = linkedMapOf<Int, Move>()
        for (cell in indexes) {
            if (board[cell] != 0) continue
            val mask = candidates[cell]
            if (mask == 0) return Trial(emptyList(), candidates,
                HintStep("No digit can go in ${name(cell)}.") { nakedEvidence(board, cell, 0).first })
            if (Integer.bitCount(mask) == 1) {
                val digit = Integer.numberOfTrailingZeros(mask)
                moves[cell * 10 + digit] = Move(cell, digit)
            }
        }
        for (unit in units) {
            val used = unit.cells.fold(0) { mask, cell -> mask or bit(board[cell]) }
            for (digit in digits) {
                if (used and bit(digit) != 0) continue
                val places = places(unit, digit, candidates)
                if (places.isEmpty()) return Trial(emptyList(), candidates,
                    HintStep("There is nowhere to put ${digit} in ${unit.name}.") {
                        hiddenEvidence(board, -1, digit, unit).first
                    })
                if (places.size == 1) {
                    val cell = places.first()
                    moves.putIfAbsent(cell * 10 + digit, Move(cell, digit, unit))
                }
            }
        }
        return Trial(moves.values.toList(), candidates)
    }

    private fun moveStep(board: IntArray, move: Move): HintStep {
        val text = move.unit?.let { "In ${it.name}, ${move.digit} can only go in ${placeIn(it, move.cell)}." }
            ?: "Only ${move.digit} can go in ${name(move.cell)}."
        return HintStep(text, move.technique) { evidence(board, move).first }
    }

    private data class Path(val board: IntArray, val steps: List<HintStep>)

    private fun shortProof(board: IntArray, budget: HintBudget): List<HintStep>? {
        val pending = mutableListOf(Path(board, emptyList()))
        val seen = hashSetOf(board.joinToString(""))
        var index = 0
        while (index < pending.size) {
            budget.check()
            val (work, steps) = pending[index++]
            val trial = trial(work)
            trial.deadEnd?.let { return steps + it }
            for (move in trial.moves) {
                if (pending.size >= SHORT_PROOF_LIMIT) break
                val next = work.copyOf()
                next[move.cell] = move.digit
                if (seen.add(next.joinToString(""))) pending += Path(next, steps + moveStep(work, move))
            }
        }
        return null
    }

    private fun branch(board: IntArray, cell: Int, digit: Int) = HintStep("What if ${name(cell)} is ${digit}?") { budget ->
        contradiction(board.copyOf().apply { this[cell] = digit }, budget)
    }

    private fun contradiction(board: IntArray, budget: HintBudget): List<HintStep> {
        shortProof(board, budget)?.let { return it }
        var work = board
        val steps = mutableListOf<HintStep>()
        var trial = trial(work)
        while (trial.deadEnd == null && trial.moves.isNotEmpty()) {
            budget.check()
            val move = trial.moves.first()
            steps += moveStep(work, move)
            work = work.copyOf().apply { this[move.cell] = move.digit }
            trial = trial(work)
        }
        val visible = if (steps.size > 4) listOf(HintStep("Follow ${steps.size} forced moves") { steps }) else steps
        trial.deadEnd?.let { return visible + it }
        // A branch is entered only after the original assumption was proved impossible.
        val cell = checkNotNull(mostConstrained(trial.candidates))
        val digits = digitsIn(trial.candidates[cell])
        return visible + HintStep("The only candidates in ${name(cell)} are ${join(digits)}. Open each choice to see why it fails.") +
            digits.map { branch(work, cell, it) }
    }

    fun hint(board: IntArray, budget: HintBudget = HintBudget(), crossedOff: IntArray = IntArray(COUNT)): Hint? {
        budget.check()
        if (board.size != COUNT || board.any { it !in 0..SIZE } ||
            indexes.any { cell -> board[cell] != 0 && peers[cell].any { board[it] == board[cell] } }) return null
        val analysis = analyze(board, budget, crossedOff)
        analysis.move?.let { move ->
            if (analysis.eliminations.isEmpty()) {
                val unit = cellUnits[move.cell].firstOrNull { places(it, move.digit, analysis.candidates).size == 1 }
                val direct = move.copy(unit = unit)
                val reason = if (unit == null) {
                    "The row, column, and box rule out every digit except ${move.digit}."
                } else {
                    "In ${unit.name}, only ${placeIn(unit, move.cell)} can take ${move.digit}."
                }
                return Hint(direct.cell, direct.digit, direct.technique, listOf(HintStep(reason)))
            }
            val (steps, needs) = evidence(board, move)
            val conclusion = if (move.unit == null) "${move.digit} is the only digit that fits in this cell."
            else "This is the only cell in ${move.unit.name} where ${move.digit} can go."
            return Hint(move.cell, move.digit, move.technique, steps + relevant(analysis.eliminations, needs) + HintStep(conclusion))
        }
        for ((index, step) in analysis.eliminations.withIndex()) {
            val removals = step.removed.filter { isPending(it, crossedOff) }.map { CandidateRemoval(it / 10, it % 10) }
            if (removals.isEmpty()) continue
            val first = removals.first()
            val technique = checkNotNull(step.reason.technique)
            val reasoning = relevant(analysis.eliminations.take(index), step.needs.toMutableSet()) + step.reason
            return Hint(first.cell, first.digit, technique, reasoning, removals, step.focusNotes,
                step.reason.heading ?: technique.title)
        }
        val cell = mostConstrained(analysis.candidates) ?: return null
        val options = digitsIn(candidates(board, cell))
        val (possible, rejected) = options.partition { digit ->
            search(board.copyOf().apply { this[cell] = digit }, 1, null, budget) > 0
        }
        val digit = possible.singleOrNull() ?: return null
        val introduction = HintStep("The row, column, and box leave ${join(options)} as candidates here.")
        val trials = if (rejected.size == 1) {
            val assumed = rejected.first()
            listOf(HintStep("Suppose ${assumed} goes in ${name(cell)}.")) +
                contradiction(board.copyOf().apply { this[cell] = assumed }, budget)
        } else rejected.map { branch(board, cell, it) }
        return Hint(cell, digit, Technique.CONTRADICTION, listOf(introduction) + trials +
            HintStep("Only ${digit} remains for this cell."))
    }
}
