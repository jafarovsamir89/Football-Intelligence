# Development log

## 2026-11-09 — repository audit and analytics foundation

### Work completed

- Cloned and inspected the existing GitHub repository in the workspace; retained the existing native Android project.
- Read README, roadmap, Gradle files, main activity, workflow, commit history, and recent GitHub Actions runs.
- Recorded the baseline audit in AUDIT.md; documented current sources, architecture, methods, decisions, testing gaps, and limitations.
- Added Android-independent market de-vigging (basic, Power, Shin), Poisson/Dixon–Coles probability primitives, and Brier/log-loss/EV metrics.
- Added unit tests for validation, normalized output, model output, and score calculations.
- Added a Gradle 8.11.1 wrapper with the distribution checksum pinned and ignored generated Gradle/Android build files.
- Updated GitHub Actions to run unit tests and assemble the APK through the checked-in wrapper.
- Changed SQLite refresh writes to guarded updates/inserts to preserve prior settled results when an incomplete fixture response arrives.
- Tightened quote freshness to exclude future timestamps and clarified cross-book price semantics.
- Normalized provider timestamps to UTC when the source omits a zone and preserved explicit offsets.
- Fixed ticket history and ROI lookup so tickets outside the 250-match screen list still resolve against their stored match result.
- Kept market display language to “average of recorded lines” because these user-entered prices are not checked against bookmakers.

### Findings

- Audited HEAD b09f96d had 328 lines of app logic in MainActivity.kt, no automated tests, and no Gradle wrapper.
- Follow-up review found that ROI lookup reused the 250-row screen query; the ticket history now queries every match referenced by a ticket.
- GitHub Actions run 42698704826 succeeded and uploaded a debug APK. Four prior runs failed before compilation because Android SDK setup or sdkmanager was unavailable on the runner.
- The local environment had JDK 17 and Android SDK but no global gradle executable; the wrapper resolved this reproducibility gap.
- OpenLigaDB is the existing unauthenticated schedule/results source. No no-key live odds service with verified broad soccer coverage was confirmed. Manual import remains the fallback.

### Test / CI status

- Verification: ./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug passed locally on 2026-11-09. Eleven unit tests passed; the APK was built at app/build/outputs/apk/debug/app-debug.apk.
- GitHub Actions run 42704812973 passed all steps on d4b4c38, including unit tests, APK build, and artifact upload. It reported deprecation warnings for Node 20 action runtimes and setup-java v4.
- Updated workflow runtimes and runner in commit 3372f23: checkout v7, setup-java v6, setup-gradle v6 with basic cache, upload-artifact v7, and Ubuntu 24.04.
- GitHub Actions run 42705882847 passed all steps on 3372f23. It passed unit tests, built the APK, and uploaded the 15,899,212-byte football-intelligence-debug-apk artifact. The Node 20/setup-java v4 warnings are gone; CI now reports only that Gradle 8.11.1 is out of date.
- Android's current compatibility table lists Gradle 8.9 as the minimum for the app's AGP 8.7 line. Keep the passing 8.11.1 wrapper for this milestone; evaluate a paired AGP/Gradle update separately.

### Test correction

The first run caught an incorrect test fixture: the supposed underround Shin example actually had an implied-probability sum above 1. The fixture was corrected to decimal odds 3.0 / 4.0 / 5.0, and the full test/build command then passed.

### Next stage

1. Extract persistence/domain code and add database regression tests.
2. Add import/export with explicit source, market, and UTC timestamps.
3. Add timestamped prediction logging and walk-forward tooling only when a legal historical dataset can be imported.
4. Review a paired AGP/Gradle version update with regression build verification.

## 2026-11-09 — experimental Elo baseline (CI passed)

### Work completed

- Added a pure Kotlin Elo engine. It trains on completed local results whose kickoff is strictly earlier than the forecast match, uses same-league history from SQLite, and avoids using any result at or after the forecast cutoff.
- Added a smoothed league draw prior and an explicit sample gate: at least 10 prior league matches and 3 appearances for each team. The UI reports the local source and sample counts and labels the estimate experimental, uncalibrated, and not a recommendation.
- Added `AGENTS.md` with the enduring project mission, constraints, engineering practices, and the exact continuation point; this log remains the detailed session/milestone record.
- Updated the README, architecture, roadmap, limitations, decisions, changelog, and test guide to describe the Elo baseline and its limits.

### Model defaults and test correction

- Defaults: rating 1500, K=20, home advantage +65 Elo points, and 25% draw prior weighted as 4 matches. These are transparent starting values, not fitted or validated values.
- The first run compiled but two assertions encoded incorrect expectations: home advantage was tested as an away advantage after a rating update, and the empty-history output was expected to be symmetric despite the nonzero home advantage. The fixtures were corrected to test the model's actual settings; this was a test correction, not a model parameter tuning exercise.

### Verification and next actions

- `./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug` passed locally on 2026-11-09; 18 unit tests completed and the debug APK was built.
- Committed and pushed as `63eedc1` (`Add leakage-safe experimental Elo baseline`). GitHub Actions run [42708351125](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/42708351125) passed tests and APK build and uploaded `football-intelligence-debug-apk`. The run includes the existing Gradle 8.11.1 maintenance annotation.
- Next: implement timestamp-safe walk-forward evaluation and a licensed historical-results import path; explicitly track date-only source data so it cannot leak same-day results into pre-match forecasts.

## 2026-11-09 — manual historical CSV import (local verification passed; CI pending)

### Work completed

- Added a user-triggered Android document picker for Football-Data-style CSV and a 20 MiB file limit. Files are selected locally; the app does not crawl or schedule downloads.
- Added a tolerant CSV parser for `Div`, `Date`, `HomeTeam`, `AwayTeam`, `FTHG`, and `FTAG`, with UTF-8/Windows-1252 decoding, quoted/semicolon input support, date parsing, row rejection details, and duplicate suppression. Odds fields are ignored.
- Added SQLite version 2 tables for imported results and source-file/import-time records. The v1→v2 upgrade adds tables/indexes without replacing matches, odds, picks, or tickets.
- Stored source-date rows with a conservative availability boundary at UTC midnight two days after the listed date. Imported data remains separate from OpenLigaDB IDs and is not yet consumed by Elo/backtest screens.
- Rechecked Football-Data.co.uk usage policy: manual downloads or light automation up to twice weekly for private personal/academic use, with restrictions on commercial and heavy automated data-training projects. The app uses only user-selected files and does not redistribute the data. Verified Android's Storage Access Framework file-picker guidance in official documentation.

### Verification and gaps

- First build attempt exposed a malformed Kotlin test string around an escaped CSV quote; corrected the test fixture. The subsequent `./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug` passed locally: 26 tests, APK build successful.
- No dedicated Android instrumentation test currently exercises the v1→v2 SQLite migration or UI file-picker flow. Imported score rows are stored but have not yet been connected to walk-forward metrics; this is the immediate next implementation stage.
- Next: finish diff/docs review; commit/push; verify GitHub Actions for this importer; then add a chronological evaluator that consumes imported rows using `available_at`, testing simultaneous/date-only rows without future leakage.
