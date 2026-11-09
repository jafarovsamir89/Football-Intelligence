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
- GitHub Actions run 42704812973 passed all steps on d4b4c38, including unit tests, APK build, and upload of the 15,899,210-byte football-intelligence-debug-apk artifact.
- That first run reported deprecation warnings for Node 20 action runtimes and setup-java v4. The workflow is being upgraded to current Node 24 action releases; a verification run is pending.

### Test correction

The first run caught an incorrect test fixture: the supposed underround Shin example actually had an implied-probability sum above 1. The fixture was corrected to decimal odds 3.0 / 4.0 / 5.0, and the full test/build command then passed.

### Next stage

1. Confirm the refreshed GitHub Actions runtime also passes and uploads the APK.
2. Extract persistence/domain code and add database regression tests.
3. Add import/export with explicit source, market, and UTC timestamps.
4. Add timestamped prediction logging and walk-forward tooling only when a legal historical dataset can be imported.
