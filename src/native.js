/**
 * Android shell wiring.
 *
 * Bundled by esbuild into www/native.js and loaded after app.js, so it can
 * reach the game instance through window.sudokuGame. It is a no-op when the
 * page is opened in a normal browser, which keeps the www/ folder usable for
 * quick desktop checks.
 */
import { App } from '@capacitor/app';

const isNative = () => window.Capacitor && window.Capacitor.isNativePlatform && window.Capacitor.isNativePlatform();

const game = () => window.sudokuGame;

/** Pause a running game; the web app exposes this and owns the overlay. */
function pauseForBackground() {
  const g = game();
  if (!g || !g.puzzle || g.view !== 'game') return;
  if (g.paused || g.finished || g.generating) return;
  g.pauseGame('hidden');
}

async function initNative() {
  // Let app.js finish booting before attaching listeners.
  if (!game()) {
    await new Promise((resolve) => {
      if (window.sudokuGame) return resolve();
      document.addEventListener('DOMContentLoaded', () => resolve(), { once: true });
    });
  }

  // Android back button: pause first, then return to the home screen, and
  // only close the app when there is nothing left to go back to.
  await App.addListener('backButton', ({ canGoBack }) => {
    const g = game();
    if (!g) return;

    const resultOpen = g.dom && g.dom.resultOverlay && !g.dom.resultOverlay.hidden;
    if (resultOpen) {
      g.dom.resultOverlay.hidden = true;
      return;
    }

    if (g.view === 'game' && !g.finished) {
      if (g.paused) {
        g.showHome();
      } else {
        g.pauseGame('manual');
      }
      return;
    }

    if (canGoBack && g.view === 'home') {
      App.exitApp();
      return;
    }

    App.exitApp();
  });

  // The WebView does not reliably fire visibilitychange when Android
  // backgrounds the app, so mirror the browser behaviour here: stop the clock
  // and leave the game paused until the player resumes.
  await App.addListener('appStateChange', ({ isActive }) => {
    if (isActive) {
      const g = game();
      if (g && g.view === 'game' && g.paused) g.saveNow();
      return;
    }
    pauseForBackground();
  });
}

if (isNative()) {
  initNative().catch((err) => {
    // The game must stay playable even if a native plugin is unavailable.
    console.error('Sudoku: native integration failed', err);
  });
}
