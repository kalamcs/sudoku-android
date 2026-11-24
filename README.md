# Sudoku for Android

A Sudoku game built with Kotlin and native Android Views. Puzzle generation,
gameplay, saved games, and statistics work offline. Sending feedback uses
Firebase with anonymous sign-in.

- Package `com.kalamcs.sudoku`
- Minimum Android 7.0 (API 24)
- Target Android 16 (API 36)

## How to play

Fill the board so that each row, column, and 3×3 box contains the digits 1
through 9 exactly once. The starting clues stay fixed.

- Tap a cell and a digit to enter a number. Tap a filled cell to highlight
  matching entries and pencil marks. With a starting clue selected, tap a
  keypad digit to highlight its matches. Completed digits stay gray on the
  keypad. Duplicate entries appear in red.
- At the bottom of the game, switch from **Cell first** to **Digit first** to
  pick a keypad digit before tapping cells. The digit stays selected as you
  enter it in different cells. Tap it again to clear the selection. Notes mode
  works with either entry order.
- Pinch the board to zoom up to 3×. Drag with one finger to move around the
  enlarged board, then pinch inward or tap **Reset zoom** to see the full board.
- Toggle **Notes: Off** or **Notes: On** to pencil in candidates.
  **Auto-fill Candidates** alongside the keypad lists the digits
  allowed by the current row, column, and box.
  If you correct or erase your latest entry before another board edit, the pencil
  marks it removed are restored. Undo and redo track entries and notes, including
  when you close the app and continue the saved puzzle later.
- **Hint** counts incorrect entries first and offers to highlight them. On a
  correct board, it explains a forced placement and can select the cell. Open
  the reasoning under a step to see the supporting constraints.
- **Check Progress** reports incorrect entries and offers to highlight them. **Restart Puzzle**
  opens the reset confirmation. The timer pauses in the game menu and runs
  while a hint or an error result is open.
- **Pause** and Android Back save the puzzle and return to Home. Tap
  **Continue Puzzle** to pick up where you left off. After waking the phone or
  returning to the app, an open puzzle resumes automatically.
  The home stats table shows solved puzzles, best times and dates, and average
  times by difficulty. The timer also stops when you leave the app or open the
  guide from a game.
- **Screen timeout during a game** follows Android by default. Settings can
  keep the display awake for at least two or three minutes after an interaction,
  or for as long as the puzzle is open in the foreground.
- In **Settings → Number Keypad Layout**, choose **Auto**, **3×3**, or **1×9**.
  Auto measures the board and controls within the current safe area. It
  favors 3×3 when both layouts give the board similar space, and chooses
  1×9 when it makes the board meaningfully larger and can keep 44 dp keys.
  A manual 1×9 choice falls back to 3×3 when the row cannot fit.
- After a win, choose **View board** and then **Replay Puzzle** to solve it again
  for practice. Only the first completion is recorded in your stats.

### Basic solving methods

Start with a nearly full row, column, or box and list its missing digits.
Check which digits fit each blank. A **naked single** is a cell with only one
candidate. A **hidden single** is the only place a particular digit can go in
a row, column, or box. Recheck the affected areas after each placement.

### Advanced solving techniques

**Locked candidates** use the overlap between a line and a box to eliminate
options. A **naked pair** reserves two digits for two cells in the same row,
column, or box. A **proof by contradiction** rules out a candidate by showing
that its forced consequences break a Sudoku rule.

Open **How to Play** from Home or the hamburger menu. **Rules** shows
row, column, and box diagrams. **Strategies** groups basic and advanced methods
and links to their worked examples.

## Puzzle difficulty

A clue is a number that is already on the board when the puzzle starts. Every
puzzle has exactly one solution.

| Level | Target clues | How the app chooses puzzles |
|---|---:|---|
| Easy | 40–45 | Removes clues from a completed grid while keeping a unique solution. |
| Medium | 32–37 | Uses the same generation method with fewer starting clues. |
| Hard | 26–30 | Checks that the puzzle can be solved with naked singles, hidden singles, locked candidates, and naked pairs. |
| Extreme | 23–25 | Varies prechecked boards that require reasoning beyond the Hard technique set. |

Clue placement also affects difficulty. Two puzzles with the same clue count
can call for different techniques. The Hard check sets an upper limit on the
techniques needed, so some Hard puzzles can be solved with singles alone.

Further reading on puzzle difficulty:

- [HoDoKu's rating and difficulty levels](https://hodoku.dev/docs_cre) explains
  a technique-based rating that combines the hardest step with the total score.
- Radek Pelánek's [Difficulty Rating of Sudoku Puzzles](https://arxiv.org/abs/1403.7373)
  compares difficulty metrics with how people solve puzzles.

## Appearance

Open **Settings** from the home screen or the game menu. The app starts in
**Light** mode with **Ocean Blue** styling. Choose **Dark** to use dark colors or
**System** to follow Android's light or dark setting. Appearance and theme
choices are saved independently.

Open **Colour Palette** to compare Ocean Blue, Forest Green, Royal Purple,
Warm Sand, Rose, and Slate side by side in Light and Dark. The previews show
the selected cell, matching digits, and candidate notes. Choosing a palette
keeps your current Light, Dark, or System setting. If your saved palette was
retired, the app uses Ocean Blue.

## Build and test

Use JDK 17–21 and Android SDK Platform 36. The Gradle 8.14.3 wrapper downloads
the Kotlin and AndroidX dependencies. Android SDK Build-Tools 36.0.0 and
Platform-Tools supply the APK verification and ADB commands.

On the project's WSL2 setup:

```bash
export JAVA_HOME="$HOME/.local/opt/jdk-21-current"
export ANDROID_HOME="$HOME/Android/Sdk"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
npm test
npm run apk
```

The debug APK is at `android/app/build/outputs/apk/debug/app-debug.apk`.
`npm run aab` builds the release app bundle. Both scripts call Gradle directly.
Node package installation is only needed for the old web tests and icon tools.
`npm test` runs JVM unit tests. To run the Android UI tests, start an emulator
or connect a test device and run `cd android && ./gradlew connectedDebugAndroidTest`.

## Set up in-app feedback

1. Sign in to the [Firebase console](https://console.firebase.google.com/) with
   the Google account that should own the feedback. Create a project and keep
   it on the **Spark** plan. Google Analytics is optional.
2. In **Authentication**, open **Sign-in method** and enable **Anonymous**.
   The SDK signs in anonymously when feedback is submitted.
3. In **Firestore Database**, create the default database in **Standard edition**
   and choose its location. Start in **production mode**. In the **Rules** tab,
   paste [firestore.rules](firestore.rules) and publish it. These rules validate
   feedback and allow the app to create documents. Read feedback in the Firebase
   console using your project account.
4. Add an Android app to the Firebase project using package
   `com.kalamcs.sudoku`. Download `google-services.json` into
   `android/app/google-services.json`. The repository ignores that file so each
   build can use the project its owner chooses.
5. Run `npm run apk`. Install that APK and submit a test message from
   **Menu → Send Feedback**. Check the **Data** tab in Firestore for the
   `feedback` collection.

For a file downloaded to Windows, copy it from the project root in WSL:

```bash
cp /mnt/c/Users/kalam/Downloads/google-services.json android/app/google-services.json
```

Builds without that file can play Sudoku and open the feedback form.
Submissions require Firebase configuration and connectivity. Firestore stores
the feedback type, message, app version, and submission time. It includes a
contact email only when the player opts in.

The [Firestore Standard free quota](https://firebase.google.com/docs/firestore/quotas)
includes 20,000 writes and 50,000 reads per day and 1 GiB of storage. A feedback
submission uses one write.

To test the rules locally with the Firebase CLI and Node.js 22:

```bash
firebase emulators:exec --only auth,firestore --project demo-sudoku-feedback \
  'node --test tests/firestore-rules.test.mjs'
```

## Install on a phone

For wireless debugging, use the pairing port from the phone's **Pair device
with pairing code** dialog and the connection port from its **Wireless
debugging** screen.

```bash
adb pair <phone-ip>:<pair-port>
adb connect <phone-ip>:<connection-port>
adb devices -l
adb -s <device-serial> install -r android/app/build/outputs/apk/debug/app-debug.apk
```

You can also copy the APK to your phone and open it from the file manager. To
carry over progress from the prior web-based app, install as an update using
the same signing key. On first launch, the app imports its saved puzzle and
stats from the old local storage into Android SharedPreferences.

## Source layout

| Path | Purpose |
|---|---|
| `android/app/src/main/kotlin/com/kalamcs/sudoku/` | Native activity, board, illustrated guide, puzzle engine, game state, and storage |
| `android/app/src/test/kotlin/com/kalamcs/sudoku/` | Puzzle, hint, saved-data, and board viewport tests |
| `www/`, `src/native.js`, `test/` | Prior web implementation and its tests, available via `npm run test:web` |
| `assets/` and `scripts/generate-icons.mjs` | Launcher icon source and regeneration via `npm run icons` |

The Android UI uses native widgets throughout and draws the game and guide
boards with Canvas. The build does not package the web app. An offscreen
WebView is used only to import local storage from a previous installation.

## Release to Google Play

1. Keep the upload keystore outside the repository. Configure release signing
   in `android/app/build.gradle` with credentials from an ignored local file.
2. Keep the existing application ID `com.kalamcs.sudoku` and increment the
   version code for each release.
3. Run `npm run aab` and upload
   `android/app/build/outputs/bundle/release/app-release.aab` to Play Console.

Back up the upload key and its credentials. Keep keystores, signing credentials,
and `android/local.properties` out of source control.
