package com.kalamcs.sudoku

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.res.ColorStateList
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class SudokuActivity : AppCompatActivity() {
    private enum class Screen { HOME, GAME, GUIDE, LESSON, SETTINGS }

    private val ui by lazy { Ui(this) }
    private val store by lazy { GameStore(this) }
    private val guide by lazy { GuideScreen(this, ui) }
    private val worker = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var screen = Screen.HOME
    private lateinit var appearanceStore: AppearanceStore
    private lateinit var appearance: AppearanceSettings
    private var settingsOrigin = Screen.HOME
    private var game: Game? = null
    private var records = emptyMap<Difficulty, Record>()
    private var guideOrigin = Screen.HOME
    private var guideReturnToIndex = true
    private var lesson: Technique? = null
    private var board: BoardView? = null
    private var controls: GameControls? = null
    private var gameDialog: AlertDialog? = null
    private var generationInProgress = false
    private var restoring = true
    private var foreground = false
    private var hintRequest = 0
    private var hintPending = false
    private var displayedSecond = -1L
    private var lastSaveTime = 0L
    private val releaseScreen = Runnable { window.clearFlags(FLAG_KEEP_SCREEN_ON) }

    private data class GameControls(
        val time: TextView,
        val undo: AppCompatButton,
        val redo: AppCompatButton,
        val notes: AppCompatButton,
        val autoNotes: AppCompatButton,
        val erase: AppCompatButton,
        val pause: AppCompatButton,
        val hint: AppCompatButton,
        val digits: List<AppCompatButton>,
    )

    private val tick = object : Runnable {
        override fun run() {
            val active = game
            if (foreground && screen == Screen.GAME && active != null && !active.paused && !active.finished) {
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
                    Screen.SETTINGS -> leaveSettings()
                    Screen.GAME -> showHome()
                    Screen.HOME -> finish()
                }
            }
        })
        renderHome()
        store.importLegacy {
            if (isDestroyed || isFinishing) return@importLegacy
            game = store.game()
            records = store.records()
            restoring = false
            guideOrigin = Screen.entries.firstOrNull { it.name == state?.getString("guideOrigin") } ?: Screen.HOME
            guideReturnToIndex = state?.getBoolean("guideIndex", true) ?: true
            settingsOrigin = if (state?.getBoolean("settingsFromGame") == true) Screen.GAME else Screen.HOME
            when (state?.getString("screen")) {
                Screen.GAME.name -> if (game != null) showGame() else renderHome()
                Screen.GUIDE.name -> renderGuide()
                Screen.SETTINGS.name -> renderSettings()
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
        outState.putBoolean("guideIndex", guideReturnToIndex)
        outState.putString("lesson", lesson?.name)
        outState.putBoolean("settingsFromGame", settingsOrigin == Screen.GAME)
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
        worker.shutdownNow()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        when (screen) {
            Screen.HOME -> renderHome()
            Screen.GAME -> renderGame()
            Screen.GUIDE -> renderGuide()
            Screen.LESSON -> lesson?.let(::renderLesson)
            Screen.SETTINGS -> renderSettings()
        }
    }

    private fun now() = SystemClock.elapsedRealtime()
    private fun formatTime(seconds: Long) = String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60)
    private fun saveGame() {
        val active = game ?: return
        lastSaveTime = now()
        store.save(active, lastSaveTime)
    }

    private fun page(): LinearLayout {
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
        val root = layoutInflater.inflate(R.layout.activity_main, null) as FrameLayout
        root.setBackgroundColor(palette.page)
        val frame = root.findViewById<FrameLayout>(R.id.page_content)
        val column = ui.column().apply {
            setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(20))
        }
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val pageWidth = if (landscape) 500 else 600
        val maxWidth = resources.configuration.screenWidthDp.coerceAtMost(pageWidth)
        column.layoutDirection = View.LAYOUT_DIRECTION_LTR
        frame.addView(column, FrameLayout.LayoutParams(ui.dp(maxWidth), WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val system = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(system.left, system.top, system.right, system.bottom)
            if (maxWidth == resources.configuration.screenWidthDp) {
                val width = (ui.dp(maxWidth) - system.left - system.right).coerceAtLeast(ui.dp(220))
                val params = column.layoutParams
                if (params.width != width) {
                    params.width = width
                    column.layoutParams = params
                }
            }
            insets
        }
        setContentView(root)
        ViewCompat.requestApplyInsets(root)
        return column
    }

    private fun title(page: LinearLayout, text: String, action: String? = null, go: (() -> Unit)? = null, trailing: View? = null) {
        val row = ui.row()
        if (action != null && go != null) {
            row.addView(ui.button(action, onClick = go), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        }
        row.addView(ui.heading(text, 27), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
            marginStart = if (action == null) ui.dp(4) else ui.dp(12)
        })
        if (trailing != null) row.addView(trailing, LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)))
        ui.add(page, row)
    }

    private fun showHome() {
        cancelHint()
        game?.stop(now())
        saveGame()
        gameDialog?.dismiss()
        gameDialog = null
        renderHome()
    }

    private fun renderHome() {
        screen = Screen.HOME
        controls = null
        board = null
        val page = page()
        val busy = restoring || generationInProgress
        val settingsButton = if (busy) null else ui.iconButton("Settings", R.drawable.ic_settings, ::openSettings)
        title(page, "Sudoku", trailing = settingsButton)
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
        if (saved != null) {
            val card = ui.card()
            ui.add(card, ui.heading("Continue your game", 20))
            ui.add(card, ui.text("${saved.puzzle.difficulty.label} · ${formatTime(saved.elapsed(now()) / 1000)}", 15, ui.palette.muted), 4)
            val row = ui.row()
            ui.equal(row, ui.button("Continue", true) { showGame() })
            ui.equal(row, ui.button("Discard") {
                AlertDialog.Builder(this)
                    .setTitle("Discard your saved game?")
                    .setMessage("Your entries and notes for this puzzle will be removed.")
                    .setPositiveButton("Discard") { _, _ ->
                        store.discard()
                        game = null
                        renderHome()
                    }
                    .setNegativeButton("Cancel", null)
                    .showThemed()
            }, 8)
            ui.add(card, row, 12)
            ui.add(page, card, 16)
        }

        val start = ui.card()
        ui.add(start, ui.heading("New game", 20))
        for (pair in Difficulty.entries.chunked(2)) {
            val row = ui.row()
            pair.forEachIndexed { i, difficulty ->
                ui.equal(row, ui.button(difficulty.label) { askForNew(difficulty) }, if (i == 0) 0 else 8)
            }
            ui.add(start, row, 8)
        }
        ui.add(page, start, 12)

        val resources = ui.row()
        ui.equal(resources, ui.button("How to play", icon = R.drawable.ic_guide) { openGuide() })
        ui.equal(resources, ui.button("Send feedback", icon = R.drawable.ic_feedback) { feedback() }, 8)
        ui.add(page, resources, 12)

        ui.add(page, homeStats(), 12)
    }

    private fun homeStats(): LinearLayout {
        val stats = ui.card(12)
        ui.add(stats, ui.heading("Your stats", 20))
        val table = TableLayout(this).apply {
            isShrinkAllColumns = true
            setColumnStretchable(2, true)
            setColumnStretchable(3, true)
        }

        fun addRow(cells: List<TextView>, header: Boolean = false) {
            val row = TableRow(this).apply { gravity = Gravity.CENTER_VERTICAL }
            if (header) row.setBackgroundColor(ui.palette.relatedCell)
            cells.forEachIndexed { index, cell ->
                cell.gravity = if (index == 0) Gravity.START or Gravity.CENTER_VERTICAL else Gravity.CENTER
                cell.setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8))
                if (index > 0 && !header) cell.fontFeatureSettings = "'tnum'"
                row.addView(cell, TableRow.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            }
            table.addView(row)
        }

        addRow(listOf("Level", "Solved", "Best", "Avg.").map { ui.text(it, 13, bold = true) }, header = true)
        for ((index, difficulty) in Difficulty.entries.withIndex()) {
            if (index > 0) table.addView(View(this).apply {
                setBackgroundColor(ui.palette.border)
            }, TableLayout.LayoutParams(MATCH_PARENT, ui.dp(1)))
            val record = records[difficulty] ?: Record()
            val best = record.best?.let(::formatTime) ?: "—"
            val average = record.average?.let(::formatTime) ?: "—"
            val bestDate = record.bestAt?.takeIf { record.best != null }?.let {
                DateFormat.getDateInstance(DateFormat.SHORT).format(Date(it))
            }
            val bestCell = ui.text(best, 14).apply {
                if (bestDate != null) {
                    text = SpannableString("$best\n$bestDate").apply {
                        setSpan(RelativeSizeSpan(.85f), best.length + 1, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        setSpan(ForegroundColorSpan(ui.palette.muted), best.length + 1, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
            }
            addRow(listOf(
                ui.text(difficulty.label, 14, bold = true),
                ui.text(record.wins.toString(), 14),
                bestCell,
                ui.text(average, 14),
            ))
        }
        ui.add(stats, table, 8)
        return stats
    }

    private fun askForNew(difficulty: Difficulty) {
        if (generationInProgress) return
        if (game?.finished == false) {
            AlertDialog.Builder(this)
                .setTitle("Start a new ${difficulty.label.lowercase()} game?")
                .setMessage("This will replace your saved puzzle.")
                .setPositiveButton("Start") { _, _ -> newGame(difficulty) }
                .setNegativeButton("Cancel", null)
                .showThemed()
        } else newGame(difficulty)
    }

    private fun newGame(difficulty: Difficulty) {
        showHome()
        generationInProgress = true
        renderHome()
        worker.execute {
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
        val page = page()
        val header = ui.row()
        header.addView(ui.iconButton("Game menu", R.drawable.ic_menu) { menu() },
            LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)))
        header.addView(ui.heading("Sudoku", 28), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = ui.dp(10) })
        val time = ui.text(formatTime(active.elapsed(now()) / 1000), 19, ui.palette.ink, true).apply {
            gravity = Gravity.CENTER
            setPadding(ui.dp(12), ui.dp(6), ui.dp(12), ui.dp(6))
            background = ui.shape(ui.palette.selectedCell, ui.palette.cardRadius)
        }
        header.addView(time)
        ui.add(page, header)
        ui.add(page, ui.text(active.puzzle.difficulty.label, 16, ui.palette.accent, true).apply {
            setPadding(ui.dp(4), 0, 0, 0)
            contentDescription = "Difficulty ${active.puzzle.difficulty.label}"
        }, 6)

        val card = ui.card(9)
        val view = BoardView(this, ui.palette) { cell ->
            if (!active.paused) {
                cancelHint()
                active.select(cell)
                updateGame()
            }
        }
        board = view
        ui.add(card, view)

        val first = ui.row()
        val undo = ui.button("Undo", icon = R.drawable.ic_undo) { edit { it.undo() } }
        val redo = ui.button("Redo", icon = R.drawable.ic_redo) { edit { it.redo() } }
        val notes = ui.button("Notes: Off", icon = R.drawable.ic_notes) {
            if (!active.paused && !active.finished) {
                active.notesMode = !active.notesMode
                saveGame()
                updateGame()
            }
        }
        ui.equal(first, undo)
        ui.equal(first, redo, 5)
        ui.equal(first, notes, 5)
        ui.add(card, first, 10)

        val second = ui.row()
        val autoNotes = ui.button("Auto notes", icon = R.drawable.ic_auto_notes) { edit { it.autoNotes() } }
        val erase = ui.button("Erase", icon = R.drawable.ic_erase) { edit { it.erase() } }
        val pause = ui.button("Pause", icon = R.drawable.ic_pause) { showHome() }
        ui.equal(second, autoNotes)
        ui.equal(second, erase, 5)
        ui.equal(second, pause, 5)
        ui.add(card, second, 5)

        val keypad = ui.row()
        val digits = (1..9).map { digit ->
            ui.button(digit.toString()) { edit { it.enter(digit) } }.apply {
                textSize = 17f
                setPadding(0, ui.dp(3), 0, ui.dp(3))
                ui.equal(keypad, this, if (digit == 1) 0 else 3)
            }
        }
        ui.add(card, keypad, 8)

        val actions = ui.row()
        val gesture = ui.text("Pinch to zoom", 13, ui.palette.muted)
        actions.addView(gesture, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        val resetZoom = ui.button("Reset zoom") { view.resetZoom() }.apply { visibility = View.GONE }
        actions.addView(resetZoom, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        val hint = ui.button("Hint", true, R.drawable.ic_hint) { requestHint() }
        actions.addView(hint, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = ui.dp(6) })
        view.onZoomChanged = { zoomed ->
            if ((resetZoom.visibility == View.VISIBLE) != zoomed) {
                resetZoom.visibility = if (zoomed) View.VISIBLE else View.GONE
                gesture.text = if (zoomed) "Drag to pan" else "Pinch to zoom"
            }
        }
        ui.add(card, actions, 8)
        ui.add(page, card, 10)
        controls = GameControls(time, undo, redo, notes, autoNotes, erase, pause, hint, digits)
        displayedSecond = -1L
        updateGame()
    }

    private fun updateClock(active: Game) {
        val seconds = active.elapsed(now()) / 1000
        if (seconds == displayedSecond) return
        displayedSecond = seconds
        controls?.time?.text = formatTime(seconds)
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
            ui.styleButton(buttons.notes, active = active.notesMode)
        }
        buttons.notes.isEnabled = playable
        buttons.autoNotes.isEnabled = playable && active.hasBlank
        buttons.erase.isEnabled = playable && active.puzzle.givens[active.selected] == 0 &&
            (active.values[active.selected] != 0 || active.notes[active.selected] != 0)
        buttons.pause.isEnabled = playable
        buttons.hint.isEnabled = playable && !hintPending
        val hintLabel = if (hintPending) "Finding…" else "Hint"
        if (buttons.hint.text.toString() != hintLabel) buttons.hint.text = hintLabel
        for ((index, digit) in buttons.digits.withIndex()) {
            digit.isEnabled = active.canEnter(index + 1)
            val complete = active.counts[index + 1] == 9
            if (digit.isActivated != complete) {
                digit.isActivated = complete
                ui.styleButton(digit, completed = complete)
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
        active.finish(now())
        updateScreenTimeout()
        val record = records.getValue(active.puzzle.difficulty)
        val best = record.win(active.elapsed(now()) / 1000, System.currentTimeMillis())
        store.recordWin(active, now(), records)
        gameDialog = AlertDialog.Builder(this)
            .setTitle(if (best) "New personal best" else "Puzzle solved")
            .setMessage("${active.puzzle.difficulty.label} · ${formatTime(active.elapsed(now()) / 1000)}")
            .setPositiveButton("Home") { _, _ -> showHome() }
            .setNegativeButton("View board", null)
            .showThemed()
    }

    private fun resumeScreen() {
        val active = game ?: return
        active.start(now())
        updateGame()
        updateScreenTimeout()
    }

    private fun menu() {
        val active = game ?: return
        val actions = linkedMapOf<String, () -> Unit>(
            "How to play" to { openGuide() },
            "Settings" to { openSettings() },
            "Home" to { showHome() },
            "New game" to { chooseDifficulty() },
            "Reset board" to { resetBoard() },
            "Send feedback" to { feedback() },
        )
        gameDialog = AlertDialog.Builder(this)
            .setTitle("${active.puzzle.difficulty.label} game")
            .setItems(actions.keys.toTypedArray()) { _, which -> actions.values.elementAt(which).invoke() }
            .setNegativeButton("Close", null)
            .showThemed()
    }

    private fun chooseDifficulty() {
        gameDialog = AlertDialog.Builder(this).setTitle("New game")
            .setItems(Difficulty.entries.map(Difficulty::label).toTypedArray()) { _, which -> askForNew(Difficulty.entries[which]) }
            .setNegativeButton("Cancel", null).showThemed()
    }

    private fun resetBoard() {
        gameDialog = AlertDialog.Builder(this)
            .setTitle("Reset board?")
            .setMessage("This clears your entries and notes and restarts the timer.")
            .setPositiveButton("Reset") { _, _ ->
                cancelHint()
                game?.reset(now())
                board?.resetZoom()
                saveGame()
                updateGame()
            }
            .setNegativeButton("Cancel", null)
            .showThemed()
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
        val page = page()
        title(page, "Settings", "Back", ::leaveSettings)

        val modeCard = ui.card()
        ui.add(modeCard, ui.heading("Appearance", 20))
        ui.add(modeCard, ui.choices(AppearanceMode.entries, appearance.mode, AppearanceMode::label, horizontal = true) {
            changeAppearance(appearance.copy(mode = it))
        }, 4)
        ui.add(modeCard, ui.text("System follows Android’s light or dark setting.", 14, ui.palette.muted), 4)
        ui.add(page, modeCard, 16)

        val themeCard = ui.card()
        ui.add(themeCard, ui.heading("Theme", 20))
        ui.add(themeCard, ui.choices(GameTheme.entries, appearance.theme, { it.label(ui.palette.dark) }) {
            changeAppearance(appearance.copy(theme = it))
        }, 4)
        ui.add(page, themeCard, 12)

        val screenCard = ui.card()
        ui.add(screenCard, ui.heading("Screen timeout during a game", 20))
        ui.add(screenCard, ui.choices(ScreenTimeout.entries, appearance.screenTimeout, ScreenTimeout::label) {
            appearance = appearance.copy(screenTimeout = it)
            appearanceStore.save(appearance)
        }, 4)
        ui.add(screenCard, ui.text("After a timed option ends, Android handles dimming and sleep.", 14, ui.palette.muted), 4)
        ui.add(page, screenCard, 12)
    }

    private fun changeAppearance(updated: AppearanceSettings) {
        if (appearance == updated) return
        appearance = updated
        appearanceStore.save(updated)
        delegate.localNightMode = updated.mode.nightMode
        renderSettings()
    }

    private fun leaveSettings() {
        if (settingsOrigin == Screen.GAME && game != null) showGame() else showHome()
    }

    private fun openGuide(technique: Technique? = null) {
        guideOrigin = screen
        guideReturnToIndex = technique == null
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
        title(page, "How to play", "Back", ::leaveGuide)
        guide.index(page, ::renderLesson)
    }

    private fun renderLesson(technique: Technique) {
        screen = Screen.LESSON
        lesson = technique
        val page = page()
        title(page, technique.title, "Back", ::leaveLesson)
        guide.lesson(page, technique)
    }

    private fun leaveLesson() {
        if (guideReturnToIndex) renderGuide() else leaveGuide()
    }

    private fun leaveGuide() {
        if (guideOrigin == Screen.GAME && game != null) showGame() else showHome()
    }

    private fun cancelHint() {
        hintRequest++
        hintPending = false
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
        worker.execute {
            val hint = Sudoku.hint(values)
            runOnUiThread {
                if (isFinishing || isDestroyed || hintRequest != request) return@runOnUiThread
                hintPending = false
                updateGame()
                if (screen != Screen.GAME || game !== active || active.paused || !foreground) return@runOnUiThread
                if (hint == null) message("Hint", "There is no forced placement to show for this board.")
                else showHint(hint)
            }
        }
    }

    private fun showMistakes(count: Int) {
        val noun = if (count == 1) "entry" else "entries"
        gameDialog = AlertDialog.Builder(this)
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
            ui.add(panel, ui.button(technique.title) {
                gameDialog?.dismiss()
                openGuide(technique)
            }, 5)
        }
        val scroll = ScrollView(this).apply {
            addView(panel)
            isFillViewport = false
        }
        gameDialog = AlertDialog.Builder(this)
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
                        loaded = true
                        toggle.isEnabled = false
                        worker.execute {
                            val expanded = detail()
                            runOnUiThread {
                                if (isFinishing || isDestroyed) return@runOnUiThread
                                addSteps(children, expanded)
                                toggle.isEnabled = true
                            }
                        }
                    }
                }
            }
            ui.add(panel, toggle, 6)
            ui.add(panel, children, 2)
        }
    }

    private fun feedback() {
        val email = Uri.encode(BuildConfig.FEEDBACK_EMAIL, "@")
        val subject = Uri.encode("Sudoku for Android feedback")
        val draft = Uri.parse("mailto:$email?subject=$subject")
        try {
            startActivity(Intent(Intent.ACTION_SENDTO, draft))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "Set up an email app to send feedback.", Toast.LENGTH_LONG).show()
        }
    }

    private fun message(title: String, content: String) {
        val dialog = AlertDialog.Builder(this).setTitle(title).setMessage(content).setPositiveButton("Close", null).showThemed()
        if (screen == Screen.GAME) gameDialog = dialog
    }

    private fun AlertDialog.Builder.showThemed() = create().also { it.showThemed() }

    private fun AlertDialog.showThemed() {
        show()
        ui.tintDialog(this)
    }
}
