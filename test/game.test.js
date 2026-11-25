// Copyright (c) 2026 Kalam Shah
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const path = require('node:path');
const { test } = require('node:test');
const esbuild = require('esbuild');
const { JSDOM } = require('jsdom');
const core = require('../www/generator.js');

const root = path.join(__dirname, '..');
const read = (file) => readFileSync(path.join(root, file), 'utf8');
const saveKey = 'sudoku_save_v1';
const html = read('www/index.html');
const scripts = [read('www/generator.js'), read('www/app.js')];
const buildNative = (email) => esbuild.buildSync({
  entryPoints: [path.join(root, 'src/native.js')],
  bundle: true,
  format: 'iife',
  target: 'es2020',
  define: { __SUDOKU_FEEDBACK_EMAIL__: JSON.stringify(email) },
  write: false
}).outputFiles[0].text;
const nativeBundle = buildNative('');
const testRecipient = 'feedback@example.test';
const emailNativeBundle = buildNative(testRecipient);

const flush = () => new Promise((resolve) => setImmediate(resolve));

async function loadGame(t, { native = false, savedGame, emailFeedback = false, noEmailApp = false } = {}) {
  const dom = new JSDOM(html, {
    url: 'https://localhost',
    runScripts: 'outside-only',
    pretendToBeVisual: true
  });
  t.after(() => dom.window.close());
  const { window } = dom;
  const { document, localStorage } = window;
  const listeners = new Map();
  const bridgeCalls = [];
  if (native) {
    window.androidBridge = {};
    window.Capacitor = {
      PluginHeaders: [
        {
          name: 'App',
          methods: [
            { name: 'getInfo', rtype: 'promise' },
            { name: 'exitApp', rtype: 'promise' },
            { name: 'addListener', rtype: 'callback' }
          ]
        },
        { name: 'Browser', methods: [{ name: 'open', rtype: 'promise' }] },
        { name: 'SudokuFeedback', methods: [{ name: 'openEmail', rtype: 'promise' }] }
      ],
      async nativePromise(plugin, method, options) {
        bridgeCalls.push({ plugin, method, options });
        if (method === 'getInfo') return { name: 'Sudoku', version: '1.0', build: '1' };
        if (plugin === 'SudokuFeedback' && noEmailApp) throw new Error('No email app is available');
      },
      async nativeCallback(plugin, method, { eventName }, callback) {
        assert.equal(plugin, 'App');
        assert.equal(method, 'addListener');
        listeners.set(eventName, callback);
        return listeners.size;
      }
    };
  }

  let now = Date.now();
  let seed = 0x1a2b3c4d;
  window.Date.now = () => now;
  window.Math.random = () => {
    seed = (Math.imul(seed, 1664525) + 1013904223) >>> 0;
    return seed / 2 ** 32;
  };
  window.confirm = () => true;
  window.scrollTo = () => {};
  window.Element.prototype.scrollIntoView = () => {};
  if (savedGame) localStorage.setItem(saveKey, savedGame);
  for (const script of scripts) window.eval(script);
  window.eval(emailFeedback ? emailNativeBundle : nativeBundle);
  await flush();
  if (native) assert.equal(listeners.size, 2);
  const game = window.sudokuGame;
  const get = (selector) => document.querySelector(selector);
  return {
    window, document, game, get, bridgeCalls,
    click: (selector) => get(selector).click(),
    cell: (index) => game.cells[index].click(),
    digit: (digit) => get(`#numpad button[data-n="${digit}"]`),
    elapse: (seconds) => { now += seconds * 1000; },
    emit: (eventName, details) => listeners.get(eventName)(details),
    savedGame: () => localStorage.getItem(saveKey),
    async start(difficulty = 'easy') {
      get(`.home-difficulty[data-difficulty="${difficulty}"]`).click();
      await new Promise((resolve) => window.setTimeout(resolve, 50));
      assert.equal(game.view, 'game');
      assert.equal(game.generating, false);
    }
  };
}

function fillBoard(app, wrongIndex = -1) {
  const { game } = app;
  for (const [index, digit] of game.solution.entries()) {
    if (game.puzzle[index]) continue;
    app.cell(index);
    app.digit(index === wrongIndex ? digit % 9 + 1 : digit).click();
  }
}

for (const native of [false, true]) {
  test(`backgrounding freezes the timer with an incorrect-board dialog open (${native ? 'Android' : 'web'})`, async (t) => {
    const app = await loadGame(t, { native });
    await app.start();
    app.elapse(15);
    fillBoard(app, app.game.selected);
    assert.equal(app.get('#result-title').textContent, 'Not quite yet');
    assert.equal(app.get('#result-overlay').hidden, false);

    if (native) {
      app.emit('appStateChange', { isActive: false });
    } else {
      Object.defineProperty(app.document, 'hidden', { value: true, configurable: true });
      app.document.dispatchEvent(new app.window.Event('visibilitychange'));
    }
    app.elapse(45);

    assert.equal(app.get('#pause-overlay').hidden, false);
    assert.equal(app.get('#pause-label').textContent, 'Resume');
    assert.equal(app.get('#drawer-pause-label').textContent, 'Resume');
    assert.equal(app.game.getElapsed(), 15);
    assert.equal(JSON.parse(app.savedGame()).e, 15);
    assert.equal(JSON.parse(app.savedGame()).z, 1);
    if (native) {
      app.emit('appStateChange', { isActive: true });
    } else {
      Object.defineProperty(app.document, 'hidden', { value: false });
      app.document.dispatchEvent(new app.window.Event('visibilitychange'));
    }
    assert.equal(app.game.getElapsed(), 15);
    app.get('#pause-overlay button').click();
    assert.equal(app.get('#pause-label').textContent, 'Pause');
    assert.equal(app.get('#drawer-pause-label').textContent, 'Pause');
    assert.equal(app.get('#result-overlay').hidden, false);
    assert.equal(app.document.activeElement, app.get('#result-primary'));
    app.elapse(3);
    assert.equal(app.game.getElapsed(), 18);
  });
}

test('Android Back dismisses the solved dialog, returns home, and then exits', async (t) => {
  const app = await loadGame(t, { native: true });
  await app.start();
  app.elapse(23);
  fillBoard(app);
  assert.equal(app.game.finished, true);
  assert.equal(app.game.stats.easy.wins, 1);
  assert.equal(app.game.stats.easy.best, 23);

  app.emit('backButton');
  assert.equal(app.get('#result-overlay').hidden, true);
  assert.equal(app.game.view, 'game');
  app.emit('backButton');
  await flush();
  assert.equal(app.game.view, 'home');
  assert.equal(app.bridgeCalls.filter(({ method }) => method === 'exitApp').length, 0);
  app.emit('backButton');
  await flush();
  assert.equal(app.bridgeCalls.filter(({ method }) => method === 'exitApp').length, 1);
});

test('drawer close and Android Back dismiss the menu and restore focus', async (t) => {
  const app = await loadGame(t, { native: true });
  await app.start();
  const menu = app.get('#menu-btn');
  const close = app.get('#drawer-close');
  assert.equal(close.getAttribute('aria-label'), 'Close menu');

  menu.click();
  assert.equal(app.document.activeElement, close);
  close.click();
  assert.equal(app.document.activeElement, menu);
  menu.click();
  app.emit('backButton');
  assert.equal(menu.getAttribute('aria-expanded'), 'false');
  assert.equal(app.document.activeElement, menu);
  assert.equal(app.game.paused, false);
  app.emit('backButton');
  assert.equal(app.game.paused, true);
  app.emit('backButton');
  assert.equal(app.game.view, 'home');
});

test('a conflicting ninth entry leaves the digit usable until correctly completed', async (t) => {
  const app = await loadGame(t);
  await app.start();
  const { game } = app;
  const target = game.selected;
  const digit = game.solution[target];
  const wrongCell = game.puzzle.findIndex((given, i) => !given && game.solution[i] !== digit);
  const key = app.digit(digit);

  for (const [index, solution] of game.solution.entries()) {
    if (solution === digit && index !== target && !game.puzzle[index]) {
      app.cell(index);
      key.click();
    }
  }
  assert.equal(game.values.filter((value) => value === digit).length, 8);
  app.cell(wrongCell);
  key.click();
  assert.equal(game.values.filter((value) => value === digit).length, 9);
  assert.ok(game.cells[wrongCell].classList.contains('cell--conflict'));

  app.cell(target);
  assert.equal(key.disabled, false);
  key.click();
  assert.equal(game.values[target], digit);
  app.cell(wrongCell);
  app.click('#erase-btn');
  assert.equal(key.disabled, true);
  assert.ok(key.classList.contains('numpad__btn--complete'));

  const given = game.puzzle.findIndex((value) => value > 0);
  app.cell(given);
  assert.equal(key.disabled, false);
  key.click();
  assert.equal(game.highlightDigit, digit);
  assert.ok(key.classList.contains('numpad__btn--active'));
  app.cell(target);
  assert.equal(key.disabled, true);
});

test('auto notes are a single undoable edit and entries remove peer notes', async (t) => {
  const app = await loadGame(t);
  await app.start();
  const { game } = app;
  const index = game.selected;
  const digit = game.solution[index];
  app.click('#auto-notes-btn');
  assert.ok(game.notes[index].has(digit));
  app.click('#undo-btn');
  assert.ok(game.notes.every((set) => set.size === 0));
  app.click('#redo-btn');
  assert.ok(game.notes[index].has(digit));

  app.digit(digit).click();
  assert.equal(game.values[index], digit);
  assert.equal(game.notes[index].size, 0);
  for (const peer of game.peerSet(index)) assert.equal(game.notes[peer].has(digit), false);
  app.click('#undo-btn');
  assert.equal(game.values[index], 0);
  assert.ok(game.notes[index].has(digit));
});

test('undo can reach the starting board after more than 300 edits', async (t) => {
  const app = await loadGame(t);
  await app.start();
  const { game } = app;
  const index = game.selected;
  const digit = game.solution[index];

  app.digit(digit).click();
  const second = game.values.findIndex((value) => value === 0);
  app.cell(second);
  app.click('#notes-btn');
  for (let edit = 0; edit < 300; edit++) app.digit(digit).click();
  for (let edit = 0; edit < 301; edit++) app.click('#undo-btn');

  assert.deepEqual(game.values, game.puzzle);
  assert.ok(game.notes.every((set) => set.size === 0));
  assert.equal(app.get('#undo-btn').disabled, true);
  app.click('#redo-btn');
  assert.equal(game.values[index], digit);
});

test('restoring autosave keeps entries, notes, and elapsed time', async (t) => {
  const app = await loadGame(t);
  await app.start('medium');
  const { game } = app;
  const first = game.selected;
  app.digit(game.solution[first]).click();
  const second = game.values.findIndex((value) => value === 0);
  app.cell(second);
  app.click('#notes-btn');
  app.digit(game.solution[second]).click();
  app.elapse(42);
  app.click('#pause-btn');

  const restored = await loadGame(t, { savedGame: app.savedGame() });
  restored.click('#resume-game-btn');
  assert.equal(restored.game.difficulty, 'medium');
  assert.equal(restored.game.values[first], game.solution[first]);
  assert.ok(restored.game.notes[second].has(game.solution[second]));
  assert.equal(restored.game.getElapsed(), 42);
  assert.equal(restored.game.paused, true);
});

test('reset restores the starting board, clears history, and restarts the timer', async (t) => {
  const app = await loadGame(t);
  await app.start();
  const { game } = app;
  app.digit(game.solution[game.selected]).click();
  app.click('#auto-notes-btn');
  app.elapse(25);
  app.click('#menu-btn');
  app.click('#drawer-reset');
  assert.deepEqual(game.values, game.puzzle);
  assert.ok(game.notes.every((set) => set.size === 0));
  assert.equal(app.get('#undo-btn').disabled, true);
  assert.equal(game.getElapsed(), 0);
  app.elapse(5);
  assert.equal(game.getElapsed(), 5);
});

test('hint counts mistakes before offering to highlight them', async (t) => {
  const app = await loadGame(t);
  await app.start('extreme');
  const { game } = app;
  const wrong = [];
  for (let index = 0; index < 81 && wrong.length < 2; index++) {
    if (game.values[index]) continue;
    const grid = Array.from({ length: 9 }, (_, row) => game.values.slice(row * 9, row * 9 + 9));
    const candidates = game.core.candidatesFor(grid, Math.floor(index / 9), index % 9);
    const digit = candidates.find((candidate) => candidate !== game.solution[index]);
    if (!digit) continue;
    app.cell(index);
    app.digit(digit).click();
    wrong.push(index);
  }
  assert.equal(wrong.length, 2);
  assert.equal(game.conflicts.size, 0);
  app.click('#hint-btn');
  assert.match(app.get('#hint-message').textContent, /2 filled cells are incorrect/);
  assert.equal(app.get('#hint-steps').hidden, true);
  assert.equal(app.document.querySelectorAll('.cell--error').length, 0);
  app.click('#hint-close');
  assert.equal(app.document.querySelectorAll('.cell--error').length, 0);
  app.click('#hint-btn');
  app.click('#hint-action');
  assert.equal(app.get('#hint-overlay').hidden, true);
  assert.deepEqual([...game.errors], wrong);
  for (const index of wrong) {
    assert.ok(game.cells[index].classList.contains('cell--error'));
    assert.match(game.cells[index].getAttribute('aria-label'), /incorrect/);
  }
  app.click('#erase-btn');
  assert.equal(game.errors.size, 0);
});

test('an Extreme hint explains and selects a forced cell without filling it', async (t) => {
  const app = await loadGame(t);
  await app.start('extreme');
  const { game } = app;
  const original = game.values.slice();
  assert.equal(app.get('#difficulty-label').textContent, 'Extreme');
  assert.equal(JSON.parse(app.savedGame()).d, 'extreme');
  app.click('#hint-btn');
  assert.match(app.get('#hint-message').textContent, /Put [1-9] in row [1-9], column [1-9]/);
  assert.ok(app.get('#hint-steps').textContent.trim());
  const { move } = game.pendingHint;
  app.click('#hint-action');
  assert.equal(game.selected, move.row * 9 + move.col);
  assert.equal(game.solution[game.selected], move.value);
  assert.deepEqual(game.values, original);
  assert.equal(game.undoStack.length, 0);
});

test('opening a longer hint branch reveals its reasoning without changing the game', async (t) => {
  const digits = '079000000060903107800750900008010030706000005003400000907200000680000002002160079';
  const board = digits.match(/.{9}/g).map((row) => [...row].map(Number));
  const save = {
    v: 1, d: 'extreme', p: digits, b: digits, s: core.solveGrid(board).flat().join(''),
    n: '.'.repeat(80), e: 10, z: 0,
  };
  const app = await loadGame(t, { savedGame: JSON.stringify(save) });
  app.click('#resume-game-btn');
  app.click('#hint-btn');
  const summaries = [...app.document.querySelectorAll('#hint-steps summary')];
  const choice = summaries.find((summary) => summary.textContent === 'What if row 2, column 3 is 4?');
  assert.ok(choice);
  const detail = choice.parentElement;
  assert.equal(detail.querySelector('ol'), null);
  detail.open = true;
  detail.dispatchEvent(new app.window.Event('toggle'));
  assert.ok(detail.querySelector('ol li'));
  assert.equal(app.game.values.join(''), digits);
  assert.equal(app.game.undoStack.length, 0);
});

test('How to play is available from home and the game drawer and Back closes it', async (t) => {
  const app = await loadGame(t, { native: true });
  app.click('#help-btn');
  assert.equal(app.get('#help-overlay').hidden, false);
  assert.match(app.get('#help-overlay').textContent, /row, column, and 3×3 box/);
  assert.match(app.get('#help-overlay').textContent, /Locked candidates/);
  app.emit('backButton');
  assert.equal(app.get('#help-overlay').hidden, true);
  assert.equal(app.game.view, 'home');
  await app.start();
  app.click('#menu-btn');
  app.click('#drawer-help');
  assert.equal(app.get('#help-overlay').hidden, false);
  assert.equal(app.game.menuOpen, false);
  app.emit('backButton');
  assert.equal(app.get('#help-overlay').hidden, true);
  assert.equal(app.game.view, 'game');
  assert.equal(app.game.paused, false);
});

test('feedback opens a prefilled GitHub issue through the browser plugin', async (t) => {
  const app = await loadGame(t, { native: true });
  app.click('#feedback-btn');
  app.get('#feedback-details').value = 'Report details';
  app.get('#feedback-form').dispatchEvent(new app.window.Event('submit', { cancelable: true }));
  await flush();

  const call = app.bridgeCalls.find(({ plugin, method }) => plugin === 'Browser' && method === 'open');
  const url = new URL(call.options.url);
  assert.equal(url.origin + url.pathname, 'https://github.com/kalamcs/sudoku-android/issues/new');
  assert.equal(url.searchParams.get('title'), 'Sudoku for Android feedback');
  assert.match(url.searchParams.get('body'), /^Report details\n[\s\S]*App: Sudoku for Android 1\.0/);
});

test('configured feedback opens an email draft with the player’s text', async (t) => {
  const app = await loadGame(t, { native: true, emailFeedback: true });
  await app.start();
  app.elapse(10);
  app.click('#menu-btn');
  app.click('#drawer-feedback');
  assert.equal(app.get('#feedback-submit').textContent, 'Open email draft');
  app.get('#feedback-details').value = 'Keep + & ?\nThanks!';
  app.get('#feedback-form').dispatchEvent(new app.window.Event('submit', { cancelable: true }));
  await flush();

  const call = app.bridgeCalls.find(({ plugin, method }) => plugin === 'SudokuFeedback' && method === 'openEmail');
  assert.equal(call.options.recipient, testRecipient);
  assert.equal(call.options.subject, 'Sudoku for Android feedback');
  assert.match(call.options.body, /^Keep \+ & \?\nThanks!/);
  assert.equal(app.bridgeCalls.some(({ plugin }) => plugin === 'Browser'), false);
  assert.equal(app.get('#feedback-overlay').hidden, true);
  app.elapse(30);
  assert.equal(app.game.paused, true);
  assert.equal(app.game.getElapsed(), 10);
});

test('feedback stays editable if Android has no email app', async (t) => {
  const app = await loadGame(t, { native: true, emailFeedback: true, noEmailApp: true });
  app.click('#feedback-btn');
  app.get('#feedback-details').value = 'Keep this draft';
  app.get('#feedback-form').dispatchEvent(new app.window.Event('submit', { cancelable: true }));
  await flush();

  assert.equal(app.get('#feedback-overlay').hidden, false);
  assert.equal(app.get('#feedback-error').hidden, false);
  assert.match(app.get('#feedback-error').textContent, /Could not open an email app/);
  assert.equal(app.get('#feedback-details').value, 'Keep this draft');
  assert.equal(app.get('#feedback-submit').disabled, false);
});
