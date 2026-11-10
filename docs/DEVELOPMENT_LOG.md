# Development log

## 2026-11-10 — SQLite concurrency diagnosis and WAL fix (local API 34 passed; CI pending)

### Finding

- After update-installing CI commit `8d5ab00` (Actions [42889711265](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/42889711265)), I reopened the API 34 app to verify the next public odds capture. The app's prior successful WebView snapshot was from 07:21 UTC and had expired. At 20:00 UTC, while WorkManager was importing current football data, both new captures were saved as `ERROR: database is locked`.
- Android logcat showed `SQLiteConnectionPool` with one active connection and foreground reads waiting 30–60 seconds. The Compose startup relink duplicated work already performed by the sync Worker after provider import, adding another large database scan during the same window.
- The separate instrumentation emulator 5558 has no active default network (Wi-Fi radio initially off; `active default network: none`; public ping unreachable). Its `chromewebdata/` WebView result is therefore an offline harness condition, not evidence of a bookmaker blocking or redirecting the page. The emulator radio was restored to its original Wi-Fi-off state.

### Change and local verification

- Enabled SQLite WAL in `Store` so foreground reads can continue against the last committed snapshot during provider writes. Removed the redundant foreground startup relink; the Worker relinks unmatched quotes after each provider sync segment.
- Added a randomized-database Android instrumentation regression that holds one Store's write transaction open and verifies a second Store can read the committed snapshot within three seconds. Existing production schema and user rows are not migrated or deleted.
- Added safe provider-route diagnostics that report only public host/path (never query parameters) when a page URL is outside its expected route.
- Passed `./gradlew --no-daemon :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:assembleDebug`: 88 JVM tests, Android test compilation, APK assembly. `ANDROID_SERIAL=emulator-5558 ./gradlew --no-daemon :app:connectedDebugAndroidTest` passed all 11 API 34 instrumentation tests with zero failures/errors/skips. `git diff --check` passed.
- The code/docs changes are not yet committed or CI-verified. The latest successful public-page capture remains the 07:21 UTC snapshot: Misli 10 rows / 0 exact links; TOPAZ 25 / 7 exact links. Those prices are stale and no new live price is claimed.

### Source rights review and next action

- Rechecked official [Misli robots.txt](https://www.misli.az/robots.txt): it permits `/` for crawlers and disallows account, shared-coupon and live-detail paths. That is not an odds-reuse license. Read the current [eTopaz terms](https://etopaz.az/terms-conditions): they frame a BAL LINE subscriber/wagering agreement and limit subscriber-account use, but contain no express automated-reuse permission for anonymous public odds. Do not infer either permission or prohibition beyond the text.
- Read the requested [reference scraper](https://github.com/aliabbasov99/ai-football-prediction/blob/main/backend/scripts/scrape_misli_all.py): it uses `undetected_chromedriver`, stealth arguments, page scrolling, BeautifulSoup selectors, explicit aliases and fuzzy `SequenceMatcher` thresholds. No code or evasion mechanism was copied; this app keeps normal public WebView rendering and exact reviewed aliases.
- Next: commit/push WAL and diagnostics; wait for green Actions, download and verify its stable-signed artifact, update-install on the connected primary API 34 emulator without clearing its 39k-match DB, and repeat first-open sync plus visible bookmaker capture after WorkManager finishes.

## 2026-11-10 — isolate Worker failure instrumentation from network (CI and API 34 update-install passed)

- Commit `8d5ab00` replaced the loopback-proxy failure test with an injected `ProviderHttpClient` that records OpenFootball/OpenLigaDB catalog calls and throws without a socket. The previous documentation rerun [42778440884](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/42778440884) had received `PARTIAL` despite airplane mode and a loopback proxy, so the earlier network-isolation claim was withdrawn.
- Actions [42889711265](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/42889711265) passed 88 JVM tests, 10 API 34 instrumentation tests, APK build, stable signing and upload. Signer SHA-256: `da1506fd2e896c67d3ceafc8b3ab7f33824e759e092adf938fdf11c52fa8d935`.
- Update-install on the existing API 34 emulator used `adb install -r` only. The database was byte-identical before/after (SHA-256 `d0ff6e9616e5a4db71592fa3a8cd0536e8ae0ffaa23094568caebd94f3dcd618`). On the subsequent connected app open, the result count reached 30,114/39,181 and the old foreground capture exposed a database contention issue documented in the next entry.

## 2026-11-10 — make Worker failure instrumentation network-proof (local API 34 passed; CI pending)

### Finding and correction

- The documentation-only CI rerun [42778440884](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/42778440884) failed in `workerReturnsRetryWhenBothProviderCatalogsFail`: OpenFootball returned `PARTIAL`, not `ERROR`. This proves the previous combination of airplane mode, `activeNetwork == null`, and a default `ProxySelector` did not guarantee that no public HTTP response reached the worker. The prior claim that this test could not reach live sources was too strong; the CI failure is recorded rather than hidden.
- Replaced the loopback network proxy with an injected `ProviderHttpClient` on the real `AutomaticFootballSyncWorker`. The fake records the OpenFootball and OpenLigaDB catalog URLs and throws a simulated I/O error. It has no socket or HTTP implementation, so this worker test can no longer contact either public host. The worker still exercises catalog error handling, persisted provider statuses, retry selection and sync-lock release against a random database.

### Verification and next action

- `./gradlew --no-daemon :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:assembleDebug` passed: 88 JVM tests, androidTest compilation and APK assembly.
- `ANDROID_SERIAL=emulator-5558 ./gradlew --no-daemon :app:connectedDebugAndroidTest` passed all 10 API 34 instrumentation tests, 0 failures/errors/skips. The Worker test reached both fake catalog URL routes; the app user database and radio state were untouched by that test.
- The previous backoff test itself passed locally and in CI run [42777428309](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/42777428309), but the subsequent Actions rerun failed at the old proxy-based provider failure test. This deterministic network-free replacement passes locally; commit/push and a complete CI rerun remain.

## 2026-11-10 — WorkManager retry/backoff rescheduling (CI and API 34 update-install passed)

### Change

- Added an instrumentation test that enqueues a unique real WorkManager request with the production 30-second exponential backoff policy. A test-only Worker returns `retry()` once and then succeeds; it does not use the network or the football database.
- Verified that WorkManager persisted the first attempt as `ENQUEUED`, kept it queued for at least 20 seconds of its 30-second backoff, then executed it again and reached `SUCCEEDED` on attempt 2. The random preference key and work request are cleaned up in `finally`.
- Extended the offline-radio transition wait in both scheduler and Worker instrumentation tests from 10 to 30 seconds. A documentation-only push's Actions run [42774937884](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/42774937884) exposed a transient CI emulator timeout at the 10-second airplane-mode/Wi-Fi check; no production code or live network request failed.

### Verification and next action

- `./gradlew --no-daemon :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:assembleDebug` passed; 88 JVM test results remain green.
- `ANDROID_SERIAL=emulator-5558 ./gradlew --no-daemon :app:connectedDebugAndroidTest` passed all 10 API 34 instrumentation tests, 0 failures/errors/skips. The backoff test also passed by itself. Radio-sensitive tests restore emulator settings; this run did not touch the primary app database.
- Commit `ee438ac` passed Actions [42777428309](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/42777428309): 88 JVM tests, all 10 API 34 instrumentation tests, APK build, stable signing and artifact upload. The 10 Android tests completed successfully in the CI runner; the APK certificate SHA-256 matched the established key `da1506fd2e896c67d3ceafc8b3ab7f33824e759e092adf938fdf11c52fa8d935`.
- Update-installed the CI APK over the existing API 34 app with `adb install -r`; no uninstall or data clear. Before and after: schema v11, 39,181 matches, 184 competitions, 385 bookmaker snapshots, 71 accumulators/402 legs, and 62 forecast snapshots/0 settlements. OpenFootball and OpenLigaDB remained `OK`; MainActivity resumed without a fatal log.
- The preceding docs-only Actions run [42774937884](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/42774937884) failed at an unrelated 10-second radio-state wait in the offline scheduler test. Increasing the test transition allowance to 30 seconds resolved it; the combined revision passed Actions. Next: add focused evidence for provider pacing and conditional HTTP cache responses; the current retry/backoff scheduling gap is closed.

## 2026-11-10 — Worker provider-failure retry routing (CI and API 34 update-install passed)

### Change

- The initial version of the Android instrumentation test ran the real `AutomaticFootballSyncWorker` with airplane mode/Wi-Fi disabled and a local rejecting proxy. A later CI rerun showed this setup did not guarantee network isolation; see the newer entry above. The current test injects a client that cannot make HTTP requests.
- The worker runs with a random isolated SQLite filename and test context. The test verifies requests reached both `api.github.com` and `api.openligadb.de`, the worker returns WorkManager `Result.retry()`, both provider states report `ERROR` with no successful timestamp, and the sync lock is released. The proxy selector and temporary database are restored/deleted in `finally`.
- This exercises the production decision from actual provider failures through the Worker's retry result. WorkManager's separate delayed scheduling/resumption was added and verified in the following development-log entry.

### Verification and next action

- `./gradlew --no-daemon :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:assembleDebug` passed: 88 JVM tests, instrumentation compilation and APK assembly.
- `ANDROID_SERIAL=emulator-5558 ./gradlew --no-daemon :app:connectedDebugAndroidTest` passed all 9 API 34 instrumentation tests, 0 failures/errors/skips; the aggregate report completed in 13.187s. The separate Android test emulator returned to its original airplane/Wi-Fi settings, and the primary database was not used.
- Commit `6073cfe` passed Actions [42773538883](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/42773538883): 88 JVM tests, all 9 API 34 instrumentation tests, APK build, stable signing and artifact upload. The APK's certificate SHA-256 matched the established key: `da1506fd2e896c67d3ceafc8b3ab7f33824e759e092adf938fdf11c52fa8d935`.
- Update-installed the CI APK on the primary API 34 emulator using `adb install -r` only; no uninstall or data clear. Before and after: schema v11, 39,181 matches, 184 competitions, 350 bookmaker snapshots, 69 accumulators/384 legs, and 62 forecast snapshots/0 settlements. Both automatic source statuses remained `OK`; MainActivity resumed without a fatal log. The first proxy-only CI attempt [42771702073](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/42771702073) was cancelled because it did not guarantee isolation on a networked emulator; the network-disabled revision passed.
- Next: directly verify WorkManager's delayed retry/backoff rescheduling with a deterministic isolated test. That scheduling path is distinct from the now-tested real Worker's failure-to-`Result.retry()` decision.

## 2026-11-10 — WorkManager offline gating instrumentation (CI and API 34 update-install passed)

### Change

- Added an integration test that turns airplane mode on and disables Wi-Fi through Android's supported system commands, enqueues the same zero-delay `CONNECTED` sync used by app-open scheduling, and waits for both Wi-Fi to be off and `ConnectivityManager.activeNetwork` to be null before checking the queue. Both immediate and periodic work must stay `ENQUEUED` with zero attempts and no progress.
- The test always cancels its unique work and restores the original airplane-mode and Wi-Fi values in `finally`; it runs only on the isolated instrumentation emulator.

### Verification and next action

- `./gradlew --no-daemon :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:assembleDebug` passed: 88 JVM tests, instrumentation compilation and APK assembly.
- `ANDROID_SERIAL=emulator-5558 ./gradlew --no-daemon :app:connectedDebugAndroidTest` passed all 8 Android instrumentation tests on API 34. The test emulator returned to airplane mode off; the primary API 34 database was not touched by instrumentation.
- Commit `8d9da3c` passed Actions [42769411532](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/42769411532): 88 JVM tests, 8 API 34 instrumentation tests, APK build, stable signing and upload. The artifact signer SHA-256 matched the established key. Update-installed on the primary API 34 app without uninstall/data clear; schema v11, 39,181 matches, 350 quote snapshots, 69 accumulators/384 legs, and 62 forecasts/0 settlements were retained. MainActivity resumed without a fatal log.

## 2026-11-10 — cross-provider duplicate provenance and score retention (CI and API 34 update-install passed)

### Change

- Extended natural-fixture merge so a duplicate from another provider contributes its source name, URL and latest capture time even when it does not supply a better kickoff or new score.
- Preserve the already-settled score when a second provider has no result or reports a conflicting completed score. A valid incoming exact kickoff and result can still upgrade a date-only unresolved fixture without adding a second match row.
- Added two isolated SQLite instrumentation tests: completed-match provenance merge plus score conflict/partial-refresh retention; date-only-to-exact kickoff and final-score promotion.

### Verification and next action

- `./gradlew --no-daemon :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:assembleDebug` passed: 88 JVM tests, instrumentation compilation and APK assembly.
- `ANDROID_SERIAL=emulator-5558 ./gradlew --no-daemon :app:connectedDebugAndroidTest` passed all 7 instrumentation tests on the isolated API 34 emulator. Test databases are random, isolated, and deleted in `finally`; the primary API 34 user database was untouched.
- `git diff --check` passed. Commit `fab515a` passed Actions [42767934594](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/42767934594): 88 JVM tests, all 7 API 34 instrumentation tests, APK build, stable signing, and artifact upload.
- Verified CI signer SHA-256 `da1506fd2e896c67d3ceafc8b3ab7f33824e759e092adf938fdf11c52fa8d935`; update-installed the artifact over the existing API 34 app without uninstall or data clear. Before and after install: schema v11, 39,181 matches, 315 bookmaker snapshots, 69 accumulators/384 legs, 62 prospective forecasts/0 settlements. MainActivity resumed with no fatal log.
- Next: commit and CI-verify the real Worker failure-to-`Result.retry()` test; then consider direct WorkManager delayed retry/backoff rescheduling. The API 26 continuation check covers a separate path.

## 2026-11-10 — prospective forecast ledger and cutoff correction (CI and API 34 update-install passed)

### Change

- Added SQLite v11 `prospective_forecast_snapshots` and `prospective_forecast_settlements`. Snapshots are immutable per model version, fixture and UTC date; score/source corrections update only the independent settlement record. The UI now shows frozen probabilities, history size, result provenance, settled count, and prospective Brier / Log Loss / top-pick accuracy only after results are captured.
- Live Elo forecasts now use only historical matches whose kickoff and conservative availability precede the actual calculation cutoff. Conservative availability is the later of source availability and local capture time; un-timestamped results are excluded from live forecasts.
- Added v10→v11 migration coverage and regression tests for one-snapshot-per-day, delayed settlement, source provenance, score corrections and frozen probabilities.
- The new settlement test exposed an existing `Store.upsert` bug: Android SQLite binds `whereArgs` as text, while the completed-score correction condition compared the bound value with integer `1`. Changed the comparison to `'1'`; corrected provider scores now reach the result history.

### Verification and next action

- `./gradlew --no-daemon :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:assembleDebug` passed: 88 JVM tests, instrumentation compilation and APK assembly.
- `ANDROID_SERIAL=emulator-5558 ./gradlew --no-daemon :app:connectedDebugAndroidTest` passed all 5 instrumentation tests on the isolated API 34 emulator. SQLite fixture DBs use random names and are deleted in `finally`; the primary app database was not touched.
- `git diff --check` passed after final edits. Local result XML totals: 88 JVM + 5 Android instrumentation tests, 0 failures/errors/skips. Commit `22f88f5` passed Actions [42765702211](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/42765702211), including JVM tests, all 5 API 34 instrumentation tests, APK build, stable signing and artifact upload.
- Verified CI signer SHA-256 `da1506fd2e896c67d3ceafc8b3ab7f33824e759e092adf938fdf11c52fa8d935`; update-installed the artifact on the existing API 34 emulator with no uninstall/data clear. Schema upgraded v10→v11. Matches stayed 39,181 and bookmaker snapshots stayed 315; all 59 previous accumulator headers and 328 legs remained (current totals 69/384 because the app created 10 fresh snapshots/56 legs). No result loss.
- MainActivity resumed without a fatal log. The home screen retained `openfootball OK`/25,904 and `openligadb OK`/17,776. Foreground WebView showed 9 Misli lines (0 exact fixture links) and 25 TOPAZ lines (7 exact links). The coupon UI rendered the new forecast ledger: 62 frozen future snapshots, 0 settled; it correctly says the app is waiting for completed results. No prospective score is claimed.
- Next: continue with isolated cross-provider duplicate/provenance tests and ensure a second source cannot erase a settled result or create a duplicate fixture; then add actual WorkManager offline/retry integration coverage.

## 2026-11-10 — WorkManager queue instrumentation (CI and local API 34 passed)

### Change

- Extracted the freshness-driven enqueue path so production app-open scheduling and instrumentation exercise the same queue construction. The app still uses a zero-delay one-time request; instrumentation supplies a one-hour delay to inspect persistent WorkManager state without starting network sync.
- Added unit checks for the eight-hour stale boundary and an Android instrumentation test for the connected-network constraint, 24-hour periodic interval, queued/no-attempt state, unique KEEP behavior on repeated enqueue, and suppression of one-time work while provider data is fresh.

### Verification and next action

- `./gradlew --no-daemon :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:assembleDebug` passed.
- `ANDROID_SERIAL=emulator-5558 ./gradlew --no-daemon :app:connectedDebugAndroidTest` passed all 3 tests on the isolated API 34 AVD: 2 SQLite migration tests and 1 WorkManager scheduler test.
- Local result XML totals: 84 JVM tests + 3 Android instrumentation tests, 0 failures/errors/skips. The delayed request was cancelled in `finally`; no Worker or HTTP sync remained queued on the test emulator.
- Pushed as `92836d8`; Actions [42761781971](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/42761781971) passed the same 84 JVM + 3 API 34 instrumentation tests, built and signed the APK, and uploaded it.
- Next: add immutable versioned forecast snapshots with result settlement and a strictly forecast-time-filtered history. Actual offline gating/worker retry and cross-provider score retention remain further integration gaps.

## 2026-11-10 — isolated SQLite migration instrumentation (CI and API 34 update-install passed)

### Change

- Added an optional database-name parameter to `Store` (default remains `football_intelligence.db`) so Android instrumentation can exercise real SQLite migrations without deleting or modifying a user's application database.
- Added AndroidJUnitRunner instrumentation tests for the original v1 schema upgrading through v10 and the v9→v10 accumulator migration. The v1 test verifies match identity/score, availability metadata, quote/pick/ticket preservation, required new tables, and that an incomplete refresh cannot erase a settled result. The v9 test verifies old accumulator rows survive and get the `MODEL_RANKED` migration default.
- Added AndroidX Test runner/JUnit dependencies and configured the GitHub Actions workflow to launch an API 34 emulator for `connectedDebugAndroidTest`.

### Verification and next action

- `./gradlew --no-daemon :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:assembleDebug` passed locally; 83 JVM unit tests passed and instrumentation source compiled.
- `ANDROID_SERIAL=emulator-5558 ./gradlew --no-daemon :app:connectedDebugAndroidTest` ran on the separate, previously app-empty `memora_synthetic_capture` API 34 emulator: 2 tests passed. Test DB names contain random UUIDs and are deleted in `finally`; the pre-existing API 34 app database was not touched.
- Pushed as `fb65bcc`; GitHub Actions [42759573588](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/42759573588) passed JVM tests, both API 34 instrumentation tests, APK build, stable signing and artifact upload.
- Verified the downloaded APK certificate SHA-256 `da1506fd2e896c67d3ceafc8b3ab7f33824e759e092adf938fdf11c52fa8d935`. Update-install on the existing API 34 emulator succeeded without uninstall or clearing data; before/after SQLite counts matched: 39,181 matches, 197 bookmaker snapshots, 59 accumulators and 328 accumulator legs. MainActivity launched and remained resumed without a fatal exception.
- Next: add dedicated WorkManager enqueue/constraint integration coverage; the current pure retry-routing tests and API 26 continuation observation do not replace it. Then implement immutable versioned prospective forecast snapshots.

## 2026-11-10 — retrospective one-vs-rest calibration diagnostics (CI and API 34 runtime passed)

### Change

- The walk-forward already reported Brier and Log Loss but did not expose whether predicted outcome probabilities aligned with observed frequencies. Added `CalibrationDiagnostics`: equal-width probability bins for home/draw/away one-vs-rest channels, per-outcome expected calibration error (ECE), macro mean ECE, observed frequency, average predicted probability and sample size.
- Added four unit tests for reliability values, probability-1 final-bin inclusion, score-to-outcome mapping, empty input, and bin-count validation. The initial test fixture expected 0.80 and 0.90 values to share a 10% bin; the assertion fixture was corrected to two values in the same 80–90% bin. Final local run passed 83 tests and assembled the debug APK.
- The automatic and optional imported Backtest reports now show the three per-outcome ECE summaries and an expandable list of occupied bins. Copy explicitly says this describes the retrospective sample; it does not calibrate probabilities or establish future quality.
- Updated roadmap, architecture, limitations, testing and changelog. Corrected an old zero-touch status paragraph that described eTopaz as unavailable despite the later API 34 capture.

### Verification and next action

- `./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug` passed: 83 unit tests, APK assembled. `git diff --check` passed before commit.
- Committed/pushed as `6faac0b`; GitHub Actions [42757663129](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/42757663129) passed unit tests, APK build, stable signature and artifact upload. Downloaded the CI APK and confirmed certificate SHA-256 `da1506fd2e896c67d3ceafc8b3ab7f33824e759e092adf938fdf11c52fa8d935`.
- Update-installed that artifact over API 34 via `adb install -r`, without uninstall or data clear. Database counts before/after were identical: 39,181 matches, 197 bookmaker snapshots, 59 virtual accumulators.
- Opened Backtest on API 34 and waited for the local walk-forward to finish. The saved data produced 29,588 targets, 19,185 forecasts, 10,403 insufficient-history skips, Brier 0.6106, Log Loss 1.0235; macro one-vs-rest ECE 2.2%, with home 3.1%, draw 0.9%, and away 2.5%. The expanded view exposed actual occupied bins with mean probability, observed frequency and sample count. This evidence confirms screen/runtime execution, not prospective model quality. No app fatal exception appeared in the inspected log.
- Remaining work: add Android database/WorkManager instrumentation and immutable, versioned forecast snapshots for prospective settlement. The current reliability ECE remains retrospective only.

## 2026-11-10 — avoid exponential delays between first-run import segments

### Finding and change

- Continued passive observation of the API 26 Android 8 install. At 03:47 UTC the DB contained 32,680 matches; OpenFootball was `OK`/25,904, OpenLigaDB was `PARTIAL`/9,392, and 114 league-season states had been processed. The lease was free and Android had scheduled the next retry about nine minutes later. Network validation/ping succeeded.
- The delay was caused by returning `Result.retry()` for the expected eight-minute time budget. WorkManager applies exponential retry backoff, so routine progress was delayed as though the provider had failed.
- Split a normal time-budget continuation from actual provider/network errors. A time-budget hit now appends another connected one-time Worker with `ExistingWorkPolicy.APPEND_OR_REPLACE` and returns success for the current segment. This continues after healthy work or resets a failed/cancelled chain. Actual request/catalog errors and lock contention still use retry/backoff. Periodic refresh can also append its continuation to the same one-time chain.
- Added four tests for continuation, success and retry routing. Updated the architecture, data-source, zero-touch, roadmap, limitations, testing and agent instructions to distinguish normal continuation from failure retry.

### Verification and next actions

- `./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug` passed locally: 79 tests, 0 failures, 0 errors, 0 skipped; debug APK assembled. `git diff --check` passed.
- Committed and pushed as `e8c3a33` (`Queue bounded football sync continuations`). Actions run [42752522409](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/42752522409) passed unit tests, APK build, stable signing and artifact upload. The artifact signer SHA-256 is `da1506fd2e896c67d3ceafc8b3ab7f33824e759e092adf938fdf11c52fa8d935`. Local Gradle verification passed 79 tests, 0 failures/errors/skips and assembled the debug APK.
- Update-installed that same-key CI artifact on API 26. Schema v10 and the existing 32,680 matches survived without uninstall or data clear. The prior one-time WorkManager request (retry count 8) ran the new code, finished `SUCCESS` after its eight-minute boundary, and immediately started an appended child. The child had retry count 1 while active, then finished `SUCCESS`; no exponential pause or active sync lock remained. WorkManager's daily periodic request remains scheduled.
- Final API 26 provider state at 04:12 UTC: SQLite has 38,609 matches, 29,588 scored rows, 183 competitions and 10 season values. OpenFootball is `OK`/25,904; OpenLigaDB is `OK`/17,202; all 214 cached catalog league-season entries have state. Provider timestamps advanced to the final segment. No fatal app exception or SQLite helper leak appeared in the inspected log.
- API 26 UI hierarchy and screenshot showed the home source status plus cached fixtures. The analysis view generated an experimental Elo estimate for Italien—Frankreich (45.5% / 22.8% / 31.7%) from 88 earlier league matches; model text labels it uncalibrated and not a recommendation. The coupon view showed separate operators, `ERROR` / zero fresh lines, and a model-only draft with a marginal estimate; no odds were invented. The results view listed saved completed scores from OpenLigaDB and OpenFootball.
- Both API 26 ordinary bookmaker WebViews still returned no visible page text, with `ERROR` and zero quote snapshots. No hidden endpoint or evasion was used. API 34 offline cache remains verified from the preceding CI APK; no physical handset or dedicated Android instrumentation test was available.
- Reinstalled the same `e8c3a33` stable-signed artifact on API 34. Fresh visible-page capture at 04:21 UTC returned 9 Misli rows (0 exact local links) and 25 TOPAZ rows (7 exact local links); Portugal—Denmark / local match 85257 had 1.45/4.31/5.29 and England—Croatia had 1.21. The UI showed a separate two-leg TOPAZ virtual candidate with model marginals, 30-minute capture freshness and a Fréchet joint-probability interval. No Misli price-backed candidate was made because none mapped exactly.
- Repeated offline cold-launch validation on the `e8c3a33` API 34 APK after disabling Wi-Fi and mobile data; ping failed, while the UI retained cached match/source status and SQLite retained 39,181 matches, 72 Misli quote snapshots, 125 TOPAZ snapshots and 59 accumulator records. Network access was restored after the check. Latest capture data is timestamped and ages out of the fresh display; it must not be described as a currently open line.
- Remaining verification is limited to a physical device, dedicated WorkManager/SQLite instrumentation coverage, bookmaker page behavior on a current handset/WebView, and explicit bookmaker automated-reuse permission. Continue to show no odds when a public page does not produce usable visible data.

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

## 2026-11-09 — manual historical CSV import (CI passed)

### Work completed

- Added a user-triggered Android document picker for Football-Data-style CSV and a 20 MiB file limit. Files are selected locally; the app does not crawl or schedule downloads.
- Added a tolerant CSV parser for `Div`, `Date`, `HomeTeam`, `AwayTeam`, `FTHG`/`HG`, and `FTAG`/`AG`, with UTF-8/Windows-1252 decoding, quoted/semicolon input support, date parsing, row rejection details, and duplicate suppression. Odds fields are ignored.
- Added SQLite version 2 tables for imported results and source-file/import-time records. The v1→v2 upgrade adds tables/indexes without replacing matches, odds, picks, or tickets.
- Stored source-date rows with a conservative availability boundary at UTC midnight two days after the listed date. Imported data remains separate from OpenLigaDB IDs.
- Rechecked Football-Data.co.uk usage policy: manual downloads or light automation up to twice weekly for private personal/academic use, with restrictions on commercial and heavy automated data-training projects. The app uses only user-selected files and does not redistribute the data. Verified Android's Storage Access Framework file-picker guidance in official documentation.

### Verification and gaps

- First build attempt exposed a malformed Kotlin test string around an escaped CSV quote; corrected the test fixture. The subsequent `./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug` passed locally: 26 tests, APK build successful.
- No dedicated Android instrumentation test exercises the v1→v2 SQLite migration or UI file-picker flow. GitHub Actions run [42711478723](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/42711478723) passed the CSV revision, including unit tests, APK build, and artifact upload.

## 2026-11-09 — availability-aware Elo walk-forward (local verification passed; CI pending)

### Work completed

- Extended historical rows with a separate safe-availability timestamp. Elo training now filters by `availableAtMillis`, not only event kickoff.
- Elo updates all results sharing an availability timestamp as one batch from a common pre-batch rating snapshot, so row ordering cannot affect later ratings.
- Added `EloWalkForwardEngine`: groups targets by cutoff, fits one model per cutoff, excludes results whose availability is equal to/after that cutoff, suppresses predictions under the same 10-match/3-per-team gates, and computes mean multiclass Brier and Log Loss.
- Connected the imported-results table to a standalone «Бэктест» screen. It uses one date-level cutoff for every match on a source date and the conservative two-day `available_at` delay. It reports target/prediction/skipped counts and scores; it does not import odds or claim superiority to a market.
- Marked the backtest as retrospective. No actual source-file vintage or publication-time record exists; no real CSV dataset has been imported and evaluated in this workspace yet.

### Verification and next actions

- Current working tree passes `./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug`: 32 unit tests and APK build successful. The new walk-forward revision has not yet been committed or sent through GitHub Actions.
- Next: review all `available_at`/cutoff paths and UI state, update documentation, commit and push, inspect CI and artifact, then add tests for the actual SQLite migration/import transaction when an Android test runtime is available.

## 2026-11-09 — zero-touch multi-provider data and automated coupon drafts (local checks passed; CI/device validation in progress)

### Work completed

- Added an independent OpenFootball `football.json` loader that discovers all JSON competitions from five recent European seasons, caches catalogs/data in SQLite, uses conditional HTTP metadata, retries partial failures, and retains date-only fixture precision.
- Added dynamic OpenLigaDB football-catalog sync across five calendar years. It uses an official catalog, throttles each request to 1.25 seconds (48/minute), polls current group/last-change only for the current year, refreshes historical seasons weekly, and stops individual workers at an 8-minute budget for WorkManager retry continuation.
- Scheduled unique WorkManager periodic refresh every 24 hours and a one-time sync when the last successful provider check is at least eight hours stale. Network connectivity is required; Android background scheduling remains best-effort. A 9-minute SQLite lease prevents overlapping workers.
- Added provider status, file cache, per-league change state, source URL, capture time, safe availability time, exact/date-only kickoff, season and sync freshness. Preserved earlier SQLite data with migrations through version 6 and retained settled results when later feed rows are incomplete.
- Added conservative cross-feed dedupe requiring same event date, normalized names and home/away orientation. Ambiguous or fuzzy name matches stay separate. Exact kickoff and results can enrich a date-only row while URLs/source labels are retained.
- Removed manual odds and tipster entry from the main analysis flow. CSV is available only as an additional Settings action; the ordinary app path needs no upload or refresh button.
- Added automatic Elo-only coupon drafts for Misli and TOPAZ, stored once per operator/match before kickoff and automatically checked against local results. They report each model probability, never fabricate decimal odds, payout or joint coupon probability.
- Added separate recent-result history and source status with last-check/last-success information.
- Added explicit `MisliOddsProvider`/`TopazOddsProvider` classes that remain `UNAVAILABLE` and emit no snapshots until a source is verified. Local test covers this no-invention invariant.

### Azerbaijan bookmaker proof-of-concept outcome

- Ordinary public browser inspection showed a JavaScript-rendered Misli football page with visible odds, but the observed rows did not provide a verified exact upcoming fixture/date/team mapping. Misli's [robots.txt](https://www.misli.az/robots.txt) allows the root path and disallows account, shared-coupon, and live-detail path patterns. Robots rules are not a data license; permission to automate reuse remains unconfirmed.
- Ordinary public eTopaz UI showed 1X2 odds for visibly in-play events; those prices are rejected for prematch use. The [official terms](https://etopaz.az/terms-conditions) are a subscriber contract and do not state public automated reuse permission. Static extraction sees a JS-rendered shell. No technical block was concluded from this evidence.
- Read `aliabbasov99/ai-football-prediction` and its `scrape_misli_all.py` at a high level. It uses browser stealth/evasion and fuzzy string matching; none of those mechanisms or code were copied. Explicit aliases/strict event matching were retained as design ideas only.
- Result: neither operator is labeled LIVE; independent source cards explain why there is currently no prematch price. A standard Android WebView inspection against several exact future fixtures remains a next proof step if it can be done using allowed public content.

### Local verification

- The first build identified an unfinished old manual-form reference; it was removed from the main flow. A later run caught a malformed Kotlin regex escape; corrected to a raw string.
- `./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug` now passes locally with 44 unit tests and produces `app/build/outputs/apk/debug/app-debug.apk`.
- API 34 emulator `OBD_API_34` was started; fresh APK installation, first-open sync, SQLite result inspection and offline restart are being checked. No physical handset is connected.
- GitHub Actions for this revision is pending commit/push; no current-revision CI claim yet.

### Next concrete work

1. Finish clean emulator install and inspect first automatic network sync, generated result/fixture counts, logs, and offline launch. Record whether multi-year data fits one or multiple Worker attempts.
2. Add a debug-only, standard Android WebView proof screen to check public Misli and eTopaz rendering without stealth or hidden endpoints; verify exact future events and visible prices, or retain `UNAVAILABLE` with the observed reason.
3. Review/cover migration and dedupe behavior, fix issues, rerun local tests/build.
4. Commit/push this major milestone, inspect Actions and download the artifact, then continue with automatic-source walk-forward and remaining reliability/test gaps.

## 2026-11-09 — timestamped bookmaker coupons and source-quality filtering (local tests passed; CI pending)

### Implemented and verified locally

- Rechecked the full API 34 working installation after exact quote relinking. On the latest foreground pass, Misli returned 9 visible future 1X2 rows and TOPAZ returned 25. Misli had 0 exact local forecast links; 7 TOPAZ snapshots linked to local fixtures by reviewed aliases, home/away order, date and kickoff. The ordinary visible-page WebView path was used; no login, hidden endpoint, stealth, or control bypass was used.
- Verified a real TOPAZ fixture example in SQLite: Portugal—Denmark maps to local match 85257; the captured 1/X/2 was 1.45 / 4.31 / 5.29. The Elo home estimate was 59.1%; basic normalized market home probability was 62.1%. This is a timestamped observation, not a guarantee of a currently open line or model advantage.
- Added separate provider-specific priced accumulator drafts. Current capture produced TOPAZ sizes 2, 3 and 5 only; the matching rows had same-provider combined prices 1.75, 2.09 and 26.87. For the two-leg example, England—Croatia H at 1.21 plus Portugal—Denmark H at 1.45 yielded 1.75 combined and 9.9–50.8% Fréchet bounds. No independence assumption or profit promise is made. Misli had no sufficiently large set of linked priced matches, so only its model-ranked drafts were available.
- The UI places complete same-operator priced drafts before model-only drafts, shows each quote's capture time, and withholds combined odds unless all legs have a fresh, exact same-operator line. Old snapshots remain hidden from price selection after 30 minutes. Model-only drafts show marginals and joint-probability Fréchet bounds without synthesizing price or multiplying probabilities.
- Added durable SQLite v9/v10 virtual accumulator history and result settlement: pending/partial/win/loss, schedule changes over 30 minutes, invalid placeholder legs, Wilson interval, and same-operator virtual ROI only for fully settled coupons with a complete recorded line. No future coupon result has settled yet.
- Found 566 legacy database rows with placeholder identities such as `Empty Stands Dummy Team`. They remain stored for migration safety but are filtered from visible match/history lists, fixture matching, forecasts, picks, accumulators and walk-forward; new parser/upsert paths reject them. Existing invalid accumulator records are marked `INVALID` rather than counted as losses. Full post-filter walk-forward figures have not yet been recomputed.
- Tightened coupon construction itself to reject prices without a timestamp, older than 30 minutes, or timestamped in the future, even if a caller passes them. Repeated captures with identical quote values retain the independent quote snapshot history but do not create a duplicate coupon record; a changed price creates a new priced draft. Priced drafts are sorted ahead of model-only choices.

### Verification notes

- API 34 SQLite v10 migration was locally update-installed with the stable test key and preserved 39,181 match rows, 30,090 completed-score rows, saved odds and coupon history. Current database showed three TOPAZ price-backed coupon rows (2/3/5 legs) and separate Misli/TOPAZ model-ranked drafts.
- The update install correctly hid expired quotes as `STALE`, then automatic foreground WebViews refreshed without a button press. The later capture returned Misli 10 and TOPAZ 25; exact local links were 0/10 and 7/25. Portugal—Denmark match 85257 retained the real TOPAZ 1.45 / 4.31 / 5.29 line at 2026-11-10 02:36:36 UTC. The line has since passed its 30-minute TTL and is not a current quote.
- Re-ran the automatic walk-forward from the Backtest tab after filtering legacy placeholders: 29,588 targets, 19,185 forecasts, 10,403 skipped for insufficient history, mean Brier 0.6106, mean Log Loss 1.0235. Compared with old count, 502 scored placeholder rows were removed from targets; forecast count and scores happened to remain the same. Scores are still retrospective, uncalibrated, and not evidence of advantage.
- Inspected the Compose screens: home shows source freshness and sync time; the coupon tab now shows concise provider status/count/link totals and keeps the lengthy raw public odds list collapsed by default. Coupon drafts show provider, capture time, per-leg marginals and prices, and Fréchet bounds without an independence assumption.
- Regression coverage now exercises coupon sizes, provider isolation, quote timestamps, stale/future timestamp rejection, placeholder filtering, accumulator settlement and historical metrics. Full `./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug` passed: 75 tests, 0 failures/errors and APK build successful. Stable-key `adb install -r` over the API 34 installation preserved SQLite v10 and 39,181 match rows. GitHub Actions current revision remains pending.

### Next steps

1. Commit and push this substantial stage, wait for GitHub Actions, download the CI APK, verify its certificate and update-install over the existing app without losing SQLite. Record the CI run.
2. Add dedicated migration/WorkManager tests and API 26 verification; keep odds-reuse permission and device/timezone compatibility clearly open.

## 2026-11-09 — CI artifact verified; API 26 cold-start responsiveness follow-up (local build passed; CI pending)

- Commit `0bff2f6` was pushed. GitHub Actions run [42744967721](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/42744967721) passed unit tests, APK build, stable personal test signing and artifact upload. CI certificate SHA-256 matched the saved key fingerprint `da1506fd2e896c67d3ceafc8b3ab7f33824e759e092adf938fdf11c52fa8d935`.
- Downloaded and update-installed that artifact over API 34 without uninstall. SQLite v10 remained at 39,181 match rows and retained bookmaker snapshot and accumulator history (120 quote snapshots / 59 accumulator rows at inspection).
- Installed the same artifact on a clean API 26 emulator (Android 8.0). `MainActivity` stayed resumed with no fatal exception. The first automatic history sync was still running/retrying at 10,445 local match rows; this is a partial observation, not a completed API 26 sync. Both bookmaker visible-page collectors returned `ERROR` / no visible data on this old emulator WebView. Do not bypass it; this may differ on a handset with an updated System WebView. API 34 POC remains the only successful live odds page validation.
- The API 26 cold-open run logged skipped frames while 30k-history reads and forecast construction were synchronous in Compose. Moved league history reads to `Dispatchers.IO`, bulk forecast assembly and selected-match Elo fitting to `Dispatchers.Default`, and stopped reloading the full history for every WorkManager progress revision. API 26 relaunch after this local change had only a short initial JIT frame skip in the observed log; repeat a warm/cold comparison after CI.
- The coupon screen on API 34 still shows fresh-source counts and puts real same-book TOPAZ coupons first; it retains the source quote list behind an expand control. The stable-key local build update preserved schema v10 and all 39,181 match rows.
- Latest full local `./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug` after the off-main-thread change passed: 75 tests, 0 failures/errors, APK built. API 34 local update install and API 26 same-key update both succeeded. Current `MainActivity.kt` performance change is uncommitted and needs its own Actions verification.

### Next steps

1. Commit/push the responsiveness fix with this log update; verify Actions test/build/signature.
2. Update-install that CI APK on API 34 and API 26. Verify no launch crash, collect cold/warm frame logs, and check existing SQLite preservation.
3. Let the API 26 first-run retry continue until provider statuses/counts settle or a repeatable external connectivity condition explains the limit. Do not call it complete while it is partial.
4. Report API 26 WebView as unavailable for that emulator if it still yields no visible page; never use alternate hidden endpoints or evasion. Existing API 34 integration remains tested.

## 2026-11-09 — UI responsiveness and SQLite lifecycle follow-up (CI passed; API 26 continuation pending)

- Commit `3cdcd96` passed Actions run [42747055613](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/42747055613): 75 JUnit tests, APK build, stable signing and artifact upload. Downloaded CI APK certificate SHA-256 matched `da1506fd2e896c67d3ceafc8b3ab7f33824e759e092adf938fdf11c52fa8d935` and update-installed over API 34/API 26 without uninstall.
- API 34 still has SQLite v10, 39,181 matches, OpenFootball `OK`/25,904, OpenLigaDB `OK`/17,776, 120 quote snapshots and 59 accumulator records. No saved content was lost.
- API 26 kept schema v10 and 15,741 cached rows. The app resumed and rendered cached fixtures without a fatal exception; both bookmaker WebViews returned no visible text and showed `ERROR`. First-run full sync remains unverified. The CI APK update interrupted an earlier worker with a still-valid SQLite sync lease. WorkManager correctly kept the one-time request queued with a retry delay although emulator connectivity was validated (`CONNECTIVITY`/`VALIDATED`, ping succeeded). Do not describe this as a network outage or completed sync. A fresh post-update worker attempt should be allowed to run without another APK update.
- API 26 cold launch on this CI artifact logged 44 skipped frames once during initial JIT compilation and then rendered the main screen. It also logged a leaked SQLite connection from short-lived `Store` helpers used by sync scheduling/worker paths.
- A local API 34 cold launch then showed 103 skipped frames. Tracing the remaining synchronous work found that `Store.matches()` sorted the full 39,181-row table before returning 250 rows, and `recentCompletedMatches()` was also read on the UI thread. Both queries and the imported-competition list now load on `Dispatchers.IO`, keyed to provider freshness rather than every UI revision; bulk forecasts remain on `Dispatchers.Default`.
- Commit `c7bf03c` closes the temporary scheduler helper, worker helper on success and lock-contention paths, and activity-owned helper in `onDestroy`; it also changes queued-status copy to explain WorkManager/system delay or network constraints. Local build/tests passed (75 tests, 0 failures/errors/skips). Actions run [42749108983](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/42749108983) passed all stages. Downloaded CI artifact SHA-256 certificate matched `da1506fd2e896c67d3ceafc8b3ab7f33824e759e092adf938fdf11c52fa8d935`; its API 34 update install preserved SQLite v10, all 39,181 matches, provider states OpenFootball `OK`/25,904 and OpenLigaDB `OK`/17,776, 163 quote snapshots and 59 accumulators. No data was lost.
- The same local code moved the 39k-match browse sort, recent result list and imported-code list to `Dispatchers.IO`. Its API 34 update preserved the DB and captured 9 Misli rows (0 exact links) and 25 TOPAZ rows (7 exact links), including Portugal—Denmark / match 85257 at 1.45/4.31/5.29. Captures expire for pricing after 30 minutes.
- On that local build API 34 reported 68 skipped frames during initial JIT startup, versus 103 before the list query moved off the main thread; `ActivityTaskManager` reported the activity displayed in 3.6 seconds and UI hierarchy contained the Compose controls. No new ANR or SQLite helper leak appeared in the fresh process log. This is a single emulator cold-start measurement, not a physical-device performance guarantee.
- API 26 was left on the previous CI APK after its install interrupted a prior Worker, allowing WorkManager to resume uninterrupted. Latest readable state at 2026-11-10 03:29 UTC: 29,490 merged rows, OpenFootball `OK`/25,904, OpenLigaDB `PARTIAL`/5,463 and 57 league states. The worker released its lock after the bounded attempt and awaits continuation. Emulator network validation/ping succeeds. Full OpenLigaDB/API 26 completion remains pending; do not install a new APK over an active attempt.
- Next: let API 26's continuation reach provider `OK` or a repeatable provider limit, inspecting it without reinstalling. After that, update-install the `c7bf03c` CI artifact on API 26 to verify same-signature data preservation and the new SQLite lifecycle. API 26 bookmaker WebView remains `ERROR` with no visible text; no bypass. No physical handset is available.

## 2026-11-09 — offline CI-artifact and diary CI verification

- Documentation/status follow-up was pushed as `0fac233`; Actions run [42749894995](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/42749894995) passed the full 75-test, APK build, signing and upload workflow. It contains no app-code changes; app-code artifact validation remains run `42749108983` for `c7bf03c`.
- Re-tested offline behavior on the API 34 installation of the stable-signed `c7bf03c` CI APK. Wi-Fi and mobile data were disabled; `ping 1.1.1.1` returned `Network is unreachable`; Compose accessibility hierarchy continued to expose cached match cards and provider source states. Networking was restored immediately after inspection.
- API 26 sync is still continuing without APK updates. Its last committed diary snapshot remains 29,490 merged rows / OpenFootball `OK` 25,904 / OpenLigaDB `PARTIAL` 5,463 / 57 league states at 03:29 UTC. Backoff delay was visible in WorkManager scheduling; no full API 26 result is claimed yet.

## 2026-11-09 — Misli WebView proof and first clean-install capture (local build passed; data sync still running)

### Implemented

- Added a normal Android WebView collector in the production app for `https://www.misli.az/idman-novleri/futbol`. It loads automatically on app open, leaves the stock WebView user agent unchanged, reads only rendered `document.body.innerText`, and never visits login/account, ticket, private API, or restricted live-detail routes. A 30-minute minimum interval avoids loading the page on every recomposition/reopen; this WebView is foreground-only because WorkManager does not host this browser.
- Added a strict visible-text parser that requires a future date and local kickoff, a complete labeled 1/X/2 market, prices in range, known page host/path, and the WebView's IANA timezone. It rejects in-play score rows, incomplete odds, old events, and conflicting duplicates. A small reviewed Azerbaijan-to-canonical alias table is exact; fuzzy matching is not used.
- Added optional fixture linking only on exact home/away orientation, same local event date, kickoff within 20 minutes, and a unique local fixture. The five UEFA Women's Champions League events were not present in local fixture history, so no model forecast or recommendation was attached.
- Added SQLite version 7 bookmaker snapshots and status, retaining capture time, local timezone/date, raw team labels, source URL, market, price, and optional match ID. The UI shows actual fresh Misli prices separately and hides them after 30 minutes. TOPAZ remains `UNAVAILABLE` because the normal public page showed two in-play rows and one past event, no future prematch row.
- Updated source/permission notes: Misli `robots.txt` allows the public route, but it is not an explicit reuse license. No affirmative automated-reuse permission was found. Quotes are stored locally and not redistributed; this permission question remains open.
- Studied `aliabbasov99/ai-football-prediction` and its Misli scraper at the design level. No stealth, evasion, hidden endpoint, or source code was copied.

### Live-page evidence

- On 2026-11-09 at about 15:47 Pacific, the clean API 34 emulator run captured eight future public Misli 1X2 rows, with a UTC capture timestamp and `America/Los_Angeles` source timezone.
- Five captured fixtures for 10 Nov 2026 were `Häcken–Chelsea`, `Real Madrid–Paris FC`, `Inter–Manchester City`, `PSG–Bayern`, and `Roma–Servette`. Their displayed times (09:45 and 12:00 Pacific) align with the official UEFA list's 18:45 and 21:00 CET schedule. The app's local OpenFootball/OpenLigaDB match data did not contain these exact events, so comparison with Elo is unavailable.
- The other three visible rows were also future dated and had complete 1X2 lines at capture time. Quotes are snapshots, not guarantees of current availability.
- This verifies technical rendering/parsing on one API 34 emulator/timezone. It does not resolve permission for automated reuse or physical-device compatibility.

### Verification and open work

- `./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug` passed locally with 49 JUnit tests and built `app/build/outputs/apk/debug/app-debug.apk`.
- Cleared app data, installed the APK and launched `MainActivity` on API 34. The main screen showed automatic sync in progress and the Misli capture status. After about 15 seconds, SQLite contained 686 match rows and 8 bookmaker snapshots; OpenLigaDB was still busy, so final multi-season/result counts are not yet known.
- The test APK's screenshot showed the source status and populated match list without a setup or import prompt. Coupon-tab screenshot, sync completion, DB v6→v7 preservation, and offline reopen remain outstanding.
- That initial observation was partial. The final sync, second bookmaker, v8 schema, offline behavior, and automatic historical model evaluation are recorded in the continuation entry below.

## 2026-11-09 — complete provider sync, dual odds capture, and automatic model evaluation

### Work completed

- Updated the installed API 34 APK while preserving its SQLite data and confirmed schema version 8.
- Completed the first OpenFootball/OpenLigaDB sync. The provider state records OpenFootball `OK` with 25,904 rows and OpenLigaDB `OK` with 18,050 rows. Strict cross-provider merging resulted in 39,330 local matches across 188 distinct competitions and five seasons (`2022` through `2026-27`); 30,180 rows have a completed score.
- Found 12 OpenLigaDB date placeholders from year 1970 in persisted input. Added season/date validity filtering in parser, match lists, completed-results list and model-history query; all 12 stay excluded from visible matches and walk-forward data.
- Verified standard Android WebView collection from both public operator pages on API 34. Misli stored 8 future 1X2 rows; after selecting eTopaz's visible `İdman` tab, TOPAZ stored 25 future rows for 12–14 Nov 2026. The eTopaz UI omits event year; the parser marks its bounded next-occurrence inference in SQLite.
- Independently checked five Misli Women's Champions League fixtures against UEFA's published schedule and checked the eTopaz Türkiye–Belgium event/time against UEFA's Nations League fixture list. The captured event rows had zero exact local fixture IDs, so the app attached no model comparison to those operator prices.
- Captured the Matches and Coupons screens. The UI showed source freshness, true provider-specific prices and timestamps, and a clear “no verified local model match” message. Misli and TOPAZ prices are never combined across operators. Airplane-mode relaunch retained cached matches and provider status; expired odds leave the fresh-price display.
- A repeated foreground reload initially exposed duplicate event cards because multiple 30-minute snapshots were retained. Added newest-snapshot-per-event selection for display and coupon calculations while keeping the stored capture history. The UI now distinguishes stale snapshots and captures with no future lines from fresh `LIVE` quotes.
- Added a background-thread automatic walk-forward report over local result sources, grouped by competition, with strict safe-availability cutoff. Refactored the engine to apply availability-time batches once, preserving same-cutoff snapshots while avoiding a full-history refit for every target.
- On the installed API 34 database, the walk-forward evaluated 29,970 valid completed match targets: 19,078 forecasts, 10,892 skipped for insufficient earlier sample; mean Brier 0.6105 and mean Log Loss 1.0234. The screen explains that current files do not preserve archived source vintages, date-only rows use calendar-date cutoffs and +2-day result availability, and late-captured OpenLigaDB rows cannot train historical cutoffs. These are retrospective research metrics, not a claim of profit or known future calibration.
- Forty prospective Elo coupon picks were recorded on the emulator and zero were settled at inspection time. The match list, source statuses, real prices and walk-forward data persisted offline.
- Updated `AGENTS.md`, README and project docs to match observed behavior and preserve source permission uncertainty. Misli and eTopaz explicit odds-reuse permission has not been established; snapshots stay local and aren't redistributed. No login, hidden endpoint, restricted route, CAPTCHA, stealth or evasion was used.

### Verification

- `./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug` passed after the automatic walk-forward and quote freshness/deduplication changes: 62 JUnit tests, debug APK built.
- API 34 emulator screenshots and UI hierarchy verified the main, coupon, offline and backtest screens. Repeated capture history displayed once per event per operator. The automatic backtest completed over the full local database without a UI-thread block; the displayed totals/metrics were 29,970 / 19,078 / Brier 0.6105 / Log Loss 1.0234.
- Airplane-mode relaunch succeeded with previously synchronized local data. eTopaz/Misli quotes remain freshness-limited to 30 minutes.
- Main implementation was committed/pushed as `4edec14`; GitHub Actions run `42729262549` passed all jobs and uploaded artifact `13723518681`. No API 26 or physical-device check was done. Existing v7→v8 data preservation was previously observed, but dedicated SQLite migration/instrumentation tests are not present.

### Next concrete work

The next items in this historical checkpoint were completed and recorded in the final verification entry below. Open limitations remain: operator terms do not affirm automated quote reuse; no stored bookmaker event joins an exact local fixture; no operator quote is reused as a price for an unmatched local model pick; only one API 34 emulator was tested; historic source vintages are absent; no forecast outcomes have settled yet.

## 2026-11-09 — GitHub Actions artifact verification and stable update signing

- Actions run [42729262549](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/42729262549) completed successfully for `4edec14` in 3m51s. Unit tests, APK build and artifact upload all passed. Artifact ID `13723518681` downloaded as a 17,140,940-byte `az.simplesoft.footballintelligence` debug APK (version 0.1.0, minSdk 26, targetSdk 35).
- Fresh CI artifact installed and launched after a clean uninstall. WorkManager started filling SQLite automatically; first complete DB inspection reached 5,394 matches/2,338 scored rows in 15 competitions while the long sync was still running. The standard WebViews stored eight Misli and 25 eTopaz rows. Earlier local-build fresh-install testing completed full sync to 39,330 rows; this CI build uses the same commit/source.
- `adb install -r` from the CI artifact over the workstation-signed APK correctly rejected the mismatched debug certificate. To protect cached history on later APK updates, generated a private RSA test keystore and stored its keystore/password/alias only in GitHub Actions Secrets. Updated the workflow to sign the debug APK after Gradle. No signing key was added to the repo.
- Actions run [42731002068](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/42731002068) passed including stable signing. The downloaded APK's certificate SHA-256 `da1506fd2e896c67d3ceafc8b3ab7f33824e759e092adf938fdf11c52fa8d935` matched the temporary test key. `adb install -r` updated the existing stable-signed app without uninstall; SQLite v8 and the 33 bookmaker snapshots remained. During this update, the active worker was interrupted while its 9-minute SQLite lease persisted.
- Fixed lease contention: a worker that cannot acquire the sync lease now reports `waiting_for_sync_lock` and returns `Result.retry()` instead of falsely completing. The updated local build passed `:app:testDebugUnitTest` and `:app:assembleDebug` with 62 JUnit tests; its sync-lock fix still needs a CI run and update-install verification.
- The current stable-signed emulator database has 8,894 match rows and 4,417 scored rows at the last inspection; its interrupted provider sync has not yet been confirmed complete. WorkManager had previously marked two contending workers `SUCCESS`, motivating the retry fix. No final counts should be inferred until provider completion states are inspected.

## 2026-11-09 — stable APK acceptance, retried full sync, offline and backtest verification

### Work completed

- Committed and pushed the lease-contention fix as `5a64c43`. A worker unable to acquire the SQLite sync lease now sets progress to `waiting_for_sync_lock` and returns `Result.retry()` rather than silently reporting success.
- Actions run [42731992205](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/42731992205) completed successfully: 62 unit tests, debug APK build, stable signing and artifact upload. Downloaded artifact certificate SHA-256 matched the stable CI key (`da1506fd2e896c67d3ceafc8b3ab7f33824e759e092adf938fdf11c52fa8d935`). The APK update-installed over the previous same-key API 34 app without uninstall; SQLite v8 and bookmaker snapshots were preserved.
- The final APK performed a clean source sync over multiple bounded WorkManager runs. An initial worker and one continuation reported `PARTIAL` at the eight-minute runtime boundary; WorkManager retried automatically and the final attempt reported `SUCCESS`. Both final provider records are `OK`: OpenFootball 25,904 rows and OpenLigaDB 17,776. Five cached OpenLigaDB catalogs contained 214 league-season entries. SQLite contains 39,181 merged match rows, 30,090 finished results, and 184 distinct competitions, with season keys from `2022` through `2026-27`. No 1970 date placeholders remained.
- On the final Backtest screen, automatic walk-forward returned 30,090 targets, 19,185 forecasts, 10,905 skipped for insufficient prior history, mean Brier 0.6106 and mean Log Loss 1.0235. The computation completed without blocking the screen. It is retrospective on present source files with conservative date-only availability, not archived historical vintages or evidence of future accuracy/profit.
- Verified true offline relaunch with airplane mode plus Wi-Fi and mobile data disabled. `ping 1.1.1.1` returned `Network is unreachable`; the main screen still displayed cached fixtures and source statuses. Network access was restored after the check.
- After the final sync, a foreground bookmaker check displayed nine fresh Misli lines (`LIVE`) and marked TOPAZ/eTopaz `UNAVAILABLE` because the page returned only the public home page. Stored snapshot history contained 17 Misli captures across 9 events and 25 TOPAZ events; none had an exact local match ID. Earlier POC captured both operators and UEFA schedule cross-checks remain in the preceding entry. Explicit reuse permissions remain unconfirmed; no access controls were bypassed.

### Final verification and remaining limits

- The official CI artifact for `5a64c43` passed, verified the same stable signature and successfully updated an installed app with preserved data.
- API 34 first launch reached both source `OK` states and the expected broad multi-season database. Unit tests and APK build are green.
- No physical handset or API 26 execution was performed. Dedicated SQLite migration and WorkManager integration tests remain future work. Current Misli/eTopaz terms do not affirm automatic odds reuse, and current public bookmaker events do not map to local model fixtures; the app correctly makes no model comparison for them.

## 2026-11-09 — exact rematching of bookmaker snapshots to local fixtures (CI passed)

### Implemented and inspected

- Reviewed the saved TOPAZ rows against the local API 34 fixture database. Added explicit German national-team aliases and a shared strict matcher: canonical exact home/away order, same event date in the snapshot timezone, a unique local fixture, and kickoff within 20 minutes. No fuzzy matching was introduced.
- Added relinking of recent unmatched future quote snapshots after football-provider sync and once after app open. Existing stored quotes can gain a local match ID when fixture history arrives later; this does not make a stale price fresh.
- Verified the historical eTopaz row Türkiye—Belgium (3.30 / 3.64 / 1.88) maps to OpenLigaDB match ID 85220 (`Türkei`—`Belgien`, 12 Nov 2026 17:00 UTC). Seven quote snapshot rows now have a local fixture ID. The observed quote capture was about 57 minutes old at inspection, beyond the 30-minute freshness window; the current eTopaz page state remains `UNAVAILABLE`, so the stale price is neither shown as live nor used in the current coupon price map.
- Corrected README, roadmap, limitations, source research, zero-touch status, changelog, and this log so historical linkage is not confused with a currently available bookmaker quote. `AGENTS.md` now records this exact continuation point and remaining accumulator/device tasks.

### Verification

- `./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug` passed locally after the code change: 63 tests, 0 failures/errors/skips, debug APK built. The regression test covers the visible Azerbaijani names against the German OpenLigaDB fixture. `git diff --check` passed.
- Installed the locally built APK signed with the same stable personal-test key over the existing API 34 app using `adb install -r`; update succeeded without uninstall. SQLite still contains 39,181 matches / 30,090 finished results, and exact rematching linked the stored TOPAZ event to match 85220. The link count is 7.
- Committed as `bc4783b` and pushed to `main`. Actions run [42738579732](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/42738579732) passed all jobs, including 63 unit tests, APK build, stable signing, and artifact upload. The downloaded APK signer certificate SHA-256 was `da1506fd2e896c67d3ceafc8b3ab7f33824e759e092adf938fdf11c52fa8d935`.
- Installed the CI artifact over the existing API 34 installation with `adb install -r`; the install succeeded without uninstall. The SQLite database retained 39,181 matches, 30,090 finished results, 7 bookmaker snapshots with local fixture IDs, and the TOPAZ `match_id=85220` link. Relaunched the application from the updated artifact.

### Next concrete work

1. Implement explicit virtual accumulator sizes (2/3/5/8/10) with no invented combined probability or payoff where a required market price is absent; add historical outcome checking that does not imply profit without timestamped odds.
2. Continue test/compatibility audit (SQLite migration, sync retry, API 26) and leave an accurate record of unsupported or unverified bookmaker access.
