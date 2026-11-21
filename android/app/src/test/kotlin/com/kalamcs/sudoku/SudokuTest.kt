package com.kalamcs.sudoku

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SudokuTest {
    private fun board(text: String) = checkNotNull(Sudoku.decode(text))

    @Test fun generatedPuzzlesAreUniqueAndMatchDifficulty() {
        val random = Random(0x1a2b3c4d)
        for (difficulty in Difficulty.entries) repeat(8) {
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
        val evidence = checkNotNull(hint.steps[5].details).invoke().joinToString { it.text }
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
                choices.forEach { checkBranches(checkNotNull(it.details).invoke()) }
            } else {
                val deadEnd = steps.first { it.text.startsWith("There is nowhere") || it.text.startsWith("No digit") }
                assertTrue(checkNotNull(deadEnd.details).invoke().isNotEmpty())
                deadEnds++
            }
        }
        checkBranches(hint.steps)
        assertTrue(deadEnds >= 2)
        assertEquals(0, board[hint.cell])
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
}
