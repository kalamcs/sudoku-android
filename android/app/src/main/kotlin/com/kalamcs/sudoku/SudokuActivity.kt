package com.kalamcs.sudoku

import android.content.Context
import android.content.Intent
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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import com.google.android.gms.oss.licenses.OssLicensesMenuActivity
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
    private val feedbackModel by lazy { ViewModelProvider(this)[FeedbackModel::class.java] }
    private val puzzleWorker = Executors.newSingleThreadExecutor()
    private val hintWorker = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var screen = Screen.HOME
    private lateinit var appearanceStore: AppearanceStore
    private lateinit var appearance: AppearanceSettings
    private var settingsOrigin = Screen.HOME
    private var feedbackOrigin = Screen.SETTINGS
    private var game: Game? = null
    private var records = emptyMap<Difficulty, Record>()
    private var guideOrigin = Screen.HOME
    private var guideTab = GuideTab.RULES
    private var guideReturnToIndex = true
    private var lesson: Technique? = null
    private var board: BoardView? = null
    private var controls: GameControls? = null
    private var pageDialog: AlertDialog? = null
    private var dialogPausesClock = false
    private var generationInProgress = false
    private var restoring = true
    private var foreground = false
    private var hintRequest = 0
    private var hintPending = false
    private var hintTask: Future<*>? = null
    private val detailTasks = mutableSetOf<Future<*>>()
    private var displayedSecond = -1L
    private var lastSaveTime = 0L
    private val releaseScreen = Runnable { window.clearFlags(FLAG_KEEP_SCREEN_ON) }

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
        val digits: List<AppCompatButton>,
    )

    private val tick = object : Runnable {
        override fun run() {
            val active = game
            if (foreground && screen == Screen.GAME && !dialogPausesClock && active != null && !active.paused && !active.finished) {
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
                when (screen) {
                    Screen.LESSON -> leaveLesson()
                    Screen.GUIDE -> leaveGuide()
                    Screen.PALETTE -> renderSettings()
                    Screen.ABOUT -> renderSettings()
                    Screen.FEEDBACK, Screen.FEEDBACK_SENT -> leaveFeedback()
                    Screen.SETTINGS -> leaveSettings()
                    Screen.GAME -> showHome()
                    Screen.HOME -> finish()
                }
            }
        })
        feedbackModel.delivery.observe(this) {
            if (screen == Screen.FEEDBACK) renderFeedback()
        }
        renderHome()
        store.importLegacy {
            if (isDestroyed || isFinishing) return@importLegacy
            game = store.game()
            records = store.records()
            restoring = false
            guideOrigin = Screen.entries.firstOrNull { it.name == state?.getString("guideOrigin") } ?: Screen.HOME
            guideTab = GuideTab.entries.firstOrNull { it.name == state?.getString("guideTab") } ?: GuideTab.RULES
            guideReturnToIndex = state?.getBoolean("guideIndex", true) ?: true
            settingsOrigin = if (state?.getBoolean("settingsFromGame") == true) Screen.GAME else Screen.HOME
            feedbackOrigin = if (state?.getBoolean("feedbackFromGame") == true) Screen.GAME else Screen.SETTINGS
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

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("screen", screen.name)
        outState.putString("guideOrigin", guideOrigin.name)
        outState.putString("guideTab", guideTab.name)
        outState.putBoolean("guideIndex", guideReturnToIndex)
        outState.putString("lesson", lesson?.name)
        outState.putBoolean("settingsFromGame", settingsOrigin == Screen.GAME)
        outState.putBoolean("feedbackFromGame", feedbackOrigin == Screen.GAME)
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
            Screen.GUIDE -> renderGuide()
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

    private fun page(): LinearLayout {
        preparePage()
        val root = layoutInflater.inflate(R.layout.activity_main, null) as FrameLayout
        val frame = root.findViewById<FrameLayout>(R.id.page_content)
        val column = ui.column().apply {
            setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(20))
        }
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val pageWidth = if (landscape) 500 else 600
        val maxWidth = resources.configuration.screenWidthDp.coerceAtMost(pageWidth)
        column.layoutDirection = View.LAYOUT_DIRECTION_LTR
        frame.addView(column, FrameLayout.LayoutParams(ui.dp(maxWidth), WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        mountPage(root) { horizontalInsets ->
            if (maxWidth == resources.configuration.screenWidthDp) {
                val width = (ui.dp(maxWidth) - horizontalInsets).coerceAtLeast(ui.dp(220))
                val params = column.layoutParams
                if (params.width != width) {
                    params.width = width
                    column.layoutParams = params
                }
            }
        }
        return column
    }

    private fun mountPage(root: FrameLayout, onHorizontalInsets: (Int) -> Unit = {}) {
        root.setBackgroundColor(ui.palette.page)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val system = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val bottom = if (screen == Screen.FEEDBACK) maxOf(system.bottom, insets.getInsets(WindowInsetsCompat.Type.ime()).bottom)
                else system.bottom
            view.setPadding(system.left, system.top, system.right, bottom)
            onHorizontalInsets(system.left + system.right)
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
        val page = page()
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
        ui.add(page, banner)
        page.addView(HomeWaves(this, ui.palette), LinearLayout.LayoutParams(MATCH_PARENT, ui.dp(36)))
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

        val saved = game?.takeUnless { it.finished }
        if (saved != null) ui.add(page, continueCard(saved), 4)

        ui.add(page, ui.heading("Start New Puzzle", 20), 19)
        val choices = ui.row()
        Difficulty.entries.forEachIndexed { index, difficulty ->
            val button = ui.button(difficulty.label) { askForNew(difficulty) }.apply {
                minimumHeight = ui.dp(68)
                setPadding(ui.dp(2), ui.dp(9), ui.dp(2), ui.dp(8))
                letterSpacing = 0f
                setCompoundDrawablesRelative(null, difficultyBars(difficulty, 25), null, null)
                compoundDrawableTintList = null
                compoundDrawablePadding = ui.dp(5)
                background = ui.dashboardPress(ui.dashboardSurface(13))
                elevation = ui.dp(2).toFloat()
            }
            ui.equal(choices, button, if (index == 0) 0 else 7)
        }
        ui.add(page, choices, 11)
        ui.add(page, homeStats(), 18)

        val resources = ui.row()
        ui.equal(resources, ui.dashboardButton("How to Play", R.drawable.ic_guide) { openGuide() })
        ui.equal(resources, ui.dashboardButton("Settings", R.drawable.ic_settings, onClick = ::openSettings), 8)
        ui.add(page, resources, 12)
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

    private fun difficultyBars(difficulty: Difficulty, size: Int) = DifficultyBars(difficulty, ui.palette.dark).apply {
        setBounds(0, 0, ui.dp(size), ui.dp(size))
    }

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
                    setCompoundDrawablesRelative(difficultyBars(difficulty, 17), null, null, null)
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
        if (generationInProgress) return
        if (game?.hasProgress == true) {
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
            val result = runCatching { Sudoku.generate(difficulty) }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                generationInProgress = false
                result.onSuccess {
                    game = Game(it)
                    saveGame()
                    showGame()
                }.onFailure {
                    renderHome()
                    message("Could not create a puzzle", "Choose a difficulty to try again.")
                }
            }
        }
    }

    private fun showGame() {
        game?.paused = false
        renderGame()
        if (foreground) resumeScreen()
    }

    private fun renderGame() {
        val active = game ?: return renderHome()
        screen = Screen.GAME
        preparePage()
        val view = BoardView(this, ui.palette) { cell ->
            if (!active.paused) {
                cancelHint()
                active.select(cell)
                updateGame()
            }
        }
        board = view

        val header = FrameLayout(this).apply { setBackgroundColor(ui.palette.toolbarSurface) }
        val back = ui.iconButton("Back to home", R.drawable.ic_back, quiet = true, onClick = ::showHome)
        val resetZoom = ui.iconButton("Reset zoom", R.drawable.ic_reset_zoom, quiet = true, onClick = view::resetZoom)
            .apply { visibility = View.GONE }
        val pause = ui.iconButton("Pause", R.drawable.ic_pause, quiet = true, onClick = ::showHome)
        val menu = ui.iconButton("Game menu", R.drawable.ic_settings, quiet = true, onClick = ::menu)
        fun navigation(vararg buttons: View) = ui.row().apply {
            for (button in buttons) addView(button, LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)))
        }
        header.addView(ui.heading("Sudoku", 22).apply {
            gravity = Gravity.CENTER
            setSingleLine()
            ellipsize = TextUtils.TruncateAt.END
        }, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER).apply {
            leftMargin = ui.dp(96)
            rightMargin = ui.dp(96)
        })
        header.addView(navigation(back, resetZoom), FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.START))
        header.addView(navigation(pause, menu), FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.END))
        view.onZoomChanged = { zoomed -> resetZoom.visibility = if (zoomed) View.VISIBLE else View.GONE }

        val status = ui.row().apply {
            background = ui.shape(ui.palette.selectedCell, 12)
            setPadding(ui.dp(4), ui.dp(7), ui.dp(4), ui.dp(7))
        }
        val difficultyLabel = active.puzzle.difficulty.label
        val difficulty = ui.text("Difficulty: $difficultyLabel", 14).apply {
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
            ui.equal(toolbar, action, if (index == 0) 0 else 5)
        }

        val shortcuts = ui.row().apply { background = ui.shape(ui.palette.toolbarSurface, 12) }
        val autoNotes = ui.gameAction("Auto-fill\nCandidates", R.drawable.ic_auto_notes, shortcut = true) { edit { it.autoNotes() } }
        val check = ui.gameAction("Check\nProgress", R.drawable.ic_check_progress, shortcut = true, onClick = ::checkErrors)
        val restart = ui.gameAction("Restart\nPuzzle", R.drawable.ic_restart, shortcut = true, onClick = ::resetBoard)
        listOf(autoNotes, check, restart).forEachIndexed { index, button ->
            if (index != 0) addDivider(shortcuts, 32, ui.palette.border)
            ui.equal(shortcuts, button)
        }

        val digits = (1..9).map { digit ->
            ui.button(digit.toString()) { edit { it.enter(digit) } }.apply {
                textSize = 23f
                setPadding(0, ui.dp(3), 0, ui.dp(3))
                ui.styleDigit(this, selected = false, completed = false)
            }
        }
        val root = FrameLayout(this).apply {
            addView(SolvingLayout(this@SudokuActivity, ui, header, status, view, toolbar, digits, shortcuts),
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
            digit.isEnabled = active.canEnter(index + 1)
            val complete = active.counts[index + 1] == 9
            val selected = !complete && active.matchingDigit == index + 1
            if (digit.isActivated != complete || digit.isSelected != selected) {
                digit.isActivated = complete
                digit.isSelected = selected
                ui.styleDigit(digit, selected, complete)
                ViewCompat.setStateDescription(digit, if (complete) "All nine placed" else null)
            }
        }
    }

    private fun edit(change: (Game) -> Boolean) {
        val active = game ?: return
        if (change(active)) {
            cancelHint()
            saveGame()
            if (!active.hasBlank) checkCompleted(active)
        }
        updateGame()
    }

    private fun checkCompleted(active: Game) {
        val mistakes = active.mistakes().size
        if (mistakes > 0) {
            showMistakes(mistakes)
            return
        }
        val firstWin = active.finish(now())
        updateScreenTimeout()
        val best = if (firstWin) {
            val record = records.getValue(active.puzzle.difficulty)
            record.win(active.elapsed(now()) / 1000, System.currentTimeMillis()).also {
                store.recordWin(active, now(), records)
            }
        } else {
            saveGame()
            false
        }
        AlertDialog.Builder(this)
            .setTitle(if (best) "New personal best" else "Puzzle solved")
            .setMessage("${active.puzzle.difficulty.label} · ${formatTime(active.elapsed(now()) / 1000)}")
            .setPositiveButton("Home") { _, _ -> showHome() }
            .setNegativeButton("View board", null)
            .showThemed()
    }

    private fun resumeScreen() {
        val active = game ?: return
        if (!dialogPausesClock) active.start(now())
        updateGame()
        updateScreenTimeout()
    }

    private fun menu() {
        val active = game ?: return
        cancelHint()
        updateGame()
        val actions = linkedMapOf<String, () -> Unit>().apply {
            if (!active.finished && !active.paused && active.hasBlank) {
                put("Auto notes") { edit { it.autoNotes() } }
            }
            put("Check for errors", ::checkErrors)
            put(if (active.finished) "Replay puzzle" else "Reset board", ::resetBoard)
            put("How to play") { openGuide() }
            put("Settings", ::openSettings)
            put("Home", ::showHome)
            put("New game", ::chooseDifficulty)
            put("Send feedback", ::openFeedback)
        }
        AlertDialog.Builder(this)
            .setTitle("Game menu")
            .setItems(actions.keys.toTypedArray()) { _, which -> actions.values.elementAt(which).invoke() }
            .setNegativeButton("Close", null)
            .showThemed(pauseClock = true).window?.apply {
                setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
                val width = this@SudokuActivity.window.decorView.width.coerceAtMost(ui.dp(520))
                setLayout(if (width > 0) width else MATCH_PARENT, WRAP_CONTENT)
            }
    }

    private fun checkErrors() {
        val mistakes = game?.mistakes()?.size ?: return
        if (mistakes == 0) message("Check for errors", "There are no incorrect entries so far.")
        else showMistakes(mistakes)
    }

    private fun chooseDifficulty() {
        AlertDialog.Builder(this).setTitle("New game")
            .setItems(Difficulty.entries.map(Difficulty::label).toTypedArray()) { _, which -> askForNew(Difficulty.entries[which]) }
            .setNegativeButton("Cancel", null).showThemed(pauseClock = true)
    }

    private fun resetBoard() {
        val active = game ?: return
        val replay = active.finished
        AlertDialog.Builder(this)
            .setTitle(if (replay) "Replay this puzzle?" else "Reset board?")
            .setMessage(if (replay) "Your original result stays in your stats." else "This clears your entries and notes and restarts the timer.")
            .setPositiveButton(if (replay) "Replay" else "Reset") { _, _ ->
                cancelHint()
                active.reset(now())
                active.stop(now())
                board?.resetZoom()
                saveGame()
                updateGame()
            }
            .setNegativeButton("Cancel", null)
            .showThemed(pauseClock = true)
    }

    private fun openSettings() {
        settingsOrigin = screen
        if (screen == Screen.GAME) {
            cancelHint()
            game?.stop(now())
            saveGame()
        }
        renderSettings()
    }

    private fun renderSettings() {
        screen = Screen.SETTINGS
        settings.settings(page(), appearance, ::leaveSettings,
            setMode = { changeAppearance(appearance.copy(mode = it)) },
            openPalette = ::renderPalette,
            selectTimeout = ::chooseScreenTimeout,
            openGuide = { openGuide() },
            openFeedback = ::openFeedback,
            openAbout = ::renderAbout,
        )
    }

    private fun renderPalette() {
        screen = Screen.PALETTE
        settings.palette(page(), appearance.theme, ::renderSettings) { changeAppearance(appearance.copy(theme = it)) }
    }

    private fun renderAbout() {
        screen = Screen.ABOUT
        settings.about(page(), ::renderSettings) {
            startActivity(Intent(this, OssLicensesMenuActivity::class.java))
        }
    }

    private fun chooseScreenTimeout() {
        val options = ScreenTimeout.entries
        AlertDialog.Builder(this)
            .setTitle("Screen Timeout")
            .setSingleChoiceItems(options.map(ScreenTimeout::label).toTypedArray(), options.indexOf(appearance.screenTimeout)) { dialog, index ->
                dialog.dismiss()
                changeAppearance(appearance.copy(screenTimeout = options[index]))
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

    private fun leaveSettings() {
        if (settingsOrigin == Screen.GAME && game != null) showGame() else showHome()
    }

    private fun openGuide(technique: Technique? = null) {
        guideOrigin = screen
        guideReturnToIndex = technique == null
        guideTab = GuideTab.RULES
        if (screen == Screen.GAME) {
            cancelHint()
            game?.stop(now())
            saveGame()
        }
        if (technique == null) renderGuide() else renderLesson(technique)
    }

    private fun renderGuide() {
        screen = Screen.GUIDE
        val page = page()
        ui.add(page, ui.pageHeading("How to Play", ::leaveGuide))
        ui.add(page, ui.segments(GuideTab.entries, guideTab, GuideTab::label) {
            if (guideTab != it) {
                guideTab = it
                renderGuide()
            }
        }, 8)
        when (guideTab) {
            GuideTab.RULES -> guide.rules(page)
            GuideTab.STRATEGIES -> guide.strategies(page, ::renderLesson)
        }
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
        when {
            guideOrigin == Screen.GAME && game != null -> showGame()
            guideOrigin == Screen.SETTINGS -> renderSettings()
            else -> showHome()
        }
    }

    private fun cancelHint() {
        hintRequest++
        hintPending = false
        hintTask?.cancel(true)
        hintTask = null
    }

    private fun requestHint() {
        val active = game ?: return
        if (active.paused || active.finished || hintPending) return
        val mistakes = active.mistakes()
        if (mistakes.isNotEmpty()) {
            showMistakes(mistakes.size)
            return
        }
        hintPending = true
        val request = ++hintRequest
        val values = active.values.copyOf()
        updateGame()
        hintTask = hintWorker.submit {
            val result = try {
                Result.success(Sudoku.hint(values))
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
                    hint != null -> showHint(hint)
                    else -> message("Hint", "There is no forced placement to show for this board.")
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

    private fun showHint(hint: Hint) {
        val panel = ui.column().apply { setPadding(ui.dp(22), ui.dp(4), ui.dp(22), ui.dp(12)) }
        ui.add(panel, ui.text("Put ${hint.digit} in ${Sudoku.name(hint.cell)}.", 17, ui.palette.ink, true))
        addSteps(panel, hint.steps)
        val techniques = (listOf(hint.technique) + hint.steps.mapNotNull { it.technique }).distinct()
        ui.add(panel, ui.heading("Technique guide", 16), 18)
        for (technique in techniques) {
            ui.add(panel, ui.button(technique.title) { openGuide(technique) }, 5)
        }
        val scroll = ScrollView(this).apply {
            addView(panel)
            isFillViewport = false
        }
        AlertDialog.Builder(this)
            .setTitle(hint.technique.title)
            .setView(scroll)
            .setPositiveButton("Show cell") { _, _ ->
                game?.select(hint.cell)
                board?.reveal(hint.cell)
                updateGame()
            }
            .setNegativeButton("Close", null)
            .showThemed()
    }

    private fun addSteps(panel: LinearLayout, steps: List<HintStep>) {
        for ((index, step) in steps.withIndex()) {
            val label = step.technique?.let { "${it.title}. ${step.text}" } ?: step.text
            ui.add(panel, ui.text("${index + 1}. $label"), 10)
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
            ui.add(panel, toggle, 6)
            ui.add(panel, children, 2)
        }
    }

    private fun openFeedback() {
        feedbackOrigin = screen
        if (screen == Screen.GAME) {
            cancelHint()
            game?.stop(now())
            saveGame()
        }
        renderFeedback()
    }

    private fun renderFeedback() {
        val delivery = feedbackModel.delivery.value ?: FeedbackDelivery.Ready
        if (delivery == FeedbackDelivery.Sent) {
            renderFeedbackSent()
            return
        }
        screen = Screen.FEEDBACK
        feedbackPage.form(page(), feedbackModel.draft, delivery, ::leaveFeedback,
            keepDraft = { feedbackModel.draft = it },
            submit = {
                WindowCompat.getInsetsController(window, window.decorView).hide(WindowInsetsCompat.Type.ime())
                feedbackModel.submit()
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
        if (feedbackOrigin == Screen.GAME && game != null) showGame() else renderSettings()
        if (sent) feedbackModel.finish()
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
