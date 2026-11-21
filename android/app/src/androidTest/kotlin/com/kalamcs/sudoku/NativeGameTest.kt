package com.kalamcs.sudoku

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
import android.widget.TextView
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
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.isChecked
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.espresso.util.TreeIterables
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.hamcrest.Matcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class NativeGameTest {
    @Test fun checkingErrorsKeepsTimeAndOnlyHighlightsWithPermission() {
        installFixture()
        ActivityScenario.launch(SudokuActivity::class.java).use { scenario ->
            onView(withText("Continue")).perform(click())
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
            onView(withText("Continue")).perform(click())
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

    @Test fun rerenderingHomeDismissesItsDiscardConfirmation() {
        installFixture()
        ActivityScenario.launch(SudokuActivity::class.java).use { scenario ->
            onView(withText("Discard")).perform(click())
            onView(withText("Discard your saved game?")).check(matches(isDisplayed()))
            scenario.onActivity { it.onConfigurationChanged(Configuration(it.resources.configuration)) }
            onView(withText("Discard your saved game?")).check(doesNotExist())
            onView(withText("Continue your game")).check(matches(isDisplayed()))
        }
    }

    @Test fun boardFitsAHeightConstrainedParent() {
        installFixture()
        ActivityScenario.launch(SudokuActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val view = BoardView(activity, GameTheme.FLUENT.palette(dark = false))
                val width = View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.AT_MOST)
                val height = View.MeasureSpec.makeMeasureSpec(180, View.MeasureSpec.AT_MOST)
                view.measure(width, height)
                assertEquals(180, view.measuredWidth)
                assertEquals(180, view.measuredHeight)
            }
        }
    }

    private fun displayedTime(scenario: ActivityScenario<SudokuActivity>): String {
        var time = ""
        scenario.onActivity { activity ->
            time = TreeIterables.depthFirstViewTraversal(activity.findViewById(R.id.page_content))
                .filterIsInstance<TextView>()
                .map { it.text.toString() }
                .single { Regex("[0-9]+:[0-9]{2}").matches(it) }
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
            onView(withText("Continue")).perform(click())
            onView(withText("Notes: Off")).perform(scrollTo(), click())
            onView(withText("Notes: On")).check(matches(isDisplayed()))
            pressBack()
            onView(withText("Continue your game")).check(matches(isDisplayed()))
            onView(withText("Solved")).perform(scrollTo()).check(matches(isDisplayed()))
            onView(withText("Continue")).perform(scrollTo(), click())
            onView(withText("Notes: On")).perform(scrollTo(), click())
            onView(withText("Notes: Off")).check(matches(isDisplayed()))
        }
    }

    @Test fun pauseReturnsHomeAndBackgroundingResumesTheOpenPuzzle() {
        installFixture()
        ActivityScenario.launch(SudokuActivity::class.java).use { scenario ->
            onView(withText("Continue")).perform(click())
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            onView(withText("Notes: Off")).perform(scrollTo(), click())
            onView(withText("Pause")).perform(scrollTo(), click())
            onView(withText("Continue your game")).check(matches(isDisplayed()))
            onView(withText("Continue")).perform(click())
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
                onView(withText("Continue")).perform(click())
                assertKeepScreenOn(scenario, false)
                pressBack()
                onView(withContentDescription("Settings")).perform(click())
                onView(withText("Use Android setting")).perform(scrollTo()).check(matches(isChecked()))
                onView(withText("At least 3 minutes")).perform(scrollTo(), click()).check(matches(isChecked()))
                assertEquals(ScreenTimeout.THREE_MINUTES, preferences.load().screenTimeout)
                scenario.recreate()
                onView(withText("At least 3 minutes")).perform(scrollTo()).check(matches(isChecked()))
                onView(withText("Back")).perform(scrollTo(), click())
                assertKeepScreenOn(scenario, false)
                onView(withText("Continue")).perform(click())
                assertKeepScreenOn(scenario, true)
                onView(withText("Pause")).perform(scrollTo(), click())
                assertKeepScreenOn(scenario, false)
            }
        } finally {
            preferences.save(initial)
        }
    }

    @Test fun completedDigitStaysGrayAndCanStillHighlightFromAGivenCell() {
        installFixture()
        ActivityScenario.launch(SudokuActivity::class.java).use {
            onView(withText("Continue")).perform(click())
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

    @Test
    @SdkSuppress(minSdkVersion = 26)
    fun feedbackOpensAnAddressedEmailDraftWithOneTap() {
        installFixture()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val draft = AtomicReference<Intent>()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != Intent.ACTION_SENDTO) return null
                draft.set(Intent(intent))
                return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            ActivityScenario.launch(SudokuActivity::class.java).use {
                onView(withText("Send feedback")).perform(scrollTo(), click())
                val address = checkNotNull(draft.get()?.data)
                assertEquals("mailto", address.scheme)
                assertEquals("${BuildConfig.FEEDBACK_EMAIL}?subject=Sudoku for Android feedback", address.schemeSpecificPart)
            }
        } finally {
            instrumentation.removeMonitor(monitor)
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
                onView(withContentDescription("Settings")).perform(click())
                onView(withText("Light")).check(matches(isChecked()))
                onView(withText("Fluent 2")).check(matches(isChecked()))
                onView(withText("Back")).perform(click())
                onView(withText("Continue")).perform(click())
                onView(withContentDescription("Game menu")).perform(click())
                onView(withText("Settings")).perform(click())
                onView(withText("Dark")).perform(click())
                onView(withText("Catppuccin Mocha")).perform(click()).check(matches(isChecked()))
                assertEquals(AppearanceSettings(AppearanceMode.DARK, GameTheme.CATPPUCCIN), preferences.load())
                onView(withText("Back")).perform(click())
                onView(withText("Extreme")).check(matches(isDisplayed()))
                scenario.onActivity { activity ->
                    val root = activity.findViewById<View>(R.id.page_scroll).parent as View
                    assertEquals(GameTheme.CATPPUCCIN.palette(dark = true).page, (root.background as ColorDrawable).color)
                }

                onView(withContentDescription("Game menu")).perform(click())
                onView(withText("Settings")).perform(click())
                onView(withText("System")).perform(click())
                scenario.recreate()
                onView(withText("System")).check(matches(isChecked()))
                val systemDark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                    Configuration.UI_MODE_NIGHT_YES
                onView(withText(GameTheme.CATPPUCCIN.label(systemDark))).check(matches(isChecked()))
            }
        } finally {
            preferences.save(initial)
        }
    }

    @Test fun keypadSelectionHighlightsMatchingNotesAcrossTheBoard() {
        installFixture()
        ActivityScenario.launch(SudokuActivity::class.java).use {
            onView(withText("Continue")).perform(click())
            onView(withText("Auto notes")).perform(scrollTo(), click())
            val board = onView(isAssignableFrom(BoardView::class.java))
            board.perform(scrollTo(), tapFirstCell())
            onView(withText("3")).perform(scrollTo(), click())
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
            onView(withText("Continue")).perform(click())
            onView(withText("Pinch to zoom")).check(matches(isDisplayed()))
            onView(isAssignableFrom(BoardView::class.java)).perform(pinch(out = true))
            onView(withText("Reset zoom")).perform(scrollTo()).check(matches(isDisplayed()))
            onView(isAssignableFrom(BoardView::class.java)).perform(scrollTo(), pinch(out = false))
            onView(withText("Pinch to zoom")).perform(scrollTo()).check(matches(isDisplayed()))
        }
    }

    @Test fun howToPlayOpensAWorkedLessonFromTheGameMenu() {
        installFixture()
        ActivityScenario.launch(SudokuActivity::class.java).use {
            onView(withText("Continue")).perform(click())
            onView(withContentDescription("Game menu")).perform(click())
            onView(withText("How to play")).perform(click())
            onView(withId(R.id.page_content)).check { view, error ->
                if (error != null) throw error
                val sections = listOf("Rules and controls", "Basic solving methods", "Advanced solving techniques")
                val headings = TreeIterables.depthFirstViewTraversal(view)
                    .filterIsInstance<TextView>()
                    .map { it.text.toString() }
                    .filter { it in sections }
                assertEquals(sections, headings)
            }
            onView(withText(Technique.NAKED_PAIR.title)).perform(scrollTo(), click())
            onView(withText("Worked example")).check(matches(isDisplayed()))
            onView(withText("Back")).perform(click())
            onView(withText("How to play")).check(matches(isDisplayed()))
            onView(withText("Back")).perform(click())
            onView(withText("Extreme")).check(matches(isDisplayed()))
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
