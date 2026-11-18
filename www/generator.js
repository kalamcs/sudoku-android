/**
 * Sudoku engine (pure logic, no DOM).
 *
 * Works in the browser (exposed as window.SudokuCore) and in Node (module.exports)
 * so it can be unit-tested without a browser.
 */
(function (root, factory) {
  if (typeof module === 'object' && module.exports) {
    module.exports = factory();
  } else {
    root.SudokuCore = factory();
  }
})(typeof self !== 'undefined' ? self : this, function () {
  'use strict';

  var SIZE = 9;
  var BOX = 3;

  // Clue-count targets per difficulty. The generator removes clues (while keeping
  // the solution unique) until it reaches a random value inside the range, or
  // until no more clues can safely be removed.
  var DIFFICULTY_CLUES = {
    easy: [40, 45],
    medium: [32, 37],
    hard: [26, 30]
  };

  var DIFFICULTIES = ['easy', 'medium', 'hard'];

  function emptyGrid() {
    var grid = [];
    for (var r = 0; r < SIZE; r++) {
      grid.push(new Array(SIZE).fill(0));
    }
    return grid;
  }

  function cloneGrid(grid) {
    return grid.map(function (row) { return row.slice(); });
  }

  function randInt(min, max, rng) {
    return min + Math.floor(rng() * (max - min + 1));
  }

  function shuffle(array, rng) {
    rng = rng || Math.random;
    var copy = array.slice();
    for (var i = copy.length - 1; i > 0; i--) {
      var j = Math.floor(rng() * (i + 1));
      var tmp = copy[i];
      copy[i] = copy[j];
      copy[j] = tmp;
    }
    return copy;
  }

  /** True if placing `value` at (row, col) does not violate Sudoku rules. */
  function isValidPlacement(grid, row, col, value) {
    if (value < 1 || value > SIZE) return false;

    for (var c = 0; c < SIZE; c++) {
      if (c !== col && grid[row][c] === value) return false;
    }
    for (var r = 0; r < SIZE; r++) {
      if (r !== row && grid[r][col] === value) return false;
    }

    var boxRow = Math.floor(row / BOX) * BOX;
    var boxCol = Math.floor(col / BOX) * BOX;
    for (var br = boxRow; br < boxRow + BOX; br++) {
      for (var bc = boxCol; bc < boxCol + BOX; bc++) {
        if ((br !== row || bc !== col) && grid[br][bc] === value) return false;
      }
    }
    return true;
  }

  /** Candidate values for a cell (used by the MRV solver). */
  function candidatesFor(grid, row, col) {
    var candidates = [];
    for (var n = 1; n <= SIZE; n++) {
      if (isValidPlacement(grid, row, col, n)) candidates.push(n);
    }
    return candidates;
  }

  /** Fill an empty grid completely using randomized backtracking. */
  function generateSolvedGrid(rng) {
    rng = rng || Math.random;
    var grid = emptyGrid();
    var numbers = [1, 2, 3, 4, 5, 6, 7, 8, 9];

    function fill() {
      var target = -1;
      for (var i = 0; i < SIZE * SIZE; i++) {
        var r = Math.floor(i / SIZE);
        var c = i % SIZE;
        if (grid[r][c] === 0) { target = i; break; }
      }
      if (target === -1) return true; // complete

      var row = Math.floor(target / SIZE);
      var col = target % SIZE;
      var values = shuffle(numbers, rng);
      for (var k = 0; k < values.length; k++) {
        if (isValidPlacement(grid, row, col, values[k])) {
          grid[row][col] = values[k];
          if (fill()) return true;
          grid[row][col] = 0;
        }
      }
      return false;
    }

    if (!fill()) throw new Error('Failed to generate a solved Sudoku grid');
    return grid;
  }

  /**
   * Count solutions for a grid, stopping once `limit` is reached.
   * Uses MRV (most-constrained cell first) so uniqueness checks stay fast.
   */
  function countSolutions(grid, limit) {
    limit = limit || 2;
    var work = cloneGrid(grid);
    var count = 0;

    function search() {
      var bestRow = -1;
      var bestCol = -1;
      var bestCandidates = null;

      outer:
      for (var r = 0; r < SIZE; r++) {
        for (var c = 0; c < SIZE; c++) {
          if (work[r][c] !== 0) continue;
          var candidates = candidatesFor(work, r, c);
          if (candidates.length === 0) return false; // dead end
          if (bestCandidates === null || candidates.length < bestCandidates.length) {
            bestRow = r;
            bestCol = c;
            bestCandidates = candidates;
            if (candidates.length === 1) break outer;
          }
        }
      }

      if (bestCandidates === null) {
        count++;
        return count >= limit; // signal caller to stop searching
      }

      for (var i = 0; i < bestCandidates.length; i++) {
        work[bestRow][bestCol] = bestCandidates[i];
        if (search()) {
          work[bestRow][bestCol] = 0;
          return true;
        }
        work[bestRow][bestCol] = 0;
      }
      return false;
    }

    search();
    return count;
  }

  /** Solve a grid (returns the solved grid or null when unsolvable). */
  function solveGrid(grid) {
    var work = cloneGrid(grid);

    function search() {
      for (var r = 0; r < SIZE; r++) {
        for (var c = 0; c < SIZE; c++) {
          if (work[r][c] !== 0) continue;
          for (var n = 1; n <= SIZE; n++) {
            if (isValidPlacement(work, r, c, n)) {
              work[r][c] = n;
              if (search()) return true;
              work[r][c] = 0;
            }
          }
          return false;
        }
      }
      return true;
    }

    return search() ? work : null;
  }

  /**
   * Generate a puzzle + solution for a difficulty.
   * @returns {{puzzle:number[][], solution:number[][], difficulty:string, clues:number}}
   */
  function generatePuzzle(difficulty, rng) {
    difficulty = difficulty || 'medium';
    rng = rng || Math.random;
    var range = DIFFICULTY_CLUES[difficulty] || DIFFICULTY_CLUES.medium;
    var targetClues = randInt(range[0], range[1], rng);

    var solution = generateSolvedGrid(rng);
    var puzzle = cloneGrid(solution);

    var positions = [];
    for (var i = 0; i < SIZE * SIZE; i++) positions.push(i);
    positions = shuffle(positions, rng);

    var clues = SIZE * SIZE;
    for (var p = 0; p < positions.length && clues > targetClues; p++) {
      var row = Math.floor(positions[p] / SIZE);
      var col = positions[p] % SIZE;
      var backup = puzzle[row][col];
      if (backup === 0) continue;

      puzzle[row][col] = 0;
      if (countSolutions(puzzle, 2) === 1) {
        clues--;
      } else {
        puzzle[row][col] = backup; // removal would break uniqueness
      }
    }

    return {
      puzzle: puzzle,
      solution: solution,
      difficulty: difficulty,
      clues: clues
    };
  }

  /** Verify a completed grid matches the solution. */
  function isSolved(grid, solution) {
    if (!grid || !solution) return false;
    for (var r = 0; r < SIZE; r++) {
      for (var c = 0; c < SIZE; c++) {
        if (grid[r][c] !== solution[r][c]) return false;
      }
    }
    return true;
  }

  return {
    SIZE: SIZE,
    BOX: BOX,
    DIFFICULTIES: DIFFICULTIES,
    DIFFICULTY_CLUES: DIFFICULTY_CLUES,
    emptyGrid: emptyGrid,
    cloneGrid: cloneGrid,
    shuffle: shuffle,
    isValidPlacement: isValidPlacement,
    candidatesFor: candidatesFor,
    generateSolvedGrid: generateSolvedGrid,
    countSolutions: countSolutions,
    solveGrid: solveGrid,
    generatePuzzle: generatePuzzle,
    isSolved: isSolved
  };
});
