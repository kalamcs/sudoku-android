package com.kalamcs.sudoku

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameTest {
    private fun game(): Game {
        val givens = checkNotNull(Sudoku.decode(Guide.NAKED_BOARD))
        return Game(Puzzle(Difficulty.EASY, givens, checkNotNull(Sudoku.solve(givens))))
    }

    @Test fun notesConflictsAndUndoRedoStayInSync() {
        val game = game()
        game.select(40)
        assertTrue(game.autoNotes())
        assertEquals(Sudoku.bit(5), game.notes[40])
        assertTrue(game.enter(5))
        assertEquals(5, game.values[40])
        assertEquals(0, game.notes[40])
        assertTrue(Sudoku.peers[40].all { game.notes[it] and Sudoku.bit(5) == 0 })
        assertTrue(game.undo())
        assertEquals(0, game.values[40])
        assertEquals(Sudoku.bit(5), game.notes[40])
        assertTrue(game.redo())
        assertTrue(game.enter(3))
        assertEquals(listOf(40), game.mistakes())
        assertTrue(game.conflicts[40])
        game.markMistakes()
        assertEquals(setOf(40), game.markedErrors)
        assertTrue(game.erase())
        assertFalse(game.conflicts[40])
        assertTrue(game.markedErrors.isEmpty())
        assertFalse(game.canRedo)
    }

    @Test fun givenCellsCanHighlightDigitsWithoutEditingOrAddingHistory() {
        val game = game()
        game.select(0)
        assertFalse(game.enter(5))
        assertEquals(5, game.highlightedDigit)
        assertEquals(5, game.values[0])
        assertFalse(game.canUndo)
        game.select(2)
        assertEquals(0, game.highlightedDigit)
    }

    @Test fun digitFirstSelectionAppliesToMultipleCellsAndPreservesUndoHistory() {
        val game = game()
        assertTrue(game.changeEntryOrder(EntryOrder.DIGIT_FIRST))
        assertFalse(game.tapDigit(4))
        assertEquals(4, game.matchingDigit)
        assertEquals(0, game.values[2])
        assertFalse(game.canUndo)

        assertFalse(game.tapCell(0))
        assertEquals(5, game.values[0])
        assertEquals(4, game.pickedDigit)
        assertTrue(game.tapCell(2))
        assertFalse(game.tapCell(2))
        assertEquals(4, game.values[2])
        assertTrue(game.tapCell(40))
        assertEquals(4, game.values[40])
        assertTrue(game.undo())
        assertEquals(0, game.values[40])
        assertEquals(4, game.values[2])
        assertTrue(game.redo())
        assertEquals(4, game.values[40])
        assertEquals(4, game.matchingDigit)
        assertFalse(game.tapDigit(4))
        assertEquals(0, game.pickedDigit)
    }

    @Test fun digitFirstNotesAndSelectionResumeFromTheSavedPuzzle() {
        val game = game()
        game.changeEntryOrder(EntryOrder.DIGIT_FIRST)
        game.notesMode = true
        assertFalse(game.tapDigit(4))
        assertTrue(game.tapCell(2))
        assertEquals(Sudoku.bit(4), game.notes[2])
        assertEquals(4, game.matchingNote(2))
        assertTrue(game.tapCell(40))

        val restored = checkNotNull(SaveFormat.game(SaveFormat.encode(game, 0)))
        assertEquals(EntryOrder.DIGIT_FIRST, restored.entryOrder)
        assertEquals(4, restored.pickedDigit)
        assertTrue(restored.notesMode)
        assertTrue(restored.tapCell(2))
        assertEquals(0, restored.notes[2])
        assertEquals(Sudoku.bit(4), restored.notes[40])
        assertTrue(restored.changeEntryOrder(EntryOrder.CELL_FIRST))
        assertEquals(0, restored.pickedDigit)
        assertTrue(restored.tapDigit(5))
        assertEquals(Sudoku.bit(5), restored.notes[2])
    }

    @Test fun correctingTheLatestEntryRestoresOnlyTheNotesItRemoved() {
        val game = game()
        val four = Sudoku.bit(4)
        val two = Sudoku.bit(2)
        val eight = Sudoku.bit(8)
        game.notes[3] = four or two or eight
        game.notes[5] = eight
        game.notes[40] = four

        game.select(2)
        assertTrue(game.enter(4))
        assertEquals(two or eight, game.notes[3])
        game.select(40)
        game.select(2)
        assertTrue(game.enter(2))
        assertEquals(four or eight, game.notes[3])
        assertEquals(eight, game.notes[5])
        assertEquals(four, game.notes[40])
        assertTrue(game.erase())
        assertEquals(four or two or eight, game.notes[3])
    }

    @Test fun editingAnotherCellInvalidatesNoteRestoration() {
        val game = game()
        game.notes[3] = Sudoku.bit(4)
        game.select(2)
        assertTrue(game.enter(4))

        game.select(40)
        game.notesMode = true
        assertTrue(game.enter(5))
        game.notesMode = false
        game.select(2)
        assertTrue(game.enter(2))
        assertEquals(0, game.notes[3])
        assertEquals(Sudoku.bit(5), game.notes[40])
    }

    @Test fun undoRedoAndSavedGamesPreserveEligibleNoteRestoration() {
        val game = game()
        val four = Sudoku.bit(4)
        game.notes[3] = four
        game.select(2)
        assertTrue(game.enter(4))
        assertTrue(game.undo())
        assertEquals(four, game.notes[3])
        assertTrue(game.redo())
        assertEquals(0, game.notes[3])

        val restored = checkNotNull(SaveFormat.game(SaveFormat.encode(game, 0)))
        assertTrue(restored.enter(2))
        assertEquals(four, restored.notes[3])
    }

    @Test fun savedUndoAndRedoRestoreEntriesNotesAndPruningInOrder() {
        val game = game()
        game.select(3)
        game.notesMode = true
        assertTrue(game.enter(4))
        game.notesMode = false
        game.select(2)
        assertTrue(game.enter(4))
        assertTrue(game.enter(2))
        assertTrue(game.undo())

        val restored = checkNotNull(SaveFormat.game(SaveFormat.encode(game, 0)))
        assertEquals(4, restored.values[2])
        assertEquals(0, restored.notes[3])
        assertEquals(PrunedNotes(2, listOf(3)), restored.prunedNotes)
        assertTrue(restored.undo())
        assertEquals(0, restored.values[2])
        assertEquals(Sudoku.bit(4), restored.notes[3])
        assertTrue(restored.undo())
        assertEquals(0, restored.notes[3])
        assertFalse(restored.canUndo)

        val reopened = checkNotNull(SaveFormat.game(SaveFormat.encode(restored, 0)))
        assertTrue(reopened.redo())
        assertEquals(Sudoku.bit(4), reopened.notes[3])
        assertTrue(reopened.redo())
        assertEquals(4, reopened.values[2])
        assertEquals(0, reopened.notes[3])
        assertTrue(reopened.redo())
        assertEquals(2, reopened.values[2])
        assertEquals(Sudoku.bit(4), reopened.notes[3])
        assertFalse(reopened.canRedo)
    }

    @Test fun damagedHistoryDoesNotPreventResumingTheCurrentBoard() {
        val game = game()
        game.select(2)
        assertTrue(game.enter(4))
        assertTrue(game.enter(2))
        assertTrue(game.undo())
        val saved = JSONObject(SaveFormat.encode(game, 0))
        saved.getJSONObject("history").getJSONArray("undo").put(JSONObject())

        val restored = checkNotNull(SaveFormat.game(saved.toString()))
        assertEquals(4, restored.values[2])
        assertFalse(restored.canUndo)
        assertTrue(restored.redo())
        assertEquals(2, restored.values[2])
    }

    @Test fun selectedDigitsHighlightOnlyMatchingPencilMarks() {
        val game = game()
        game.notes[40] = Sudoku.bit(5) or Sudoku.bit(9)
        game.notes[2] = Sudoku.bit(9)

        game.select(0)
        assertEquals(5, game.matchingNote(40))
        assertEquals(0, game.matchingNote(2))
        assertFalse(game.enter(9))
        assertEquals(9, game.matchingNote(40))
        assertEquals(9, game.matchingNote(2))
        assertFalse(game.canUndo)

        game.select(40)
        assertEquals(0, game.matchingNote(2))
        game.notesMode = true
        assertTrue(game.enter(9))
        assertEquals(0, game.matchingNote(40))
        assertEquals(9, game.matchingNote(2))
        assertEquals(Sudoku.bit(5), game.notes[40])
        assertTrue(game.undo())
        assertEquals(9, game.matchingNote(40))
        game.notesMode = false
        assertTrue(game.enter(5))
        assertEquals(5, game.matchingDigit)
        assertEquals(0, game.matchingNote(2))
    }

    @Test fun pausedGameDoesNotAccumulateTime() {
        val game = game()
        game.start(1_000)
        game.stop(3_345)
        game.paused = true
        game.start(30_000)
        assertEquals(2_345L, game.elapsed(45_000))
        assertFalse(game.enter(3))
        game.paused = false
        game.start(50_000)
        assertEquals(3_960L, game.elapsed(51_615))
    }

    @Test fun completingAReplayDoesNotRecordAnotherWinEvenAfterRestoringIt() {
        val game = game()
        assertTrue(game.finish(0))
        game.reset(1_000)
        assertFalse(game.finished)

        val restored = checkNotNull(SaveFormat.game(SaveFormat.encode(game, 2_000)))
        assertFalse(restored.finish(3_000))
        assertTrue(restored.finished)
    }

    @Test fun replacingThePuzzleOnlyProtectsEntriesAndNotes() {
        val game = game()
        game.start(1_000)
        game.stop(2_000)
        game.select(2)
        assertFalse(game.hasProgress)
        assertTrue(game.enter(4))
        assertTrue(game.hasProgress)
        assertTrue(game.erase())
        assertFalse(game.hasProgress)
        game.notesMode = true
        assertTrue(game.enter(4))
        assertTrue(game.hasProgress)
    }

    @Test fun oldSaveRestoresEntriesNotesAndTimeAndCanRoundTripNatively() {
        val game = game()
        val legacy = JSONObject()
            .put("v", 1)
            .put("d", "easy")
            .put("p", Guide.NAKED_BOARD)
            .put("s", game.puzzle.solution.joinToString(""))
            .put("b", Guide.NAKED_BOARD.replaceRange(2, 3, "4"))
            .put("n", List(81) { if (it == 40) "59" else "" }.joinToString("."))
            .put("e", 95)
            .put("z", 1)
            .put("w", 0)
        val restored = checkNotNull(SaveFormat.game(legacy.toString()))
        assertEquals(4, restored.values[2])
        assertEquals(listOf(5, 9), Sudoku.digitsIn(restored.notes[40]))
        assertEquals(EntryOrder.CELL_FIRST, restored.entryOrder)
        assertEquals(0, restored.pickedDigit)
        assertTrue(restored.editHistory().undo.isEmpty())
        assertTrue(restored.editHistory().redo.isEmpty())
        assertEquals(95_000L, restored.elapsed(1234))
        assertTrue(restored.paused)
        restored.select(40)
        restored.notesMode = true
        val native = checkNotNull(SaveFormat.game(SaveFormat.encode(restored, 1432)))
        assertEquals(40, native.selected)
        assertTrue(native.notesMode)
        assertEquals(95_000L, native.elapsedMs)
        assertNull(SaveFormat.game(legacy.put("n", "garbage").toString()))
    }

    @Test fun oldStatsKeepWinsBestTimeAndAverage() {
        val legacy = """{"hard":{"best":258,"bestAt":1700000000000,"wins":3,"totalTime":994,"timedWins":3}}"""
        val restored = SaveFormat.records(legacy)
        val hard = restored.getValue(Difficulty.HARD)
        assertEquals(3L, hard.wins)
        assertEquals(258L, hard.best)
        assertEquals(331L, hard.average)
        assertTrue(hard.win(250, 1700000010000))
        val native = SaveFormat.records(SaveFormat.encode(restored)).getValue(Difficulty.HARD)
        assertEquals(4L, native.wins)
        assertEquals(250L, native.best)
        assertEquals(311L, native.average)
    }
}
