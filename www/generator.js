// Copyright (c) 2026 Kalam Shah
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

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
  var SHORT_PROOF_LIMIT = 256;

  // Clue-count targets per difficulty. The generator removes clues (while keeping
  // the solution unique) until it reaches a random value inside the range, or
  // until no more clues can safely be removed.
  var DIFFICULTY_CLUES = {
    easy: [40, 45],
    medium: [32, 37],
    hard: [26, 30],
    extreme: [23, 25]
  };

  var DIFFICULTIES = Object.keys(DIFFICULTY_CLUES);
  var HARD_FALLBACK = '000800094009004000007906805003090600000020050960008200450080030000100000010005048';
  // Generated locally and checked for a unique solution beyond the Hard
  // technique limit. Sudoku symmetries preserve that difficulty on-device.
  var EXTREME_PUZZLES = [
    '080090000041070005000200800500010009070300040006025000900002000000000700008000260',
    '000000027120000006000001804000800670003007090010000000008090300200068400500000000',
    '000000000060740300048000510003600000107000000000105408300200104000409060000050000',
    '005200000000001000010040700001096082070050009004000300060080040000020103500000070',
    '000000000800406900700028001000003020040000610002700000086107050000005860050000009',
    '094030000800900000000005030050006080100003400060040000010600700087090000000102908'
  ];
  var DIGITS = [1, 2, 3, 4, 5, 6, 7, 8, 9];
  var CELL_INDEXES = Array.from({ length: SIZE * SIZE }, function (_, index) { return index; });
  var UNITS = ['row', 'column', 'box'].flatMap(function (type) {
    return DIGITS.map(function (number) {
      var cells = CELL_INDEXES.filter(function (index) {
        var row = Math.floor(index / SIZE);
        var col = index % SIZE;
        if (type === 'row') return row === number - 1;
        if (type === 'column') return col === number - 1;
        return Math.floor(row / BOX) * BOX + Math.floor(col / BOX) === number - 1;
      });
      return { type: type, name: type + ' ' + number, cells: cells };
    });
  });
  var CELL_UNITS = CELL_INDEXES.map(function (index) {
    return UNITS.filter(function (unit) { return unit.cells.includes(index); });
  });

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
      var values = shuffle(DIGITS, rng);
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
  function searchSolutions(grid, limit, onSolution) {
    if (!hasValidValues(grid)) return 0;
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
        if (onSolution) onSolution(cloneGrid(work));
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

  function countSolutions(grid, limit) {
    return searchSolutions(grid, limit || 2);
  }

  /** Solve a grid (returns the solved grid or null when unsolvable). */
  function solveGrid(grid) {
    var solution = null;
    searchSolutions(grid, 1, function (solved) { solution = solved; });
    return solution;
  }

  function hasValidValues(grid) {
    return Array.isArray(grid) && grid.length === SIZE && grid.every(function (row, r) {
      return Array.isArray(row) && row.length === SIZE && row.every(function (value, c) {
        return value === 0 || (Number.isInteger(value) && isValidPlacement(grid, r, c, value));
      });
    });
  }

  function cellName(index) {
    return 'row ' + (Math.floor(index / SIZE) + 1) + ', column ' + (index % SIZE + 1);
  }

  function placeIn(unit, index) {
    if (unit.type === 'row') return 'column ' + (index % SIZE + 1);
    if (unit.type === 'column') return 'row ' + (Math.floor(index / SIZE) + 1);
    return cellName(index);
  }

  function formatDigits(digits) {
    if (digits.length <= 1) return digits.join('');
    var separator = digits.length === 2 ? ' and ' : ', and ';
    return digits.slice(0, -1).join(', ') + separator + digits[digits.length - 1];
  }

  function moveAt(index, value, technique, explanation) {
    return {
      row: Math.floor(index / SIZE),
      col: index % SIZE,
      value: value,
      technique: technique,
      explanation: explanation
    };
  }

  function boardCandidates(grid, filled) {
    return CELL_INDEXES.map(function (index) {
      return filled[index] === 0 ? candidatesFor(grid, Math.floor(index / SIZE), index % SIZE) : [];
    });
  }

  function placesFor(unit, digit, candidates) {
    return unit.cells.filter(function (index) { return candidates[index].includes(digit); });
  }

  function findSingle(candidates) {
    var single = candidates.findIndex(function (values) { return values.length === 1; });
    if (single !== -1) {
      var value = candidates[single][0];
      return moveAt(single, value, 'Naked single', value + ' is the only digit that fits in this cell.');
    }
    for (var unit of UNITS) {
      for (var digit of DIGITS) {
        var places = placesFor(unit, digit, candidates);
        if (places.length === 1) {
          return Object.assign(moveAt(places[0], digit, 'Hidden single',
            'This is the only cell in ' + unit.name + ' where ' + digit + ' can go.'), { unit: unit });
        }
      }
    }
    return null;
  }

  // A digit confined to the intersection of two units cannot be placed
  // elsewhere in the second unit. This covers both pointing and claiming.
  function findLockedCandidates(candidates) {
    for (var unit of UNITS) {
      for (var digit of DIGITS) {
        var places = placesFor(unit, digit, candidates);
        if (places.length < 2) continue;
        for (var other of CELL_UNITS[places[0]]) {
          if (other === unit || !places.every(function (index) { return other.cells.includes(index); })) continue;
          var cells = other.cells.filter(function (index) {
            return !unit.cells.includes(index) && candidates[index].includes(digit);
          });
          if (cells.length === 0) continue;
          return {
            cells: cells, digits: [digit],
            neededCells: unit.cells.filter(function (index) { return !other.cells.includes(index); }),
            neededDigits: [digit],
            reason: 'In ' + unit.name + ', ' + digit + ' can only go in ' +
              places.map(function (index) { return placeIn(unit, index); }).join(' or ') +
              '. Those cells share ' + other.name + '. Cross off ' + digit +
              ' elsewhere in ' + other.name + ', outside ' + unit.name + '.'
          };
        }
      }
    }
    return null;
  }

  function findNakedPair(candidates) {
    for (var unit of UNITS) {
      var pairs = new Map();
      for (var index of unit.cells) {
        var values = candidates[index];
        if (values.length !== 2) continue;
        var key = values.join('');
        if (!pairs.has(key)) pairs.set(key, []);
        pairs.get(key).push(index);
      }
      for (var group of pairs.values()) {
        if (group.length !== 2) continue;
        var pair = candidates[group[0]];
        var cells = unit.cells.filter(function (index) {
          return !group.includes(index) && candidates[index].some(function (value) { return pair.includes(value); });
        });
        if (cells.length === 0) continue;
        return {
          cells: cells, digits: pair, neededCells: group,
          neededDigits: DIGITS.filter(function (digit) { return !pair.includes(digit); }),
          reason: 'The cells at ' + cellName(group[0]) + ' and ' + cellName(group[1]) + ' must take ' +
            pair.join(' and ') + ' in some order. Cross off both digits elsewhere in ' + unit.name + '.'
        };
      }
    }
    return null;
  }

  function analyzeCandidates(grid) {
    var filled = grid.flat();
    var candidates = boardCandidates(grid, filled);
    var eliminations = [];
    while (true) {
      if (filled.some(function (value, index) { return value === 0 && candidates[index].length === 0; })) {
        return { move: null, candidates: [], eliminations: eliminations };
      }
      var move = findSingle(candidates);
      if (move) return { move: move, candidates: candidates, eliminations: eliminations };
      var step = findLockedCandidates(candidates) || findNakedPair(candidates);
      if (!step) return { move: null, candidates: candidates, eliminations: eliminations };
      step.removed = [];
      step.cells.forEach(function (index) {
        candidates[index] = candidates[index].filter(function (digit) {
          if (!step.digits.includes(digit)) return true;
          step.removed.push(index * 10 + digit);
          return false;
        });
      });
      eliminations.push(step);
    }
  }

  // Start with the candidates the hint actually needs to rule out. Follow
  // their dependencies backwards and leave unrelated eliminations out.
  function relevantEliminations(eliminations, needed) {
    var reasons = [];
    for (var index = eliminations.length - 1; index >= 0; index--) {
      var step = eliminations[index];
      if (!step.removed.some(function (key) { return needed.has(key); })) continue;
      reasons.push(step.reason);
      step.removed.forEach(function (key) { needed.delete(key); });
      step.neededCells.forEach(function (cell) {
        step.neededDigits.forEach(function (digit) { needed.add(cell * 10 + digit); });
      });
    }
    return reasons.reverse();
  }

  function explainNakedSingle(filled, index, value) {
    var missing = new Set(DIGITS.filter(function (digit) { return digit !== value; }));
    var steps = [];
    for (var unit of CELL_UNITS[index]) {
      var blocked = unit.cells.map(function (cell) { return filled[cell]; }).filter(function (digit) {
        return missing.delete(digit);
      });
      if (blocked.length) {
        blocked.sort(function (a, b) { return a - b; });
        steps.push(capitalizeUnit(unit.name) + ' already has ' + formatDigits(blocked) + '.');
      }
    }
    if (missing.size > 0) {
      steps.push('That leaves ' + formatDigits(Array.from(missing).concat(value).sort(function (a, b) { return a - b; })) +
        ' to check in this cell.');
    }
    return { steps: steps, needed: new Set(Array.from(missing, function (digit) { return index * 10 + digit; })) };
  }

  function capitalizeUnit(name) {
    return name.charAt(0).toUpperCase() + name.slice(1);
  }

  function explainHiddenSingle(filled, index, value, unit) {
    var blockedBy = new Map();
    var needed = new Set();
    for (var other of unit.cells) {
      if (other === index || filled[other] !== 0) continue;
      var blockingUnit = CELL_UNITS[other].find(function (candidate) {
        return candidate.cells.some(function (cell) { return filled[cell] === value; });
      });
      if (!blockingUnit) {
        needed.add(other * 10 + value);
        continue;
      }
      if (!blockedBy.has(blockingUnit)) blockedBy.set(blockingUnit, []);
      blockedBy.get(blockingUnit).push(other);
    }
    var steps = Array.from(blockedBy, function (entry) {
      var unitName = capitalizeUnit(entry[0].name);
      return unitName + ' already contains ' + value + ', ruling it out at ' + entry[1].map(cellName).join(' and ') + '.';
    });
    if (steps.length === 0 && needed.size === 0) {
      steps.push('This is the last empty cell in ' + unit.name + '.');
    }
    return { steps: steps, needed: needed };
  }

  function explainSingle(grid, analysis) {
    var move = analysis.move;
    var index = move.row * SIZE + move.col;
    var filled = grid.flat();
    var evidence = move.unit
      ? explainHiddenSingle(filled, index, move.value, move.unit)
      : explainNakedSingle(filled, index, move.value);
    return Object.assign(move, {
      steps: evidence.steps.concat(relevantEliminations(analysis.eliminations, evidence.needed))
    });
  }

  function canSolveWithoutSearch(grid) {
    if (!hasValidValues(grid)) return false;
    var work = cloneGrid(grid);
    while (work.some(function (row) { return row.includes(0); })) {
      var move = analyzeCandidates(work).move;
      if (!move) return false;
      work[move.row][move.col] = move.value;
    }
    return true;
  }

  function mostConstrainedCell(candidates) {
    var best = -1;
    candidates.forEach(function (values, index) {
      if (values.length > 0 && (best === -1 || values.length < candidates[best].length)) best = index;
    });
    return best;
  }

  function trialMoveStep(filled, move) {
    return {
      label: move.reason,
      expand: function () {
        return move.unit
          ? explainHiddenSingle(filled, move.index, move.digit, move.unit).steps
          : explainNakedSingle(filled, move.index, move.digit).steps;
      }
    };
  }

  // Find all currently forced singles and check both kinds of dead end:
  // a square without a candidate or a unit without a place for a missing digit.
  function inspectTrial(grid) {
    var filled = grid.flat();
    var candidates = boardCandidates(grid, filled);
    var moves = new Map();
    for (var index of CELL_INDEXES) {
      if (filled[index] !== 0) continue;
      var values = candidates[index];
      if (values.length === 0) {
        return { deadEnd: {
          label: 'No digit can go in ' + cellName(index) + '.',
          expand: function () { return explainNakedSingle(filled, index, 0).steps; }
        } };
      }
      if (values.length === 1) {
        var digit = values[0];
        moves.set(index * 10 + digit, { index: index, digit: digit,
          reason: 'Only ' + digit + ' can go in ' + cellName(index) + '.' });
      }
    }

    for (var unit of UNITS) {
      var present = new Set(unit.cells.map(function (index) { return filled[index]; }));
      for (var digit of DIGITS) {
        if (present.has(digit)) continue;
        var places = placesFor(unit, digit, candidates);
        if (places.length === 0) {
          return { deadEnd: {
            label: 'There is nowhere to put ' + digit + ' in ' + unit.name + '.',
            expand: function () { return explainHiddenSingle(filled, -1, digit, unit).steps; }
          } };
        }
        var key = places[0] * 10 + digit;
        if (places.length === 1 && !moves.has(key)) {
          moves.set(key, { index: places[0], digit: digit, unit: unit,
            reason: 'In ' + unit.name + ', ' + digit + ' can only go in ' + placeIn(unit, places[0]) + '.' });
        }
      }
    }
    return { deadEnd: null, moves: Array.from(moves.values()), candidates: candidates };
  }

  function placeTrialMove(grid, move) {
    grid[Math.floor(move.index / SIZE)][move.index % SIZE] = move.digit;
  }

  // A breadth-first pass avoids listing unrelated singles before the dead end.
  // The cap limits how much time we spend making the explanation shorter.
  function shortContradiction(grid) {
    var pending = [{ grid: grid, steps: [] }];
    var seen = new Set([grid.flat().join('')]);
    for (var i = 0; i < pending.length; i++) {
      var state = pending[i];
      var trial = inspectTrial(state.grid);
      if (trial.deadEnd) return state.steps.concat(trial.deadEnd);

      for (var move of trial.moves) {
        if (pending.length >= SHORT_PROOF_LIMIT) break;
        var next = cloneGrid(state.grid);
        placeTrialMove(next, move);
        var key = next.flat().join('');
        if (seen.has(key)) continue;
        seen.add(key);
        pending.push({ grid: next, steps: state.steps.concat(trialMoveStep(state.grid.flat(), move)) });
      }
    }
    return null;
  }

  function trialBranch(grid, index, digit) {
    return {
      label: 'What if ' + cellName(index) + ' is ' + digit + '?',
      expand: function () {
        var work = cloneGrid(grid);
        placeTrialMove(work, { index: index, digit: digit });
        return explainContradiction(work);
      }
    };
  }

  // The caller has verified this assumption has no solution. Show forced
  // moves first, and trace any remaining branches only when the player opens them.
  function explainContradiction(grid) {
    var short = shortContradiction(grid);
    if (short) return short;

    var work = cloneGrid(grid);
    var steps = [];
    var trial = inspectTrial(work);
    while (!trial.deadEnd && trial.moves.length > 0) {
      var move = trial.moves[0];
      steps.push(trialMoveStep(work.flat(), move));
      placeTrialMove(work, move);
      trial = inspectTrial(work);
    }
    var visibleSteps = steps.length > 4 ? [{
      label: 'Follow ' + steps.length + ' forced moves',
      expand: function () { return steps; }
    }] : steps;
    if (trial.deadEnd) return visibleSteps.concat(trial.deadEnd);

    var branch = mostConstrainedCell(trial.candidates);
    var choices = trial.candidates[branch];
    return visibleSteps.concat('The only candidates in ' + cellName(branch) + ' are ' + formatDigits(choices) +
      '. Open each choice to see why it fails.', choices.map(function (digit) {
      return trialBranch(work, branch, digit);
    }));
  }

  /** Explain one forced entry using the current board, independent of its saved solution. */
  function findHint(grid) {
    if (!hasValidValues(grid)) return null;
    var analysis = analyzeCandidates(grid);
    if (analysis.move) return explainSingle(grid, analysis);

    var best = mostConstrainedCell(analysis.candidates);
    if (best === -1) return null;

    var work = cloneGrid(grid);
    var row = Math.floor(best / SIZE);
    var col = best % SIZE;
    var candidates = candidatesFor(grid, row, col);
    var possible = [];
    var ruledOut = [];
    // Check the original candidates so the proof stands without unrelated
    // eliminations from the earlier pass.
    for (var digit of candidates) {
      work[row][col] = digit;
      if (countSolutions(work, 1) === 0) ruledOut.push(digit);
      else possible.push(digit);
    }
    if (possible.length !== 1) return null;
    var steps = ['The row, column, and box leave ' + formatDigits(candidates) + ' as candidates here.'];
    steps = steps.concat(ruledOut.length === 1
      ? ['Suppose ' + ruledOut[0] + ' goes in ' + cellName(best) + '.'].concat(trialBranch(grid, best, ruledOut[0]).expand())
      : ruledOut.map(function (digit) { return trialBranch(grid, best, digit); }));
    return Object.assign(moveAt(best, possible[0], 'Proof by contradiction',
      'Only ' + possible[0] + ' remains for this cell.'), { steps: steps });
  }

  function generateRandomPuzzle(difficulty, rng) {
    var range = DIFFICULTY_CLUES[difficulty];
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

  function shuffleBands(rng) {
    return shuffle([0, 1, 2], rng).flatMap(function (band) {
      return shuffle([0, 1, 2], rng).map(function (offset) { return band * BOX + offset; });
    });
  }

  function varyPuzzle(template, difficulty, rng) {
    var digits = [0].concat(shuffle(DIGITS, rng));
    var rows = shuffleBands(rng);
    var columns = shuffleBands(rng);
    var transpose = rng() < 0.5;
    var puzzle = rows.map(function (row) {
      return columns.map(function (col) {
        var index = transpose ? col * SIZE + row : row * SIZE + col;
        return digits[Number(template[index])];
      });
    });
    return {
      puzzle: puzzle,
      solution: solveGrid(puzzle),
      difficulty: difficulty,
      clues: puzzle.flat().filter(Boolean).length
    };
  }

  /** Generate a unique puzzle and its solution for the requested difficulty. */
  function generatePuzzle(difficulty, rng) {
    if (!DIFFICULTIES.includes(difficulty)) difficulty = 'medium';
    rng = rng || Math.random;
    if (difficulty === 'extreme') {
      return varyPuzzle(EXTREME_PUZZLES[randInt(0, EXTREME_PUZZLES.length - 1, rng)], difficulty, rng);
    }
    if (difficulty !== 'hard') return generateRandomPuzzle(difficulty, rng);

    // Hard tops out at singles, locked candidates, and naked pairs. Extreme
    // continues beyond those techniques. Bound retries to keep startup quick.
    for (var attempt = 0; attempt < 6; attempt++) {
      var result = generateRandomPuzzle(difficulty, rng);
      if (result.clues <= DIFFICULTY_CLUES.hard[1] && canSolveWithoutSearch(result.puzzle)) return result;
    }
    return varyPuzzle(HARD_FALLBACK, difficulty, rng);
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
    canSolveWithoutSearch: canSolveWithoutSearch,
    findHint: findHint,
    generatePuzzle: generatePuzzle,
    isSolved: isSolved
  };
});
