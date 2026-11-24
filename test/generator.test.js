// Copyright (c) 2026 Kalam Shah
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

const assert = require('node:assert/strict');
const { test } = require('node:test');
const core = require('../www/generator.js');
const stepText = (step) => typeof step === 'string' ? step : step.label;

for (const [difficulty, [minClues, maxClues]] of Object.entries(core.DIFFICULTY_CLUES)) {
  test(`generates a valid, uniquely solvable ${difficulty} puzzle`, () => {
    let seed = 0x1a2b3c4d;
    const rng = () => {
      seed = (Math.imul(seed, 1664525) + 1013904223) >>> 0;
      return seed / 2 ** 32;
    };
    for (let sample = 0; sample < 5; sample++) {
      const { puzzle, solution, clues } = core.generatePuzzle(difficulty, rng);

      assert.equal(clues, puzzle.flat().filter(Boolean).length);
      assert.ok(clues >= minClues && clues <= maxClues);
      for (let row = 0; row < core.SIZE; row++) {
        for (let col = 0; col < core.SIZE; col++) {
          assert.ok(core.isValidPlacement(solution, row, col, solution[row][col]));
          if (puzzle[row][col]) assert.equal(puzzle[row][col], solution[row][col]);
        }
      }
      assert.equal(core.countSolutions(puzzle, 2), 1);
      assert.deepEqual(core.solveGrid(puzzle), solution);
    }
  });
}

test('candidates exclude digits in the same row, column, and box', () => {
  const grid = core.emptyGrid();
  grid[0][8] = 1;
  grid[8][0] = 2;
  grid[1][1] = 3;
  grid[8][8] = 4;
  assert.deepEqual(core.candidatesFor(grid, 0, 0), [4, 5, 6, 7, 8, 9]);
});

test('solution counting stops at the requested limit', () => {
  assert.equal(core.countSolutions(core.emptyGrid(), 2), 2);
});

test('contradictory givens are rejected by the solver and hint engine', () => {
  const grid = core.emptyGrid();
  grid[0][0] = 1;
  grid[0][1] = 1;
  assert.equal(core.countSolutions(grid, 2), 0);
  assert.equal(core.solveGrid(grid), null);
  assert.equal(core.findHint(grid), null);
});

test('Hard fits the logical technique limit and Extreme exceeds it', () => {
  let seed = 0x55aa5809;
  const rng = () => {
    seed = (Math.imul(seed, 1664525) + 1013904223) >>> 0;
    return seed / 2 ** 32;
  };
  const extreme = new Set();
  for (let sample = 0; sample < 12; sample++) {
    assert.equal(core.canSolveWithoutSearch(core.generatePuzzle('hard', rng).puzzle), true);
    const puzzle = core.generatePuzzle('extreme', rng).puzzle;
    assert.equal(core.canSolveWithoutSearch(puzzle), false);
    extreme.add(puzzle.flat().join(''));
  }
  assert.equal(extreme.size, 12);
});

test('a hint explains a naked single without changing the grid', () => {
  const solved = core.generateSolvedGrid(() => 0.5);
  const board = core.cloneGrid(solved);
  board[1][8] = 0;
  const move = core.findHint(board);
  assert.deepEqual([move.row, move.col, move.value], [1, 8, solved[1][8]]);
  assert.equal(move.technique, 'Naked single');
  assert.match(move.steps[0], /^Row 2 already has /);
  assert.equal(move.explanation, solved[1][8] + ' is the only digit that fits in this cell.');
  assert.equal(board[1][8], 0);
});

test('advanced hints rule out candidates using board constraints', () => {
  const digits = '904700030020040009000009427402097300109600742000400095295831674871964253346275918';
  const board = digits.match(/.{9}/g).map((row) => [...row].map(Number));
  const move = core.findHint(board);
  assert.equal(move.technique, 'Proof by contradiction');
  assert.deepEqual([move.row, move.col, move.value], [0, 1, 1]);
  const trials = move.steps.filter((step) => typeof step !== 'string');
  assert.deepEqual(trials.map(stepText), [
    'What if row 1, column 2 is 5?',
    'What if row 1, column 2 is 6?',
    'What if row 1, column 2 is 8?',
  ]);
  for (const trial of trials) {
    assert.ok(trial.expand().map(stepText).some((step) => step.startsWith('There is nowhere to put')));
  }
  assert.equal(board[0][1], 0);
  board[0][1] = 8;
  assert.equal(core.countSolutions(board, 1), 0);
  board[0][1] = move.value;
  assert.equal(core.countSolutions(board, 2), 1);
});

test('the screenshot hint shows a short forced chain and the missing 7', () => {
  const digits = '600090741479165382182347596800000150001006009200071063008600010300010900010000630';
  const board = digits.match(/.{9}/g).map((row) => [...row].map(Number));
  const move = core.findHint(board);
  assert.deepEqual([move.row, move.col, move.value], [0, 1, 5]);
  assert.equal(move.technique, 'Proof by contradiction');
  assert.deepEqual(move.steps.map(stepText), [
    'The row, column, and box leave 3 and 5 as candidates here.',
    'Suppose 3 goes in row 1, column 2.',
    'In column 3, 3 can only go in row 4.',
    'In row 4, 7 can only go in column 9.',
    'In row 5, 7 can only go in column 1.',
    'There is nowhere to put 7 in row 7.',
  ]);
  const evidence = move.steps.at(-1).expand();
  assert.ok(evidence.includes('Column 1 already contains 7, ruling it out at row 7, column 1.'));
  assert.ok(evidence.includes('Column 9 already contains 7, ruling it out at row 7, column 9.'));
  assert.equal(board.flat().join(''), digits);
});

test('an elimination hint explains only deductions needed by the chosen cell', () => {
  const digits = '000000000000020708043907000402609800085001206060050000724090000001460007698000030';
  const board = digits.match(/.{9}/g).map((row) => [...row].map(Number));
  const move = core.findHint(board);
  assert.deepEqual([move.row, move.col, move.value], [4, 3, 7]);
  assert.ok(move.steps.includes('That leaves 3 and 7 to check in this cell.'));
  const eliminations = move.steps.filter((step) => step.includes('Cross off'));
  assert.equal(eliminations.length, 2);
  assert.match(eliminations[0], /In row 2, 3 can only go in column 4 or column 6/);
  assert.match(eliminations[1], /Cross off 3 elsewhere in box 5, outside column 5/);
});

test('a hidden single explains blocked cells and the pair that removes the last alternative', () => {
  const digits = '031004009007030480008020035002093048683040007094000300015076000309500064006009500';
  const board = digits.match(/.{9}/g).map((row) => [...row].map(Number));
  const move = core.findHint(board);
  assert.deepEqual([move.row, move.col, move.value], [0, 3, 8]);
  assert.equal(move.technique, 'Hidden single');
  assert.ok(move.steps.includes('Box 1 already contains 8, ruling it out at row 1, column 1.'));
  assert.ok(move.steps.some((step) => step.includes('row 8, column 5 and row 9, column 5 must take 1 and 8')));
});

test('a deep proof exposes every branch down to a concrete dead end', () => {
  const digits = '079000000060903107800750900008010030706000005003400000907200000680000002002160079';
  const board = digits.match(/.{9}/g).map((row) => [...row].map(Number));
  const move = core.findHint(board);
  assert.deepEqual([move.row, move.col, move.value], [0, 3, 8]);

  function inspect(steps) {
    const branches = steps.filter((step) => stepText(step).startsWith('What if '));
    if (branches.length) {
      for (const branch of branches) inspect(branch.expand());
      return;
    }
    const deadEnd = steps.find((step) => /^(There is nowhere to put|No digit can go)/.test(stepText(step)));
    assert.ok(deadEnd);
    assert.ok(deadEnd.expand().length > 0);
  }

  const choices = move.steps.filter((step) => stepText(step).startsWith('What if '));
  assert.deepEqual(choices.map(stepText), [
    'What if row 2, column 3 is 4?',
    'What if row 2, column 3 is 5?',
  ]);
  inspect(move.steps);
  assert.equal(board.flat().join(''), digits);
});
