// Copyright (c) 2026 Kalam Shah
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.kalamcs.sudoku

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.ColorDrawable
import android.os.SystemClock
import android.util.TypedValue
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.widget.AppCompatButton
import androidx.core.view.ViewCompat
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.GeneralClickAction
import androidx.test.espresso.action.Press
import androidx.test.espresso.action.Tap
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isPlatformPopup
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.hasErrorText
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.isCompletelyDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.ViewMatchers.isSelected
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withHint
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.espresso.util.TreeIterables
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.firebase.FirebaseApp
import org.hamcrest.Matcher
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.anyOf
import org.hamcrest.Matchers.not
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class NativeGameTest {
    private val targetContext get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val appearanceStore by lazy { AppearanceStore(targetContext) }
    private val gamePreferences by lazy { targetContext.getSharedPreferences("sudoku", Context.MODE_PRIVATE) }
    private lateinit var initialAppearance: AppearanceSettings
    private lateinit var initialGamePreferences: Map<String, *>

    @Before fun saveTestPreferences() {
        initialAppearance = appearanceStore.load()
        initialGamePreferences = gamePreferences.all
        appearanceStore.save(initialAppearance.copy(keypadLayout = KeypadLayout.AUTO, entryOrder = EntryOrder.CELL_FIRST))
        gamePreferences.edit().putBoolean(LEGACY_IMPORTED, true).apply()
    }

    @After fun restoreTestPreferences() {
        try {
            val editor = gamePreferences.edit()
                .putString(SaveFormat.GAME, initialGamePreferences[SaveFormat.GAME] as String?)
                .putString(SaveFormat.STATS, initialGamePreferences[SaveFormat.STATS] as String?)
            val legacyImported = initialGamePreferences[LEGACY_IMPORTED] as Boolean?
            if (legacyImported == null) editor.remove(LEGACY_IMPORTED)
            else editor.putBoolean(LEGACY_IMPORTED, legacyImported)
            assertTrue("Could not restore the original puzzle preferences", editor.commit())
        } finally {
            appearanceStore.save(initialAppearance)
        }
    }

    @Test fun everyDifficultyCanCreateAPuzzleInTheInstalledApp() {
        val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets
        for (difficulty in Difficulty.entries) {
            val puzzle = RatedPuzzleBank.generate(difficulty, assets)
            assertEquals(difficulty, puzzle.difficulty)
            assertEquals(1, Sudoku.countSolutions(puzzle.givens, 2))
            assertTrue(puzzle.solution.contentEquals(Sudoku.solve(puzzle.givens)))
        }
    }

    @Test fun dashboardShowsTheSavedPuzzleAndAllFourDifficultyTiles() {
        installFixture()
        ActivityScenario.launch(SudokuActivity::class.java).use { scenario ->
            onView(withText("Continue Puzzle")).check(matches(isDisplayed()))
            onView(isAssignableFrom(SavedPuzzlePreview::class.java)).check(matches(isDisplayed()))
            scenario.onActivity { activity ->
                val buttons = TreeIterables.depthFirstViewTraversal(activity.findViewById(R.id.page_content))
                    .filterIsInstance<AppCompatButton>()
                val difficulties = Difficulty.entries.map(Difficulty::label)
                val tiles = buttons.filter { it.text.toString() in difficulties }
                assertEquals(difficulties, tiles.map { it.text.toString() })
                assertTrue(tiles.all { it.parent === tiles.first().parent })
            }
            onView(withText("Statistics")).perform(scrollTo()).check(matches(isDisplayed()))
            onView(withText("Best Time\n(Achieved On)")).perform(scrollTo()).check(matches(isDisplayed()))
            onView(withText("How to Play")).perform(scrollTo()).check(matches(isDisplayed()))
            onView(withText("Settings")).perform(scrollTo()).check(matches(isDisplayed()))
        }
    }

    @Test fun checkingErrorsKeepsTimeAndOnlyHighlightsWithPermission() {
        installFixture()
        ActivityScenario.launch(SudokuActivity::class.java).use { scenario ->
            onView(withText("Continue Puzzle")).perform(click())
            onView(withText("3")).perform(scrollTo(), click())
            onView(withContentDescription("Game menu")).perform(scrollTo(), click())
            onView(withText("New game")).inRoot(isPlatformPopup()).check(doesNotExist())
            val paused = displayedTime(scenario)
            SystemClock.sleep(1_500)
            assertEquals(paused, displayedTime(scenario))

            pressBack()
            onView(withText("Check\nProgress")).perform(scrollTo(), click())
            onView(withText("1 incorrect entry")).check(matches(isDisplayed()))
            val checking = displayedTime(scenario)
            SystemClock.sleep(1_500)
            assertTrue(displayedTime(scenario) != checking)
            onView(withText("Close")).perform(click())
            assertCellDescription(1, "Row 1, column 2, 3")

            onView(withText("Check\nProgress")).perform(scrollTo(), click())
            onView(withText("Highlight")).perform(click())
            assertCellDescription(1, "Row 1, column 2, 3, incorrect")

            onView(withText("Erase")).perform(scrollTo(), click())
            onView(withText("Check\nProgress")).perform(scrollTo(), click())
            onView(withText("There are no incorrect entries so far.")).check(matches(isDisplayed()))
            onView(withText("Highlight")).check(doesNotExist())
        }
    }

    @Test fun hintShowsCandidateEliminationsWithAReadOnlyBoardPreview() {
        installFixture()
        ActivityScenario.launch(SudokuActivity::class.java).use { scenario ->
            onView(withText("Continue Puzzle")).perform(click())
            onView(withText("Hint")).perform(scrollTo(), click())
            waitForCandidateHint()
            onView(withText("Row 5, column 4 · 2")).check(matches(isDisplayed()))
            onView(withText("Show board preview")).perform(scrollTo(), click())
            onView(withText("Hide board preview")).check(matches(isDisplayed()))
            onView(allOf(withText("Locked candidates"), isAssignableFrom(AppCompatButton::class.java)))
                .perform(scrollTo(), click())
            onView(withText("Worked example")).check(matches(isDisplayed()))
            scenario.recreate()
            onView(withText("Worked example")).check(matches(isDisplayed()))
            pressBack()
            waitForCandidateHint()
            onView(withText("Show first cell")).check(matches(isDisplayed()))
            onView(withText("Hide board preview")).perform(scrollTo()).check(matches(isDisplayed()))
            onView(withText("Show first cell")).perform(click())
            assertCellDescription(39, "Row 5, column 4, empty")
            onView(withText("Undo")).check(matches(not(isEnabled())))
        }
    }

    @Test fun repeatHintWaitsUntilTheSuggestedCandidatesAreCrossedOff() {
        installFixture { it.autoNotes() }
        ActivityScenario.launch(SudokuActivity::class.java).use {
            onView(withText("Continue Puzzle")).perform(click())
            val hintButton = onView(withText("Hint"))
            val firstCandidate = onView(withText("Row 5, column 4 · 2")).inRoot(isDialog())
            val close = onView(withText("Close")).inRoot(isDialog())

            hintButton.perform(scrollTo(), click())
            waitForCandidateHint()
            firstCandidate.check(matches(isDisplayed()))
            close.perform(click())
            hintButton.perform(scrollTo(), click())
            firstCandidate.check(matches(isDisplayed()))
            close.perform(click())

            onView(withText("Notes: Off")).perform(scrollTo(), click())
            val board = onView(isAssignableFrom(BoardView::class.java))
            val digit = onView(withText("2"))
            board.perform(scrollTo(), tapCell(39))
            digit.perform(scrollTo(), click())
            hintButton.perform(scrollTo(), click())
            waitForCandidateHint()
            firstCandidate.check(doesNotExist())
            onView(withText("Row 5, column 5 · 2")).inRoot(isDialog()).check(matches(isDisplayed()))
            close.perform(click())

            board.perform(scrollTo(), tapCell(40))
            digit.perform(scrollTo(), click())
            hintButton.perform(scrollTo(), click())
            waitForCandidateHint()
            firstCandidate.check(doesNotExist())
        }
    }

    @Test fun hintSkipsEliminationsRecordedBeforeItWasOpened() {
        installFixture { game ->
            game.autoNotes()
            game.notesMode = true
            game.select(39)
            game.enter(2)
            game.select(40)
            game.enter(2)
            game.notesMode = false
        }
        ActivityScenario.launch(SudokuActivity::class.java).use {
            onView(withText("Continue Puzzle")).perform(click())
            onView(withText("Hint")).perform(scrollTo(), click())
            waitForCandidateHint()
            onView(withText("Row 5, column 4 · 2")).check(doesNotExist())
            onView(withText("Row 5, column 5 · 2")).check(doesNotExist())
        }
    }

    private fun waitForCandidateHint() {
        onView(withText("Candidates to cross off")).inRoot(isDialog()).check(matches(isDisplayed()))
    }

    @Test fun replayCanBeCompletedWithoutChangingTheWinRecord() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = GameStore(context)
        val solution = checkNotNull(Sudoku.solve(checkNotNull(Sudoku.decode(Guide.PROOF_BOARD))))
        val givens = solution.copyOf().apply { this[0] = 0 }
        val before = store.records().getValue(Difficulty.EASY).wins
        store.save(Game(Puzzle(Difficulty.EASY, givens, solution), elapsedMs = 11_000), SystemClock.elapsedRealtime())

        ActivityScenario.launch(SudokuActivity::class.java).use {
            onView(withText("Continue Puzzle")).perform(click())
            onView(withText(solution[0].toString())).perform(scrollTo(), click())
            onView(withText("View board")).perform(click())
            val recorded = store.records().getValue(Difficulty.EASY)
            assertEquals(before + 1, recorded.wins)

            onView(withText("Replay\nPuzzle")).perform(scrollTo(), click())
            onView(withText("Replay")).perform(click())
            onView(withText(solution[0].toString())).perform(scrollTo(), click())
            onView(withText("Puzzle solved")).check(matches(isDisplayed()))
            assertEquals(recorded, store.records().getValue(Difficulty.EASY))
        }
    }

    @Test fun restoredCompletePuzzleRecordsTheWinOnce() {
        val store = GameStore(InstrumentationRegistry.getInstrumentation().targetContext)
        val solution = checkNotNull(Sudoku.solve(checkNotNull(Sudoku.decode(Guide.PROOF_BOARD))))
        val givens = solution.copyOf().apply { this[0] = 0 }
        val before = store.records().getValue(Difficulty.EASY).wins
        store.save(Game(Puzzle(Difficulty.EASY, givens, solution), values = solution.copyOf(), elapsedMs = 11_000),
            SystemClock.elapsedRealtime())

        ActivityScenario.launch(SudokuActivity::class.java).use {
            onView(anyOf(withText("New personal best"), withText("Puzzle solved")))
                .inRoot(isDialog()).check(matches(isDisplayed()))
            assertEquals(before + 1, store.records().getValue(Difficulty.EASY).wins)
            onView(withText("Home")).inRoot(isDialog()).perform(click())
        }

        ActivityScenario.launch(SudokuActivity::class.java).use {
            onView(withText("Start New Puzzle")).check(matches(isDisplayed()))
            onView(withText("Continue Puzzle")).check(doesNotExist())
            assertEquals(before + 1, store.records().getValue(Difficulty.EASY).wins)
        }
    }

    @Test fun homeKeepsTheSavedPuzzleWithoutADiscardAction() {
        installFixture()
        ActivityScenario.launch(SudokuActivity::class.java).use { scenario ->
            onView(withText("Discard")).check(doesNotExist())
            scenario.onActivity { it.onConfigurationChanged(Configuration(it.resources.configuration)) }
            onView(withText("Continue Puzzle")).check(matches(isDisplayed()))
        }
    }

    @Test fun startingNewGameAsksBeforeReplacingAnUntouchedSaveShownOnHome() {
        installFixture(elapsedMs = 11_000)
        ActivityScenario.launch(SudokuActivity::class.java).use {
            onView(withText("Continue Puzzle")).check(matches(isDisplayed()))
            onView(allOf(withText("Easy"), isAssignableFrom(AppCompatButton::class.java))).perform(scrollTo(), click())
            onView(withText("Start a new easy game?")).check(matches(isDisplayed()))
            onView(withText("This will replace your saved puzzle.")).check(matches(isDisplayed()))
            onView(withText("Cancel")).perform(click())
            onView(withText("Continue Puzzle")).perform(click())
            onView(withContentDescription("Difficulty Extreme")).check(matches(isDisplayed()))
        }
    }

    @Test fun undoAndRedoSurviveClosingAndContinuingThePuzzle() {
        installFixture()
        ActivityScenario.launch(SudokuActivity::class.java).use {
            onView(withText("Continue Puzzle")).perform(click())
            onView(withText("3")).perform(scrollTo(), click())
            onView(withText("5")).perform(scrollTo(), click())
            onView(withText("Undo")).perform(scrollTo(), click())
            assertCellDescription(1, "Row 1, column 2, 3")
            onView(withContentDescription("Back to home")).perform(click())
        }

        ActivityScenario.launch(SudokuActivity::class.java).use {
            onView(withText("Continue Puzzle")).perform(click())
            assertCellDescription(1, "Row 1, column 2, 3")
            onView(withText("Redo")).perform(scrollTo(), click())
            assertCellDescription(1, "Row 1, column 2, 5")
            onView(withText("Undo")).perform(scrollTo(), click())
            assertCellDescription(1, "Row 1, column 2, 3")
            onView(withText("Undo")).perform(scrollTo(), click())
            assertCellDescription(1, "Row 1, column 2, empty")
            onView(withText("Undo")).check(matches(not(isEnabled())))
        }
    }

    @Test fun entryOrderSettingKeepsTheDigitSelectedAndCanReturnToCellFirst() {
        installFixture()
        val preferences = AppearanceStore(InstrumentationRegistry.getInstrumentation().targetContext)
        val initial = preferences.load()
        preferences.save(initial.copy(entryOrder = EntryOrder.CELL_FIRST))
        try {
            ActivityScenario.launch(SudokuActivity::class.java).use { scenario ->
                val board = onView(isAssignableFrom(BoardView::class.java))
                val five = onView(withText("5"))
                onView(withText("Continue Puzzle")).perform(click())
                openMenuDestination("Game menu", "Settings")
                onView(withContentDescription("Entry order, Cell first")).perform(scrollTo(), click())
                onView(withText("Digit first")).perform(click())
                scenario.recreate()
                onView(withContentDescription("Entry order, Digit first")).perform(scrollTo()).check(matches(isDisplayed()))
                onView(withContentDescription("Back")).perform(click())
                onView(withText("Digit first")).check(doesNotExist())
                five.perform(scrollTo(), click()).check(matches(isSelected()))
                assertCellDescription(1, "Row 1, column 2, empty")
                five.check { view, _ -> assertEquals("6 placements remaining", ViewCompat.getStateDescription(view!!)) }
                board.perform(tapCell(0), tapCell(1), tapCell(36))
                assertCellDescription(0, "Row 1, column 1, given 6")
                assertCellDescription(1, "Row 1, column 2, 5")
                assertCellDescription(36, "Row 5, column 1, 5")
                five.check(matches(isSelected()))
                five.check { view, _ -> assertEquals("4 placements remaining", ViewCompat.getStateDescription(view!!)) }
                five.perform(click()).check(matches(not(isSelected())))
                board.perform(tapCell(2))
                assertCellDescription(2, "Row 1, column 3, empty")

                openMenuDestination("Game menu", "Settings")
                onView(withContentDescription("Entry order, Digit first")).perform(scrollTo(), click())
                onView(withText("Cell first")).perform(click())
                onView(withContentDescription("Back")).perform(click())
                board.perform(tapCell(2))
                onView(withText("3")).perform(scrollTo(), click())
                assertCellDescription(2, "Row 1, column 3, 3")
            }
        } finally {
            preferences.save(initial)
        }
    }

    @Test fun homeHidesAnUntouchedTenSecondSaveAndShowsEarlierEdits() {
        installFixture(elapsedMs = 10_000)
        ActivityScenario.launch(SudokuActivity::class.java).use {
            onView(withText("Start New Puzzle")).check(matches(isDisplayed()))
            onView(withText("Continue Puzzle")).check(doesNotExist())
        }
        installFixture(elapsedMs = 0) {
            it.notesMode = true
            it.enter(5)
        }
        ActivityScenario.launch(SudokuActivity::class.java).use {
            onView(withText("Continue Puzzle")).check(matches(isDisplayed()))
        }
    }

    @Test fun homeUsesSideBySidePanelsInLandscapeWithRoomForStatistics() {
        ActivityScenario.launch(SudokuActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val landscape = Configuration(activity.resources.configuration).apply {
                    orientation = Configuration.ORIENTATION_LANDSCAPE
                    fontScale = 1f
                }
                val context = activity.createConfigurationContext(landscape)
                val ui = Ui(context)
                for (hasContinueCard in listOf(false, true)) {
                    val puzzles = ui.text("Start New Puzzle").apply { minimumHeight = ui.dp(if (hasContinueCard) 176 else 80) }
                    val stats = ui.text("Statistics").apply { minimumHeight = ui.dp(280) }
                    val actions = ui.text("Settings").apply { minimumHeight = ui.dp(48) }
                    val panels = HomePanels(context, ui, puzzles, stats, actions, hasContinueCard)
                    fun size(width: Int) {
                        panels.measure(View.MeasureSpec.makeMeasureSpec(ui.dp(width), View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                        panels.layout(0, 0, panels.measuredWidth, panels.measuredHeight)
                    }
                    size(840)
                    assertTrue(stats.left > puzzles.right)
                    assertEquals(puzzles.top, stats.top)
                    assertEquals(puzzles.left, actions.left)
                    if (hasContinueCard) assertEquals(stats.bottom, actions.bottom)
                    else assertEquals(puzzles.bottom + ui.dp(12), actions.top)

                    size(480)
                    assertEquals(puzzles.left, stats.left)
                    assertTrue(stats.top > puzzles.bottom)
                    assertTrue(actions.top > stats.bottom)
                }
            }
        }
    }

    @Test fun landscapeHomeWithoutContinueKeepsActionsVisible() {
        installFixture(elapsedMs = 10_000)
        ActivityScenario.launch(SudokuActivity::class.java).use { scenario ->
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            val deadline = SystemClock.uptimeMillis() + 8_000
            do {
                var measured = false
                scenario.onActivity { activity ->
                    val frame = activity.findViewById<ViewGroup>(R.id.page_content)
                    if (activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
                        frame.width > frame.height && frame.width > 0 && !frame.isLayoutRequested) {
                        assertEquals(frame.width, frame.getChildAt(0).width)
                        measured = true
                    }
                }
                if (measured) {
                    onView(withText("Start New Puzzle")).check(matches(isDisplayed()))
                    onView(withText("Continue Puzzle")).check(doesNotExist())
                    onView(withContentDescription("How to Play")).check(matches(isCompletelyDisplayed()))
                    onView(withContentDescription("Settings")).check(matches(isCompletelyDisplayed()))
                    return
                }
                SystemClock.sleep(150)
            } while (SystemClock.uptimeMillis() < deadline)
            throw AssertionError("The landscape dashboard did not finish laying out")
        }
    }

    @Test fun boardFitsAHeightConstrainedParent() {
        installFixture()
        ActivityScenario.launch(SudokuActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val view = BoardView(activity, GameTheme.OCEAN.palette(dark = false))
                val width = View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.AT_MOST)
                val height = View.MeasureSpec.makeMeasureSpec(180, View.MeasureSpec.AT_MOST)
                view.measure(width, height)
                assertEquals(180, view.measuredWidth)
                assertEquals(180, view.measuredHeight)
            }
        }
    }

    @Test fun autoFitsPhoneViewportsAfterSystemInsets() {
        installFixture()
        ActivityScenario.launch(SudokuActivity::class.java).use { scenario ->
            onView(withText("Continue Puzzle")).perform(click())
            scenario.onActivity { activity ->
                val layout = activity.findViewById<SolvingLayout>(R.id.page_scroll)
                val contents = TreeIterables.depthFirstViewTraversal(layout).toList()
                val board = contents.filterIsInstance<BoardView>().single()
                val text = contents.filterIsInstance<TextView>()
                val toolbar = text.single { it.text == "Undo" }.parent as ViewGroup
                assertEquals(listOf("Undo", "Erase", "Notes: Off", "Hint", "Redo"),
                    (0 until toolbar.childCount).map { (toolbar.getChildAt(it) as TextView).text.toString() })
                val shortcuts = text.single { it.text == "Check\nProgress" }.parent as ViewGroup
                assertEquals(listOf("Auto-fill\nCandidates", "Check\nProgress", "Restart\nPuzzle"),
                    (0 until shortcuts.childCount).map { shortcuts.getChildAt(it) }
                        .filterIsInstance<TextView>().map { it.text.toString() })

                val density = activity.resources.displayMetrics.density
                fun dp(value: Int) = (value * density).roundToInt()
                val originalWidth = layout.width
                val originalHeight = layout.height
                val interactive = contents.filterIsInstance<AppCompatButton>()
                val header = (contents.single { it.contentDescription == "Back to home" }.parent as View).parent as View
                for ((widthDp, heightDp) in listOf(320 to 568, 360 to 640, 393 to 852, 430 to 932,
                    568 to 320, 640 to 360, 696 to 294, 764 to 380, 852 to 393, 932 to 430, 700 to 1200, 430 to 640)) {
                    val landscape = widthDp > heightDp
                    val width = dp(widthDp - if (landscape) 48 else 0)
                    val height = dp(heightDp - if (landscape) 24 else 72)
                    measureViewport(layout, width, height)
                    val viewport = "${widthDp}×$heightDp"
                    val contentHeight = layout.getChildAt(0).height
                    assertEquals(board.width, board.height)
                    if (!landscape || widthDp > 640) {
                        assertFalse("$viewport requires scrolling (content $contentHeight, viewport $height)",
                            layout.canScrollVertically(1))
                    }
                    if (widthDp == 568) assertTrue(layout.canScrollVertically(1))
                    assertEquals(landscape, layout.sideBySide)
                    if (landscape) {
                        val columns = if (widthDp == 696 || widthDp >= 852) 9 else 3
                        assertEquals("$viewport automatic keypad", columns, layout.keypadColumns)
                        assertTrue("$viewport must keep the board and controls separate", board.right < toolbar.left)
                        val minPanel = 5 * dp(44) + 4 * dp(4)
                        val largestBoard = minOf(height - 2 * dp(4), width - 2 * dp(8) - dp(10) - minPanel)
                        assertEquals("$viewport must size the board independently of the keypad", largestBoard, board.height)
                        assertEquals("$viewport should align the header and board", board.top, header.top)
                        assertTrue("$viewport should place navigation in the right pane", header.left > board.right)
                    } else if (heightDp >= 852) {
                        assertEquals("$viewport should use the entire width between side margins", width - 2 * dp(8), board.width)
                    }
                    if (widthDp == 430 && heightDp == 640) assertEquals(9, layout.keypadColumns)
                    if (widthDp == 393 || heightDp >= 852 && !landscape) assertEquals(3, layout.keypadColumns)
                    val digits = interactive.filterIsInstance<DigitKey>()
                    assertEquals(9, digits.size)
                    val keypad = digits.first().parent as View
                    assertEquals(9 / layout.keypadColumns, digits.map { it.top }.distinct().size)
                    assertTrue("$viewport places shortcuts below the keypad", shortcuts.top >= keypad.bottom)
                    assertTrue(digits.all { it.stackedCount == (layout.keypadColumns == 9) })
                    val minKeyWidth = dp(if (widthDp == 696) 36 else 44)
                    assertTrue("$viewport keypad touch targets", digits.all { it.width >= minKeyWidth && it.height >= dp(44) })
                    if (widthDp == 696) assertTrue(digits.all { it.width < dp(44) })
                    val origin = IntArray(2).apply(layout::getLocationOnScreen)
                    for (button in interactive) {
                        val at = IntArray(2).apply(button::getLocationOnScreen)
                        assertTrue("$viewport clips ${button.text}", at[0] >= origin[0] && at[1] >= origin[1] &&
                            at[0] + button.width <= origin[0] + width &&
                            at[1] + button.height <= origin[1] + maxOf(height, contentHeight))
                    }
                }
                val originalSizes = text.associateWith { it.textSize }
                text.forEach { it.setTextSize(TypedValue.COMPLEX_UNIT_PX, it.textSize * 1.3f) }
                measureViewport(layout, dp(393), dp(780))
                assertFalse("Large font clips typical portrait controls", layout.canScrollVertically(1))
                assertEquals(dp(393) - 2 * dp(8), board.width)
                originalSizes.forEach { (view, size) -> view.setTextSize(TypedValue.COMPLEX_UNIT_PX, size) }
                measureViewport(layout, originalWidth, originalHeight)
            }
            onView(withContentDescription("Back to home")).perform(click())
            onView(withText("Continue Puzzle")).check(matches(isDisplayed()))
        }
    }

    private fun measureViewport(layout: SolvingLayout, width: Int, height: Int) {
        layout.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        layout.layout(layout.left, layout.top, layout.left + width, layout.top + height)
    }

    @Test fun keypadPreferencePersistsAndFallsBackWhenARowCannotFit() {
        installFixture()
        val preferences = AppearanceStore(InstrumentationRegistry.getInstrumentation().targetContext)
        val initial = preferences.load()
        preferences.save(initial.copy(keypadLayout = KeypadLayout.AUTO))
        try {
            ActivityScenario.launch(SudokuActivity::class.java).use { scenario ->
                onView(withText("Continue Puzzle")).perform(click())
                for ((choice, columns) in listOf(KeypadLayout.ROW to 9, KeypadLayout.GRID to 3, KeypadLayout.AUTO to 3)) {
                    openMenuDestination("Game menu", "Settings")
                    val value = preferences.load().keypadLayout.label
                    onView(withContentDescription("Number Keypad Layout, $value")).perform(scrollTo(), click())
                    onView(withText(choice.label)).perform(click())
                    scenario.recreate()
                    onView(withContentDescription("Number Keypad Layout, ${choice.label}")).check(matches(isDisplayed()))
                    onView(withContentDescription("Back")).perform(click())
                    assertEquals(choice, preferences.load().keypadLayout)
                    scenario.onActivity { activity ->
                        val solver = activity.findViewById<SolvingLayout>(R.id.page_scroll)
                        val (width, height) = solver.width to solver.height
                        val density = activity.resources.displayMetrics.density
                        fun dp(value: Int) = (value * density).roundToInt()
                        measureViewport(solver, dp(430), dp(860))
                        assertEquals(columns, solver.keypadColumns)
                        measureViewport(solver, dp(320), dp(496))
                        assertEquals(3, solver.keypadColumns)
                        assertFalse(solver.canScrollVertically(1))
                        measureViewport(solver, dp(724), dp(356))
                        val board = TreeIterables.depthFirstViewTraversal(solver).filterIsInstance<BoardView>().single()
                        assertEquals(dp(356) - 2 * dp(4), board.height)
                        assertEquals(if (choice == KeypadLayout.ROW) 9 else 3, solver.keypadColumns)
                        measureViewport(solver, width, height)
                    }
                }
            }
        } finally {
            preferences.save(initial)
        }
    }

    @Test fun shortcutsUseTheExistingCandidateCheckAndRestartActions() {
        installFixture()
        ActivityScenario.launch(SudokuActivity::class.java).use {
            onView(withText("Continue Puzzle")).perform(click())
            onView(withText("Auto-fill\nCandidates")).perform(scrollTo(), click())
            assertCellDescription(1, "Row 1, column 2, notes 3, 5")
            onView(withText("Check\nProgress")).perform(scrollTo(), click())
            onView(withText("There are no incorrect entries so far.")).check(matches(isDisplayed()))
            onView(withText("Close")).perform(click())
            onView(withText("Restart\nPuzzle")).perform(scrollTo(), click())
            onView(withText("Reset board?")).check(matches(isDisplayed()))
            onView(withText("Reset")).perform(click())
            assertCellDescription(1, "Row 1, column 2, empty")
        }
    }

    private fun displayedTime(scenario: ActivityScenario<SudokuActivity>): String {
        var time = ""
        scenario.onActivity { activity ->
            time = TreeIterables.depthFirstViewTraversal(activity.findViewById(R.id.page_content))
                .filterIsInstance<TextView>()
                .map { it.text.toString() }
                .single { Regex("[0-9]{2,}:[0-9]{2}:[0-9]{2}").matches(it) }
        }
        return time
    }

    private fun assertCellDescription(cell: Int, description: String) {
        onView(isAssignableFrom(BoardView::class.java)).check { view, error ->
            if (error != null) throw error
            val provider = checkNotNull(view.accessibilityNodeProvider)
            assertEquals(description, provider.createAccessibilityNodeInfo(cell)?.contentDescription?.toString())
        }
    }

    @Test fun backReturnsHomeAndTheNotesControlShowsBothStates() {
        installFixture()
        ActivityScenario.launch(SudokuActivity::class.java).use {
            onView(withText("Continue Puzzle")).perform(click())
            onView(withText("Notes: Off")).perform(scrollTo(), click())
            onView(withText("Notes: On")).check(matches(isDisplayed()))
            pressBack()
            onView(withText("Continue Puzzle")).check(matches(isDisplayed()))
            onView(withText("Solved")).perform(scrollTo()).check(matches(isDisplayed()))
            onView(withText("Continue Puzzle")).perform(scrollTo(), click())
            onView(withText("Notes: On")).perform(scrollTo(), click())
            onView(withText("Notes: Off")).check(matches(isDisplayed()))
        }
    }

    @Test fun pauseReturnsHomeAndBackgroundingResumesTheOpenPuzzle() {
        installFixture()
        ActivityScenario.launch(SudokuActivity::class.java).use { scenario ->
            onView(withText("Continue Puzzle")).perform(click())
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            onView(withText("Notes: Off")).perform(scrollTo(), click())
            onView(withContentDescription("Pause")).perform(scrollTo(), click())
            onView(withText("Continue Puzzle")).check(matches(isDisplayed()))
            onView(withText("Continue Puzzle")).perform(click())
            onView(withText("Notes: On")).check(matches(isDisplayed()))
        }
    }

    @Test fun screenTimeoutFollowsAndroidByDefaultAndKeepsOnlyActiveGamesAwake() {
        installFixture()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = AppearanceStore(context)
        val initial = preferences.load()
        preferences.save(AppearanceSettings())

        try {
            ActivityScenario.launch(SudokuActivity::class.java).use { scenario ->
                onView(withText("Continue Puzzle")).perform(click())
                assertKeepScreenOn(scenario, false)
                pressBack()
                onView(withText("Settings")).perform(scrollTo(), click())
                onView(withContentDescription("Screen Timeout, Use System")).perform(click())
                onView(withText("At least 3 minutes")).perform(click())
                assertEquals(ScreenTimeout.THREE_MINUTES, preferences.load().screenTimeout)
                scenario.recreate()
                onView(withContentDescription("Screen Timeout, At least 3 minutes")).check(matches(isDisplayed()))
                onView(withContentDescription("Back")).perform(click())
                assertKeepScreenOn(scenario, false)
                onView(withText("Continue Puzzle")).perform(click())
                assertKeepScreenOn(scenario, true)
                onView(withContentDescription("Pause")).perform(scrollTo(), click())
                assertKeepScreenOn(scenario, false)
            }
        } finally {
            preferences.save(initial)
        }
    }

    @Test fun completedDigitStaysGrayAndCanStillHighlightFromAGivenCell() {
        installFixture()
        ActivityScenario.launch(SudokuActivity::class.java).use {
            onView(withText("Continue Puzzle")).perform(click())
            val key = onView(withText("1"))
            var completedColor = 0
            key.check { view, error ->
                if (error != null) throw error
                val button = view as TextView
                assertFalse(button.isEnabled)
                assertFalse(button.paint.isStrikeThruText)
                assertEquals("All nine placed", ViewCompat.getStateDescription(button)?.toString())
                completedColor = button.currentTextColor
            }
            onView(isAssignableFrom(BoardView::class.java)).perform(scrollTo(), tapFirstCell())
            key.perform(scrollTo(), click()).check { view, error ->
                if (error != null) throw error
                val button = view as TextView
                assertTrue(button.isEnabled)
                assertFalse(button.paint.isStrikeThruText)
                assertEquals("All nine placed", ViewCompat.getStateDescription(button)?.toString())
                assertEquals(completedColor, button.currentTextColor)
            }
        }
    }

    @Test fun feedbackValidatesTheDraftAndDoesNotConfirmAnUnconfiguredWrite() {
        installFixture()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch(SudokuActivity::class.java).use {
            openMenuDestination("Home menu", "Send Feedback")
            val send = onView(allOf(withText("Send Feedback"), isAssignableFrom(AppCompatButton::class.java)))
            val feedback = onView(withHint("Tell us what you think…"))
            val email = onView(withHint("Your email address"))
            email.check(matches(not(isEnabled())))
            send.perform(scrollTo(), click())
            feedback.check(matches(hasErrorText("Enter your feedback.")))
            onView(withText("Bug Report")).perform(scrollTo(), click()).check(matches(isSelected()))
            feedback.perform(scrollTo(), replaceText("A report from the test device"))
            onView(withText("I’d like to be contacted about this feedback")).perform(scrollTo(), click())
            email.check(matches(isEnabled())).perform(replaceText("not-an-email"))
            send.perform(scrollTo(), click())
            email.check(matches(hasErrorText("Enter a valid email address.")))

            // A configured build must never send a test document to the project's live database.
            if (FirebaseApp.getApps(context).isEmpty()) {
                email.perform(replaceText("test@example.com"))
                send.perform(scrollTo(), click())
                onView(withText("Feedback is unavailable in this build.")).perform(scrollTo()).check(matches(isDisplayed()))
                onView(withText("A report from the test device")).check(matches(isDisplayed()))
                onView(withText("Feedback Sent!")).check(doesNotExist())
            }
        }
    }

    @Test fun aboutShowsVersionCopyrightAndSourceLicenseDirectly() {
        installFixture()
        ActivityScenario.launch(SudokuActivity::class.java).use {
            onView(withText("Continue Puzzle")).perform(click())
            openMenuDestination("Game menu", "About")
            onView(withText("Version ${BuildConfig.VERSION_NAME}")).check(matches(isDisplayed()))
            onView(withText("Copyright (c) 2026 Kalam Shah")).check(matches(isDisplayed()))
            onView(withText("Source available under the")).check(matches(isDisplayed()))
            onView(withText("PolyForm Noncommercial License 1.0.0")).check(matches(isDisplayed()))
            onView(withContentDescription("Open-source licenses")).check(doesNotExist())
            onView(withContentDescription("Back")).perform(click())
            onView(withContentDescription("Difficulty Extreme")).check(matches(isDisplayed()))
        }
    }

    private fun assertKeepScreenOn(scenario: ActivityScenario<SudokuActivity>, expected: Boolean) {
        scenario.onActivity { activity ->
            assertEquals(expected, activity.window.attributes.flags and FLAG_KEEP_SCREEN_ON != 0)
        }
    }

    @Test fun appearanceSettingsPersistAndApplyToAnInProgressGame() {
        installFixture()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = AppearanceStore(context)
        val initial = preferences.load()
        preferences.save(AppearanceSettings())

        try {
            ActivityScenario.launch(SudokuActivity::class.java).use { scenario ->
                onView(withText("Settings")).perform(scrollTo(), click())
                onView(withText("Light")).check(matches(isSelected()))
                onView(withContentDescription("Colour Palette, Ocean Blue")).perform(click())
                onView(withContentDescription("Ocean Blue")).check(matches(isSelected()))
                onView(withId(R.id.page_content)).check { view, error ->
                    if (error != null) throw error
                    val previews = TreeIterables.depthFirstViewTraversal(view).filterIsInstance<BoardSampleView>()
                    assertEquals(GameTheme.entries.size * 2, previews.size)
                }
                onView(withContentDescription("Back")).perform(click())
                onView(withContentDescription("Back")).perform(click())
                onView(withText("Continue Puzzle")).perform(click())
                onView(withContentDescription("Game menu")).perform(click())
                onView(withText("Settings")).perform(click())
                onView(withText("Dark")).perform(click())
                onView(withContentDescription("Colour Palette, Ocean Blue")).perform(click())
                onView(withContentDescription("Royal Purple")).perform(scrollTo(), click()).check(matches(isSelected()))
                scenario.recreate()
                onView(withContentDescription("Royal Purple")).check(matches(isSelected()))
                assertEquals(AppearanceSettings(AppearanceMode.DARK, GameTheme.PURPLE), preferences.load())
                onView(withContentDescription("Back")).perform(click())
                onView(withContentDescription("Back")).perform(click())
                onView(withContentDescription("Difficulty Extreme")).check(matches(isDisplayed()))
                scenario.onActivity { activity ->
                    val root = activity.findViewById<View>(R.id.page_scroll).parent as View
                    assertEquals(GameTheme.PURPLE.palette(dark = true).page, (root.background as ColorDrawable).color)
                }

                onView(withContentDescription("Game menu")).perform(click())
                onView(withText("Settings")).perform(click())
                onView(withText("System")).perform(click())
                scenario.recreate()
                onView(withText("System")).check(matches(isSelected()))
                onView(withContentDescription("Colour Palette, Royal Purple")).check(matches(isDisplayed()))
            }
        } finally {
            preferences.save(initial)
        }
    }

    @Test fun keypadSelectionHighlightsMatchingNotesAcrossTheBoard() {
        installFixture()
        ActivityScenario.launch(SudokuActivity::class.java).use {
            onView(withText("Continue Puzzle")).perform(click())
            onView(withText("Auto-fill\nCandidates")).perform(scrollTo(), click())
            val board = onView(isAssignableFrom(BoardView::class.java))
            board.perform(scrollTo(), tapFirstCell())
            onView(withText("3")).perform(scrollTo(), click()).check { view, error ->
                if (error != null) throw error
                assertTrue(view.isSelected)
            }
            board.check { view, error ->
                if (error != null) throw error
                val provider = checkNotNull(view.accessibilityNodeProvider)
                assertEquals(
                    "Row 1, column 2, notes 3, 5, highlighted 3",
                    provider.createAccessibilityNodeInfo(1)?.contentDescription?.toString(),
                )
                assertEquals(
                    "Row 1, column 4, notes 2, 8",
                    provider.createAccessibilityNodeInfo(3)?.contentDescription?.toString(),
                )
            }
        }
    }

    @Test fun pinchChangesTheBoardZoomAndCanReturnToFullBoard() {
        installFixture()
        ActivityScenario.launch(SudokuActivity::class.java).use {
            onView(withText("Continue Puzzle")).perform(click())
            onView(withContentDescription("Reset zoom")).check(matches(not(isDisplayed())))
            onView(isAssignableFrom(BoardView::class.java)).perform(pinch(out = true))
            onView(withContentDescription("Reset zoom")).check(matches(isDisplayed()))
            onView(isAssignableFrom(BoardView::class.java)).perform(scrollTo(), pinch(out = false))
            onView(withContentDescription("Reset zoom")).check(matches(not(isDisplayed())))
        }
    }

    @Test fun keyboardMovesBetweenVirtualCellsAndSelectsWithoutTouchingTheBoard() {
        installFixture()
        ActivityScenario.launch(SudokuActivity::class.java).use {
            onView(withText("Continue Puzzle")).perform(click())
            onView(isAssignableFrom(BoardView::class.java)).perform(scrollTo()).check { view, error ->
                if (error != null) throw error
                view.isFocusableInTouchMode = true
                assertTrue(view.requestFocus(View.FOCUS_FORWARD))
                val provider = checkNotNull(view.accessibilityNodeProvider)
                assertTrue(provider.createAccessibilityNodeInfo(0)?.isFocused == true)
                repeat(2) {
                    assertTrue(view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)))
                }
                assertTrue(provider.createAccessibilityNodeInfo(2)?.isFocused == true)
                assertFalse(provider.createAccessibilityNodeInfo(2)?.isSelected == true)
                assertTrue(view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)))
                assertTrue(provider.createAccessibilityNodeInfo(2)?.isSelected == true)
            }
        }
    }

    @Test fun zoomClearsKeyboardFocusAndBlocksClickForAClippedCell() {
        installFixture()
        ActivityScenario.launch(SudokuActivity::class.java).use {
            onView(withText("Continue Puzzle")).perform(click())
            val board = onView(isAssignableFrom(BoardView::class.java))
            board.perform(scrollTo()).check { view, error ->
                if (error != null) throw error
                view.isFocusableInTouchMode = true
                assertTrue(view.requestFocus(View.FOCUS_FORWARD))
                val provider = checkNotNull(view.accessibilityNodeProvider)
                assertTrue(provider.createAccessibilityNodeInfo(0)?.isFocused == true)
            }
            board.perform(pinch(out = true)).check { view, error ->
                if (error != null) throw error
                val provider = checkNotNull(view.accessibilityNodeProvider)
                assertFalse(provider.createAccessibilityNodeInfo(0)?.isFocused == true)
                assertFalse(provider.performAction(0, AccessibilityNodeInfo.ACTION_CLICK, null))
                view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                assertFalse(provider.createAccessibilityNodeInfo(0)?.isSelected == true)
            }
        }
    }

    @Test fun selectingEitherDigitKeepsTheClueAndEntryColorsDistinct() {
        installFixture()
        ActivityScenario.launch(SudokuActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val values = checkNotNull(Sudoku.decode(Guide.NAKED_BOARD))
                val solution = checkNotNull(Sudoku.solve(values))
                val game = Game(Puzzle(Difficulty.EASY, values, solution))
                val entered = values.indexOf(0)
                val digit = solution[entered]
                val clue = values.indexOf(digit)
                game.select(entered)
                assertTrue(game.enter(digit))

                val cellSide = 40
                val side = cellSide * Sudoku.SIZE
                val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
                fun cellContainsColor(cell: Int, color: Int): Boolean {
                    val x = (cell % Sudoku.SIZE) * cellSide + 6
                    val y = (cell / Sudoku.SIZE) * cellSide + 6
                    return (x until x + 28).any { px -> (y until y + 28).any { py -> bitmap.getPixel(px, py) == color } }
                }
                for (theme in GameTheme.entries) for (dark in listOf(false, true)) {
                    val palette = theme.palette(dark)
                    val board = BoardView(activity, palette) {}
                    board.layout(0, 0, side, side)
                    for (selected in listOf(clue, entered)) {
                        game.select(selected)
                        board.render(game)
                        board.draw(Canvas(bitmap))
                        assertTrue(cellContainsColor(clue, palette.givenDigit))
                        assertTrue(cellContainsColor(entered, palette.enteredDigit))
                    }
                }
            }
        }
    }

    @Test fun howToPlayOpensAWorkedLessonFromTheGameMenu() {
        installFixture()
        ActivityScenario.launch(SudokuActivity::class.java).use { scenario ->
            onView(withText("Continue Puzzle")).perform(click())
            openMenuDestination("Game menu", "How to Play")
            onView(withText("Rules")).check(matches(isSelected()))
            onView(withText("Sudoku Rules")).check(matches(isDisplayed()))
            onView(withText("Advanced solving techniques")).check(doesNotExist())
            onView(withText("Strategies")).perform(click()).check(matches(isSelected()))
            onView(withText("Sudoku Rules")).check(doesNotExist())
            onView(withId(R.id.page_content)).check { view, error ->
                if (error != null) throw error
                val sections = listOf("Basic solving methods", "Advanced solving techniques", "Expert")
                val headings = TreeIterables.depthFirstViewTraversal(view)
                    .filterIsInstance<TextView>()
                    .map { it.text.toString() }
                    .filter { it in sections }
                assertEquals(sections, headings)
            }
            for (technique in listOf(Technique.NAKED_PAIR, Technique.HIDDEN_PAIR, Technique.X_WING, Technique.XY_WING, Technique.SWORDFISH)) {
                val techniqueButton = onView(withText(technique.title)).perform(scrollTo())
                var scrollY = 0
                scenario.onActivity { scrollY = it.findViewById<ScrollView>(R.id.page_scroll).scrollY }
                techniqueButton.perform(click())
                onView(withText("Worked example")).check(matches(isDisplayed()))
                onView(isAssignableFrom(BoardView::class.java)).check(matches(isDisplayed()))
                onView(withContentDescription("Back")).perform(click())
                onView(withText("Strategies")).check(matches(isSelected()))
                scenario.onActivity { assertEquals(scrollY, it.findViewById<ScrollView>(R.id.page_scroll).scrollY) }
            }
            pressBack()
            onView(withContentDescription("Difficulty Extreme")).check(matches(isDisplayed()))
        }
    }

    @Test fun homeMenuHighlightsTheCurrentDestinationAndGuideReturnsHome() {
        installFixture()
        ActivityScenario.launch(SudokuActivity::class.java).use { scenario ->
            onView(withContentDescription("Home menu")).perform(scrollTo(), click())
            onView(withText("Home")).inRoot(isPlatformPopup()).check { view, error ->
                if (error != null) throw error
                assertEquals("Current destination", ViewCompat.getStateDescription(view).toString())
                val list = view.parent as ViewGroup
                assertEquals(listOf("Home", "How to Play", "Settings", "Send Feedback", "About"),
                    (0 until list.childCount).map { (list.getChildAt(it) as TextView).text.toString() })
            }
            pressBack()
            openMenuDestination("Home menu", "How to Play")
            onView(withText("Rules")).check(matches(isSelected()))
            onView(withId(R.id.page_content)).check { view, error ->
                if (error != null) throw error
                assertEquals(3, TreeIterables.depthFirstViewTraversal(view).filterIsInstance<BoardSampleView>().size)
            }
            onView(withText("Strategies")).perform(click())
            scenario.recreate()
            onView(withText("Strategies")).check(matches(isSelected()))
            pressBack()
            onView(withText("Continue Puzzle")).check(matches(isDisplayed()))
        }
    }

    private fun openMenuDestination(menu: String, destination: String) {
        onView(withContentDescription(menu)).perform(scrollTo(), click())
        onView(withText(destination)).inRoot(isPlatformPopup()).perform(click())
    }

    @Test fun strategiesAndWorkedBoardsUseAllTwelvePalettes() {
        ActivityScenario.launch(SudokuActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val ui = Ui(activity)
                val guide = GuideScreen(activity, ui)
                val technique = Technique.NAKED_SINGLE
                for (theme in GameTheme.entries) for (dark in listOf(false, true)) {
                    val palette = theme.palette(dark)
                    ui.palette = palette
                    val directory = ui.column()
                    guide.strategies(directory) {}
                    val title = TreeIterables.depthFirstViewTraversal(directory).filterIsInstance<TextView>()
                        .single { it.text == technique.title }
                    assertEquals(palette.accentInk, title.currentTextColor)

                    val detail = ui.column()
                    guide.lesson(detail, technique)
                    val board = TreeIterables.depthFirstViewTraversal(detail).filterIsInstance<BoardView>().single()
                    val side = 180
                    val spec = View.MeasureSpec.makeMeasureSpec(side, View.MeasureSpec.EXACTLY)
                    board.measure(spec, spec)
                    board.layout(0, 0, side, side)
                    val image = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
                    board.draw(Canvas(image))
                    // In this lesson r5c5 is the focus and r5c4 is in the related group.
                    assertEquals(palette.selectedCell, image.getPixel(85, 85))
                    assertEquals(palette.relatedCell, image.getPixel(65, 85))
                }
            }
        }
    }

    private fun installFixture(elapsedMs: Long = 11_000, edit: (Game) -> Unit = {}) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val board = checkNotNull(Sudoku.decode(Guide.PROOF_BOARD))
        val game = Game(Puzzle(Difficulty.EXTREME, board, checkNotNull(Sudoku.solve(board))), elapsedMs = elapsedMs)
        edit(game)
        GameStore(context).save(game, SystemClock.elapsedRealtime())
    }

    private fun tapFirstCell() = tapCell(0)

    private fun tapCell(cell: Int) = GeneralClickAction(Tap.SINGLE, { view ->
        val origin = IntArray(2).apply(view::getLocationOnScreen)
        val step = view.width / 9f
        floatArrayOf(origin[0] + (cell % 9 + .5f) * step, origin[1] + (cell / 9 + .5f) * step)
    }, Press.FINGER, InputDevice.SOURCE_TOUCHSCREEN, MotionEvent.BUTTON_PRIMARY)

    private fun pinch(out: Boolean) = object : ViewAction {
        override fun getDescription() = if (out) "spread two fingers on the Sudoku board" else "pinch two fingers on the Sudoku board"
        override fun getConstraints(): Matcher<View> = isAssignableFrom(BoardView::class.java)

        override fun perform(uiController: UiController, view: View) {
            val origin = IntArray(2).apply(view::getLocationOnScreen)
            val x = origin[0] + view.width / 2f
            val y = origin[1] + view.height / 2f
            val narrow = view.width * .05f
            val wide = view.width * .35f
            val start = if (out) narrow else wide
            val end = if (out) wide else narrow
            val downTime = SystemClock.uptimeMillis()
            val ids = Array(2) { index ->
                MotionEvent.PointerProperties().apply { id = index; toolType = MotionEvent.TOOL_TYPE_FINGER }
            }
            fun inject(action: Int, span: Float, count: Int = 2) {
                val coords = Array(count) { index ->
                    MotionEvent.PointerCoords().apply {
                        this.x = x + if (index == 0) -span else span
                        this.y = y
                        pressure = 1f
                        size = 1f
                    }
                }
                val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, count, ids, coords,
                    0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
                try {
                    assertTrue(uiController.injectMotionEvent(event))
                } finally {
                    event.recycle()
                }
                uiController.loopMainThreadForAtLeast(16)
            }

            inject(MotionEvent.ACTION_DOWN, start, 1)
            inject(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), start)
            for (step in 1..20) inject(MotionEvent.ACTION_MOVE, start + (end - start) * step / 20)
            inject(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), end)
            inject(MotionEvent.ACTION_UP, end, 1)
            uiController.loopMainThreadUntilIdle()
        }
    }

    private companion object {
        const val LEGACY_IMPORTED = "legacy_imported"
    }
}
