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

    private fun IntArray.crossOff(removals: List<CandidateRemoval>) {
        for ((cell, digit) in removals) this[cell] = this[cell] or Sudoku.bit(digit)
    }

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
            val crossedOff = IntArray(Sudoku.COUNT)
            while (0 in work) {
                val hint = checkNotNull(Sudoku.hint(work, crossedOff = crossedOff))
                assertEquals(0, work[hint.cell])
                assertTrue(hint.technique in Guide.order)
                assertTrue(hint.steps.isNotEmpty())
                assertTrue(hint.steps.all { step -> step.technique == null || step.technique in Guide.order })
                if (hint.removals.isNotEmpty()) {
                    for ((cell, digit) in hint.removals) {
                        assertEquals(0, work[cell])
                        assertTrue(puzzle.solution[cell] != digit)
                        assertEquals(0, crossedOff[cell] and Sudoku.bit(digit))
                    }
                    crossedOff.crossOff(hint.removals)
                } else {
                    assertEquals(puzzle.solution[hint.cell], hint.digit)
                    work[hint.cell] = hint.digit
                    crossedOff.fill(0)
                }
            }
            assertArrayEquals(puzzle.solution, work)
        }
    }

    @Test fun shortProofUsesTheSameTermsAsTheGuideAndNamesTheDeadEnd() {
        val board = board(Guide.PROOF_BOARD)
        val hint = checkNotNull(Sudoku.hint(board, crossedOff = IntArray(Sudoku.COUNT) { Sudoku.ALL }))
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

    @Test fun candidateHintsAdvanceAcrossTheSameBoardBeforeFallingBackToProof() {
        val puzzle = board(Guide.PROOF_BOARD)
        val first = checkNotNull(Sudoku.hint(puzzle))
        assertEquals(Technique.LOCKED_CANDIDATES, first.technique)
        assertEquals(listOf(CandidateRemoval(39, 2), CandidateRemoval(40, 2)), first.removals)
        val crossedOff = IntArray(Sudoku.COUNT).apply { crossOff(first.removals) }
        var handledMore = false
        repeat(Sudoku.COUNT * Sudoku.SIZE) {
            val next = checkNotNull(Sudoku.hint(puzzle, crossedOff = crossedOff))
            if (next.technique == Technique.CONTRADICTION) {
                assertTrue(handledMore)
                assertEquals(Guide.PROOF_BOARD, puzzle.joinToString(""))
                return
            }
            assertTrue(next.removals.isNotEmpty())
            assertTrue(next.removals.all { crossedOff[it.cell] and Sudoku.bit(it.digit) == 0 })
            crossedOff.crossOff(next.removals)
            handledMore = true
        }
        throw AssertionError("Did not reach the proof after all candidate eliminations")
    }

    @Test fun anEliminationHintListsOnlyCandidatesStillToCrossOff() {
        val puzzle = board(Guide.PROOF_BOARD)
        val crossedOff = IntArray(Sudoku.COUNT).apply { this[39] = Sudoku.bit(2) }
        val hint = checkNotNull(Sudoku.hint(puzzle, crossedOff = crossedOff))
        assertEquals(Technique.LOCKED_CANDIDATES, hint.technique)
        assertEquals(40, hint.cell)
        assertEquals(listOf(CandidateRemoval(40, 2)), hint.removals)
    }

    @Test fun theReportedEasyPuzzleExplainsTheSingleInOneSentence() {
        val puzzle = board("068290000000700001170038609810027054702003196056904000001389007604000900900540208")
        val hint = checkNotNull(Sudoku.hint(puzzle))
        assertEquals(20, hint.cell)
        assertEquals(5, hint.digit)
        assertEquals(Technique.HIDDEN_SINGLE, hint.technique)
        assertEquals(listOf("In row 3, only column 3 can take 5."), hint.steps.map(HintStep::text))
    }

    @Test fun expertAndHiddenPairHintsShowOnlyValidCandidatesToCrossOff() {
        val cases = listOf(
            Technique.HIDDEN_PAIR to "079000000060903107800750900008010030706000005003400000907200000680000002002160079",
            Technique.X_WING to "060050780000276003057810026400985600689000000070601894000140060106090008790060010",
            Technique.XY_WING to "168934752723518964549762381430000100005109036916203800690000003300090010050300600",
        )
        for ((technique, data) in cases) {
            val puzzle = board(data)
            val solution = checkNotNull(Sudoku.uniqueSolution(puzzle))
            val crossedOff = IntArray(Sudoku.COUNT)
            val hint = checkNotNull((0..30).asSequence().mapNotNull {
                Sudoku.hint(puzzle, crossedOff = crossedOff)?.also { hint -> crossedOff.crossOff(hint.removals) }
            }
                .takeWhile { it.removals.isNotEmpty() }.firstOrNull { it.technique == technique })
            assertTrue(hint.focus.isNotEmpty())
            for ((cell, digit) in hint.removals) {
                assertEquals(0, puzzle[cell])
                assertTrue(Sudoku.candidates(puzzle, cell) and Sudoku.bit(digit) != 0)
                assertTrue(solution[cell] != digit)
            }
            assertEquals(data, puzzle.joinToString(""))
        }
    }

    @Test fun swordfishFindsTheRemovalsInTheGuideInEitherOrientation() {
        val example = Guide.lesson(Technique.SWORDFISH).pictures.single().diagram
        fun transpose(cell: Int) = cell % Sudoku.SIZE * Sudoku.SIZE + cell / Sudoku.SIZE
        val notes = example.notes
        val rowPattern = checkNotNull(Sudoku.fish(notes, 3))
        assertEquals(Technique.SWORDFISH, rowPattern.reason.technique)
        assertEquals(Sudoku.bit(2), rowPattern.mask)
        assertEquals(example.crossed, rowPattern.cells.toSet())
        val colPattern = checkNotNull(Sudoku.fish(IntArray(Sudoku.COUNT) { notes[transpose(it)] }, 3))
        assertEquals(Sudoku.bit(2), colPattern.mask)
        assertEquals(example.crossed, colPattern.cells.map(::transpose).toSet())
    }

    @Test fun deepProofBranchesEachReachAnExplainableContradiction() {
        val board = board("079000000060903107800750900008010030706000005003400000907200000680000002002160079")
        val hint = checkNotNull(Sudoku.hint(board, crossedOff = IntArray(Sudoku.COUNT) { Sudoku.ALL }))
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

    @Test fun aTimeoutStillShowsAnUnreadEliminationAlreadyProved() {
        val puzzle = board(Guide.PROOF_BOARD)
        fun budgetAfterOneElimination(): HintBudget {
            var reads = 0
            return HintBudget(1) { if (reads++ <= 2) 0L else 2_000_000L }
        }
        val hint = checkNotNull(Sudoku.hint(puzzle, budgetAfterOneElimination()))
        assertEquals(listOf(CandidateRemoval(39, 2), CandidateRemoval(40, 2)), hint.removals)
        assertThrows(HintTimedOut::class.java) {
            val crossedOff = IntArray(Sudoku.COUNT).apply { crossOff(hint.removals) }
            Sudoku.hint(puzzle, budgetAfterOneElimination(), crossedOff)
        }
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
