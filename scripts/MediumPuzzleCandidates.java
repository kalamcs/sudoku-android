// Copyright (c) 2026 Kalam Shah
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

import com.kalamcs.sudoku.Sudoku;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Random;
import kotlin.random.RandomKt;

class MediumPuzzleCandidates {
    public static void main(String[] args) {
        int count = Integer.parseInt(args[0]);
        var grids = RandomKt.Random(20261126);
        var removals = new Random(20261126);
        var cells = new ArrayList<Integer>(81);
        for (int cell = 0; cell < 81; cell++) cells.add(cell);

        int candidates = 0;
        while (candidates < count) {
            var board = Sudoku.INSTANCE.generateEasy(grids).getSolution();
            int target = 25 + removals.nextInt(6);
            int clues = 81;
            Collections.shuffle(cells, removals);
            for (int cell : cells) {
                if (clues == target) break;
                int digit = board[cell];
                board[cell] = 0;
                if (Sudoku.INSTANCE.countSolutions(board, 2) == 1) {
                    clues--;
                } else {
                    board[cell] = digit;
                }
            }
            if (Sudoku.INSTANCE.solvesWithSingles(board)) continue;
            var line = new StringBuilder(81);
            for (int digit : board) line.append(digit);
            System.out.println(line);
            candidates++;
        }
    }
}
