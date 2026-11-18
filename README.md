# Sudoku for Android

Android build of the Sudoku game, packaged with [Capacitor 8](https://capacitorjs.com).
The game itself is the same HTML/CSS/JS code as the browser version in
`../sudoku`, with small Android-specific adaptations. This directory is an
independent copy: changes here do **not** touch the browser project.

## Layout

| Path | Purpose |
|---|---|
| `www/` | The game (HTML, CSS, JS) that ships inside the app |
| `src/native.js` | Capacitor wiring: Android back button + background auto-pause. Bundled to `www/native.js` by esbuild |
| `android/` | Generated native Android project (Capacitor). Safe to edit, but `npx cap sync` owns `assets/public` |
| `capacitor.config.json` | App id, name, web dir, system bar and splash settings |

`www/native.js` is generated and git-ignored; run `npm run build:native`
(or any of the scripts below) to produce it.

## Differences from the browser version

- **Storage:** `localStorage` only. The browser build wrote cookies with a
  `localStorage` fallback; cookies are capped at ~4 KB and can be cleared by the
  WebView, so the app uses the same keys (`sudoku_save_v1`, `sudoku_stats_v1`)
  directly in `localStorage`.
- **Edge-to-edge:** Android 15+ enforces edge-to-edge for `targetSdk 35+`, so
  the page is drawn under the status and navigation bars. `styles.css` pads the
  page with `--safe-area-inset-*`, injected by Capacitor's built-in `SystemBars`
  plugin. `@capacitor/status-bar` is not used: its `overlaysWebView` and
  `backgroundColor` options have no effect on API 36.
- **Back button:** pauses the game, then returns to the home screen, and only
  exits from the home screen.
- **App backgrounded:** the clock stops and the game stays paused, matching the
  browser's `visibilitychange` behaviour (which Android WebView does not fire
  reliably).

## Requirements

No Android Studio needed. The toolchain lives in the user's home directory:

- JDK 21 (`~/.local/opt/jdk-21-current`), `JAVA_HOME` exported in `~/.bashrc`
- Android SDK (`~/Android/Sdk`) with `ANDROID_HOME` exported, plus
  `platforms;android-36`, `build-tools;36.0.0`, `platform-tools`
- Node.js 22+

## Build

```bash
npm install          # once
npm run sync         # rebuild www/native.js and copy www/ into android/
npm run apk          # debug APK
```

Output: `android/app/build/outputs/apk/debug/app-debug.apk`

`npm run apk` is equivalent to `npm run sync && cd android && ./gradlew assembleDebug`.
Gradle is provided by the wrapper (`android/gradlew`), pinned to 8.14.3.

## Install on the phone

Wireless debugging (Android 11+), no USB needed inside WSL2:

```bash
adb pair <phone-ip>:<pair-port>     # code from Developer options
adb connect <phone-ip>:5555
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

Alternatively copy the APK to the phone and open it (allow "install unknown
apps" for the file manager). WSL2 does not see USB devices by default; running
`adb` from Windows PowerShell against a copy of the APK also works.

## Release (Play Store)

1. Create an upload keystore **outside this repo** (or keep it out of git):

   ```bash
   keytool -genkey -v -keystore ~/keys/sudoku-upload.jks \
     -alias sudoku -keyalg RSA -keysize 2048 -validity 10000
   ```

2. Add `android/keystore.properties` (git-ignored) pointing at it, and wire the
   signing config into `android/app/build.gradle`.
3. `npm run aab` → `android/app/build/outputs/bundle/release/app-release.aab`
4. Upload the `.aab` in Play Console (one-time $25 developer account).

Keep the keystore and its passwords backed up: losing them means you can never
update the app under the same listing.

> `appId` in `capacitor.config.json` (`com.kalamcs.sudoku`) becomes the package
> name. It can still be changed now, but **not after the first Play Store
> upload**. If you change it, update `android/app/build.gradle`
> (`namespace` and `applicationId`), `android/app/src/main/res/values/strings.xml`
> (`package_name`, `custom_url_scheme`), and the `MainActivity` package.

## Git

Commits use the GitHub noreply address, so the account's email is never exposed
in public commit metadata:

```bash
git config --global user.name "Kalam Shah"
git config --global user.email "1391401+kalamcs@users.noreply.github.com"
```

The repository is pushed over SSH (key added to GitHub). To create the remote:

```bash
git remote add origin git@github.com:<you>/sudoku-android.git
git push -u origin main
```

Never commit `*.keystore`, `keystore.properties`, or `android/local.properties`
— they are already in `.gitignore`.
