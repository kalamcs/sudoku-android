package com.kalamcs.sudoku

import android.content.Context
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout

internal enum class GuideTab(val label: String) { RULES("Rules"), STRATEGIES("Strategies") }

internal data class GuidePicture(val diagram: Diagram, val caption: String)
internal data class Lesson(
    val summary: String,
    val instructions: List<String>,
    val pictures: List<GuidePicture>,
    val takeaway: String,
)

internal object Guide {
    const val PROOF_BOARD = "600090741479165382182347596800000150001006009200071063008600010300010900010000630"
    const val LOCKED_BOARD = "000000000000020708043907000402609800085001206060050000724090000001460007698000030"
    const val PAIR_BOARD = "031004009007030480008020035002093048683040007094000300015076000309500064006009500"
    const val HIDDEN_BOARD = "000000508000815030000000070098403010030020040700090603080002004300047081009086000"
    const val NAKED_BOARD = "530070000600195000098000060800060003400803001700020006060000280000419005000080079"

    val basicTechniques = listOf(Technique.NAKED_SINGLE, Technique.HIDDEN_SINGLE)
    val advancedTechniques = listOf(Technique.LOCKED_CANDIDATES, Technique.NAKED_PAIR, Technique.CONTRADICTION)
    val order = basicTechniques + advancedTechniques

    fun definition(technique: Technique) = when (technique) {
        Technique.NAKED_SINGLE -> "Only one digit fits an empty cell."
        Technique.HIDDEN_SINGLE -> "Only one cell in a row, column, or box can hold a particular digit."
        Technique.LOCKED_CANDIDATES -> "A digit's possible spots line up inside one box, eliminating that digit from nearby cells."
        Technique.NAKED_PAIR -> "Two cells in the same row, column, or box share exactly the same two candidates."
        Technique.CONTRADICTION -> "Follow what a candidate would force and see whether it breaks a Sudoku rule."
    }

    fun lesson(technique: Technique): Lesson {
        val summary = definition(technique)
        return when (technique) {
            Technique.NAKED_SINGLE -> {
                val board = board(NAKED_BOARD)
                val cell = 40
                Lesson(summary, listOf(
                    "Look at row 5, column 5. Its row contains 1, 3, 4, and 8.",
                    "Column 5 rules out 2, 6, 7, and 9. Check the middle box too.",
                    "Only 5 is left. Fill the outlined cell with 5.",
                ), listOf(picture(board, Sudoku.peers[cell].toSet(), setOf(cell), setOf(cell), caption =
                    "The outlined cell has just one candidate, 5. The lightly shaded cells share its row, column, or box.")),
                    "Check one empty cell at a time. A cell with one candidate is ready to fill.")
            }
            Technique.HIDDEN_SINGLE -> {
                val board = board(HIDDEN_BOARD)
                val open = row(2).filter { board[it] == 0 }.toSet()
                Lesson(summary, listOf(
                    "Scan row 3 for 8. A cell is eligible only if its column and box also allow 8.",
                    "Columns 2, 3, 4, 5, and 9 already contain 8. Box 2 blocks column 6, and box 3 blocks column 7.",
                    "Put 8 in row 3, column 1. This cell has several candidates, but the row needs its only available spot for 8.",
                ), listOf(picture(board, row(2), setOf(18), open, caption =
                    "Row 3 is shaded. Of the empty cells, only column 1 shows a small 8.")),
                    "Check one digit across an entire row, column, or box.")
            }
            Technique.LOCKED_CANDIDATES -> {
                val board = board(LOCKED_BOARD)
                val aligned = setOf(12, 14)
                val remove = setOf(3, 4, 5)
                Lesson(summary, listOf(
                    "In row 2, 3 can go only in column 4 or column 6. Both positions lie in the top-middle box.",
                    "One of the two outlined cells will hold 3, so the top-middle box already has a spot reserved for 3.",
                    "Cross off the 3s marked in red in row 1. This step narrows the candidates. Keep looking for a single you can fill.",
                ), listOf(picture(board, row(1) + box(1), aligned, aligned + remove, remove,
                    "The outlined cells in row 2 are the two possible spots for 3. Red outlines mark the cells that lose candidate 3.", Sudoku.bit(3))),
                    "Also look in the other direction. If all spots for a digit in a box lie on one row or column, cross off that digit from the line outside the box.")
            }
            Technique.NAKED_PAIR -> {
                val board = board(PAIR_BOARD)
                val pair = setOf(67, 76)
                val remove = setOf(4, 49)
                Lesson(summary, listOf(
                    "In column 5, row 8 and row 9 each allow only 1 and 8.",
                    "One of those cells must take 1 and the other must take 8. Both digits are reserved for that pair.",
                    "Cross off 8 in row 1, column 5 and cross off 1 and 8 in row 6, column 5. Row 1 now has only one spot for 8, in column 4. That next move is a ${Technique.HIDDEN_SINGLE.title.lowercase()}.",
                ), listOf(picture(board, column(4), pair, pair + remove, remove,
                    "The outlined pair in column 5 allows only 1 and 8. The red cells show just the candidates being crossed off.",
                    Sudoku.bit(1) or Sudoku.bit(8))),
                    "The two cells must have exactly the same two candidates and share a row, column, or box.")
            }
            Technique.CONTRADICTION -> {
                val original = board(PROOF_BOARD)
                val deadEnd = original.copyOf().apply {
                    this[1] = 3
                    this[29] = 3
                    this[35] = 7
                    this[36] = 7
                }
                val blockers = setOf(35, 36)
                Lesson(summary, listOf(
                    "Row 1, column 2 allows 3 or 5. Try 3 temporarily.",
                    "Column 3 now has only one spot for 3, in row 4. Place that 3. Row 4 now needs 7 in column 9.",
                    "In row 5, 7 can then only go in column 1. Follow each of these ${Technique.HIDDEN_SINGLE.title.lowercase()}s.",
                    "Before the trial, row 7 allowed 7 in columns 1 and 9. The new 7s block both spots. Row 7 must contain 7, so the trial cannot work.",
                    "Undo every trial digit. The starting cell has only 5 left.",
                ), listOf(
                    picture(original, row(0), setOf(1), setOf(1), caption = "Start with the two notes in row 1, column 2."),
                    picture(deadEnd, row(6), blockers, emptySet(), setOf(54, 62),
                        "After following the trial, the outlined 7s block both remaining spots for 7 in row 7."),
                ), "Follow forced moves until you can name the rule that fails. The hint can show the supporting steps for each move.")
            }
        }
    }

    private fun board(value: String) = requireNotNull(Sudoku.decode(value))
    private fun row(index: Int) = (index * 9 until index * 9 + 9).toSet()
    private fun column(index: Int) = (0..8).map { it * 9 + index }.toSet()
    private fun box(index: Int) = (0..8).map { (index / 3 * 3 + it / 3) * 9 + index % 3 * 3 + it % 3 }.toSet()

    private fun picture(
        board: IntArray,
        group: Set<Int>,
        focus: Set<Int>,
        showNotes: Set<Int>,
        crossed: Set<Int> = emptySet(),
        caption: String,
        mask: Int = Sudoku.ALL,
    ) = GuidePicture(
        Diagram(board, IntArray(81) { if (it in showNotes && board[it] == 0) Sudoku.candidates(board, it) and mask else 0 }, group, focus, crossed),
        caption,
    )
}

internal class GuideScreen(private val context: Context, private val ui: Ui) {
    fun rules(page: LinearLayout) {
        ui.add(page, ui.heading("Sudoku Rules", 21), 14)
        ui.add(page, ui.text("Fill the 9 × 9 grid with digits 1 to 9. The starting clues stay fixed.", 15), 3)
        addRule(page, BoardSample.ROW, 108, "Each horizontal row must contain the digits 1 to 9 exactly once.")
        addRule(page, BoardSample.COLUMN, 19, "Each vertical column must contain the digits 1 to 9 exactly once.")
        addRule(page, BoardSample.BOX, 65, "Each outlined 3 × 3 box must contain the digits 1 to 9 exactly once.")
        val tips = ui.row().apply {
            background = ui.shape(ui.palette.selectedCell, 11)
            setPadding(ui.dp(10), ui.dp(9), ui.dp(10), ui.dp(9))
            addView(ImageView(context).apply {
                setImageDrawable(ui.icon(R.drawable.ic_hint, 23))
                imageTintList = ColorStateList.valueOf(ui.palette.accentInk)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(ui.dp(25), ui.dp(25)))
            ui.equal(this, ui.text("Use logic to place numbers. Every puzzle has one solution.", 14), 8)
        }
        ui.add(page, tips, 12)
        ui.add(page, ui.heading("Game controls", 18), 17)
        ui.add(page, ui.text("Tap a cell, then a digit. Use Erase to clear your entry. Tap a filled cell to highlight matching entries and pencil marks.", 14), 5)
        ui.add(page, ui.text("Turn on Notes to pencil in candidates. Auto-fill Candidates lists the digits allowed by the current row, column, and box. Use Hint to check entries or see an explained move.", 14), 7)
        ui.add(page, ui.text("Rows are numbered top to bottom. Columns are numbered left to right. Boxes 1–3 are across the top, 4–6 across the middle, and 7–9 across the bottom.", 14), 7)
    }

    fun strategies(page: LinearLayout, onLesson: (Technique) -> Unit) {
        val basics = ui.card()
        ui.add(basics, ui.heading("Basic solving methods", 20))
        ui.add(basics, ui.text("Start with a row, column, or box that is nearly full. List the digits that are missing."), 8)
        ui.add(basics, ui.text("Check an empty cell against its row, column, and box. If only one digit fits, place it."), 8)
        ui.add(basics, ui.text("Pick a missing digit and scan a row, column, or box for possible spots. If only one spot remains, place it there. After each placement, check the affected lines and box again."), 8)
        for (technique in Guide.basicTechniques) addLessonLink(basics, technique, onLesson)
        ui.add(page, basics, 12)

        val advanced = ui.card()
        ui.add(advanced, ui.heading("Advanced solving techniques", 20))
        ui.add(advanced, ui.text("When the basic checks stall, look for patterns that let you cross off candidates. After each elimination, check for a single you can fill."), 8)
        for (technique in Guide.advancedTechniques) addLessonLink(advanced, technique, onLesson)
        ui.add(page, advanced, 12)
    }

    private fun addRule(page: LinearLayout, sample: BoardSample, sampleWidth: Int, explanation: String) {
        val row = ui.row().apply { minimumHeight = ui.dp(73) }
        val checkPalette = GameTheme.FOREST.palette(ui.palette.dark)
        val illustration = FrameLayout(context).apply {
            addView(BoardSampleView(context, ui.palette, sample), FrameLayout.LayoutParams(ui.dp(sampleWidth), WRAP_CONTENT, Gravity.CENTER))
            addView(ImageView(context).apply {
                background = ui.shape(checkPalette.accent, 12)
                setPadding(ui.dp(3), ui.dp(3), ui.dp(3), ui.dp(3))
                setImageDrawable(ui.icon(R.drawable.ic_check, 14))
                imageTintList = ColorStateList.valueOf(checkPalette.onAccent)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, FrameLayout.LayoutParams(ui.dp(18), ui.dp(18), Gravity.END or Gravity.BOTTOM))
        }
        row.addView(illustration, LinearLayout.LayoutParams(ui.dp(132), WRAP_CONTENT))
        ui.equal(row, ui.text(explanation, 15), 12)
        ui.add(page, row, 7)
        page.addView(View(context).apply { setBackgroundColor(ui.palette.toolbarSurface) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ui.dp(1)).apply { topMargin = ui.dp(6) })
    }

    private fun addLessonLink(section: LinearLayout, technique: Technique, onLesson: (Technique) -> Unit) {
        ui.add(section, ui.button(technique.title) { onLesson(technique) }, 12)
        ui.add(section, ui.text(Guide.definition(technique), 15, ui.palette.muted), 4)
    }

    fun lesson(page: LinearLayout, technique: Technique) {
        val lesson = Guide.lesson(technique)
        val card = ui.card()
        ui.add(card, ui.text(lesson.summary, 17))
        ui.add(card, ui.heading("Worked example", 20), 18)
        addPicture(card, lesson.pictures.first())
        for ((index, instruction) in lesson.instructions.withIndex()) {
            if (lesson.pictures.size > 1 && index == 3) addPicture(card, lesson.pictures[1])
            ui.add(card, ui.text("${index + 1}. $instruction"), 10)
        }
        ui.add(card, ui.text(lesson.takeaway, 15, ui.palette.muted, bold = true), 18)
        ui.add(page, card, 16)
    }

    private fun addPicture(container: LinearLayout, picture: GuidePicture) {
        ui.add(container, BoardView(context, ui.palette).apply { render(picture.diagram, picture.caption) }, 14)
        ui.add(container, ui.text(picture.caption, 14, ui.palette.muted).apply {
            importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, 6)
    }
}
