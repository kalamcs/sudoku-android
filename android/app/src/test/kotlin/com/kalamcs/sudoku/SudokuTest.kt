package com.kalamcs.sudoku

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SudokuTest {
    private fun board(text: String) = checkNotNull(Sudoku.decode(text))

    @Test fun generatedPuzzlesAreUniqueAndMatchDifficulty() {
        val random = Random(0x1a2b3c4d)
        for (difficulty in Difficulty.entries) repeat(if (difficulty in listOf(Difficulty.EASY, Difficulty.MEDIUM)) 24 else 8) {
            val puzzle = Sudoku.generate(difficulty, random)
            assertEquals(difficulty, puzzle.difficulty)
            assertTrue(puzzle.givens.count { it != 0 } in difficulty.clues)
            assertEquals(1, Sudoku.countSolutions(puzzle.givens))
            assertArrayEquals(puzzle.solution, Sudoku.solve(puzzle.givens))
            when (difficulty) {
                Difficulty.HARD -> assertTrue(Sudoku.canSolveWithoutSearch(puzzle.givens))
                Difficulty.EXTREME -> assertFalse(Sudoku.canSolveWithoutSearch(puzzle.givens))
                else -> Unit
            }
        }
    }

    @Test fun contradictoryGivensAreRejected() {
        val board = IntArray(81).apply { this[0] = 1; this[1] = 1 }
        assertNull(Sudoku.solve(board))
        assertNull(Sudoku.hint(board))
        assertEquals(0, Sudoku.countSolutions(board))
        assertEquals(2, Sudoku.countSolutions(IntArray(81), 2))
        assertNull(Sudoku.uniqueSolution(IntArray(81)))
        val puzzle = board(Guide.PROOF_BOARD)
        assertArrayEquals(Sudoku.solve(puzzle), Sudoku.uniqueSolution(puzzle))
    }

    @Test fun everyHintAdvancesAValidPuzzleAndUsesACoveredTechnique() {
        val random = Random(509)
        for (difficulty in Difficulty.entries) repeat(3) {
            val puzzle = Sudoku.generate(difficulty, random)
            val work = puzzle.givens.copyOf()
            repeat(work.count { it == 0 }) {
                val hint = checkNotNull(Sudoku.hint(work))
                assertEquals(0, work[hint.cell])
                assertEquals(puzzle.solution[hint.cell], hint.digit)
                assertTrue(hint.technique in Guide.order)
                assertTrue(hint.steps.isNotEmpty())
                assertTrue(hint.steps.all { step -> step.technique == null || step.technique in Guide.order })
                work[hint.cell] = hint.digit
            }
            assertArrayEquals(puzzle.solution, work)
        }
    }

    @Test fun shortProofUsesTheSameTermsAsTheGuideAndNamesTheDeadEnd() {
        val board = board(Guide.PROOF_BOARD)
        val hint = checkNotNull(Sudoku.hint(board))
        assertEquals(1, hint.cell)
        assertEquals(5, hint.digit)
        assertEquals(Technique.CONTRADICTION, hint.technique)
        assertEquals(listOf(
            "The row, column, and box leave 3 and 5 as candidates here.",
            "Suppose 3 goes in row 1, column 2.",
            "In column 3, 3 can only go in row 4.",
            "In row 4, 7 can only go in column 9.",
            "In row 5, 7 can only go in column 1.",
            "There is nowhere to put 7 in row 7.",
            "Only 5 remains for this cell.",
        ), hint.steps.map(HintStep::text))
        assertEquals(List(3) { Technique.HIDDEN_SINGLE }, hint.steps.mapNotNull(HintStep::technique))
        val evidence = checkNotNull(hint.steps[5].details).invoke(HintBudget()).joinToString { it.text }
        assertTrue("Column 1 already contains 7" in evidence)
        assertTrue("Column 9 already contains 7" in evidence)
        assertEquals(Guide.PROOF_BOARD, board.joinToString(""))
    }

    @Test fun deepProofBranchesEachReachAnExplainableContradiction() {
        val board = board("079000000060903107800750900008010030706000005003400000907200000680000002002160079")
        val hint = checkNotNull(Sudoku.hint(board))
        assertEquals(3, hint.cell)
        assertEquals(8, hint.digit)
        var deadEnds = 0
        fun checkBranches(steps: List<HintStep>) {
            val choices = steps.filter { it.text.startsWith("What if") }
            if (choices.isNotEmpty()) {
                choices.forEach { checkBranches(checkNotNull(it.details).invoke(HintBudget())) }
            } else {
                val deadEnd = steps.first { it.text.startsWith("There is nowhere") || it.text.startsWith("No digit") }
                assertTrue(checkNotNull(deadEnd.details).invoke(HintBudget()).isNotEmpty())
                deadEnds++
            }
        }
        checkBranches(hint.steps)
        val branch = hint.steps.first { it.text.startsWith("What if") }
        assertThrows(HintTimedOut::class.java) { checkNotNull(branch.details).invoke(HintBudget(0)) }
        assertTrue(deadEnds >= 2)
        assertEquals(0, board[hint.cell])
    }

    @Test fun anExpiredHintBudgetStopsBeforeGivingAnAnswer() {
        val puzzle = board(Guide.PROOF_BOARD)
        assertThrows(HintTimedOut::class.java) { Sudoku.hint(puzzle, HintBudget(0)) }
        assertEquals(Guide.PROOF_BOARD, puzzle.joinToString(""))
    }

    @Test fun guideExamplesUseValidCandidatesAndHintTerminology() {
        assertEquals(Technique.entries, Guide.order)
        for (technique in Guide.order) {
            val lesson = Guide.lesson(technique)
            assertTrue(lesson.instructions.isNotEmpty())
            assertTrue(lesson.pictures.isNotEmpty())
            for (picture in lesson.pictures) {
                val diagram = picture.diagram
                diagram.board.indices.filter { diagram.notes[it] != 0 }.forEach { cell ->
                    assertEquals(0, diagram.board[cell])
                    assertEquals(diagram.notes[cell], diagram.notes[cell] and Sudoku.candidates(diagram.board, cell))
                }
            }
        }
        val naked = Guide.lesson(Technique.NAKED_SINGLE).pictures.single().diagram
        assertEquals(Sudoku.bit(5), naked.notes[40])
        val hidden = Guide.lesson(Technique.HIDDEN_SINGLE).pictures.single().diagram
        assertEquals(listOf(18), (18..26).filter { hidden.notes[it] and Sudoku.bit(8) != 0 })
        val locked = checkNotNull(Sudoku.hint(board(Guide.LOCKED_BOARD)))
        assertEquals(39, locked.cell)
        assertEquals(7, locked.digit)
        assertEquals(List(2) { Technique.LOCKED_CANDIDATES }, locked.steps.mapNotNull(HintStep::technique))
        val pair = checkNotNull(Sudoku.hint(board(Guide.PAIR_BOARD)))
        assertEquals(Technique.HIDDEN_SINGLE, pair.technique)
        assertTrue(pair.steps.any { it.technique == Technique.NAKED_PAIR })
        assertNotNull(Sudoku.solve(board(Guide.PROOF_BOARD)))
    }

    @Test fun hiddenPairAndXYWingExamplesShowValidEliminations() {
        val pair = Guide.lesson(Technique.HIDDEN_PAIR).pictures.single().diagram
        val pairDigits = Sudoku.bit(1) or Sudoku.bit(5)
        assertEquals(setOf(34, 35), pair.focus)
        assertEquals(setOf(34, 35), pair.group.filter { pair.notes[it] and pairDigits != 0 }.toSet())
        assertEquals(pairDigits or Sudoku.bit(7), pair.notes[34])
        assertEquals(pairDigits or Sudoku.bit(3), pair.notes[35])
        val pairSolution = checkNotNull(Sudoku.uniqueSolution(pair.board))
        assertEquals(setOf(1, 5), pair.focus.map { pairSolution[it] }.toSet())

        val wing = Guide.lesson(Technique.XY_WING).pictures.single().diagram
        val pivot = 31
        val firstPincer = 35
        val secondPincer = 76
        assertEquals(setOf(pivot, firstPincer, secondPincer), wing.focus)
        assertEquals(listOf(3, 7), Sudoku.digitsIn(Sudoku.candidates(wing.board, pivot)))
        assertEquals(listOf(1, 3), Sudoku.digitsIn(Sudoku.candidates(wing.board, firstPincer)))
        assertEquals(listOf(1, 7), Sudoku.digitsIn(Sudoku.candidates(wing.board, secondPincer)))
        assertTrue(firstPincer in Sudoku.peers[pivot] && secondPincer in Sudoku.peers[pivot])
        val eliminations = Sudoku.peers[firstPincer].intersect(Sudoku.peers[secondPincer].toSet())
            .filter { wing.board[it] == 0 && Sudoku.candidates(wing.board, it) and Sudoku.bit(1) != 0 }.toSet()
        assertEquals(setOf(80), eliminations)
        assertEquals(eliminations, wing.crossed)
        assertEquals(Sudoku.bit(1), wing.notes[80])
        assertTrue(checkNotNull(Sudoku.uniqueSolution(wing.board))[80] != 1)
    }

    @Test fun xWingAndSwordfishExamplesShowAllCandidatePositionsAndEliminations() {
        assertRowFish(Technique.X_WING, 1, setOf(2, 8), setOf(0, 6))
        assertRowFish(Technique.SWORDFISH, 2, setOf(0, 4, 8), setOf(0, 3, 7))
    }

    private fun assertRowFish(technique: Technique, digit: Int, rows: Set<Int>, columns: Set<Int>) {
        val diagram = Guide.lesson(technique).pictures.single().diagram
        val mask = Sudoku.bit(digit)
        val candidates = diagram.board.indices.filter { diagram.board[it] == 0 && Sudoku.candidates(diagram.board, it) and mask != 0 }
        val pattern = candidates.filter { it / 9 in rows }.toSet()
        assertEquals(rows, pattern.map { it / 9 }.toSet())
        assertEquals(columns, pattern.map { it % 9 }.toSet())
        assertEquals(pattern, diagram.focus)
        val eliminations = candidates.filter { it / 9 !in rows && it % 9 in columns }.toSet()
        assertTrue(eliminations.isNotEmpty())
        assertEquals(eliminations, diagram.crossed)
        assertEquals(pattern + eliminations, diagram.notes.indices.filter { diagram.notes[it] == mask }.toSet())
        val solution = checkNotNull(Sudoku.uniqueSolution(diagram.board))
        assertTrue(eliminations.none { solution[it] == digit })
    }
}
