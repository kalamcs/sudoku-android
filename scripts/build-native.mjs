// Copyright (c) 2026 Kalam Shah
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

import { build } from 'esbuild';

const email = (process.env.SUDOKU_FEEDBACK_EMAIL || '').trim();
if (email && !/^[^\s@?&#,]+@[^\s@?&#,]+\.[^\s@?&#,]+$/.test(email)) {
  throw new Error('SUDOKU_FEEDBACK_EMAIL must contain one email address');
}

await build({
  entryPoints: ['src/native.js'],
  bundle: true,
  format: 'iife',
  target: 'es2020',
  outfile: 'www/native.js',
  define: { __SUDOKU_FEEDBACK_EMAIL__: JSON.stringify(email) },
  logLevel: 'info',
});
