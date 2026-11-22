package com.kalamcs.sudoku

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File

internal object SaveFormat {
    const val GAME = "sudoku_save_v1"
    const val STATS = "sudoku_stats_v1"

    fun encode(game: Game, now: Long) = JSONObject()
        .put("v", 1)
        .put("d", game.puzzle.difficulty.key)
        .put("p", game.puzzle.givens.joinToString(""))
        .put("s", game.puzzle.solution.joinToString(""))
        .put("b", game.values.joinToString(""))
        .put("n", game.notes.joinToString(".") { Sudoku.digitsIn(it).joinToString("") })
        .put("ms", game.elapsed(now))
        .put("w", if (game.finished) 1 else 0)
        .put("recordedWin", game.recordedWin)
        .put("z", if (game.paused) 1 else 0)
        .put("cell", game.selected)
        .put("pencil", game.notesMode)
        .put("prunedNotes", game.prunedNotes?.let { pruning ->
            JSONObject().put("cell", pruning.cell).put("peers", JSONArray(pruning.peers))
        })
        .toString()

    fun game(raw: String?): Game? {
        if (raw.isNullOrEmpty()) return null
        return runCatching {
            val data = JSONObject(raw)
            if (data.optInt("v") != 1 || data.optInt("w") == 1) return null
            val difficulty = Difficulty.fromKey(data.optString("d")) ?: return null
            val givens = Sudoku.decode(data.optString("p")) ?: return null
            val values = Sudoku.decode(data.optString("b")) ?: return null
            if (0 !in givens) return null
            val solution = Sudoku.uniqueSolution(givens) ?: return null
            val segments = data.optString("n").split('.')
            if (segments.size != Sudoku.COUNT || segments.any { it.length > 9 || it.any { d -> d !in '1'..'9' } }) return null
            val notes = IntArray(Sudoku.COUNT) { cell ->
                // The puzzle's clues take precedence over entries in older saves.
                if (givens[cell] != 0) values[cell] = givens[cell]
                if (values[cell] == 0) segments[cell].fold(0) { mask, digit -> mask or Sudoku.bit(digit - '0') } else 0
            }
            val maxElapsed = Long.MAX_VALUE / 2
            val elapsed = data.optLong("ms", data.optLong("e").coerceIn(0, maxElapsed / 1000) * 1000).coerceIn(0, maxElapsed)
            val cell = data.optInt("cell", values.indexOf(0)).coerceIn(0, Sudoku.COUNT - 1)
            val pruning = readPrunedNotes(data.optJSONObject("prunedNotes"), givens, values)
            Game(Puzzle(difficulty, givens, solution), values, notes, elapsed, data.optInt("z") == 1, cell, pruning,
                recordedWin = data.optBoolean("recordedWin"),
            ).apply {
                notesMode = data.optBoolean("pencil")
            }
        }.getOrNull()
    }

    private fun readPrunedNotes(data: JSONObject?, givens: IntArray, values: IntArray): PrunedNotes? {
        val cell = data?.optInt("cell", -1) ?: return null
        if (cell !in values.indices || givens[cell] != 0 || values[cell] == 0) return null
        val related = Sudoku.peers[cell]
        val peers = data.optJSONArray("peers") ?: return null
        if (peers.length() !in 1..related.size) return null
        val cells = List(peers.length()) { peers.optInt(it, -1) }
        if (cells.any { it !in related || values[it] != 0 } || cells.distinct().size != cells.size) return null
        return PrunedNotes(cell, cells)
    }

    fun records(raw: String?): Map<Difficulty, Record> {
        val data = runCatching { JSONObject(raw ?: "{}") }.getOrElse { JSONObject() }
        return Difficulty.entries.associateWith { difficulty ->
            val entry = data.optJSONObject(difficulty.key) ?: JSONObject()
            Record(
                best = entry.optLong("best", -1).takeIf { it >= 0 },
                bestAt = entry.optLong("bestAt").takeIf { it > 0 },
                wins = entry.optLong("wins").coerceAtLeast(0),
                totalTime = entry.optLong("totalTime").coerceAtLeast(0),
                timedWins = entry.optLong("timedWins").coerceAtLeast(0),
            )
        }
    }

    fun encode(records: Map<Difficulty, Record>) = JSONObject().apply {
        for ((difficulty, record) in records) {
            put(difficulty.key, JSONObject()
                .put("best", record.best ?: JSONObject.NULL)
                .put("bestAt", record.bestAt ?: JSONObject.NULL)
                .put("wins", record.wins)
                .put("totalTime", record.totalTime)
                .put("timedWins", record.timedWins))
        }
    }.toString()
}

internal class GameStore(private val context: Context) {
    private val preferences = context.getSharedPreferences("sudoku", Context.MODE_PRIVATE)

    fun game() = SaveFormat.game(preferences.getString(SaveFormat.GAME, null))
    fun records() = SaveFormat.records(preferences.getString(SaveFormat.STATS, null))
    fun save(game: Game, now: Long) = preferences.edit().putString(SaveFormat.GAME, SaveFormat.encode(game, now)).apply()
    // Keep the completed board and its win record in one preferences update.
    fun recordWin(game: Game, now: Long, records: Map<Difficulty, Record>) {
        preferences.edit().putString(SaveFormat.GAME, SaveFormat.encode(game, now))
            .putString(SaveFormat.STATS, SaveFormat.encode(records)).apply()
    }

    fun importLegacy(done: () -> Unit) {
        val key = "legacy_imported"
        if (preferences.getBoolean(key, false) || !File(context.dataDir, "app_webview").isDirectory) {
            preferences.edit().putBoolean(key, true).apply()
            done()
            return
        }

        // Match Capacitor's previous origin to read its localStorage. The view
        // loads a local document, never makes a request, and never becomes UI.
        val web = runCatching { WebView(context) }.getOrElse { done(); return }
        val handler = Handler(Looper.getMainLooper())
        var ended = false
        lateinit var timeout: Runnable
        fun finish(data: JSONObject?) {
            if (ended) return
            ended = true
            handler.removeCallbacks(timeout)
            if (data != null) {
                val editor = preferences.edit()
                for (name in listOf(SaveFormat.GAME, SaveFormat.STATS)) {
                    if (!data.isNull(name) && !preferences.contains(name)) editor.putString(name, data.getString(name))
                }
                editor.putBoolean(key, true).apply()
            }
            web.destroy()
            done()
        }
        timeout = Runnable { finish(null) }
        handler.postDelayed(timeout, 20_000)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = false
        web.settings.allowContentAccess = false
        web.settings.blockNetworkLoads = true
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                if (ended) return
                view.evaluateJavascript(
                    "JSON.stringify({sudoku_save_v1:localStorage.getItem('sudoku_save_v1')," +
                        "sudoku_stats_v1:localStorage.getItem('sudoku_stats_v1')})"
                ) { result ->
                    val data = runCatching { JSONObject(JSONTokener(result).nextValue() as String) }.getOrNull()
                    finish(data)
                }
            }
        }
        web.loadDataWithBaseURL("https://localhost/", "<!doctype html><title>Sudoku</title>", "text/html", "utf-8", null)
    }
}
