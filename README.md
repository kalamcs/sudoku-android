# Sudoku for Android

A complete, fully offline Sudoku game for Android, packaged with
[Capacitor 8](https://capacitorjs.com). Puzzles are generated on the device —
there is no backend, no account, and no network or analytics permission.

- **Package:** `com.kalamcs.sudoku`
- **Minimum Android:** 7.0 (API 24)
- **Target Android:** 16 (API 36)

## Features

**Puzzles**
- Easy (40–45 clues), Medium (32–37), Hard (26–30)
- Every puzzle is generated on-device with a guaranteed **unique** solution
- Difficulty is shown in the header, the active button, and the task switcher

**Solving**
- Notes / pencil marks with a 3×3 mini-grid inside each cell
- *Auto notes* fills every empty cell with its row/column/box candidates in one
  undoable action
- Undo/redo all the way back to the starting puzzle
- Completed digits grey out on the numpad; the selected digit is highlighted in
  every cell's notes
- Automatic validation once the board is full, with wrong cells marked

**Progress and stats**
- Autosave: the game is saved continuously and offered on the home screen after
  the app is closed or killed
- Per-difficulty best time, average time, puzzles solved, and best-time date
- Home screen with stats plus a "Continue your game" card
- Timer pauses manually, and automatically when the app goes to the background,
  so time away is never counted

**Android behaviour**
- Edge-to-edge layout that respects status/navigation bars and display cutouts
- Back button pauses the game and returns home; exits only from the home screen
- No pull-to-refresh, text selection, or tap highlights — it feels like an app,
  not a web page

## Requirements

No Android Studio required; the command-line SDK is enough.

| Dependency | Version |
|---|---|
| Node.js | 22+ |
| JDK | 17–21 (built with Temurin 21) |
| Android SDK Platform | `platforms;android-36` |
| Android SDK Build-Tools | `build-tools;36.0.0` |
| Android SDK Platform-Tools | `platform-tools` (for `adb`) |
| Gradle | supplied by the wrapper (8.14.3) |

`JAVA_HOME` and `ANDROID_HOME` must be set. Install the SDK packages with:

```bash
sdkmanager --install "platform-tools" "platforms;android-36" "build-tools;36.0.0"
yes | sdkmanager --licenses
```

On this machine: `JAVA_HOME=~/.local/opt/jdk-21-current` and
`ANDROID_HOME=~/Android/Sdk` (both exported from `~/.bashrc`).

## Build

```bash
npm install          # once
npm run apk          # debug APK
```

Output: `android/app/build/outputs/apk/debug/app-debug.apk`

Other scripts:

| Command | Result |
|---|---|
| `npm run sync` | Rebuild `www/native.js` and copy `www/` into the native project |
| `npm run apk` | Sync + `./gradlew assembleDebug` |
| `npm run aab` | Sync + `./gradlew bundleRelease` (Play Store bundle) |
| `npm run open` | Open `android/` in Android Studio, if installed |

Run `npm run sync` after changing anything in `www/` or `capacitor.config.json`.
The first build downloads Gradle and the AndroidX dependencies (a few hundred
MB, cached in `~/.gradle`); later builds take seconds.

## Install on the phone

With wireless debugging (Android 11+, no USB needed):

```bash
adb pair <phone-ip>:<pair-port>     # code from Developer options → Wireless debugging
adb connect <phone-ip>:5555
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

Or copy the APK to the phone and open it, allowing "install unknown apps" for
the file manager. On WSL2 the phone's USB device is not exposed to Linux by
default; wireless debugging, or running `adb` from Windows against a copy of the
APK, avoids that.

## Release to Google Play

1. Create an upload keystore **outside this repository**:

   ```bash
   keytool -genkey -v -keystore ~/keys/sudoku-upload.jks \
     -alias sudoku -keyalg RSA -keysize 2048 -validity 10000
   ```

2. Create `android/keystore.properties` (git-ignored) with the store/key
   passwords and path, and reference it from a `signingConfigs` block in
   `android/app/build.gradle`.
3. Bump `versionCode`/`versionName` in `android/app/build.gradle`.
4. `npm run aab` → `android/app/build/outputs/bundle/release/app-release.aab`
5. Upload the bundle in Play Console.

Back up the keystore and its passwords: without them the app can never be
updated under the same listing. Never commit `*.keystore`/`*.jks`,
`keystore.properties`, or `android/local.properties` — all are in `.gitignore`.

> The `appId` in `capacitor.config.json` (`com.kalamcs.sudoku`) is the permanent
> Play Store identity. Changing it means updating `android/app/build.gradle`
> (`namespace`, `applicationId`),
> `android/app/src/main/res/values/strings.xml` (`package_name`,
> `custom_url_scheme`), and the `MainActivity` package — and is not possible
> after the first upload.

## Project layout

| Path | Purpose |
|---|---|
| `www/` | The game: markup, styles, controller, puzzle generator |
| `src/native.js` | Native glue: Android back button, background auto-pause. Bundled to `www/native.js` by esbuild |
| `android/` | Native Android project generated by Capacitor. `npx cap sync` owns `app/src/main/assets/public` |
| `capacitor.config.json` | App id and name, web directory, system-bar and splash settings |
| `package.json` | Build scripts and Capacitor/esbuild dependencies |

## Implementation notes

- **Storage** uses `localStorage` (`sudoku_save_v1`, `sudoku_stats_v1`). The
  autosave payload is ~0.5 KB, far below the 5 MB quota, and survives process
  death and app updates.
- **Edge-to-edge** is mandatory on Android 15+ for apps targeting API 35+, so
  the page is drawn under the system bars. Capacitor's built-in `SystemBars`
  plugin injects `--safe-area-inset-*` on the document root (configured with
  `insetsHandling: "css"`), and `styles.css` pads the page and overlays with
  them. `@capacitor/status-bar` is deliberately not used: its `overlaysWebView`
  and `backgroundColor` options have no effect on API 36.
- **Background pause** is driven by the native `appStateChange` event, not the
  WebView's `visibilitychange`, which Android does not fire reliably when the
  app leaves the foreground.
- **The back button** is handled in `src/native.js`: close the result dialog,
  then pause, then go home, then exit.

## Troubleshooting

- `ERROR: JAVA_HOME is not set` — export `JAVA_HOME`; `sdkmanager` and Gradle
  both need it.
- `SDK location not found` — export `ANDROID_HOME=~/Android/Sdk`.
- `./gradlew: Permission denied` — `chmod +x android/gradlew`.
- `adb: no devices/emulators found` — connect the phone over wireless
  debugging, or install the APK manually.
