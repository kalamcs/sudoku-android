// Copyright (c) 2026 Kalam Shah
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

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
        .putBoard(game.values, game.notes, game.prunedNotes)
        .put("ms", game.elapsed(now))
        .put("w", if (game.finished) 1 else 0)
        .put("recordedWin", game.recordedWin)
        .put("z", if (game.paused) 1 else 0)
        .put("cell", game.selected)
        .put("pencil", game.notesMode)
        .put("digitFirst", game.entryOrder == EntryOrder.DIGIT_FIRST)
        .put("pickedDigit", game.pickedDigit)
        .apply {
            if (!game.finished) {
                val history = game.editHistory()
                if (history.undo.isNotEmpty() || history.redo.isNotEmpty()) {
                    put("history", JSONObject()
                        .put("undo", encodeHistory(history.undo))
                        .put("redo", encodeHistory(history.redo)))
                }
            }
        }
        .toString()

    fun game(raw: String?): Game? {
        if (raw.isNullOrEmpty()) return null
        return runCatching {
            val data = JSONObject(raw)
            if (data.optInt("v") != 1 || data.optInt("w") == 1) return null
            val difficulty = Difficulty.fromKey(data.optString("d")) ?: return null
            val givens = Sudoku.decode(data.optString("p")) ?: return null
            if (0 !in givens) return null
            val solution = Sudoku.uniqueSolution(givens) ?: return null
            val board = readBoard(data, givens) ?: return null
            val maxElapsed = Long.MAX_VALUE / 2
            val elapsed = data.optLong("ms", data.optLong("e").coerceIn(0, maxElapsed / 1000) * 1000).coerceIn(0, maxElapsed)
            val cell = data.optInt("cell", board.values.indexOf(0)).coerceIn(0, Sudoku.COUNT - 1)
            val history = data.optJSONObject("history")
            Game(Puzzle(difficulty, givens, solution), board.values, board.notes, elapsed, data.optInt("z") == 1, cell, board.prunedNotes,
                recordedWin = data.optBoolean("recordedWin"),
                history = EditHistory(readHistory(history?.optJSONArray("undo"), givens), readHistory(history?.optJSONArray("redo"), givens)),
                entryOrder = if (data.optBoolean("digitFirst")) EntryOrder.DIGIT_FIRST else EntryOrder.CELL_FIRST,
                pickedDigit = data.optInt("pickedDigit"),
            ).apply {
                notesMode = data.optBoolean("pencil")
            }
        }.getOrNull()
    }

    private fun JSONObject.putBoard(values: IntArray, notes: IntArray, pruning: PrunedNotes?) =
        put("b", values.joinToString(""))
            .put("n", notes.joinToString(".") { if (it == 0) "" else Sudoku.digitsIn(it).joinToString("") })
            .put("prunedNotes", pruning?.let { JSONObject().put("cell", it.cell).put("peers", JSONArray(it.peers)) })

    private fun encodeHistory(stack: List<BoardSnapshot>) = JSONArray().apply {
        for (board in stack) put(JSONObject().putBoard(board.values, board.notes, board.prunedNotes))
    }

    private fun readHistory(stack: JSONArray?, givens: IntArray): List<BoardSnapshot> {
        if (stack == null) return emptyList()
        return List(stack.length()) { index ->
            val entry = stack.optJSONObject(index) ?: return emptyList()
            readBoard(entry, givens) ?: return emptyList()
        }
    }

    private fun readBoard(data: JSONObject, givens: IntArray): BoardSnapshot? {
        val values = Sudoku.decode(data.optString("b")) ?: return null
        val segments = data.optString("n").split('.')
        if (segments.size != Sudoku.COUNT || segments.any { it.length > 9 || it.any { digit -> digit !in '1'..'9' } }) return null
        val notes = IntArray(Sudoku.COUNT) { cell ->
            // The puzzle's clues take precedence over entries in older saves.
            if (givens[cell] != 0) values[cell] = givens[cell]
            if (values[cell] == 0) segments[cell].fold(0) { mask, digit -> mask or Sudoku.bit(digit - '0') } else 0
        }
        return BoardSnapshot(values, notes, readPrunedNotes(data.optJSONObject("prunedNotes"), givens, values))
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

internal enum class LegacyImportResult { COMPLETE, RETRY_NEEDED }

internal class GameStore(private val context: Context) {
    private val preferences = context.getSharedPreferences("sudoku", Context.MODE_PRIVATE)

    fun game() = SaveFormat.game(preferences.getString(SaveFormat.GAME, null))
    fun needsLegacyData() = !preferences.contains(SaveFormat.GAME) || !preferences.contains(SaveFormat.STATS)
    fun records() = SaveFormat.records(preferences.getString(SaveFormat.STATS, null))
    fun save(game: Game, now: Long) = preferences.edit().putString(SaveFormat.GAME, SaveFormat.encode(game, now)).apply()
    // Keep the completed board and its win record in one preferences update.
    fun recordWin(game: Game, now: Long, records: Map<Difficulty, Record>) {
        preferences.edit().putString(SaveFormat.GAME, SaveFormat.encode(game, now))
            .putString(SaveFormat.STATS, SaveFormat.encode(records)).apply()
    }

    fun importLegacy(done: (LegacyImportResult) -> Unit) {
        val key = "legacy_imported"
        if (preferences.getBoolean(key, false) || !File(context.dataDir, "app_webview").isDirectory) {
            preferences.edit().putBoolean(key, true).apply()
            done(LegacyImportResult.COMPLETE)
            return
        }

        // Match Capacitor's previous origin to read its localStorage. The view
        // loads a local document, never makes a request, and never becomes UI.
        val web = runCatching { WebView(context) }.getOrElse {
            done(LegacyImportResult.RETRY_NEEDED)
            return
        }
        val handler = Handler(Looper.getMainLooper())
        val saveKeys = listOf(SaveFormat.GAME, SaveFormat.STATS)
        var ended = false
        lateinit var timeout: Runnable
        fun finish(data: JSONObject?) {
            if (ended) return
            ended = true
            handler.removeCallbacks(timeout)
            if (data != null) {
                val editor = preferences.edit()
                for (name in saveKeys) {
                    if (!data.isNull(name) && !preferences.contains(name)) editor.putString(name, data.getString(name))
                }
                editor.putBoolean(key, true).apply()
            }
            web.destroy()
            done(if (data == null) LegacyImportResult.RETRY_NEEDED else LegacyImportResult.COMPLETE)
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
                        ?.takeIf { payload ->
                            saveKeys.all { name -> payload.has(name) && (payload.isNull(name) || payload.opt(name) is String) }
                        }
                    finish(data)
                }
            }
        }
        web.loadDataWithBaseURL("https://localhost/", "<!doctype html><title>Sudoku</title>", "text/html", "utf-8", null)
    }
}
