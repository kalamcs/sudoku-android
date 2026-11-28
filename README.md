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
- In **Settings → Entry order**, switch from **Cell first** to **Digit first** to
  pick a keypad digit before tapping cells. The digit stays selected as you
  enter it in different cells. Tap it again to clear the selection. Notes mode
  works with either entry order. Each key shows the number of placements left
  and displays a check mark when all nine are in place.
- Pinch the board to zoom up to 3×. Drag with one finger to move around the
  enlarged board, then pinch inward or tap **Reset zoom** to see the full board.
- Toggle **Notes: Off** or **Notes: On** to pencil in candidates.
  **Auto-fill Candidates** alongside the keypad lists the digits
  allowed by the current row, column, and box.
  If you correct or erase your latest entry before another board edit, the pencil
  marks it removed are restored. Undo and redo track entries and notes, including
  when you close the app and continue the saved puzzle later.
- **Hint** counts incorrect entries first and offers to highlight them. On a
  correct board, it works through the techniques in How to Play. A hint can
  explain a placement or show which candidates to cross off. Hint skips
  candidates you already crossed off, including removals in saved undo history.
  After you cross off the last candidate in a hint, the next request advances.
  If you open a technique from a hint, Back reopens the hint. Proof by
  contradiction is the last resort. Open the reasoning under a step to see the
  supporting constraints.
- **Check Progress** reports incorrect entries and offers to highlight them. **Restart Puzzle**
  opens the reset confirmation. The timer pauses in the game menu and runs
  while a hint or an error result is open.
- **Pause** and Android Back save the puzzle and return to Home. Tap
  **Continue Puzzle** to pick up where you left off. Home hides the card for an
  untouched puzzle played for 10 seconds or less. After waking the phone or
  returning to the app, an open puzzle resumes automatically.
  The home stats table shows solved puzzles, best times and dates, and average
  times by difficulty. In landscape, Home puts Statistics beside the puzzle
  actions when there is room. The timer also stops when you leave the app or open the
  guide from a game.
- **Screen timeout during a game** follows Android by default. Settings can
  keep the display awake for at least two or three minutes after an interaction,
  or for as long as the puzzle is open in the foreground.
- In **Settings → Number Keypad Layout**, choose **Auto**, **3×3**, or **1×9**.
  In landscape the board takes the largest square that leaves room for the
  controls, with the shortcuts below the numbers. Auto uses 1×9 when 44 dp keys
  fit beside the board and the controls fit without scrolling. Otherwise it
  uses 3×3 if the controls fit. If 3×3 would scroll, Auto tries a 1×9 row with keys
  at least 36 dp wide. If neither layout fits, the 3×3 controls can scroll.
  In portrait, Auto uses 1×9 when it gives the board meaningfully more space
  and can keep 44 dp keys, or when 3×3 would scroll and the row fits. A manual
  1×9 choice uses keys at least 36 dp wide and falls back to 3×3 when the
  available width is insufficient.
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
column, or box, so you can remove those digits from the other cells. A **hidden
pair** occurs when two digits can go only in the same two cells, so you can
remove other candidates from those cells. Both patterns extend to triples and
quadruples. A **proof by contradiction** rules out a candidate by showing that
its forced consequences break a Sudoku rule.
[HoDoKu’s naked subsets guide](https://hodoku.sourceforge.net/en/tech_naked.php),
[HoDoKu’s hidden subsets guide](https://hodoku.sourceforge.net/en/tech_hidden.php)

### Expert solving techniques

**X-Wing** and **Swordfish** use aligned candidates for one digit across two or
three rows or columns, respectively. **XY-Wing** uses three cells with two
candidates each to eliminate a candidate from cells that see both pincers.
[HoDoKu’s fish guide](https://hodoku.sourceforge.net/en/tech_fishb.php),
[HoDoKu’s wings guide](https://hodoku.sourceforge.net/en/tech_wings.php)

Open **How to Play** from Home or the hamburger menu. **Rules** shows
row, column, and box diagrams. **Strategies** groups basic, advanced, and expert methods
and links to their worked examples.

## Puzzle difficulty

Difficulty follows the Sudoku Explainer (SE) rating, which records the hardest
technique used along the rater's solve path. All puzzles have exactly one
solution. Existing saves keep their original difficulty.

| Level | SE rating | How the app chooses puzzles |
|---|---:|---|
| Easy | Up to 2.5 | Generates a fresh puzzle on the phone and verifies that naked and hidden singles can solve it. Those techniques top out at 2.3. |
| Medium | 2.6–4.4 | Generates a variant from one of 492 bundled templates. |
| Hard | 4.5–8.5 | Generates a variant from one of 354 bundled templates. |
| Extreme | 8.6 and above | Generates a variant from one of 324 bundled templates. |

The app reads one randomly selected record from the difficulty's file in the
APK. The record contains the template's clues and solution in four bits per
cell and three transformations to choose from. The phone applies one of those
transformations to both grids. Sukaku Explainer rated every resulting board
in its classic configuration during preparation, and the app's Kotlin
solver checks each one for a unique solution in the test suite.

The imported templates come from Grant McLean's
[public-domain Sudoku Exchange Puzzle Bank](https://github.com/grantm/sudoku-exchange-puzzle-bank),
which was generated using QQWing. The rater is based on Nicolas Juillerat's
Sudoku Explainer. Credit to Suuudokuuu for its
[SE difficulty guide](https://www.suuudokuuu.com/guides/sudoku-difficulty-rating)
and [hardest-puzzle reference](https://www.suuudokuuu.com/hardest-sudoku-puzzles).
The [puzzle bank notes](android/app/src/main/resources/puzzles/SOURCES.md)
record the pinned sources and how to reproduce the bank.

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
./android/gradlew -p android :app:testDebugUnitTest
./android/gradlew -p android :app:assembleDebug
```

The debug APK is at `android/app/build/outputs/apk/debug/app-debug.apk`.
To run Android UI tests, start an emulator or connect a test device and run
`./android/gradlew -p android :app:connectedDebugAndroidTest`.
Android builds and tests use Gradle directly and require no Node dependencies.

To regenerate the launcher artwork, install Python 3, `librsvg2-bin`, and
`fonts-dejavu-core` on WSL. Run `python3 scripts/generate-icons.py` from the
project root. The script uses the source SVGs in `assets/` to regenerate the
legacy square, round, and adaptive foreground PNGs at each Android density,
plus `assets/icon.png`. Pass `--output-root <directory>` to render a preview
without changing the checked-in PNGs.

Release builds enable R8 optimization and resource shrinking. Build and measure
the unsigned release APK from the project root:

```bash
./android/gradlew -p android :app:assembleRelease
stat -c '%s bytes' android/app/build/outputs/apk/release/app-release-unsigned.apk
```

For an emulator smoke test, sign a copy with the local Android debug key. Build
the debug APK first if that key does not exist yet. Use a test emulator where
Sudoku is absent or already signed with that debug key. Use the serial shown by
`adb devices -l` in place of `emulator-5554` if it differs:

```bash
smoke_dir=$(mktemp -d)
"$ANDROID_HOME/build-tools/36.0.0/apksigner" sign \
  --ks "$HOME/.android/debug.keystore" --ks-key-alias androiddebugkey \
  --ks-pass pass:android --key-pass pass:android \
  --out "$smoke_dir/app-release-smoke.apk" \
  android/app/build/outputs/apk/release/app-release-unsigned.apk
adb devices -l
adb -s emulator-5554 install -r "$smoke_dir/app-release-smoke.apk"
```

Start a puzzle, enter a digit, pause, and reopen the app. Continue the puzzle and
check that the entry and undo history survived. Open **Send Feedback** and check
the form validation. Test connectivity handling with Wi-Fi and mobile data off
on the emulator. Before sending a valid draft, confirm that
`adb -s emulator-5554 shell dumpsys connectivity` reports no active default
network. Use the local Firestore rules suite for submission checks.

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
5. Run `./android/gradlew -p android :app:assembleDebug`. Install that APK and
   submit a test message from **Menu → Send Feedback**. Check the **Data** tab
   in Firestore for the `feedback` collection.

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

To test the rules locally, use Node.js 22 and a Firebase CLI installed outside
the checkout. The test uses Node's built-in modules and a local Firebase demo
project. It requires no repository `node_modules`:

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
| `firebase.json`, `firestore.rules`, `tests/firestore-rules.test.mjs` | Feedback configuration and Firestore rules tests |
| `assets/` and `scripts/generate-icons.py` | Launcher icon source and regeneration with Python and librsvg |

The Android UI uses native widgets throughout and draws the game and guide
boards with Canvas. An offscreen WebView imports local storage from a previous
web-based installation.

## Release to Google Play

1. Keep the upload keystore outside the repository. Configure release signing
   in `android/app/build.gradle` with credentials from an ignored local file.
2. Keep the existing application ID `com.kalamcs.sudoku` and increment the
   version code for each release.
3. Run `./android/gradlew -p android :app:bundleRelease` and upload
   `android/app/build/outputs/bundle/release/app-release.aab` to Play Console.

Back up the upload key and its credentials. Keep keystores, signing credentials,
and `android/local.properties` out of source control.

## License

Copyright (c) 2026 Kalam Shah. Sudoku Android is source available under the
[PolyForm Noncommercial License 1.0.0](LICENSE). See [NOTICE](NOTICE) for the
project's required notice and third-party licensing information.
