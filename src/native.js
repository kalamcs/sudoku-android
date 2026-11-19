/**
 * Android shell wiring.
 *
 * Bundled by esbuild into www/native.js and loaded after app.js, so it can
 * reach the game instance through window.sudokuGame. It is a no-op when the
 * page is opened in a normal browser, which keeps the www/ folder usable for
 * quick desktop checks.
 */
import { App } from '@capacitor/app';
import { Browser } from '@capacitor/browser';

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

  // Expose the installed version so feedback reports can include it.
  try {
    const info = await App.getInfo();
    window.SUDOKU_APP_INFO = {
      version: info.version,
      build: info.build,
      name: info.name,
    };
  } catch (err) {
    // Version info is optional; the feedback form still works without it.
  }

  // GitHub feedback links must leave the WebView and open in the system browser.
  document.addEventListener('sudoku:open-url', (event) => {
    const url = event.detail && event.detail.url;
    if (!url) return;
    Browser.open({ url }).catch((err) => {
      console.error('Sudoku: could not open browser', err);
      window.open(url, '_blank', 'noopener');
    });
  });

  // Android back button: close whichever layer is on top, then pause, then
  // return home, and only close the app when there is nothing left to go back to.
  await App.addListener('backButton', ({ canGoBack }) => {
    const g = game();
    if (!g) return;

    if (g.closeOverlays && g.closeOverlays()) return;

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
