// Copyright (c) 2026 Kalam Shah
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.kalamcs.sudoku

import android.content.res.AssetManager
import android.system.Os
import kotlin.random.Random

internal object RatedPuzzleBank {
    const val RECORD_SIZE = 103
    const val VARIANT_COUNT = 3
    private const val VARIANT_SIZE = 7
    private const val GRID_OFFSET = 1
    private const val VARIANTS_OFFSET = 82
    private val triples = arrayOf(
        intArrayOf(0, 1, 2), intArrayOf(0, 2, 1), intArrayOf(1, 0, 2),
        intArrayOf(1, 2, 0), intArrayOf(2, 0, 1), intArrayOf(2, 1, 0),
    )

    fun generate(difficulty: Difficulty, assets: AssetManager, random: Random = Random.Default): Puzzle {
        if (difficulty == Difficulty.EASY) return Sudoku.generateEasy(random)
        return assets.openFd("puzzles/${difficulty.key}.sudoku").use { asset ->
            select(difficulty, asset.length, random) { offset, record ->
                var read = 0
                while (read < record.size) {
                    val size = Os.pread(asset.fileDescriptor, record, read, record.size - read,
                        asset.startOffset + offset + read)
                    check(size > 0) { "Incomplete puzzle record" }
                    read += size
                }
            }
        }
    }

    fun select(
        difficulty: Difficulty,
        length: Long,
        random: Random,
        read: (offset: Long, record: ByteArray) -> Unit,
    ): Puzzle {
        check(length > 0 && length % RECORD_SIZE == 0L)
        val offset = random.nextLong(length / RECORD_SIZE) * RECORD_SIZE
        val record = ByteArray(RECORD_SIZE)
        read(offset, record)
        return decode(difficulty, record, random.nextInt(1, VARIANT_COUNT + 1))
    }

    fun rating(record: ByteArray, variant: Int): Int =
        unsigned(record[if (variant == 0) 0 else variantOffset(variant)])

    fun decode(difficulty: Difficulty, record: ByteArray, variant: Int): Puzzle {
        check(record.size == RECORD_SIZE && variant in 0..VARIANT_COUNT)
        check(rating(record, variant) in difficulty.seRatings)
        var transform = 0L
        if (variant > 0) {
            val start = variantOffset(variant)
            for (i in 6 downTo 1) transform = (transform shl 8) or unsigned(record[start + i]).toLong()
        }
        val rows = axis((transform and 0x7ff).toInt())
        val columns = axis(((transform shr 11) and 0x7ff).toInt())
        val transposed = transform and (1L shl 22) != 0L
        val digits = digits((transform shr 23).toInt())
        val givens = IntArray(Sudoku.COUNT)
        val solution = IntArray(Sudoku.COUNT)
        for (cell in givens.indices) {
            val row = rows[cell / Sudoku.SIZE]
            val column = columns[cell % Sudoku.SIZE]
            val source = if (transposed) column * Sudoku.SIZE + row else row * Sudoku.SIZE + column
            givens[cell] = digits[unpack(record, source)]
            solution[cell] = digits[unpack(record, Sudoku.COUNT + source)]
        }
        return Puzzle(difficulty, givens, solution)
    }

    private fun unsigned(value: Byte) = value.toInt() and 0xff
    private fun variantOffset(variant: Int) = VARIANTS_OFFSET + (variant - 1) * VARIANT_SIZE

    private fun unpack(record: ByteArray, cell: Int): Int {
        val pair = unsigned(record[GRID_OFFSET + cell / 2])
        return if (cell % 2 == 0) pair shr 4 else pair and 0xf
    }

    private fun axis(code: Int): IntArray {
        val result = IntArray(Sudoku.SIZE)
        val bands = triples[code / 216]
        var divisor = 36
        for (band in bands.indices) {
            val offsets = triples[code / divisor % 6]
            for (row in offsets.indices) result[3 * band + row] = 3 * bands[band] + offsets[row]
            divisor /= 6
        }
        return result
    }

    private fun digits(code: Int): IntArray {
        val available = (1..Sudoku.SIZE).toMutableList()
        val result = IntArray(Sudoku.SIZE + 1)
        var order = code
        var block = 362_880
        for (digit in 1..Sudoku.SIZE) {
            block /= available.size
            result[digit] = available.removeAt(order / block)
            order %= block
        }
        return result
    }
}
