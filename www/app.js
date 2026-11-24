// Copyright (c) 2026 Kalam Shah
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

/**
 * Sudoku web game — controller.
 *
 * Features:
 *  - difficulty-based generation (see generator.js)
 *  - manual solving UI with notes
 *  - undo/redo down to the initial puzzle state
 *  - autosave/restore through localStorage (Android app build)
 *  - evaluation on completion, error highlighting
 *  - elapsed timer + best time per difficulty with achievement timestamp
 */
(function () {
  'use strict';

  var SIZE = 9;
  var CELL_COUNT = SIZE * SIZE;
  var DIFFICULTIES = window.SudokuCore ? window.SudokuCore.DIFFICULTIES : [];

  var SAVE_KEY = 'sudoku_save_v1';
  var STATS_KEY = 'sudoku_stats_v1';
  var AUTOSAVE_EVERY_MS = 5000;
  var SAVE_DEBOUNCE_MS = 300;

  // -------------------------------------------------------------------------
  // Small helpers
  // -------------------------------------------------------------------------

  function $(id) {
    return document.getElementById(id);
  }

  function capitalize(text) {
    return text.charAt(0).toUpperCase() + text.slice(1);
  }

  function formatDuration(totalSeconds) {
    var seconds = Math.max(0, Math.floor(Number(totalSeconds) || 0));
    var hours = Math.floor(seconds / 3600);
    var minutes = Math.floor((seconds % 3600) / 60);
    var secs = seconds % 60;
    if (hours > 0) {
      return hours + ':' + String(minutes).padStart(2, '0') + ':' + String(secs).padStart(2, '0');
    }
    return String(minutes).padStart(2, '0') + ':' + String(secs).padStart(2, '0');
  }

  // Android build: localStorage is the only store. Cookies are limited to
  // ~4 KB, are shared with any http(s) origin in the WebView, and can be
  // evicted; localStorage is scoped to the app and comfortably holds the
  // autosave payload and stats.
  function writeSavedValue(name, value) {
    try {
      window.localStorage.setItem(name, value);
    } catch (err) {
      /* storage can be unavailable; the game still works without autosave */
    }
  }

  function readSavedValue(name) {
    try {
      return window.localStorage.getItem(name);
    } catch (err) {
      return null;
    }
  }

  function clearSavedValue(name) {
    try {
      window.localStorage.removeItem(name);
    } catch (err) {
      /* ignore storage errors */
    }
  }

  function parseDigitString(value, allowZero) {
    if (typeof value !== 'string' || value.length !== CELL_COUNT) return null;
    var pattern = allowZero ? /^[0-9]+$/ : /^[1-9]+$/;
    if (!pattern.test(value)) return null;
    return value.split('').map(Number);
  }

  function encodeNotes(notes) {
    return notes.map(function (set) {
      return Array.from(set).sort(function (a, b) { return a - b; }).join('');
    }).join('.');
  }

  function decodeNotes(encoded) {
    if (typeof encoded !== 'string') return null;
    var segments = encoded.split('.');
    if (segments.length !== CELL_COUNT) return null;

    var notes = [];
    for (var index = 0; index < CELL_COUNT; index++) {
      var segment = segments[index];
      if (!/^[1-9]{0,9}$/.test(segment)) return null;
      notes.push(new Set(segment.split('').map(Number)));
    }
    return notes;
  }

  function flatToGrid(flat) {
    var grid = [];
    for (var r = 0; r < SIZE; r++) {
      grid.push(flat.slice(r * SIZE, r * SIZE + SIZE));
    }
    return grid;
  }

  function defaultStats() {
    var stats = {};
    DIFFICULTIES.forEach(function (difficulty) {
      stats[difficulty] = {
        best: null,
        bestAt: null,
        wins: 0,
        totalTime: 0,
        timedWins: 0
      };
    });
    return stats;
  }

  function averageTime(stats) {
    if (!stats || !stats.timedWins) return null;
    return stats.totalTime / stats.timedWins;
  }

  function isValidTimestamp(value) {
    return typeof value === 'number' && isFinite(value) && value > 0;
  }

  function setsEqual(a, b) {
    if (a.size !== b.size) return false;
    var equal = true;
    a.forEach(function (value) {
      if (!b.has(value)) equal = false;
    });
    return equal;
  }

  function loadStats() {
    var stats = defaultStats();
    var raw = readSavedValue(STATS_KEY);
    if (!raw) return stats;
    try {
      var parsed = JSON.parse(raw);
      DIFFICULTIES.forEach(function (difficulty) {
        var entry = parsed && parsed[difficulty];
        if (!entry || typeof entry !== 'object') return;
        if (typeof entry.best === 'number' && isFinite(entry.best) && entry.best > 0) {
          stats[difficulty].best = entry.best;
        }
        if (isValidTimestamp(entry.bestAt)) {
          stats[difficulty].bestAt = entry.bestAt;
        }
        if (typeof entry.wins === 'number' && isFinite(entry.wins) && entry.wins >= 0) {
          stats[difficulty].wins = Math.floor(entry.wins);
        }
        if (typeof entry.totalTime === 'number' && isFinite(entry.totalTime) && entry.totalTime >= 0) {
          stats[difficulty].totalTime = entry.totalTime;
        }
        if (typeof entry.timedWins === 'number' && isFinite(entry.timedWins) && entry.timedWins >= 0) {
          stats[difficulty].timedWins = Math.floor(entry.timedWins);
        }
      });
    } catch (err) {
      /* corrupted stats — use defaults */
    }
    return stats;
  }

  // -------------------------------------------------------------------------
  // Game
  // -------------------------------------------------------------------------

  function SudokuGame() {
    this.core = window.SudokuCore;
    this.difficulty = 'medium';

    this.puzzle = null; // 81 numbers, 0 = empty (immutable givens)
    this.solution = null; // 81 numbers
    this.values = null; // 81 numbers (puzzle + user entries)
    this.notes = null; // 81 Sets of candidate numbers
    this.selected = null; // index 0..80
    this.notesMode = false;

    this.undoStack = [];
    this.redoStack = [];

    this.errors = new Set();
    this.conflicts = new Set(); // user cells that duplicate a row/column/box value
    this.highlightDigit = 0; // 1..9 while highlighting a given cell's digit
    this.pendingHint = null;
    this.menuOpen = false;
    this.finished = false;
    this.generating = false;
    this.paused = false;
    this.pausedReason = null;

    this.elapsedBefore = 0; // seconds accumulated before the current run
    this.resumeAt = null;
    this.timerId = null;

    this.stats = defaultStats();
    this.autosaveIntervalId = null;
    this.saveTimeoutId = null;

    this.view = 'home'; // 'home' | 'game'
    this.savedPreview = null;

    this.cells = [];
    this.numpadButtons = [];
    this.dom = {};
  }

  // ----- setup -------------------------------------------------------------

  SudokuGame.prototype.init = function () {
    if (!this.core) {
      console.error('SudokuCore failed to load.');
      return;
    }

    this.cacheDom();
    this.buildGrid();
    this.buildNumpad();
    this.bindEvents();

    this.stats = loadStats();
    this.renderStats();
    this.showHome();

    this.autosaveIntervalId = window.setInterval(function () {
      if (this.puzzle && this.view === 'game') this.saveNow();
    }.bind(this), AUTOSAVE_EVERY_MS);

    document.addEventListener('visibilitychange', function () {
      if (!this.puzzle || this.view !== 'game') return;

      if (document.hidden) {
        // Tab switched or screen locked: stop the clock so away-time is not
        // counted, and leave the game paused until the player comes back.
        if (!this.paused && !this.finished && !this.generating) {
          this.pauseGame('hidden');
        } else {
          this.saveNow();
        }
      }
    }.bind(this));

    window.addEventListener('beforeunload', function () {
      if (this.puzzle && this.view === 'game') this.saveNow();
    }.bind(this));
  };

  SudokuGame.prototype.cacheDom = function () {
    this.dom.grid = $('sudoku-grid');
    this.dom.timer = $('timer');
    this.dom.difficultyBadge = $('difficulty-badge');
    this.dom.menuBtn = $('menu-btn');
    this.dom.difficultyLabel = $('difficulty-label');
    this.dom.difficultyOptions = $('difficulty-options');
    this.dom.difficultyButtons = Array.prototype.slice.call(
      document.querySelectorAll('.difficulty-btn')
    );
    this.dom.homeScreen = $('home-screen');
    this.dom.gameScreen = $('game-screen');
    this.dom.homeDifficulties = $('home-difficulties');
    this.dom.resumeCard = $('resume-card');
    this.dom.resumeSummary = $('resume-summary');
    this.dom.resumeBtn = $('resume-game-btn');
    this.dom.discardBtn = $('discard-game-btn');
    this.dom.homeStatsBody = $('home-stats-body');
    this.dom.homeStatsEmpty = $('home-stats-empty');
    this.dom.undo = $('undo-btn');
    this.dom.redo = $('redo-btn');
    this.dom.notes = $('notes-btn');
    this.dom.autoNotes = $('auto-notes-btn');
    this.dom.erase = $('erase-btn');
    this.dom.pause = $('pause-btn');
    this.dom.pauseLabel = $('pause-label');
    this.dom.hintBtn = $('hint-btn');
    this.dom.hintOverlay = $('hint-overlay');
    this.dom.hintTitle = $('hint-title');
    this.dom.hintMessage = $('hint-message');
    this.dom.hintSteps = $('hint-steps');
    this.dom.hintAction = $('hint-action');
    this.dom.hintClose = $('hint-close');
    this.dom.helpBtn = $('help-btn');
    this.dom.helpOverlay = $('help-overlay');
    this.dom.helpTitle = $('help-title');
    this.dom.helpClose = $('help-close');
    this.dom.numpad = $('numpad');
    this.dom.saveStatus = $('save-status');
    this.dom.statusBanner = $('status-banner');
    this.dom.drawer = $('menu-drawer');
    this.dom.drawerBackdrop = $('drawer-backdrop');
    this.dom.drawerPause = $('drawer-pause');
    this.dom.drawerPauseLabel = $('drawer-pause-label');
    this.dom.drawerReset = $('drawer-reset');
    this.dom.drawerNew = $('drawer-new');
    this.dom.drawerHelp = $('drawer-help');
    this.dom.drawerFeedback = $('drawer-feedback');
    this.dom.drawerClose = $('drawer-close');
    this.dom.drawerDifficulty = $('drawer-difficulty');
    this.dom.feedbackBtn = $('feedback-btn');
    this.dom.feedbackOverlay = $('feedback-overlay');
    this.dom.feedbackForm = $('feedback-form');
    this.dom.feedbackMessage = $('feedback-message');
    this.dom.feedbackDetails = $('feedback-details');
    this.dom.feedbackError = $('feedback-error');
    this.dom.feedbackSubmit = $('feedback-submit');
    this.dom.feedbackCancel = $('feedback-cancel');

    this.dom.resultOverlay = $('result-overlay');
    this.dom.resultIcon = $('result-icon');
    this.dom.resultTitle = $('result-title');
    this.dom.resultMessage = $('result-message');
    this.dom.resultStats = $('result-stats');
    this.dom.resultPrimary = $('result-primary');
    this.dom.resultClose = $('result-close');

    this.dom.pauseOverlay = $('pause-overlay');
    this.dom.pauseResume = $('pause-resume');
    this.dom.pauseStats = $('pause-stats');
    this.dom.pauseMessage = $('pause-message');

    this.dom.loadingOverlay = $('loading-overlay');
  };

  SudokuGame.prototype.buildGrid = function () {
    var fragment = document.createDocumentFragment();
    this.cells = [];

    for (var index = 0; index < CELL_COUNT; index++) {
      var cell = document.createElement('div');
      cell.className = 'cell';
      cell.setAttribute('role', 'gridcell');
      cell.setAttribute('tabindex', '-1');
      cell.dataset.index = String(index);

      var valueEl = document.createElement('span');
      valueEl.className = 'cell__value';

      var notesEl = document.createElement('div');
      notesEl.className = 'cell__notes';
      var noteSpans = [];
      for (var n = 1; n <= 9; n++) {
        var span = document.createElement('span');
        span.className = 'cell__note';
        span.dataset.note = String(n);
        notesEl.appendChild(span);
        noteSpans.push(span);
      }

      cell.appendChild(valueEl);
      cell.appendChild(notesEl);
      cell.__valueEl = valueEl;
      cell.__noteSpans = noteSpans;

      fragment.appendChild(cell);
      this.cells.push(cell);
    }

    this.dom.grid.appendChild(fragment);
  };

  SudokuGame.prototype.buildNumpad = function () {
    var fragment = document.createDocumentFragment();
    this.numpadButtons = [];
    for (var n = 1; n <= 9; n++) {
      var button = document.createElement('button');
      button.type = 'button';
      button.className = 'numpad__btn';
      button.dataset.n = String(n);
      button.textContent = String(n);
      button.setAttribute('aria-label', 'Enter ' + n);
      fragment.appendChild(button);
      this.numpadButtons.push(button);
    }
    this.dom.numpad.appendChild(fragment);
  };

  SudokuGame.prototype.bindEvents = function () {
    var self = this;

    this.dom.grid.addEventListener('click', function (event) {
      var cell = event.target.closest('.cell');
      if (!cell || !self.dom.grid.contains(cell)) return;
      self.selectCell(Number(cell.dataset.index));
    });

    this.dom.grid.addEventListener('dblclick', function (event) {
      var cell = event.target.closest('.cell');
      if (!cell) return;
      self.selectCell(Number(cell.dataset.index));
      self.eraseSelected();
    });

    this.dom.numpad.addEventListener('click', function (event) {
      var button = event.target.closest('.numpad__btn');
      if (!button) return;
      self.applyNumber(Number(button.dataset.n));
    });

    this.dom.undo.addEventListener('click', function () {
      self.undo();
    });
    this.dom.redo.addEventListener('click', function () {
      self.redo();
    });
    this.dom.erase.addEventListener('click', function () {
      self.eraseSelected();
    });
    this.dom.notes.addEventListener('click', function () {
      self.setNotesMode(!self.notesMode);
    });
    this.dom.autoNotes.addEventListener('click', function () {
      self.fillAllNotes();
    });
    this.dom.pause.addEventListener('click', function () {
      self.togglePause();
    });
    this.dom.pauseResume.addEventListener('click', function () {
      self.resumeGame();
    });
    this.dom.hintBtn.addEventListener('click', function () {
      self.openHint();
    });
    this.dom.hintClose.addEventListener('click', function () {
      self.closeHint();
    });
    this.dom.hintAction.addEventListener('click', function () {
      self.showHintOnBoard();
    });
    this.dom.hintOverlay.addEventListener('click', function (event) {
      if (event.target === self.dom.hintOverlay) self.closeHint();
    });
    this.dom.helpBtn.addEventListener('click', function () {
      self.openHelp();
    });
    this.dom.helpClose.addEventListener('click', function () {
      self.closeHelp();
    });
    this.dom.helpOverlay.addEventListener('click', function (event) {
      if (event.target === self.dom.helpOverlay) self.closeHelp();
    });

    this.dom.menuBtn.addEventListener('click', function () {
      self.toggleMenu();
    });
    this.dom.drawerClose.addEventListener('click', function () {
      self.closeMenu();
    });
    this.dom.drawerBackdrop.addEventListener('click', function () {
      self.closeMenu();
    });
    this.dom.drawerPause.addEventListener('click', function () {
      self.closeMenu();
      self.togglePause();
    });
    this.dom.drawerReset.addEventListener('click', function () {
      self.closeMenu();
      self.resetBoard();
    });
    this.dom.drawerNew.addEventListener('click', function () {
      self.closeMenu();
      self.goHome();
    });
    this.dom.drawerHelp.addEventListener('click', function () {
      self.closeMenu();
      self.openHelp();
    });
    this.dom.drawerFeedback.addEventListener('click', function () {
      self.closeMenu();
      self.openFeedback();
    });
    this.dom.feedbackBtn.addEventListener('click', function () {
      self.openFeedback();
    });
    this.dom.feedbackCancel.addEventListener('click', function () {
      self.closeFeedback();
    });
    this.dom.feedbackForm.addEventListener('submit', function (event) {
      event.preventDefault();
      self.submitFeedback();
    });
    this.dom.feedbackOverlay.addEventListener('click', function (event) {
      if (event.target === self.dom.feedbackOverlay) self.closeFeedback();
    });

    this.dom.homeDifficulties.addEventListener('click', function (event) {
      var button = event.target.closest('.home-difficulty');
      if (!button) return;
      self.handleHomeDifficulty(button.dataset.difficulty);
    });
    this.dom.resumeBtn.addEventListener('click', function () {
      self.resumeFromHome();
    });
    this.dom.discardBtn.addEventListener('click', function () {
      self.discardSavedGame();
    });

    this.dom.resultPrimary.addEventListener('click', function () {
      if (self.finished) {
        // Finished puzzle: go back to the home screen so the difficulty can be
        // chosen deliberately and the updated stats are visible first.
        self.showHome();
      } else {
        self.hideResult();
      }
    });
    this.dom.resultClose.addEventListener('click', function () {
      self.hideResult();
    });

    document.addEventListener('keydown', function (event) {
      self.handleKeyDown(event);
    });
  };

  // ----- keyboard ----------------------------------------------------------

  SudokuGame.prototype.handleKeyDown = function (event) {
    var key = event.key;

    // Escape dismisses the topmost layer, whatever screen is showing.
    if (key === 'Escape' && this.closeOverlays()) {
      event.preventDefault();
      return;
    }

    if (!this.puzzle || this.view !== 'game') return;
    if (!this.dom.hintOverlay.hidden || !this.dom.helpOverlay.hidden || !this.dom.feedbackOverlay.hidden) return;

    if (this.paused) {
      if (key === 'p' || key === 'P' || key === 'Escape') {
        event.preventDefault();
        this.resumeGame();
      }
      return;
    }

    if (key === 'Escape') return;

    if (!this.dom.resultOverlay.hidden || !this.dom.loadingOverlay.hidden) return;

    var tag = (event.target && event.target.tagName ? event.target.tagName : '').toLowerCase();
    if (tag === 'input' || tag === 'textarea' || tag === 'select') return;

    if (event.ctrlKey || event.metaKey) {
      var lower = key.toLowerCase();
      if (lower === 'z') {
        event.preventDefault();
        if (event.shiftKey) this.redo();
        else this.undo();
      } else if (lower === 'y') {
        event.preventDefault();
        this.redo();
      }
      return;
    }

    if (event.altKey) return;

    if (key === 'p' || key === 'P') {
      event.preventDefault();
      this.pauseGame();
      return;
    }

    if (key === 'a' || key === 'A') {
      event.preventDefault();
      this.fillAllNotes();
      return;
    }

    if (key === 'm' || key === 'M') {
      event.preventDefault();
      this.toggleMenu();
      return;
    }

    if (/^[1-9]$/.test(key)) {
      event.preventDefault();
      if (this.selected !== null) this.applyNumber(Number(key));
      return;
    }

    if (key === 'Backspace' || key === 'Delete' || key === '0') {
      event.preventDefault();
      this.eraseSelected();
      return;
    }

    if (key === 'n' || key === 'N') {
      event.preventDefault();
      this.setNotesMode(!this.notesMode);
      return;
    }

    var row;
    var col;
    var moved = false;

    if (this.selected === null) {
      if (key.indexOf('Arrow') === 0) {
        event.preventDefault();
        this.selectCell(this.firstEmptyIndex());
      }
      return;
    }

    row = Math.floor(this.selected / SIZE);
    col = this.selected % SIZE;

    if (key === 'ArrowUp') {
      row = (row + SIZE - 1) % SIZE;
      moved = true;
    } else if (key === 'ArrowDown') {
      row = (row + 1) % SIZE;
      moved = true;
    } else if (key === 'ArrowLeft') {
      col = (col + SIZE - 1) % SIZE;
      moved = true;
    } else if (key === 'ArrowRight') {
      col = (col + 1) % SIZE;
      moved = true;
    }

    if (moved) {
      event.preventDefault();
      this.selectCell(row * SIZE + col);
    }
  };

  // ----- selection + rendering --------------------------------------------

  SudokuGame.prototype.selectCell = function (index) {
    if (index < 0 || index >= CELL_COUNT) return;
    this.selected = index;
    this.highlightDigit = 0; // tapping a cell leaves digit-highlight mode
    this.renderBoard();
    this.updateNumpad();
    var cell = this.cells[index];
    if (cell) {
      try {
        cell.focus({ preventScroll: true });
      } catch (err) {
        cell.focus();
      }
    }
  };

  SudokuGame.prototype.firstEmptyIndex = function () {
    if (!this.values) return 0;
    var index = this.values.indexOf(0);
    return index === -1 ? 0 : index;
  };

  SudokuGame.prototype.peerSet = function (index) {
    var peers = new Set();
    var row = Math.floor(index / SIZE);
    var col = index % SIZE;
    var r;
    var c;

    for (c = 0; c < SIZE; c++) peers.add(row * SIZE + c);
    for (r = 0; r < SIZE; r++) peers.add(r * SIZE + col);

    var boxRow = Math.floor(row / 3) * 3;
    var boxCol = Math.floor(col / 3) * 3;
    for (r = boxRow; r < boxRow + 3; r++) {
      for (c = boxCol; c < boxCol + 3; c++) peers.add(r * SIZE + c);
    }

    peers.delete(index);
    return peers;
  };

  SudokuGame.prototype.renderBoard = function () {
    if (!this.values) return;

    var selected = this.selected;
    var peers = selected === null ? null : this.peerSet(selected);
    // Pressing a digit while a given (bold) cell is selected highlights every
    // occurrence of that digit without editing the board.
    var selectedValue = this.highlightDigit ||
      (selected === null ? 0 : this.values[selected]);
    this.conflicts = this.computeConflicts();

    for (var index = 0; index < CELL_COUNT; index++) {
      var cell = this.cells[index];
      var value = this.values[index];
      var given = this.puzzle[index] !== 0;
      var classes = ['cell'];

      if (given) classes.push('cell--given');
      else if (value !== 0) classes.push('cell--user');

      if (index === selected) {
        classes.push('cell--selected');
      } else if (peers && peers.has(index)) {
        classes.push('cell--peer');
      }

      if (selectedValue !== 0 && value === selectedValue && index !== selected) {
        classes.push('cell--same');
      }

      if (this.errors.has(index)) classes.push('cell--error');
      if (this.conflicts.has(index)) classes.push('cell--conflict');

      cell.className = classes.join(' ');
      cell.__valueEl.textContent = value !== 0 ? String(value) : '';

      var notes = this.notes[index];
      for (var n = 0; n < 9; n++) {
        var noteSpan = cell.__noteSpans[n];
        var noteValue = Number(noteSpan.dataset.note);
        var showNote = value === 0 && notes.has(noteValue);
        noteSpan.textContent = showNote ? String(noteValue) : '';
        noteSpan.classList.toggle(
          'cell__note--match',
          showNote && selectedValue !== 0 && noteValue === selectedValue
        );
      }

      cell.setAttribute('tabindex', index === selected ? '0' : '-1');
      cell.setAttribute('aria-selected', index === selected ? 'true' : 'false');

      var rowLabel = Math.floor(index / SIZE) + 1;
      var colLabel = (index % SIZE) + 1;
      var label = 'Row ' + rowLabel + ', column ' + colLabel + ', ';
      if (given) {
        label += 'given ' + value;
      } else if (value !== 0) {
        label += 'entered ' + value;
      } else if (notes.size > 0) {
        label += 'notes ' + Array.from(notes).sort(function (a, b) { return a - b; }).join(' ');
      } else {
        label += 'empty';
      }
      if (this.errors.has(index)) label += ', incorrect';
      if (this.conflicts.has(index)) label += ', conflicts with another ' + value;
      cell.setAttribute('aria-label', label);
    }

    this.updateNumpad();
  };

  SudokuGame.prototype.renderAll = function () {
    this.updateDifficultyUI();
    this.renderBoard();
    this.renderTimer();
    this.updateUndoButtons();
    this.updateNotesButton();
    this.updatePauseUI();
    this.renderStats();
  };

  SudokuGame.prototype.countUnconflictedDigits = function () {
    var counts = [0, 0, 0, 0, 0, 0, 0, 0, 0, 0];
    if (!this.values) return counts;
    for (var i = 0; i < CELL_COUNT; i++) {
      var value = this.values[i];
      if (value !== 0 && !this.conflicts.has(i)) counts[value] += 1;
    }
    return counts;
  };

  SudokuGame.prototype.updateNumpad = function () {
    if (!this.values || this.numpadButtons.length === 0) return;

    var counts = this.countUnconflictedDigits();
    var locked = this.finished || this.generating || this.paused;
    var canHighlight = this.selected !== null && this.puzzle[this.selected] !== 0;

    this.numpadButtons.forEach(function (button) {
      var digit = Number(button.dataset.n);
      var complete = counts[digit] >= 9;
      var active = digit === this.highlightDigit;
      var label = complete ? 'Digit ' + digit + ' is complete' : 'Enter ' + digit;
      if (canHighlight) label = (active ? 'Highlighting ' : 'Highlight ') + digit;

      button.disabled = locked || (complete && !canHighlight);
      button.classList.toggle('numpad__btn--complete', complete);
      button.classList.toggle('numpad__btn--active', active);
      button.setAttribute('aria-label', label);
    }, this);
  };

  SudokuGame.prototype.renderTimer = function () {
    if (!this.dom.timer) return;
    this.dom.timer.textContent = formatDuration(this.getElapsed());
  };

  SudokuGame.prototype.updateDifficultyUI = function () {
    if (this.dom.difficultyLabel) {
      this.dom.difficultyLabel.textContent = capitalize(this.difficulty);
    }
    if (this.dom.difficultyButtons) {
      this.dom.difficultyButtons.forEach(function (button) {
        var isActive = button.dataset.difficulty === this.difficulty;
        button.setAttribute('aria-pressed', isActive ? 'true' : 'false');
      }, this);
    }
    if (this.dom.drawerDifficulty) {
      this.dom.drawerDifficulty.textContent = capitalize(this.difficulty);
    }
    document.title = 'Sudoku — ' + capitalize(this.difficulty);
  };

  SudokuGame.prototype.updateNotesButton = function () {
    if (!this.dom.notes) return;
    this.dom.notes.setAttribute('aria-pressed', this.notesMode ? 'true' : 'false');
    this.dom.notes.textContent = '✎ Notes: ' + (this.notesMode ? 'On' : 'Off');
  };

  SudokuGame.prototype.updateUndoButtons = function () {
    if (!this.dom.undo || !this.dom.redo) return;
    this.dom.undo.disabled = this.finished || this.paused || this.undoStack.length === 0;
    this.dom.redo.disabled = this.finished || this.paused || this.redoStack.length === 0;
  };

  SudokuGame.prototype.setNotesMode = function (enabled) {
    this.notesMode = !!enabled;
    this.updateNotesButton();
  };

  SudokuGame.prototype.setSaveStatus = function (text) {
    if (this.dom.saveStatus) this.dom.saveStatus.textContent = text;
  };

  SudokuGame.prototype.renderStats = function () {
    if (!this.dom.statsBody) return;

    var body = this.dom.statsBody;
    body.replaceChildren();
    var totalWins = 0;

    DIFFICULTIES.forEach(function (difficulty) {
      var stats = this.stats[difficulty];
      totalWins += stats.wins;

      var row = document.createElement('tr');
      if (difficulty === this.difficulty) row.className = 'is-active';

      var name = document.createElement('td');
      name.textContent = capitalize(difficulty);

      var best = document.createElement('td');
      best.textContent = stats.best !== null ? formatDuration(stats.best) : '—';

      var achieved = document.createElement('td');
      achieved.textContent = stats.bestAt
        ? new Date(stats.bestAt).toLocaleDateString(undefined, {
            year: 'numeric',
            month: 'short',
            day: 'numeric'
          })
        : '—';

      var wins = document.createElement('td');
      wins.textContent = String(stats.wins);

      row.appendChild(name);
      row.appendChild(best);
      row.appendChild(achieved);
      row.appendChild(wins);
      body.appendChild(row);
    }, this);

    if (this.dom.statsSummary) {
      this.dom.statsSummary.textContent = totalWins === 0
        ? 'No games won yet — good luck!'
        : 'You have won ' + totalWins + (totalWins === 1 ? ' game' : ' games') + '.';
    }
  };

  // ----- home / start screen ----------------------------------------------

  SudokuGame.prototype.renderHomeStats = function () {
    if (!this.dom.homeStatsBody) return;

    var body = this.dom.homeStatsBody;
    body.replaceChildren();
    var totalWins = 0;

    DIFFICULTIES.forEach(function (difficulty) {
      var stats = this.stats[difficulty];
      var average = averageTime(stats);
      totalWins += stats.wins;

      var row = document.createElement('tr');

      var name = document.createElement('td');
      name.textContent = capitalize(difficulty);

      var best = document.createElement('td');
      best.textContent = stats.best !== null ? formatDuration(stats.best) : '—';

      var avg = document.createElement('td');
      avg.textContent = average !== null ? formatDuration(average) : '—';

      var solved = document.createElement('td');
      solved.textContent = String(stats.wins);

      var achieved = document.createElement('td');
      achieved.textContent = stats.bestAt
        ? new Date(stats.bestAt).toLocaleDateString(undefined, {
            year: 'numeric',
            month: 'short',
            day: 'numeric'
          })
        : '—';

      row.appendChild(name);
      row.appendChild(best);
      row.appendChild(avg);
      row.appendChild(solved);
      row.appendChild(achieved);
      body.appendChild(row);
    }, this);

    if (this.dom.homeStatsEmpty) {
      this.dom.homeStatsEmpty.hidden = totalWins > 0;
    }
  };

  SudokuGame.prototype.peekSavedGame = function () {
    var raw = readSavedValue(SAVE_KEY);
    if (!raw) return null;

    var state;
    try {
      state = JSON.parse(raw);
    } catch (err) {
      return null;
    }

    if (!state || state.v !== 1) return null;
    if (DIFFICULTIES.indexOf(state.d) === -1) return null;
    if (state.w === 1) return null;

    var puzzle = parseDigitString(state.p, true);
    var values = parseDigitString(state.b, true);
    if (!puzzle || !values || puzzle.indexOf(0) === -1) return null;

    var filled = 0;
    for (var i = 0; i < CELL_COUNT; i++) {
      if (values[i] !== 0) filled += 1;
    }

    return {
      difficulty: state.d,
      elapsed: Math.max(0, Number(state.e) || 0),
      filled: filled,
      paused: state.z === 1
    };
  };

  SudokuGame.prototype.refreshHome = function () {
    this.renderHomeStats();

    var preview = this.peekSavedGame();
    this.savedPreview = preview;

    if (!this.dom.resumeCard) return;

    if (preview) {
      var parts = [
        capitalize(preview.difficulty),
        formatDuration(preview.elapsed) + ' elapsed',
        preview.filled + '/' + CELL_COUNT + ' filled'
      ];
      if (preview.paused) parts.push('paused');
      this.dom.resumeSummary.textContent = parts.join(' · ');
      this.dom.resumeCard.hidden = false;
    } else {
      this.dom.resumeCard.hidden = true;
    }
  };

  SudokuGame.prototype.showHome = function () {
    if (this.generating) return;

    var leavingGame = this.view === 'game';
    if (leavingGame && this.puzzle && !this.finished) {
      if (!this.paused) this.pauseTimer();
      this.saveNow();
    }

    this.view = 'home';
    this.highlightDigit = 0;
    this.menuOpen = false;
    this.hideResult();
    this.closeMenu();
    this.closeFeedback();
    this.closeHelp();
    this.closeHint();

    if (this.dom.homeScreen) this.dom.homeScreen.hidden = false;
    if (this.dom.gameScreen) this.dom.gameScreen.hidden = true;
    if (this.dom.difficultyBadge) this.dom.difficultyBadge.hidden = true;
    if (this.dom.timer) this.dom.timer.hidden = true;
    if (this.dom.menuBtn) this.dom.menuBtn.hidden = true;
    if (this.dom.pauseOverlay) this.dom.pauseOverlay.hidden = true;

    this.refreshHome();
    this.setSaveStatus(this.savedPreview ? 'Saved game available' : 'No game in progress');
    document.title = 'Sudoku';
    if (leavingGame) window.scrollTo(0, 0);
  };

  SudokuGame.prototype.showGame = function () {
    this.view = 'game';

    if (this.dom.homeScreen) this.dom.homeScreen.hidden = true;
    if (this.dom.gameScreen) this.dom.gameScreen.hidden = false;
    if (this.dom.difficultyBadge) this.dom.difficultyBadge.hidden = false;
    if (this.dom.timer) this.dom.timer.hidden = false;
    if (this.dom.menuBtn) this.dom.menuBtn.hidden = false;

    this.updateDifficultyUI();
    this.renderTimer();
    window.scrollTo(0, 0);
  };

  // ----- menu ---------------------------------------------------------------

  SudokuGame.prototype.openMenu = function () {
    if (this.view !== 'game' || this.generating) return;

    this.menuOpen = true;
    if (this.dom.drawer) {
      this.dom.drawer.classList.add('drawer--open');
      this.dom.drawer.setAttribute('aria-hidden', 'false');
    }
    if (this.dom.drawerBackdrop) this.dom.drawerBackdrop.hidden = false;
    if (this.dom.menuBtn) this.dom.menuBtn.setAttribute('aria-expanded', 'true');
    if (this.dom.drawerDifficulty) {
      this.dom.drawerDifficulty.textContent = capitalize(this.difficulty);
    }
    if (this.dom.drawerReset) this.dom.drawerReset.disabled = this.finished;
    if (this.dom.drawerClose) this.dom.drawerClose.focus();
  };

  SudokuGame.prototype.closeMenu = function () {
    this.menuOpen = false;
    if (this.dom.drawer && this.dom.drawer.contains(document.activeElement) && this.dom.menuBtn) {
      this.dom.menuBtn.focus();
    }
    if (this.dom.drawer) {
      this.dom.drawer.classList.remove('drawer--open');
      this.dom.drawer.setAttribute('aria-hidden', 'true');
    }
    if (this.dom.drawerBackdrop) this.dom.drawerBackdrop.hidden = true;
    if (this.dom.menuBtn) this.dom.menuBtn.setAttribute('aria-expanded', 'false');
  };

  SudokuGame.prototype.toggleMenu = function () {
    if (this.menuOpen) this.closeMenu();
    else this.openMenu();
  };

  /** Close the topmost dismissible layer; used by the Android back button. */
  SudokuGame.prototype.closeOverlays = function () {
    if (this.dom.feedbackOverlay && !this.dom.feedbackOverlay.hidden) {
      this.closeFeedback();
      return true;
    }
    if (!this.dom.helpOverlay.hidden) {
      this.closeHelp();
      return true;
    }
    if (!this.dom.hintOverlay.hidden) {
      this.closeHint();
      return true;
    }
    if (this.paused) return false;
    if (this.menuOpen) {
      this.closeMenu();
      return true;
    }
    if (this.dom.resultOverlay && !this.dom.resultOverlay.hidden) {
      this.hideResult();
      return true;
    }
    return false;
  };

  // ----- help and hints -----------------------------------------------------

  SudokuGame.prototype.openHelp = function () {
    this.dom.helpOverlay.hidden = false;
    this.dom.helpOverlay.querySelector('.overlay__card').scrollTop = 0;
    this.dom.helpTitle.focus({ preventScroll: true });
  };

  SudokuGame.prototype.closeHelp = function () {
    if (this.dom.helpOverlay.hidden) return;
    this.dom.helpOverlay.hidden = true;
    var trigger = this.paused && this.view === 'game' ? this.dom.pauseResume
      : this.view === 'game' ? this.dom.menuBtn : this.dom.helpBtn;
    trigger.focus({ preventScroll: true });
  };

  function renderHintSteps(list, steps) {
    list.replaceChildren();
    steps.forEach(function (step) {
      var item = document.createElement('li');
      if (typeof step === 'string') {
        item.textContent = step;
      } else {
        var detail = document.createElement('details');
        var summary = document.createElement('summary');
        summary.textContent = step.label;
        detail.className = 'hint-detail';
        detail.appendChild(summary);
        detail.addEventListener('toggle', function () {
          if (!detail.open || detail.querySelector('ol')) return;
          var child = document.createElement('ol');
          child.className = 'guide-steps';
          renderHintSteps(child, step.expand());
          detail.appendChild(child);
        });
        item.appendChild(detail);
      }
      list.appendChild(item);
    });
  }

  SudokuGame.prototype.openHint = function () {
    if (!this.values || this.view !== 'game' || this.finished || this.paused || this.generating) return;

    var wrong = this.incorrectCells();
    var move = wrong.length === 0 ? this.core.findHint(flatToGrid(this.values)) : null;
    this.pendingHint = { wrong: wrong, move: move };

    var steps = [];
    if (wrong.length > 0) {
      this.dom.hintTitle.textContent = 'Check your entries';
      this.dom.hintMessage.textContent = wrong.length === 1
        ? 'One filled cell is incorrect. Would you like to highlight it?'
        : wrong.length + ' filled cells are incorrect. Would you like to highlight them?';
      this.dom.hintAction.textContent = wrong.length === 1 ? 'Highlight error' : 'Highlight errors';
    } else if (move) {
      this.dom.hintTitle.textContent = move.technique;
      this.dom.hintMessage.textContent = 'Put ' + move.value + ' in row ' + (move.row + 1) +
        ', column ' + (move.col + 1) + '.';
      this.dom.hintAction.textContent = 'Show cell';
      steps = move.steps.concat(move.explanation);
    } else {
      this.dom.hintTitle.textContent = 'Hint';
      this.dom.hintMessage.textContent = 'No forced move was found on this board.';
    }

    renderHintSteps(this.dom.hintSteps, steps);
    this.dom.hintSteps.hidden = steps.length === 0;
    this.dom.hintAction.hidden = !move && wrong.length === 0;
    this.dom.hintOverlay.hidden = false;
    this.dom.hintSteps.scrollTop = 0;
    this.dom.hintTitle.focus({ preventScroll: true });
  };

  SudokuGame.prototype.closeHint = function () {
    if (this.dom.hintOverlay.hidden) return;
    this.dom.hintOverlay.hidden = true;
    this.pendingHint = null;
    if (this.view === 'game') {
      var trigger = this.paused ? this.dom.pauseResume : this.dom.hintBtn;
      trigger.focus({ preventScroll: true });
    }
  };

  SudokuGame.prototype.showHintOnBoard = function () {
    var hint = this.pendingHint;
    this.closeHint();
    if (!hint) return;
    if (hint.wrong.length > 0) {
      this.errors = new Set(hint.wrong);
      this.renderBoard();
      this.cells[hint.wrong[0]].scrollIntoView({ block: 'center', inline: 'nearest' });
    } else if (hint.move) {
      var index = hint.move.row * SIZE + hint.move.col;
      this.selectCell(index);
      this.cells[index].scrollIntoView({ block: 'center', inline: 'nearest' });
    }
  };

  // ----- feedback -----------------------------------------------------------

  var FEEDBACK_REPO = 'kalamcs/sudoku-android';

  SudokuGame.prototype.openFeedback = function () {
    if (!this.dom.feedbackOverlay) return;
    var byEmail = Boolean(window.SUDOKU_FEEDBACK_EMAIL);
    this.dom.feedbackMessage.textContent = byEmail
      ? 'Review the draft in your email app and send it from the account you choose.'
      : 'Review your feedback on GitHub before submitting it. A GitHub account is required.';
    this.dom.feedbackSubmit.textContent = byEmail ? 'Open email draft' : 'Open on GitHub';
    if (this.dom.feedbackError) this.dom.feedbackError.hidden = true;
    this.dom.feedbackOverlay.hidden = false;
    this.dom.feedbackOverlay.querySelector('.overlay__card').scrollTop = 0;

    // Writing a report shouldn't count towards the solve time.
    if (this.view === 'game' && !this.paused && !this.finished && !this.generating) {
      this.pauseGame('feedback');
    }

    if (this.dom.feedbackDetails) {
      var details = this.dom.feedbackDetails;
      window.setTimeout(function () {
        try {
          details.focus({ preventScroll: true });
        } catch (err) {
          details.focus();
        }
      }, 30);
    }
  };

  SudokuGame.prototype.closeFeedback = function (keepPaused) {
    var wasOpen = this.dom.feedbackOverlay && !this.dom.feedbackOverlay.hidden;
    if (this.dom.feedbackOverlay) this.dom.feedbackOverlay.hidden = true;
    if (this.dom.feedbackError) this.dom.feedbackError.hidden = true;

    if (wasOpen && this.view === 'game' && this.paused && this.pausedReason === 'feedback') {
      if (keepPaused) {
        this.pausedReason = 'hidden';
        this.updatePauseUI();
        this.saveNow();
      } else {
        this.resumeGame();
      }
    }
  };

  SudokuGame.prototype.formatFeedback = function (details) {
    var info = window.SUDOKU_APP_INFO || {};
    var lines = [
      details,
      '',
      '---',
      'App: Sudoku for Android' + (info.version ? ' ' + info.version : '') +
        (info.build ? ' (build ' + info.build + ')' : ''),
      'Platform: ' + (window.Capacitor && window.Capacitor.getPlatform ? window.Capacitor.getPlatform() : 'web'),
      'Device: ' + navigator.userAgent
    ];

    return lines.join('\n');
  };

  SudokuGame.prototype.submitFeedback = async function () {
    if (this.dom.feedbackSubmit.disabled) return;
    var details = this.dom.feedbackDetails.value.trim();

    if (!details) {
      this.dom.feedbackError.textContent = 'Please enter some feedback.';
      this.dom.feedbackError.hidden = false;
      return;
    }

    var subject = 'Sudoku for Android feedback';
    var body = this.formatFeedback(details);
    var recipient = window.SUDOKU_FEEDBACK_EMAIL;
    this.dom.feedbackError.hidden = true;
    if (recipient) {
      this.dom.feedbackSubmit.disabled = true;
      try {
        if (window.sudokuOpenEmail) {
          await window.sudokuOpenEmail({ recipient: recipient, subject: subject, body: body });
        } else {
          window.location.href = 'mailto:' + encodeURIComponent(recipient).replace('%40', '@') +
            '?subject=' + encodeURIComponent(subject) + '&body=' + encodeURIComponent(body);
        }
      } catch (err) {
        this.dom.feedbackError.textContent = 'Could not open an email app. Your feedback is still here.';
        this.dom.feedbackError.hidden = false;
        this.dom.feedbackError.scrollIntoView({ block: 'nearest' });
        return;
      } finally {
        this.dom.feedbackSubmit.disabled = false;
      }
    } else {
      var params = new URLSearchParams({ title: subject, body: body });
      this.openExternalUrl('https://github.com/' + FEEDBACK_REPO + '/issues/new?' + params.toString());
    }
    this.closeFeedback(Boolean(recipient));
    this.dom.feedbackForm.reset();
  };

  SudokuGame.prototype.openExternalUrl = function (url) {
    var native = window.Capacitor && window.Capacitor.isNativePlatform && window.Capacitor.isNativePlatform();
    if (native) {
      // Handled by the native bundle, which opens the system browser.
      document.dispatchEvent(new CustomEvent('sudoku:open-url', { detail: { url: url } }));
      return;
    }
    window.open(url, '_blank', 'noopener');
  };

  // ----- board utilities ----------------------------------------------------

  /**
   * User-entered digits that repeat a value in their row, column or box.
   * Givens are never flagged: the starting puzzle always satisfies the rules.
   */
  SudokuGame.prototype.computeConflicts = function () {
    var conflicts = new Set();
    if (!this.values || !this.puzzle) return conflicts;

    for (var index = 0; index < CELL_COUNT; index++) {
      var value = this.values[index];
      if (value === 0 || this.puzzle[index] !== 0) continue;

      var clash = false;
      this.peerSet(index).forEach(function (peer) {
        if (this.values[peer] === value) clash = true;
      }, this);
      if (clash) conflicts.add(index);
    }
    return conflicts;
  };

  /** Clear every entry and note, and restart the clock on the same puzzle. */
  SudokuGame.prototype.resetBoard = function () {
    if (!this.puzzle || !this.values || this.generating) return;
    if (!window.confirm('Reset the board? Your entries, notes and the timer will be cleared.')) return;

    this.values = this.puzzle.slice();
    this.notes = [];
    for (var i = 0; i < CELL_COUNT; i++) this.notes.push(new Set());

    this.errors = new Set();
    this.conflicts = new Set();
    this.highlightDigit = 0;
    this.finished = false;
    this.paused = false;
    this.pausedReason = null;
    this.undoStack = [];
    this.redoStack = [];
    this.selected = this.firstEmptyIndex();
    this.setNotesMode(false);

    this.elapsedBefore = 0;
    this.resumeAt = null;

    this.hideResult();
    if (this.dom.pauseOverlay) this.dom.pauseOverlay.hidden = true;

    this.renderAll();
    this.startTimer();
    this.saveNow();
    this.showBanner('Board reset — timer restarted.', 'success');
  };

  SudokuGame.prototype.goHome = function () {
    if (this.generating) return;
    this.closeMenu();
    this.closeFeedback();
    if (this.view === 'home') {
      this.refreshHome();
      return;
    }
    if (this.paused) return;
    if (this.dom.resultOverlay && !this.dom.resultOverlay.hidden) return;
    this.showHome();
  };

  SudokuGame.prototype.handleHomeDifficulty = function (difficulty) {
    if (DIFFICULTIES.indexOf(difficulty) === -1) return;

    var preview = this.peekSavedGame();
    var liveGame = this.puzzle && !this.finished && this.hasProgress();

    if ((preview || liveGame) && !window.confirm(
      'Start a new ' + difficulty + ' game? Your saved game will be replaced.'
    )) {
      return;
    }

    this.startNewGame(difficulty);
  };

  SudokuGame.prototype.resumeFromHome = function () {
    if (this.view !== 'home' || this.generating) return;

    if (this.puzzle && !this.finished) {
      this.showGame();
      this.renderAll();
      if (this.paused) {
        this.updatePauseUI();
        this.focusPauseResume();
      } else {
        this.startTimer();
      }
      this.saveNow();
      return;
    }

    var result = this.restoreGame();
    if (!result.restored) {
      this.refreshHome();
      return;
    }

    this.showGame();
    this.renderAll();
    if (this.paused) {
      this.updatePauseUI();
      this.focusPauseResume();
    } else {
      this.startTimer();
    }
    this.setSaveStatus('Restored saved game');
    this.saveNow();
  };

  SudokuGame.prototype.discardSavedGame = function () {
    if (!window.confirm('Discard your saved game? This cannot be undone.')) return;

    clearSavedValue(SAVE_KEY);
    this.pauseTimer();
    this.puzzle = null;
    this.solution = null;
    this.values = null;
    this.notes = null;
    this.selected = null;
    this.errors = new Set();
    this.conflicts = new Set();
    this.highlightDigit = 0;
    this.finished = false;
    this.paused = false;
    this.pausedReason = null;
    this.undoStack = [];
    this.redoStack = [];
    this.elapsedBefore = 0;
    this.resumeAt = null;
    this.savedPreview = null;

    if (this.dom.pauseOverlay) this.dom.pauseOverlay.hidden = true;
    this.refreshHome();
    this.setSaveStatus('No game in progress');
  };

  SudokuGame.prototype.showBanner = function (text, kind) {
    if (!this.dom.statusBanner) return;
    this.dom.statusBanner.hidden = false;
    this.dom.statusBanner.textContent = text;
    this.dom.statusBanner.className = 'status-banner status-banner--' + (kind || 'success');
  };

  SudokuGame.prototype.hideBanner = function () {
    if (!this.dom.statusBanner) return;
    this.dom.statusBanner.hidden = true;
    this.dom.statusBanner.textContent = '';
  };

  // ----- timer -------------------------------------------------------------

  SudokuGame.prototype.getElapsed = function () {
    var running = this.resumeAt !== null ? (Date.now() - this.resumeAt) / 1000 : 0;
    return this.elapsedBefore + running;
  };

  SudokuGame.prototype.startTimer = function () {
    this.stopTimerInterval();
    this.resumeAt = Date.now();
    this.timerId = window.setInterval(this.renderTimer.bind(this), 500);
    this.renderTimer();
  };

  SudokuGame.prototype.stopTimerInterval = function () {
    if (this.timerId !== null) {
      window.clearInterval(this.timerId);
      this.timerId = null;
    }
  };

  SudokuGame.prototype.pauseTimer = function () {
    if (this.resumeAt !== null) {
      this.elapsedBefore += (Date.now() - this.resumeAt) / 1000;
      this.resumeAt = null;
    }
    this.stopTimerInterval();
    this.renderTimer();
  };

  // ----- pause -------------------------------------------------------------

  SudokuGame.prototype.togglePause = function () {
    if (this.paused) this.resumeGame();
    else this.pauseGame();
  };

  SudokuGame.prototype.pauseGame = function (reason) {
    if (this.view !== 'game' || this.paused || this.finished || this.generating) return;
    if (reason !== 'hidden' && this.dom.resultOverlay && !this.dom.resultOverlay.hidden) return;

    this.paused = true;
    this.pausedReason = reason || 'manual';
    this.pauseTimer();
    this.updatePauseUI();
    this.updateNumpad();
    this.updateUndoButtons();
    // The feedback dialog manages its own focus; the pause screen is not shown.
    if (reason !== 'feedback') this.focusPauseResume();
    this.saveNow();
  };

  SudokuGame.prototype.resumeGame = function () {
    if (!this.paused) return;

    this.paused = false;
    this.pausedReason = null;
    this.updatePauseUI();
    this.updateNumpad();
    this.updateUndoButtons();
    this.startTimer();
    this.saveNow();

    var focusTarget = this.dom.resultOverlay && !this.dom.resultOverlay.hidden
      ? this.dom.resultPrimary : this.cells[this.selected];
    if (focusTarget) {
      try {
        focusTarget.focus({ preventScroll: true });
      } catch (err) {
        /* ignore focus errors */
      }
    }
  };

  SudokuGame.prototype.focusPauseResume = function () {
    if (!this.dom.pauseResume) return;
    try {
      this.dom.pauseResume.focus({ preventScroll: true });
    } catch (err) {
      this.dom.pauseResume.focus();
    }
  };

  SudokuGame.prototype.updatePauseUI = function () {
    if (!this.dom.pauseOverlay) return;

    this.dom.pauseOverlay.hidden = !this.paused || this.pausedReason === 'feedback';

    if (this.dom.pause) {
      this.dom.pause.disabled = this.finished || this.generating;
      this.dom.pause.setAttribute('aria-pressed', this.paused ? 'true' : 'false');
      this.dom.pauseLabel.textContent = this.paused ? 'Resume' : 'Pause';
    }

    if (this.dom.drawerPauseLabel) {
      this.dom.drawerPauseLabel.textContent = this.paused ? 'Resume' : 'Pause';
      this.dom.drawerPause.classList.toggle('is-resume', this.paused);
    }

    if (this.dom.autoNotes) {
      this.dom.autoNotes.disabled = this.finished || this.generating || this.paused;
    }
    this.dom.hintBtn.disabled = this.finished || this.generating || this.paused;

    if (this.dom.pauseMessage) {
      this.dom.pauseMessage.textContent = this.pausedReason === 'hidden'
        ? 'You left the page, so the timer was stopped. Resume when you are ready.'
        : 'The timer is stopped. Resume when you are ready.';
    }

    if (this.paused && this.dom.pauseStats) {
      var addRow = function (label, value) {
        var row = document.createElement('div');
        row.className = 'result-stats__row';
        var name = document.createElement('span');
        name.textContent = label;
        var val = document.createElement('span');
        val.textContent = value;
        row.appendChild(name);
        row.appendChild(val);
        this.dom.pauseStats.appendChild(row);
      }.bind(this);

      this.dom.pauseStats.replaceChildren();
      addRow('Difficulty', capitalize(this.difficulty));
      addRow('Time', formatDuration(this.getElapsed()));
    }
  };

  // ----- game flow ---------------------------------------------------------

  SudokuGame.prototype.hasProgress = function () {
    if (!this.values || !this.puzzle) return false;
    if (this.finished) return false;
    if (this.notes.some(function (set) { return set.size > 0; })) return true;
    for (var i = 0; i < CELL_COUNT; i++) {
      if (this.values[i] !== 0 && this.puzzle[i] === 0) return true;
    }
    return false;
  };

  SudokuGame.prototype.requestNewGame = function (difficulty) {
    if (this.generating) return;
    if (DIFFICULTIES.indexOf(difficulty) === -1) difficulty = 'medium';

    if (this.hasProgress()) {
      var ok = window.confirm(
        'Start a new ' + difficulty + ' game? Your current progress will be lost.'
      );
      if (!ok) return;
    }
    this.startNewGame(difficulty);
  };

  SudokuGame.prototype.startNewGame = function (difficulty) {
    if (this.generating) return;
    var self = this;
    this.generating = true;

    this.pauseTimer();
    this.hideResult();
    this.hideBanner();
    if (this.dom.loadingOverlay) this.dom.loadingOverlay.hidden = false;
    if (this.dom.grid) this.dom.grid.setAttribute('aria-busy', 'true');

    // Let the loading overlay paint before the (usually fast) generation.
    window.setTimeout(function () {
      try {
        var generated = self.core.generatePuzzle(difficulty);
        self.difficulty = difficulty;
        self.puzzle = [].concat.apply([], generated.puzzle);
        self.solution = [].concat.apply([], generated.solution);
        self.values = self.puzzle.slice();
        self.notes = [];
        for (var i = 0; i < CELL_COUNT; i++) self.notes.push(new Set());

        self.errors = new Set();
        self.conflicts = new Set();
        self.highlightDigit = 0;
        self.finished = false;
        self.paused = false;
        self.pausedReason = null;
        self.undoStack = [];
        self.redoStack = [];
        self.selected = self.firstEmptyIndex();
        self.setNotesMode(false);

        self.elapsedBefore = 0;
        self.resumeAt = null;

        self.showGame();
        self.renderAll();
        self.startTimer();
        self.saveNow();
        self.setSaveStatus('Saved ' + new Date().toLocaleTimeString());
      } catch (err) {
        console.error('Puzzle generation failed:', err);
        window.alert('Sorry, the puzzle could not be generated. Please try again.');
      } finally {
        self.generating = false;
        // If the tab was hidden while the puzzle was being generated, do not
        // let the clock run in the background.
        if (document.hidden && self.view === 'game' && !self.finished && !self.paused) {
          self.pauseGame('hidden');
        }
        self.updateNumpad();
        self.updatePauseUI();
        if (self.dom.loadingOverlay) self.dom.loadingOverlay.hidden = true;
        if (self.dom.grid) self.dom.grid.setAttribute('aria-busy', 'false');
      }
    }, 40);
  };

  // ----- history -----------------------------------------------------------

  SudokuGame.prototype.snapshot = function () {
    return {
      values: this.values.join(''),
      notes: encodeNotes(this.notes)
    };
  };

  SudokuGame.prototype.restoreSnapshot = function (snapshot) {
    this.values = parseDigitString(snapshot.values, true);
    this.notes = decodeNotes(snapshot.notes);
  };

  SudokuGame.prototype.pushHistory = function () {
    this.undoStack.push(this.snapshot());
    this.redoStack = [];
    this.updateUndoButtons();
  };

  SudokuGame.prototype.undo = function () {
    if (this.finished || this.paused || this.undoStack.length === 0) return;
    this.redoStack.push(this.snapshot());
    this.restoreSnapshot(this.undoStack.pop());
    this.afterBoardEdit();
  };

  SudokuGame.prototype.redo = function () {
    if (this.finished || this.paused || this.redoStack.length === 0) return;
    this.undoStack.push(this.snapshot());
    this.restoreSnapshot(this.redoStack.pop());
    this.afterBoardEdit();
  };

  // ----- editing -----------------------------------------------------------

  SudokuGame.prototype.applyNumber = function (number) {
    if (this.selected === null || this.paused) return;
    if (this.finished || this.generating || !this.values) return;

    if (this.puzzle[this.selected] !== 0) {
      // Pressing a digit while a given (bold) cell is selected highlights every
      // occurrence of that digit on the board. The given itself never changes.
      this.highlightDigit = number;
      this.renderBoard();
      this.updateNumpad();
      return;
    }

    if (this.countUnconflictedDigits()[number] >= 9) return;
    if (this.notesMode) {
      this.toggleNote(this.selected, number);
    } else {
      this.setValue(this.selected, number);
    }
  };

  SudokuGame.prototype.setValue = function (index, number) {
    if (this.finished || this.paused || this.generating || !this.values) return;
    if (index < 0 || index >= CELL_COUNT) return;
    if (this.puzzle[index] !== 0) return; // givens are locked

    var current = this.values[index];
    var next = number;
    if (next === current) next = 0; // pressing the same digit clears the cell
    if (next === 0 && this.notes[index].size === 0 && current === 0) return;

    this.pushHistory();
    this.values[index] = next;

    if (next !== 0) {
      this.notes[index].clear();
      // A placed digit can be removed from peers' pencil marks too.
      var peers = this.peerSet(index);
      peers.forEach(function (peer) {
        this.notes[peer].delete(next);
      }, this);
    }

    this.afterBoardEdit();
  };

  SudokuGame.prototype.toggleNote = function (index, number) {
    if (this.finished || this.paused || this.generating || !this.values) return;
    if (index < 0 || index >= CELL_COUNT) return;
    if (this.puzzle[index] !== 0 || this.values[index] !== 0) return;

    var notes = this.notes[index];
    var has = notes.has(number);
    this.pushHistory();
    if (has) notes.delete(number);
    else notes.add(number);
    this.afterBoardEdit();
  };

  SudokuGame.prototype.eraseSelected = function () {
    if (this.selected === null || this.finished || this.paused || this.generating || !this.values) return;
    var index = this.selected;
    if (this.puzzle[index] !== 0) return;

    var hasValue = this.values[index] !== 0;
    var hasNotes = this.notes[index].size > 0;
    if (!hasValue && !hasNotes) return;

    this.pushHistory();
    this.values[index] = 0;
    this.notes[index].clear();
    this.afterBoardEdit();
  };

  /**
   * Candidate pencil marks for every empty cell, using only row/column/box
   * constraints against the current board values.
   */
  SudokuGame.prototype.computeCandidateNotes = function () {
    var grid = flatToGrid(this.values);
    var nextNotes = [];

    for (var index = 0; index < CELL_COUNT; index++) {
      if (this.values[index] !== 0) {
        nextNotes.push(new Set());
        continue;
      }
      var row = Math.floor(index / SIZE);
      var col = index % SIZE;
      nextNotes.push(new Set(this.core.candidatesFor(grid, row, col)));
    }

    return nextNotes;
  };

  /** Fill (or refresh) pencil marks for the whole board as one undoable action. */
  SudokuGame.prototype.fillAllNotes = function () {
    if (this.finished || this.paused || this.generating || !this.values) return;

    var nextNotes = this.computeCandidateNotes();
    var changed = false;
    for (var i = 0; i < CELL_COUNT; i++) {
      if (!setsEqual(this.notes[i], nextNotes[i])) {
        changed = true;
        break;
      }
    }

    if (!changed) {
      this.showBanner('Pencil marks are already up to date.', 'success');
      return;
    }

    this.pushHistory();
    this.notes = nextNotes;
    this.afterBoardEdit();
    this.showBanner('Pencil marks filled from row, column and box candidates.', 'success');
  };

  SudokuGame.prototype.afterBoardEdit = function () {
    this.errors = new Set();
    this.hideBanner();
    this.renderBoard();
    this.updateUndoButtons();
    this.evaluateBoard();
    this.scheduleSave();
  };

  // ----- evaluation --------------------------------------------------------

  SudokuGame.prototype.incorrectCells = function () {
    var wrong = [];
    for (var index = 0; index < CELL_COUNT; index++) {
      if (this.values[index] !== 0 && this.values[index] !== this.solution[index]) wrong.push(index);
    }
    return wrong;
  };

  SudokuGame.prototype.evaluateBoard = function () {
    if (this.finished) return;

    for (var i = 0; i < CELL_COUNT; i++) {
      if (this.values[i] === 0) return; // not complete yet
    }

    var wrong = this.incorrectCells();

    if (wrong.length === 0) {
      this.handleWin();
    } else {
      this.errors = new Set(wrong);
      this.renderBoard();
      this.showResult(false, { wrongCount: wrong.length });
      this.saveNow();
    }
  };

  SudokuGame.prototype.handleWin = function () {
    var seconds = Math.max(1, Math.round(this.getElapsed()));

    this.finished = true;
    this.paused = false;
    this.pausedReason = null;
    this.pauseTimer();
    this.errors = new Set();
    this.conflicts = new Set();
    this.highlightDigit = 0;

    var result = this.recordWin(seconds);

    this.renderAll();
    this.showResult(true, {
      time: seconds,
      isBest: result.isBest,
      best: this.stats[this.difficulty].best
    });
    this.showBanner(
      'Solved! ' + capitalize(this.difficulty) + ' puzzle completed in ' + formatDuration(seconds) + '.',
      'success'
    );
    this.saveNow();
  };

  SudokuGame.prototype.recordWin = function (seconds) {
    var stats = this.stats[this.difficulty];
    stats.wins += 1;
    stats.totalTime = (stats.totalTime || 0) + seconds;
    stats.timedWins = (stats.timedWins || 0) + 1;

    var isBest = false;
    if (stats.best === null || seconds < stats.best) {
      stats.best = seconds;
      stats.bestAt = Date.now();
      isBest = true;
    }

    this.saveStats();
    this.renderStats();
    this.renderHomeStats();
    return { isBest: isBest };
  };

  SudokuGame.prototype.saveStats = function () {
    try {
      writeSavedValue(STATS_KEY, JSON.stringify(this.stats));
    } catch (err) {
      console.error('Could not save stats:', err);
    }
  };

  // ----- result overlay ----------------------------------------------------

  SudokuGame.prototype.showResult = function (success, details) {
    var overlay = this.dom.resultOverlay;
    if (!overlay) return;

    overlay.classList.toggle('is-error', !success);
    this.dom.resultIcon.textContent = success ? '🎉' : '⚠';
    this.dom.resultTitle.textContent = success ? 'Puzzle solved!' : 'Not quite yet';

    var statsContainer = this.dom.resultStats;
    statsContainer.replaceChildren();

    var addRow = function (label, value, highlight) {
      var row = document.createElement('div');
      row.className = 'result-stats__row' + (highlight ? ' result-stats__row--highlight' : '');
      var name = document.createElement('span');
      name.textContent = label;
      var val = document.createElement('span');
      val.textContent = value;
      row.appendChild(name);
      row.appendChild(val);
      statsContainer.appendChild(row);
    };

    if (success) {
      this.dom.resultMessage.textContent =
        'Every cell matches the solution. Great job!';
      addRow('Difficulty', capitalize(this.difficulty));
      addRow('Your time', formatDuration(details.time));
      addRow('Best for ' + this.difficulty, formatDuration(details.best));
      if (details.isBest) addRow('Record', 'New best time!', true);
      this.dom.resultPrimary.textContent = 'Back to home';
    } else {
      var count = details.wrongCount;
      this.dom.resultMessage.textContent =
        count + (count === 1 ? ' cell is' : ' cells are') +
        ' incorrect. They are highlighted in red — fix them and the puzzle will be checked again.';
      addRow('Difficulty', capitalize(this.difficulty));
      addRow('Incorrect cells', String(count));
      addRow('Time so far', formatDuration(this.getElapsed()));
      this.dom.resultPrimary.textContent = 'Keep trying';
    }

    overlay.hidden = false;
    if (this.dom.resultPrimary) {
      try {
        this.dom.resultPrimary.focus({ preventScroll: true });
      } catch (err) {
        this.dom.resultPrimary.focus();
      }
    }
  };

  SudokuGame.prototype.hideResult = function () {
    if (!this.dom.resultOverlay) return;
    this.dom.resultOverlay.hidden = true;
    this.dom.resultOverlay.classList.remove('is-error');
    if (this.view === 'game' && this.selected !== null && this.cells[this.selected]) {
      try {
        this.cells[this.selected].focus({ preventScroll: true });
      } catch (err) {
        /* ignore focus errors */
      }
    }
  };

  // ----- persistence -------------------------------------------------------

  SudokuGame.prototype.encodeState = function () {
    return {
      v: 1,
      d: this.difficulty,
      p: this.puzzle.join(''),
      s: this.solution.join(''),
      b: this.values.join(''),
      n: encodeNotes(this.notes),
      e: Math.floor(this.getElapsed()),
      w: this.finished && this.errors.size === 0 ? 1 : 0,
      z: this.paused ? 1 : 0,
      t: Date.now()
    };
  };

  SudokuGame.prototype.saveNow = function () {
    if (!this.puzzle || !this.values) return;
    try {
      writeSavedValue(SAVE_KEY, JSON.stringify(this.encodeState()));
      this.setSaveStatus('Saved ' + new Date().toLocaleTimeString());
    } catch (err) {
      console.error('Autosave failed:', err);
      this.setSaveStatus('Autosave failed');
    }
  };

  SudokuGame.prototype.scheduleSave = function () {
    if (this.saveTimeoutId !== null) window.clearTimeout(this.saveTimeoutId);
    this.saveTimeoutId = window.setTimeout(function () {
      this.saveTimeoutId = null;
      this.saveNow();
    }.bind(this), SAVE_DEBOUNCE_MS);
  };

  SudokuGame.prototype.restoreGame = function () {
    var raw = readSavedValue(SAVE_KEY);
    if (!raw) return { restored: false };

    var state;
    try {
      state = JSON.parse(raw);
    } catch (err) {
      return { restored: false };
    }

    if (!state || state.v !== 1) return { restored: false };
    var difficulty = state.d;
    if (DIFFICULTIES.indexOf(difficulty) === -1) return { restored: false };

    // A completed game is not restored as a mid-game state; start fresh with
    // the same difficulty instead.
    if (state.w === 1) return { restored: false, difficulty: difficulty };

    var puzzle = parseDigitString(state.p, true);
    var solution = parseDigitString(state.s, false);
    var values = parseDigitString(state.b, true);
    if (!puzzle || !solution || !values) {
      return { restored: false, difficulty: difficulty };
    }
    if (puzzle.indexOf(0) === -1) {
      return { restored: false, difficulty: difficulty };
    }

    for (var i = 0; i < CELL_COUNT; i++) {
      if (puzzle[i] !== 0 && puzzle[i] !== solution[i]) {
        return { restored: false, difficulty: difficulty };
      }
    }

    try {
      if (this.core.countSolutions(flatToGrid(puzzle), 2) !== 1) {
        return { restored: false, difficulty: difficulty };
      }
    } catch (err) {
      return { restored: false, difficulty: difficulty };
    }

    var notes = decodeNotes(state.n);
    if (!notes) {
      return { restored: false, difficulty: difficulty };
    }

    // Sanitize: givens always win, filled cells have no notes.
    for (var index = 0; index < CELL_COUNT; index++) {
      if (puzzle[index] !== 0) {
        values[index] = puzzle[index];
        notes[index].clear();
      } else if (values[index] !== 0) {
        notes[index].clear();
      }
    }

    this.difficulty = difficulty;
    this.puzzle = puzzle;
    this.solution = solution;
    this.values = values;
    this.notes = notes;
    this.errors = new Set();
    this.conflicts = new Set();
    this.highlightDigit = 0;
    this.finished = false;
    this.undoStack = [];
    this.redoStack = [];
    this.selected = this.values.indexOf(0);
    if (this.selected === -1) this.selected = 0;
    this.elapsedBefore = Math.max(0, Number(state.e) || 0);
    this.resumeAt = null;
    this.paused = state.z === 1;
    this.pausedReason = this.paused ? 'manual' : null;
    this.setNotesMode(false);

    return { restored: true };
  };

  // -------------------------------------------------------------------------
  // Boot
  // -------------------------------------------------------------------------

  function boot() {
    var game = new SudokuGame();
    window.sudokuGame = game; // handy for debugging / tests
    game.init();
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot);
  } else {
    boot();
  }
})();
