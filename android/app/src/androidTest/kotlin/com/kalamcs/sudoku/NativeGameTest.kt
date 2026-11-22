package com.kalamcs.sudoku

import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
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
import androidx.test.espresso.matcher.ViewMatchers.hasErrorText
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
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
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.google.android.gms.oss.licenses.OssLicensesMenuActivity
import com.google.firebase.FirebaseApp
import org.hamcrest.Matcher
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.not
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeGameTest {
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
            val paused = displayedTime(scenario)
            SystemClock.sleep(1_500)
            assertEquals(paused, displayedTime(scenario))

            onView(withText("Check for errors")).perform(click())
            onView(withText("1 incorrect entry")).check(matches(isDisplayed()))
            val checking = displayedTime(scenario)
            SystemClock.sleep(1_500)
            assertTrue(displayedTime(scenario) != checking)
            onView(withText("Close")).perform(click())
            assertCellDescription(1, "Row 1, column 2, 3")

            onView(withContentDescription("Game menu")).perform(scrollTo(), click())
            onView(withText("Check for errors")).perform(click())
            onView(withText("Highlight")).perform(click())
            assertCellDescription(1, "Row 1, column 2, 3, incorrect")

            onView(withText("Erase")).perform(scrollTo(), click())
            onView(withContentDescription("Game menu")).perform(scrollTo(), click())
            onView(withText("Check for errors")).perform(click())
            onView(withText("There are no incorrect entries so far.")).check(matches(isDisplayed()))
            onView(withText("Highlight")).check(doesNotExist())
        }
    }

    @Test fun replayCanBeCompletedWithoutChangingTheWinRecord() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = GameStore(context)
        val solution = checkNotNull(Sudoku.solve(checkNotNull(Sudoku.decode(Guide.PROOF_BOARD))))
        val givens = solution.copyOf().apply { this[0] = 0 }
        val before = store.records().getValue(Difficulty.EASY).wins
        store.save(Game(Puzzle(Difficulty.EASY, givens, solution)), SystemClock.elapsedRealtime())

        ActivityScenario.launch(SudokuActivity::class.java).use {
            onView(withText("Continue Puzzle")).perform(click())
            onView(withText(solution[0].toString())).perform(scrollTo(), click())
            onView(withText("View board")).perform(click())
            val recorded = store.records().getValue(Difficulty.EASY)
            assertEquals(before + 1, recorded.wins)

            onView(withContentDescription("Game menu")).perform(scrollTo(), click())
            onView(withText("Replay puzzle")).perform(click())
            onView(withText("Replay")).perform(click())
            onView(withText(solution[0].toString())).perform(scrollTo(), click())
            onView(withText("Puzzle solved")).check(matches(isDisplayed()))
            assertEquals(recorded, store.records().getValue(Difficulty.EASY))
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

    @Test fun solvingScreenFitsPhonePortraitsAndUsesTwoPanesInLandscape() {
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
                val originalWidth = layout.width
                val originalHeight = layout.height
                for ((widthDp, heightDp) in listOf(320 to 568, 360 to 640, 390 to 844, 430 to 932, 780 to 360)) {
                    val width = (widthDp * density).toInt()
                    val height = ((heightDp - 72) * density).toInt()
                    layout.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                    layout.layout(0, 0, width, height)
                    assertEquals(board.width, board.height)
                    if (widthDp > 320) assertFalse("${widthDp}×$heightDp requires scrolling (content ${layout.getChildAt(0).height}px, " +
                        "viewport ${layout.height}px, board ${board.height}px)", layout.canScrollVertically(1))
                    val keypadRow = text.single { it.text == "1" }.parent as ViewGroup
                    assertEquals(if (widthDp <= 360) 3 else 9, keypadRow.childCount)
                    val controls = toolbar.parent as View
                    if (widthDp > heightDp) assertTrue("Landscape board edge ${board.right}px, tools start ${controls.left}px",
                        board.right < controls.left)
                }
                layout.measure(View.MeasureSpec.makeMeasureSpec(originalWidth, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(originalHeight, View.MeasureSpec.EXACTLY))
                layout.layout(0, 0, originalWidth, originalHeight)
            }
            onView(withContentDescription("Back to home")).perform(click())
            onView(withText("Continue Puzzle")).check(matches(isDisplayed()))
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
            onView(withText("Settings")).perform(scrollTo(), click())
            onView(withContentDescription("Send Feedback")).perform(scrollTo(), click())
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

    @Test fun aboutShowsTheAppVersionAndPackagedLicenses() {
        installFixture()
        ActivityScenario.launch(SudokuActivity::class.java).use {
            onView(withText("Settings")).perform(scrollTo(), click())
            onView(withContentDescription("About Sudoku")).perform(scrollTo(), click())
            onView(withText("Version ${BuildConfig.VERSION_NAME}")).check(matches(isDisplayed()))
            onView(withContentDescription("Open-source licenses")).perform(click())
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val resumed = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                assertTrue(resumed.any { it is OssLicensesMenuActivity })
            }
            pressBack()
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
            onView(withContentDescription("Game menu")).perform(click())
            onView(withText("Auto notes")).perform(click())
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

    @Test fun howToPlayOpensAWorkedLessonFromTheGameMenu() {
        installFixture()
        ActivityScenario.launch(SudokuActivity::class.java).use {
            onView(withText("Continue Puzzle")).perform(click())
            onView(withContentDescription("Game menu")).perform(click())
            onView(withText("How to play")).perform(click())
            onView(withText("Rules")).check(matches(isSelected()))
            onView(withText("Sudoku Rules")).check(matches(isDisplayed()))
            onView(withText("Advanced solving techniques")).check(doesNotExist())
            onView(withText("Strategies")).perform(click()).check(matches(isSelected()))
            onView(withText("Sudoku Rules")).check(doesNotExist())
            onView(withId(R.id.page_content)).check { view, error ->
                if (error != null) throw error
                val sections = listOf("Basic solving methods", "Advanced solving techniques")
                val headings = TreeIterables.depthFirstViewTraversal(view)
                    .filterIsInstance<TextView>()
                    .map { it.text.toString() }
                    .filter { it in sections }
                assertEquals(sections, headings)
            }
            onView(withText(Technique.NAKED_PAIR.title)).perform(scrollTo(), click())
            onView(withText("Worked example")).check(matches(isDisplayed()))
            onView(withContentDescription("Back")).perform(click())
            onView(withText("Strategies")).check(matches(isSelected()))
            onView(withContentDescription("Back")).perform(click())
            onView(withContentDescription("Difficulty Extreme")).check(matches(isDisplayed()))
        }
    }

    @Test fun howToPlayCanReturnToSettings() {
        installFixture()
        ActivityScenario.launch(SudokuActivity::class.java).use { scenario ->
            onView(withText("Settings")).perform(scrollTo(), click())
            onView(withContentDescription("How to Play")).perform(scrollTo(), click())
            onView(withText("Rules")).check(matches(isSelected()))
            onView(withId(R.id.page_content)).check { view, error ->
                if (error != null) throw error
                assertEquals(3, TreeIterables.depthFirstViewTraversal(view).filterIsInstance<BoardSampleView>().size)
            }
            onView(withText("Strategies")).perform(click())
            scenario.recreate()
            onView(withText("Strategies")).check(matches(isSelected()))
            pressBack()
            onView(withText("Appearance")).check(matches(isDisplayed()))
        }
    }

    private fun installFixture() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val board = checkNotNull(Sudoku.decode(Guide.PROOF_BOARD))
        val game = Game(Puzzle(Difficulty.EXTREME, board, checkNotNull(Sudoku.solve(board))))
        GameStore(context).save(game, SystemClock.elapsedRealtime())
    }

    private fun tapFirstCell() = GeneralClickAction(Tap.SINGLE, { view ->
        val origin = IntArray(2).apply(view::getLocationOnScreen)
        val offset = view.width / 18f
        floatArrayOf(origin[0] + offset, origin[1] + offset)
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
}
