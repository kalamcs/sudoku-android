// Copyright (c) 2026 Kalam Shah
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.kalamcs.sudoku

import android.content.Context
import android.content.res.Configuration
import android.content.res.ColorStateList
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import androidx.appcompat.widget.ListPopupWindow
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.Future

class SudokuActivity : AppCompatActivity() {
    private enum class Screen { HOME, GAME, GUIDE, LESSON, SETTINGS, PALETTE, ABOUT, FEEDBACK, FEEDBACK_SENT }

    private val ui by lazy { Ui(this) }
    private val store by lazy { GameStore(this) }
    private val guide by lazy { GuideScreen(this, ui) }
    private val settings by lazy { SettingsScreen(this, ui) }
    private val feedbackPage by lazy { FeedbackScreen(this, ui) }
    private val puzzleWorker = Executors.newSingleThreadExecutor()
    private val hintWorker = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var screen = Screen.HOME
    private lateinit var appearanceStore: AppearanceStore
    private lateinit var appearance: AppearanceSettings
    private var settingsOrigin = Screen.HOME
    private var feedbackOrigin = Screen.HOME
    private var aboutOrigin = Screen.HOME
    private var game: Game? = null
    private var records = emptyMap<Difficulty, Record>()
    private var guideOrigin = Screen.HOME
    private var guideTab = GuideTab.RULES
    private var guideReturnToIndex = true
    private var guideScrollY = 0
    private var guideReturnHint: HintDialogPosition? = null
    private var lesson: Technique? = null
    private var board: BoardView? = null
    private var controls: GameControls? = null
    private var pageDialog: AlertDialog? = null
    private var pageMenu: ListPopupWindow? = null
    private var dialogPausesClock = false
    private var generationInProgress = false
    private var restoring = true
    private var legacyImportNeedsRetry = false
    private var foreground = false
    private var hintRequest = 0
    private var hintPending = false
    private var hintTask: Future<*>? = null
    private var hintProgress: HintProgress? = null
    private val detailTasks = mutableSetOf<Future<*>>()
    private var displayedSecond = -1L
    private var lastSaveTime = 0L
    private val releaseScreen = Runnable { window.clearFlags(FLAG_KEEP_SCREEN_ON) }

    private data class HintProgress(
        val values: IntArray,
        val hint: Hint,
        val crossedOff: IntArray,
        val notes: IntArray,
    )

    private data class HintDialogPosition(val scrollY: Int = 0, val previewOpen: Boolean = false)

    private data class GameControls(
        val time: TextView,
        val undo: AppCompatButton,
        val redo: AppCompatButton,
        val notes: AppCompatButton,
        val erase: AppCompatButton,
        val pause: View,
        val hint: AppCompatButton,
        val autoNotes: AppCompatButton,
        val restart: AppCompatButton,
        val digits: List<DigitKey>,
    )

    private val tick = object : Runnable {
        override fun run() {
            val active = game
            if (foreground && screen == Screen.GAME && !dialogPausesClock && pageMenu == null &&
                active != null && !active.paused && !active.finished) {
                updateClock(active)
                if (now() - lastSaveTime >= 5_000L) saveGame()
            }
            handler.postDelayed(this, 250)
        }
    }

    override fun attachBaseContext(newBase: Context) {
        appearanceStore = AppearanceStore(newBase)
        appearance = appearanceStore.load()
        delegate.localNightMode = appearance.mode.nightMode
        super.attachBaseContext(newBase)
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                pageMenu?.let { it.dismiss(); return }
                when (screen) {
                    Screen.LESSON -> leaveLesson()
                    Screen.GUIDE -> leaveGuide()
                    Screen.PALETTE -> renderSettings()
                    Screen.ABOUT -> leaveAbout()
                    Screen.FEEDBACK, Screen.FEEDBACK_SENT -> leaveFeedback()
                    Screen.SETTINGS -> leaveSettings()
                    Screen.GAME -> showHome()
                    Screen.HOME -> finish()
                }
            }
        })
        FeedbackModel.delivery.observe(this) {
            if (screen == Screen.FEEDBACK) renderFeedback()
        }
        restoreGame {
            guideOrigin = Screen.entries.firstOrNull { it.name == state?.getString("guideOrigin") } ?: Screen.HOME
            guideTab = GuideTab.entries.firstOrNull { it.name == state?.getString("guideTab") } ?: GuideTab.RULES
            guideReturnToIndex = state?.getBoolean("guideIndex", true) ?: true
            guideScrollY = state?.getInt("guideScrollY") ?: 0
            if (state?.containsKey("guideHintScrollY") == true) {
                guideReturnHint = HintDialogPosition(state.getInt("guideHintScrollY"), state.getBoolean("guideHintPreviewOpen"))
            }
            settingsOrigin = if (state?.getBoolean("settingsFromGame") == true) Screen.GAME else Screen.HOME
            feedbackOrigin = Screen.entries.firstOrNull { it.name == state?.getString("feedbackOrigin") }
                ?: if (state?.getBoolean("feedbackFromGame") == true) Screen.GAME else Screen.SETTINGS
            aboutOrigin = Screen.entries.firstOrNull { it.name == state?.getString("aboutOrigin") } ?: Screen.SETTINGS
            when (state?.getString("screen")) {
                Screen.GAME.name -> if (game != null) showGame() else renderHome()
                Screen.GUIDE.name -> renderGuide()
                Screen.SETTINGS.name -> renderSettings()
                Screen.PALETTE.name -> renderPalette()
                Screen.ABOUT.name -> renderAbout()
                Screen.FEEDBACK.name -> renderFeedback()
                Screen.FEEDBACK_SENT.name -> renderFeedbackSent()
                Screen.LESSON.name -> {
                    val savedLesson = Technique.entries.firstOrNull { it.name == state.getString("lesson") }
                    if (savedLesson == null) renderGuide() else renderLesson(savedLesson)
                }
                else -> renderHome()
            }
        }
    }

    private fun restoreGame(renderRestoredScreen: () -> Unit) {
        restoring = true
        renderHome()
        store.importLegacy { result ->
            if (isDestroyed || isFinishing) return@importLegacy
            legacyImportNeedsRetry = result == LegacyImportResult.RETRY_NEEDED && store.needsLegacyData()
            game = store.game()
            records = store.records()
            restoring = false
            when {
                legacyImportNeedsRetry -> renderHome()
                game?.awaitsCompletion() == true -> showGame()
                else -> renderRestoredScreen()
            }
        }
    }

    private fun confirmPlayingWithoutLegacyImport(proceed: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle("Check for older Sudoku data?")
            .setMessage("Sudoku couldn’t check for older progress or statistics. Playing now may replace that data.")
            .setPositiveButton("Play anyway") { _, _ ->
                legacyImportNeedsRetry = false
                proceed()
            }
            .setNeutralButton("Retry") { _, _ -> restoreGame(::renderHome) }
            .setNegativeButton("Cancel", null)
            .showThemed()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (screen == Screen.GUIDE) rememberGuideScroll()
        outState.putString("screen", screen.name)
        outState.putString("guideOrigin", guideOrigin.name)
        outState.putString("guideTab", guideTab.name)
        outState.putBoolean("guideIndex", guideReturnToIndex)
        outState.putInt("guideScrollY", guideScrollY)
        guideReturnHint?.let { position ->
            outState.putInt("guideHintScrollY", position.scrollY)
            outState.putBoolean("guideHintPreviewOpen", position.previewOpen)
        }
        outState.putString("lesson", lesson?.name)
        outState.putBoolean("settingsFromGame", settingsOrigin == Screen.GAME)
        outState.putString("feedbackOrigin", feedbackOrigin.name)
        outState.putString("aboutOrigin", aboutOrigin.name)
        saveGame()
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        handler.post(tick)
        if (screen == Screen.GAME) resumeScreen()
    }

    override fun onPause() {
        foreground = false
        handler.removeCallbacks(tick)
        updateScreenTimeout()
        if (screen == Screen.GAME) {
            val active = game
            if (active != null && !active.finished) {
                cancelHint()
                active.stop(now())
                saveGame()
            }
        }
        super.onPause()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        if (screen == Screen.GAME) updateScreenTimeout()
    }

    private fun updateScreenTimeout() {
        handler.removeCallbacks(releaseScreen)
        val timeout = appearance.screenTimeout
        if (!foreground || screen != Screen.GAME || game?.finished != false || timeout == ScreenTimeout.SYSTEM) {
            releaseScreen.run()
            return
        }
        window.addFlags(FLAG_KEEP_SCREEN_ON)
        timeout.holdMillis?.let { handler.postDelayed(releaseScreen, it) }
    }

    override fun onDestroy() {
        handler.removeCallbacks(releaseScreen)
        dismissPageMenu()
        cancelHint()
        dismissPageDialog()
        puzzleWorker.shutdownNow()
        hintWorker.shutdownNow()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        cancelHint()
        when (screen) {
            Screen.HOME -> renderHome()
            Screen.GAME -> showGame()
            Screen.GUIDE -> {
                rememberGuideScroll()
                renderGuide()
            }
            Screen.LESSON -> lesson?.let(::renderLesson)
            Screen.SETTINGS -> renderSettings()
            Screen.PALETTE -> renderPalette()
            Screen.ABOUT -> renderAbout()
            Screen.FEEDBACK -> renderFeedback()
            Screen.FEEDBACK_SENT -> renderFeedbackSent()
        }
    }

    private fun now() = SystemClock.elapsedRealtime()
    private fun formatTime(seconds: Long) = String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60)
    private fun formatGameTime(seconds: Long) =
        String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3_600, seconds / 60 % 60, seconds % 60)
    private fun saveGame() {
        val active = game ?: return
        lastSaveTime = now()
        store.save(active, lastSaveTime)
    }

    private fun preparePage() {
        dismissPageMenu()
        dismissPageDialog()
        updateScreenTimeout()
        val systemDark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val palette = appearance.palette(systemDark)
        ui.palette = palette
        window.setBackgroundDrawable(ColorDrawable(palette.page))
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !palette.dark
            isAppearanceLightNavigationBars = !palette.dark
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            @Suppress("DEPRECATION")
            window.navigationBarColor = if (palette.dark) palette.page else palette.ink
        }
    }

    private fun page(wideInLandscape: Boolean = false): LinearLayout {
        preparePage()
        val root = layoutInflater.inflate(R.layout.activity_main, null) as FrameLayout
        val frame = root.findViewById<FrameLayout>(R.id.page_content)
        val column = object : LinearLayout(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                val available = MeasureSpec.getSize(widthMeasureSpec)
                val width = if (landscape && wideInLandscape) available
                    else minOf(available, ui.dp(if (landscape) 500 else 600))
                super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), heightMeasureSpec)
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(20))
        }
        frame.addView(column, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        mountPage(root)
        return column
    }

    private fun mountPage(root: FrameLayout) {
        root.setBackgroundColor(ui.palette.page)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val system = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val bottom = if (screen == Screen.FEEDBACK) maxOf(system.bottom, insets.getInsets(WindowInsetsCompat.Type.ime()).bottom)
                else system.bottom
            view.setPadding(system.left, system.top, system.right, bottom)
            insets
        }
        setContentView(root)
        ViewCompat.requestApplyInsets(root)
    }

    private fun showHome() {
        cancelHint()
        game?.stop(now())
        saveGame()
        renderHome()
    }

    private fun renderHome() {
        screen = Screen.HOME
        controls = null
        board = null
        val page = page(wideInLandscape = true)
        val busy = restoring || generationInProgress
        val banner = ui.row().apply {
            minimumHeight = ui.dp(45)
            setPadding(ui.dp(8), 0, ui.dp(4), 0)
        }
        val logo = ImageView(this).apply {
            setImageDrawable(ui.icon(R.drawable.ic_auto_notes, 34))
            imageTintList = ColorStateList.valueOf(ui.palette.accentInk)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        banner.addView(logo, LinearLayout.LayoutParams(ui.dp(34), ui.dp(34)))
        banner.addView(ui.heading("Sudoku", 29), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
            marginStart = ui.dp(12)
        })
        if (!busy) banner.addView(menuButton("Home menu"), LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)))
        ui.add(page, banner)
        val waveHeight = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) 24 else 36
        page.addView(HomeWaves(this, ui.palette), LinearLayout.LayoutParams(MATCH_PARENT, ui.dp(waveHeight)))
        if (busy) {
            val loading = ui.row()
            loading.addView(ProgressBar(this).apply {
                indeterminateTintList = ColorStateList.valueOf(ui.palette.accent)
            }, LinearLayout.LayoutParams(ui.dp(28), ui.dp(28)))
            loading.addView(ui.text(if (restoring) "Loading saved game…" else "Creating puzzle…").apply {
                setPadding(ui.dp(12), 0, 0, 0)
            })
            ui.add(page, loading, 20)
            return
        }

        val puzzles = ui.column()
        val saved = game?.takeIf { it.canContinue(now()) }
        if (saved != null) ui.add(puzzles, continueCard(saved))

        ui.add(puzzles, ui.heading("Start New Puzzle", 20), if (saved == null) 15 else 19)
        val choices = ui.row()
        Difficulty.entries.forEachIndexed { index, difficulty ->
            val button = ui.button(difficulty.label) { askForNew(difficulty) }.apply {
                minimumHeight = ui.dp(68)
                setPadding(ui.dp(2), ui.dp(9), ui.dp(2), ui.dp(8))
                letterSpacing = 0f
                setCompoundDrawablesRelative(null, difficultyIcon(difficulty, 39, 26), null, null)
                compoundDrawableTintList = null
                compoundDrawablePadding = ui.dp(5)
                background = ui.dashboardPress(ui.dashboardSurface(13))
                elevation = ui.dp(2).toFloat()
            }
            ui.equal(choices, button, if (index == 0) 0 else 7)
        }
        ui.add(puzzles, choices, 11)

        val resources = ui.row()
        ui.equal(resources, ui.dashboardButton("How to Play", R.drawable.ic_guide) { openGuide() })
        ui.equal(resources, ui.dashboardButton("Settings", R.drawable.ic_settings, onClick = ::openSettings), 8)
        ui.add(page, HomePanels(this, ui, puzzles, homeStats(), resources, hasContinueCard = saved != null), 4)
    }

    private fun continueCard(saved: Game): View = ui.row().apply {
        val details = "${saved.puzzle.difficulty.label} · ${formatTime(saved.elapsed(now()) / 1000)}"
        background = ui.dashboardPress(ui.dashboardSurface(16, .11f))
        elevation = ui.dp(2).toFloat()
        setPadding(ui.dp(13), ui.dp(12), ui.dp(13), ui.dp(12))
        addView(SavedPuzzlePreview(this@SudokuActivity, ui.palette, saved), LinearLayout.LayoutParams(ui.dp(72), ui.dp(72)))
        addView(View(this@SudokuActivity).apply { setBackgroundColor(ui.palette.border) },
            LinearLayout.LayoutParams(ui.dp(1), ui.dp(64)).apply {
                leftMargin = ui.dp(12)
                rightMargin = ui.dp(12)
            })
        val description = ui.column()
        ui.add(description, ui.heading("Continue Puzzle", 19))
        ui.add(description, ui.text(details, 15, ui.palette.muted), 4)
        addView(description, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        addView(ImageView(this@SudokuActivity).apply {
            setImageDrawable(ui.icon(R.drawable.ic_chevron_right, 19))
            imageTintList = ColorStateList.valueOf(ui.palette.ink)
            background = ui.shape(ui.palette.toolbarSurface, 20, ui.palette.border)
            setPadding(ui.dp(7), ui.dp(7), ui.dp(7), ui.dp(7))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(ui.dp(32), ui.dp(32)).apply { leftMargin = ui.dp(5) })
        isFocusable = true
        contentDescription = "Continue Puzzle, $details"
        setOnClickListener { showGame() }
    }

    private fun difficultyIcon(difficulty: Difficulty, width: Int, height: Int) = ui.icon(
        when (difficulty) {
            Difficulty.EASY -> R.drawable.ic_difficulty_easy
            Difficulty.MEDIUM -> R.drawable.ic_difficulty_medium
            Difficulty.HARD -> R.drawable.ic_difficulty_hard
            Difficulty.EXTREME -> R.drawable.ic_difficulty_extreme
        },
        width, height,
    )

    private fun homeStats(): LinearLayout {
        val stats = ui.card(8).apply {
            background = ui.dashboardSurface(16)
            elevation = ui.dp(2).toFloat()
        }
        ui.add(stats, ui.heading("Statistics", 20).apply { setPadding(ui.dp(4), 0, 0, 0) })
        val widths = listOf(1.13f, .68f, 1f, .94f)

        fun addRow(cells: List<TextView>, header: Boolean = false) {
            val row = ui.row().apply {
                setPadding(ui.dp(5), ui.dp(6), ui.dp(5), ui.dp(6))
                if (!header) {
                    minimumHeight = ui.dp(44)
                    background = ui.dashboardSurface(13, .035f)
                }
            }
            cells.forEachIndexed { index, cell ->
                cell.gravity = if (index == 0) Gravity.START or Gravity.CENTER_VERTICAL else Gravity.CENTER
                cell.setPadding(ui.dp(2), 0, ui.dp(2), 0)
                if (index > 0 && !header) cell.fontFeatureSettings = "'tnum'"
                row.addView(cell, LinearLayout.LayoutParams(0, WRAP_CONTENT, widths[index]))
            }
            ui.add(stats, row, if (header) 8 else 4)
        }

        addRow(listOf("Difficulty", "Solved", "Best Time\n(Achieved On)", "Average Time").map { ui.text(it, 11) }, header = true)
        for (difficulty in Difficulty.entries) {
            val record = records[difficulty] ?: Record()
            val best = record.best?.let(::formatTime) ?: "—"
            val average = record.average?.let(::formatTime) ?: "—"
            val bestDate = record.bestAt?.takeIf { record.best != null }?.let {
                DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it))
            }
            val bestCell = ui.text(best, 15, bold = true).apply {
                if (bestDate != null) {
                    text = SpannableString("$best\n$bestDate").apply {
                        setSpan(RelativeSizeSpan(.74f), best.length + 1, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        setSpan(ForegroundColorSpan(ui.palette.muted), best.length + 1, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
            }
            addRow(listOf(
                ui.text(difficulty.label, 13, bold = true).apply {
                    setCompoundDrawablesRelative(difficultyIcon(difficulty, 18, 12), null, null, null)
                    compoundDrawablePadding = ui.dp(3)
                },
                ui.text(record.wins.toString(), 15, bold = true),
                bestCell,
                ui.text(average, 15, bold = true),
            ))
        }
        return stats
    }

    private fun askForNew(difficulty: Difficulty) {
        if (restoring || generationInProgress) return
        if (legacyImportNeedsRetry) {
            confirmPlayingWithoutLegacyImport { askForNew(difficulty) }
            return
        }
        if (game?.canContinue(now()) == true) {
            AlertDialog.Builder(this)
                .setTitle("Start a new ${difficulty.label.lowercase()} game?")
                .setMessage("This will replace your saved puzzle.")
                .setPositiveButton("Start") { _, _ -> newGame(difficulty) }
                .setNegativeButton("Cancel", null)
                .showThemed(pauseClock = screen == Screen.GAME)
        } else newGame(difficulty)
    }

    private fun newGame(difficulty: Difficulty) {
        showHome()
        generationInProgress = true
        renderHome()
        puzzleWorker.execute {
            val result = runCatching { RatedPuzzleBank.generate(difficulty, assets) }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                generationInProgress = false
                result.onSuccess {
                    game = Game(it)
                    hintProgress = null
                    saveGame()
                    showGame()
                }.onFailure {
                    renderHome()
                    message("Could not create a puzzle", "Choose a difficulty to try again.")
                }
            }
        }
    }

    private fun Game.awaitsCompletion() = !finished && !hasBlank && mistakes().isEmpty()

    private fun showGame() {
        val active = game ?: return renderHome()
        if (legacyImportNeedsRetry) {
            confirmPlayingWithoutLegacyImport(::showGame)
            return
        }
        active.paused = false
        active.changeEntryOrder(appearance.entryOrder)
        renderGame()
        if (active.awaitsCompletion()) {
            checkCompleted(active)
            updateGame()
        } else if (foreground) {
            resumeScreen()
        }
    }

    private fun renderGame() {
        val active = game ?: return renderHome()
        screen = Screen.GAME
        preparePage()
        val view = BoardView(this, ui.palette) { cell ->
            if (!active.paused) edit(cancelOnSelection = true) { it.tapCell(cell) }
        }
        board = view

        val back = ui.iconButton("Back to home", R.drawable.ic_back, quiet = true, onClick = ::showHome)
        val resetZoom = ui.iconButton("Reset zoom", R.drawable.ic_reset_zoom, quiet = true, onClick = view::resetZoom)
            .apply { visibility = View.GONE }
        val pause = ui.iconButton("Pause", R.drawable.ic_pause, quiet = true, onClick = ::showHome)
        val menu = menuButton("Game menu")
        fun navigation(vararg buttons: View) = ui.row().apply {
            for (button in buttons) addView(button, LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)))
        }
        val title = ui.heading("Sudoku", 22).apply {
            gravity = Gravity.CENTER
            setSingleLine()
            ellipsize = TextUtils.TruncateAt.END
        }
        val header = object : FrameLayout(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val leading = ui.dp(if (resetZoom.visibility == View.VISIBLE) 88 else 44)
                val trailing = ui.dp(88)
                val centerTitle = MeasureSpec.getSize(widthMeasureSpec) - 2 * trailing >=
                    title.paint.measureText(title.text.toString()) + ui.dp(4)
                (title.layoutParams as FrameLayout.LayoutParams).apply {
                    leftMargin = if (centerTitle) trailing else leading
                    rightMargin = trailing
                }
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            }
        }.apply {
            setBackgroundColor(ui.palette.toolbarSurface)
            addView(title, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.START or Gravity.CENTER_VERTICAL))
            addView(navigation(back, resetZoom), FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.START))
            addView(navigation(pause, menu), FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.END))
        }
        view.onZoomChanged = { zoomed -> resetZoom.visibility = if (zoomed) View.VISIBLE else View.GONE }

        val status = ui.row().apply {
            background = ui.shape(ui.palette.selectedCell, 12)
            setPadding(ui.dp(4), ui.dp(5), ui.dp(4), ui.dp(5))
        }
        val difficultyLabel = active.puzzle.difficulty.label
        val difficulty = ui.text("Difficulty: $difficultyLabel", 13).apply {
            gravity = Gravity.CENTER
            contentDescription = "Difficulty $difficultyLabel"
            text = SpannableString(text).apply {
                setSpan(ForegroundColorSpan(ui.palette.accentInk), length - difficultyLabel.length,
                    length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        val time = ui.text(formatGameTime(active.elapsed(now()) / 1000), 16, bold = true).apply {
            fontFeatureSettings = "'tnum'"
            setCompoundDrawablesRelative(ui.icon(R.drawable.ic_clock, 17), null, null, null)
            compoundDrawablePadding = ui.dp(7)
            compoundDrawableTintList = ColorStateList.valueOf(ui.palette.ink)
        }
        val timer = ui.row().apply {
            gravity = Gravity.CENTER
            addView(time)
        }
        ui.equal(status, difficulty)
        addDivider(status, 17, ui.palette.border)
        ui.equal(status, timer)

        val toolbar = ui.row()
        val undo = ui.gameAction("Undo", R.drawable.ic_undo) { edit { it.undo() } }
        val erase = ui.gameAction("Erase", R.drawable.ic_erase) { edit { it.erase() } }
        val notes = ui.gameAction("Notes: Off", R.drawable.ic_notes) {
            if (!active.paused && !active.finished) {
                active.notesMode = !active.notesMode
                saveGame()
                updateGame()
            }
        }
        val hint = ui.gameAction("Hint", R.drawable.ic_hint, onClick = ::requestHint)
        val redo = ui.gameAction("Redo", R.drawable.ic_redo) { edit { it.redo() } }
        listOf(undo, erase, notes, hint, redo).forEachIndexed { index, action ->
            ui.equal(toolbar, action, if (index == 0) 0 else 4)
        }

        val autoNotes = ui.gameAction("Auto-fill\nCandidates", R.drawable.ic_auto_notes, shortcut = true) { edit { it.autoNotes() } }
        val check = ui.gameAction("Check\nProgress", R.drawable.ic_check_progress, shortcut = true, onClick = ::checkErrors)
        val restart = ui.gameAction("Restart\nPuzzle", R.drawable.ic_restart, shortcut = true, onClick = ::resetBoard)

        val digits = (1..9).map { digit ->
            DigitKey(this, ui.palette, digit).apply {
                setOnClickListener { edit { it.tapDigit(digit) } }
                ui.styleDigit(this, selected = false, completed = false)
            }
        }
        val root = FrameLayout(this).apply {
            addView(SolvingLayout(this@SudokuActivity, ui, header, status, view, toolbar, digits,
                listOf(autoNotes, check, restart), appearance.keypadLayout),
                FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        controls = GameControls(time, undo, redo, notes, erase, pause, hint, autoNotes, restart, digits)
        displayedSecond = -1L
        mountPage(root)
        updateGame()
    }

    private fun addDivider(row: LinearLayout, height: Int, color: Int) {
        row.addView(View(this).apply { setBackgroundColor(color) }, LinearLayout.LayoutParams(ui.dp(1), ui.dp(height)))
    }

    private fun updateClock(active: Game) {
        val seconds = active.elapsed(now()) / 1000
        if (seconds == displayedSecond) return
        displayedSecond = seconds
        controls?.time?.text = formatGameTime(seconds)
    }

    private fun updateGame() {
        val active = game ?: return
        val buttons = controls ?: return
        board?.render(active, active.paused)
        val playable = !active.paused && !active.finished
        updateClock(active)
        buttons.undo.isEnabled = active.canUndo
        buttons.redo.isEnabled = active.canRedo
        if (buttons.notes.isSelected != active.notesMode) {
            buttons.notes.isSelected = active.notesMode
            buttons.notes.text = if (active.notesMode) "Notes: On" else "Notes: Off"
            ui.styleGameAction(buttons.notes, selected = active.notesMode)
        }
        buttons.notes.isEnabled = playable
        buttons.erase.isEnabled = playable && active.puzzle.givens[active.selected] == 0 &&
            (active.values[active.selected] != 0 || active.notes[active.selected] != 0)
        buttons.pause.isEnabled = playable
        buttons.hint.isEnabled = playable && !hintPending
        buttons.autoNotes.isEnabled = playable && active.hasBlank
        val restartLabel = if (active.finished) "Replay\nPuzzle" else "Restart\nPuzzle"
        if (buttons.restart.text.toString() != restartLabel) buttons.restart.text = restartLabel
        val hintLabel = if (hintPending) "Finding…" else "Hint"
        if (buttons.hint.text.toString() != hintLabel) buttons.hint.text = hintLabel
        for ((index, digit) in buttons.digits.withIndex()) {
            digit.isEnabled = active.canTapDigit(index + 1)
            val remaining = active.remaining(index + 1)
            val complete = remaining == 0
            val selected = if (active.entryOrder == EntryOrder.DIGIT_FIRST) active.pickedDigit == index + 1
                else !complete && active.matchingDigit == index + 1
            if (digit.isActivated != complete || digit.isSelected != selected) {
                digit.isActivated = complete
                digit.isSelected = selected
                ui.styleDigit(digit, selected, complete)
            }
            digit.showRemaining(remaining)
        }
    }

    private fun edit(cancelOnSelection: Boolean = false, change: (Game) -> Boolean) {
        val active = game ?: return
        val changed = change(active)
        if (changed || cancelOnSelection) cancelHint()
        if (changed) {
            if (active.hasBlank) saveGame() else checkCompleted(active)
        }
        updateGame()
    }

    private fun checkCompleted(active: Game) {
        val mistakes = active.mistakes().size
        if (mistakes > 0) {
            saveGame()
            showMistakes(mistakes)
            return
        }
        val completedAt = now()
        val firstWin = active.finish(completedAt)
        val elapsedSeconds = active.elapsed(completedAt) / 1000
        updateScreenTimeout()
        val best = if (firstWin) {
            val record = records.getValue(active.puzzle.difficulty)
            record.win(elapsedSeconds, System.currentTimeMillis()).also {
                lastSaveTime = completedAt
                store.recordWin(active, completedAt, records)
            }
        } else {
            saveGame()
            false
        }
        AlertDialog.Builder(this)
            .setTitle(if (best) "New personal best" else "Puzzle solved")
            .setMessage("${active.puzzle.difficulty.label} · ${formatTime(elapsedSeconds)}")
            .setPositiveButton("Home") { _, _ -> showHome() }
            .setNegativeButton("View board", null)
            .showThemed()
    }

    private fun resumeScreen() {
        val active = game ?: return
        if (!dialogPausesClock && pageMenu == null) active.start(now())
        updateGame()
        updateScreenTimeout()
    }

    private fun menuButton(label: String): View {
        lateinit var anchor: View
        anchor = ui.iconButton(label, R.drawable.ic_menu, quiet = true) { openMenu(anchor) }
        return anchor
    }

    private fun openMenu(anchor: View) {
        if (pageMenu != null) return
        val fromGame = screen == Screen.GAME
        val actions = listOf<Pair<String, () -> Unit>>(
            "Home" to ::showHome,
            "How to Play" to { openGuide() },
            "Settings" to ::openSettings,
            "Send Feedback" to ::openFeedback,
            "About" to ::openAbout,
        )
        val root = findViewById<FrameLayout>(android.R.id.content).getChildAt(0)
        val safeWidth = root.width - root.paddingLeft - root.paddingRight
        val popup = ListPopupWindow(this).apply {
            anchorView = anchor
            isModal = true
            setDropDownGravity(Gravity.END)
            horizontalOffset = -ui.dp(4)
            verticalOffset = ui.dp(4)
            width = (safeWidth - ui.dp(12)).coerceIn(1, ui.dp(224))
            setBackgroundDrawable(ui.shape(ui.palette.surface, 12, ui.palette.border))
            setAdapter(ui.menuItems(actions.map { it.first }, if (screen == Screen.HOME) 0 else -1))
            setOnItemClickListener { _, _, item, _ ->
                dismissPageMenu()
                actions[item].second()
            }
        }
        pauseGameForNavigation()
        if (fromGame) updateGame()
        pageMenu = popup
        popup.setOnDismissListener {
            if (pageMenu !== popup) return@setOnDismissListener
            pageMenu = null
            if (foreground && screen == Screen.GAME) resumeScreen()
        }
        popup.show()
        popup.listView?.selector = ui.menuSelector()
    }

    private fun dismissPageMenu() {
        val popup = pageMenu ?: return
        pageMenu = null
        popup.dismiss()
    }

    private fun checkErrors() {
        val mistakes = game?.mistakes()?.size ?: return
        if (mistakes == 0) message("Check for errors", "There are no incorrect entries so far.")
        else showMistakes(mistakes)
    }

    private fun resetBoard() {
        val active = game ?: return
        val replay = active.finished
        AlertDialog.Builder(this)
            .setTitle(if (replay) "Replay this puzzle?" else "Reset board?")
            .setMessage(if (replay) "Your original result stays in your stats." else "This clears your entries and notes and restarts the timer.")
            .setPositiveButton(if (replay) "Replay" else "Reset") { _, _ ->
                cancelHint()
                hintProgress = null
                active.reset(now())
                active.stop(now())
                board?.resetZoom()
                saveGame()
                updateGame()
            }
            .setNegativeButton("Cancel", null)
            .showThemed(pauseClock = true)
    }

    private fun pauseGameForNavigation() {
        if (screen == Screen.GAME) {
            cancelHint()
            game?.stop(now())
            saveGame()
        }
    }

    private fun openSettings() {
        settingsOrigin = screen
        pauseGameForNavigation()
        renderSettings()
    }

    private fun renderSettings() {
        screen = Screen.SETTINGS
        settings.settings(page(), appearance, ::leaveSettings,
            setMode = { changeAppearance(appearance.copy(mode = it)) },
            openPalette = ::renderPalette,
            selectTimeout = {
                chooseSetting("Screen Timeout", ScreenTimeout.entries, appearance.screenTimeout, ScreenTimeout::label) {
                    changeAppearance(appearance.copy(screenTimeout = it))
                }
            },
            selectKeypad = {
                chooseSetting("Number Keypad Layout", KeypadLayout.entries, appearance.keypadLayout, KeypadLayout::label) {
                    changeAppearance(appearance.copy(keypadLayout = it))
                }
            },
            selectEntryOrder = {
                chooseSetting("Entry order", EntryOrder.entries, appearance.entryOrder, EntryOrder::label) {
                    changeAppearance(appearance.copy(entryOrder = it))
                }
            },
        )
    }

    private fun renderPalette() {
        screen = Screen.PALETTE
        settings.palette(page(), appearance.theme, ::renderSettings) { changeAppearance(appearance.copy(theme = it)) }
    }

    private fun openAbout() {
        aboutOrigin = screen
        pauseGameForNavigation()
        renderAbout()
    }

    private fun renderAbout() {
        screen = Screen.ABOUT
        settings.about(page(), ::leaveAbout)
    }

    private fun leaveAbout() = returnTo(aboutOrigin)

    private fun <T> chooseSetting(title: String, options: List<T>, selected: T, label: (T) -> String, update: (T) -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(ui.choiceItems(options.map(label)), options.indexOf(selected)) { dialog, index ->
                dialog.dismiss()
                update(options[index])
            }
            .setNegativeButton("Cancel", null)
            .showThemed()
    }

    private fun changeAppearance(updated: AppearanceSettings) {
        if (appearance == updated) return
        val position = findViewById<ScrollView>(R.id.page_scroll)?.scrollY ?: 0
        appearance = updated
        appearanceStore.save(updated)
        delegate.localNightMode = updated.mode.nightMode
        if (screen == Screen.PALETTE) renderPalette() else renderSettings()
        val scroll = findViewById<ScrollView>(R.id.page_scroll)
        scroll.post { scroll.scrollTo(0, position) }
    }

    private fun leaveSettings() = returnTo(settingsOrigin)

    private fun openGuide(technique: Technique? = null, hintToRestore: HintDialogPosition? = null) {
        guideOrigin = screen
        guideReturnToIndex = technique == null
        guideTab = GuideTab.RULES
        guideScrollY = 0
        guideReturnHint = hintToRestore
        pauseGameForNavigation()
        if (technique == null) renderGuide() else renderLesson(technique)
    }

    private fun renderGuide() {
        screen = Screen.GUIDE
        val page = page()
        ui.add(page, ui.pageHeading("How to Play", ::leaveGuide))
        ui.add(page, ui.segments(GuideTab.entries, guideTab, GuideTab::label) {
            if (guideTab != it) {
                guideTab = it
                guideScrollY = 0
                renderGuide()
            }
        }, 8)
        when (guideTab) {
            GuideTab.RULES -> guide.rules(page)
            GuideTab.STRATEGIES -> guide.strategies(page) { technique ->
                rememberGuideScroll()
                renderLesson(technique)
            }
        }
        findViewById<ScrollView>(R.id.page_scroll).doOnLayout { view -> view.scrollTo(0, guideScrollY) }
    }

    private fun rememberGuideScroll() {
        guideScrollY = findViewById<ScrollView>(R.id.page_scroll)?.scrollY ?: 0
    }

    private fun renderLesson(technique: Technique) {
        screen = Screen.LESSON
        lesson = technique
        val page = page()
        ui.add(page, ui.pageHeading(technique.title, ::leaveLesson))
        guide.lesson(page, technique)
    }

    private fun leaveLesson() {
        if (guideReturnToIndex) renderGuide() else leaveGuide()
    }

    private fun leaveGuide() {
        val restore = guideReturnHint
        guideReturnHint = null
        returnTo(guideOrigin)
        if (screen == Screen.GAME && restore != null) {
            requestHint(restore)
        }
    }

    private fun returnTo(origin: Screen) {
        when {
            origin == Screen.GAME && game != null -> showGame()
            origin == Screen.SETTINGS -> renderSettings()
            else -> showHome()
        }
    }

    private fun cancelHint() {
        hintRequest++
        hintPending = false
        hintTask?.cancel(true)
        hintTask = null
    }

    private fun requestHint(position: HintDialogPosition = HintDialogPosition()) {
        val active = game ?: return
        if (active.paused || active.finished || hintPending) return
        val mistakes = active.mistakes()
        if (mistakes.isNotEmpty()) {
            showMistakes(mistakes.size)
            return
        }
        val crossedOff = active.crossedOffNotes()
        val previous = hintProgress
        if (previous != null && previous.values.contentEquals(active.values) &&
            previous.crossedOff.contentEquals(crossedOff) && previous.notes.contentEquals(active.notes)) {
            showHint(previous.hint, previous.values, position)
            return
        }
        hintPending = true
        val request = ++hintRequest
        val values = active.values.copyOf()
        val notes = active.notes.copyOf()
        updateGame()
        hintTask = hintWorker.submit {
            val result = try {
                Result.success(Sudoku.hint(values, crossedOff = crossedOff, notes = notes))
            } catch (_: CancellationException) {
                return@submit
            } catch (timeout: HintTimedOut) {
                Result.failure(timeout)
            }
            runOnUiThread {
                if (isFinishing || isDestroyed || hintRequest != request) return@runOnUiThread
                hintTask = null
                hintPending = false
                updateGame()
                if (screen != Screen.GAME || game !== active || active.paused || !foreground) return@runOnUiThread
                val hint = result.getOrNull()
                when {
                    result.isFailure -> message("Hint", "Finding an explanation took too long. You can try again.")
                    hint != null -> {
                        hintProgress = HintProgress(values, hint, crossedOff, notes)
                        showHint(hint, values, position)
                    }
                    else -> message("Hint", "There is no forced move to show for this board.")
                }
            }
        }
    }

    private fun showMistakes(count: Int) {
        val noun = if (count == 1) "entry" else "entries"
        AlertDialog.Builder(this)
            .setTitle("$count incorrect $noun")
            .setMessage("Highlight the incorrect numbers on the board?")
            .setPositiveButton("Highlight") { _, _ -> game?.markMistakes(); updateGame() }
            .setNegativeButton("Close", null)
            .showThemed()
    }

    private fun showHint(hint: Hint, values: IntArray, position: HintDialogPosition) {
        val panel = ui.column().apply { setPadding(ui.dp(22), ui.dp(4), ui.dp(22), ui.dp(12)) }
        val removing = hint.removals.isNotEmpty()
        val summary = ui.card(12).apply { background = ui.shape(ui.palette.selectedCell, 12) }
        if (removing) {
            ui.add(summary, ui.heading("Candidates to cross off", 17))
            for ((cell, removals) in hint.removals.groupBy { it.cell }.toSortedMap()) {
                val digits = removals.map { it.digit }.sorted().joinToString(", ")
                val position = Sudoku.name(cell).replaceFirstChar(Char::uppercaseChar)
                ui.add(summary, ui.text("$position · $digits", 14), 5)
            }
        } else {
            ui.add(summary, ui.heading("Put ${hint.digit} in ${Sudoku.name(hint.cell)}.", 17))
        }
        ui.add(panel, summary)
        val preview = if (removing) addHintPreview(panel, hint, values, position.previewOpen) else null
        ui.add(panel, ui.heading("Why this works", 17), 15)
        val reason = hint.steps.singleOrNull()
        if (!removing && reason != null && reason.details == null) ui.add(panel, ui.text(reason.text, 15), 6)
        else addSteps(panel, hint.steps)
        val techniques = (listOf(hint.technique) + hint.steps.mapNotNull { it.technique }).distinct()
        ui.add(panel, ui.heading("Technique guide", 16), 18)
        val scroll = ScrollView(this).apply { addView(panel) }
        for (technique in techniques) {
            ui.add(panel, ui.button(technique.title) {
                openGuide(technique, HintDialogPosition(scroll.scrollY, preview?.visibility == View.VISIBLE))
            }, 5)
        }
        if (position.scrollY > 0) scroll.doOnLayout { it.scrollTo(0, position.scrollY) }
        AlertDialog.Builder(this)
            .setTitle(hint.heading)
            .setView(scroll)
            .setPositiveButton(if (hint.removals.map { it.cell }.distinct().size > 1) "Show first cell" else "Show cell") { _, _ ->
                game?.select(hint.cell)
                board?.reveal(hint.cell)
                updateGame()
            }
            .setNegativeButton("Close", null)
            .showThemed()
    }

    private fun addHintPreview(panel: LinearLayout, hint: Hint, values: IntArray, previewOpen: Boolean): View {
        val removals = hint.removals.groupBy { it.cell }
        val notes = IntArray(Sudoku.COUNT)
        for ((cell, candidates) in hint.focus) notes[cell] = candidates
        for ((cell, entries) in removals) notes[cell] = entries.fold(0) { mask, entry -> mask or Sudoku.bit(entry.digit) }
        val preview = BoardView(this, ui.palette).apply {
            render(Diagram(values, notes, focus = hint.focus.keys, crossed = removals.keys),
                "Candidate elimination. The shaded cells show the pattern. The marked notes are the candidates to cross off.")
            visibility = if (previewOpen) View.VISIBLE else View.GONE
        }
        lateinit var toggle: AppCompatButton
        toggle = ui.button(if (previewOpen) "Hide board preview" else "Show board preview") {
            val show = preview.visibility != View.VISIBLE
            preview.visibility = if (show) View.VISIBLE else View.GONE
            toggle.text = if (show) "Hide board preview" else "Show board preview"
        }
        ui.add(panel, toggle, 8)
        ui.add(panel, preview, 6)
        return preview
    }

    private fun addSteps(panel: LinearLayout, steps: List<HintStep>) {
        for ((index, step) in steps.withIndex()) {
            val card = ui.card(10).apply { background = ui.shape(ui.palette.toolbarSurface, 10) }
            val row = ui.row().apply { gravity = Gravity.TOP }
            row.addView(ui.text("${index + 1}", 13, ui.palette.accentInk, true).apply {
                gravity = Gravity.CENTER
                background = ui.shape(ui.palette.selectedCell, 6)
            }, LinearLayout.LayoutParams(ui.dp(25), ui.dp(25)))
            val copy = ui.column()
            val heading = step.heading ?: step.technique?.title
            if (heading != null) ui.add(copy, ui.heading(heading, 14))
            ui.add(copy, ui.text(step.text, 14), if (heading == null) 0 else 4)
            ui.equal(row, copy, 8)
            ui.add(card, row)
            ui.add(panel, card, 7)
            val detail = step.details ?: continue
            val children = ui.column().apply {
                setPadding(ui.dp(10), 0, 0, 0)
                visibility = View.GONE
            }
            var loaded = false
            lateinit var toggle: AppCompatButton
            toggle = ui.button("Show reasoning for step ${index + 1}") {
                if (children.visibility == View.VISIBLE) {
                    children.visibility = View.GONE
                    toggle.text = "Show reasoning for step ${index + 1}"
                } else {
                    children.visibility = View.VISIBLE
                    toggle.text = "Hide reasoning for step ${index + 1}"
                    if (!loaded) {
                        val owner = pageDialog ?: return@button
                        toggle.isEnabled = false
                        ui.add(children, ui.text("Finding the reasoning…", 14, ui.palette.muted))
                        lateinit var task: Future<*>
                        task = hintWorker.submit {
                            val expanded = try {
                                detail(HintBudget())
                            } catch (_: CancellationException) {
                                return@submit
                            } catch (_: HintTimedOut) {
                                null
                            }
                            runOnUiThread {
                                detailTasks.remove(task)
                                if (pageDialog !== owner || !owner.isShowing) return@runOnUiThread
                                children.removeAllViews()
                                if (expanded == null) {
                                    ui.add(children, ui.text("Finding the reasoning took too long. Close and reopen this step to retry."))
                                } else {
                                    loaded = true
                                    addSteps(children, expanded)
                                }
                                toggle.isEnabled = true
                            }
                        }
                        detailTasks.add(task)
                    }
                }
            }
            ui.add(card, toggle, 6)
            ui.add(card, children, 2)
        }
    }

    private fun openFeedback() {
        feedbackOrigin = screen
        pauseGameForNavigation()
        renderFeedback()
    }

    private fun renderFeedback() {
        val delivery = FeedbackModel.delivery.value ?: FeedbackDelivery.Ready
        if (delivery == FeedbackDelivery.Sent) {
            renderFeedbackSent()
            return
        }
        screen = Screen.FEEDBACK
        feedbackPage.form(page(), FeedbackModel.draft, delivery, ::leaveFeedback,
            keepDraft = { FeedbackModel.draft = it },
            submit = {
                WindowCompat.getInsetsController(window, window.decorView).hide(WindowInsetsCompat.Type.ime())
                FeedbackModel.submit(application)
            },
        )
    }

    private fun renderFeedbackSent() {
        screen = Screen.FEEDBACK_SENT
        feedbackPage.sent(page(), ::leaveFeedback)
    }

    private fun leaveFeedback() {
        val sent = screen == Screen.FEEDBACK_SENT
        WindowCompat.getInsetsController(window, window.decorView).hide(WindowInsetsCompat.Type.ime())
        returnTo(feedbackOrigin)
        if (sent) FeedbackModel.finish()
    }

    private fun message(title: String, content: String) {
        AlertDialog.Builder(this).setTitle(title).setMessage(content).setPositiveButton("Close", null).showThemed()
    }

    private fun cancelDetailTasks() {
        detailTasks.forEach { it.cancel(true) }
        detailTasks.clear()
    }

    private fun dismissPageDialog() {
        val dialog = pageDialog ?: return
        pageDialog = null
        dialogPausesClock = false
        cancelDetailTasks()
        dialog.dismiss()
    }

    private fun AlertDialog.Builder.showThemed(pauseClock: Boolean = false): AlertDialog {
        if (hintPending) {
            cancelHint()
            updateGame()
        }
        val resumeClock = dialogPausesClock && !pauseClock
        dismissPageDialog()
        val dialog = create()
        pageDialog = dialog
        dialogPausesClock = pauseClock
        if (pauseClock) game?.takeUnless { it.finished }?.let { active ->
            active.stop(now())
            updateClock(active)
            saveGame()
        }
        if (resumeClock && foreground && screen == Screen.GAME) resumeScreen()
        dialog.setOnDismissListener {
            if (pageDialog !== dialog) return@setOnDismissListener
            val resumeClock = dialogPausesClock
            pageDialog = null
            dialogPausesClock = false
            cancelDetailTasks()
            if (resumeClock && foreground && screen == Screen.GAME) resumeScreen()
        }
        dialog.show()
        ui.tintDialog(dialog)
        return dialog
    }
}
