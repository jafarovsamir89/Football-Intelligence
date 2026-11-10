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
- Current revision is still uncommitted and its GitHub Actions run has not been launched. No API 26 or physical-device check was done. Existing v7→v8 data preservation was previously observed, but dedicated SQLite migration/instrumentation tests are not present.

### Next concrete work

1. Review full diff and test reports, resolve stale documentation claims, and run final local build/test after documentation changes.
2. Commit the zero-touch/bookmaker integration and automatic walk-forward as distinct major milestones.
3. Push to the existing repository, wait for GitHub Actions, download and inspect its APK artifact; fix and repeat if CI reports an issue.
4. Preserve these open limitations: operator terms do not affirm automated quote reuse; current 33 bookmaker snapshots do not join an exact local fixture; no operator quote is reused as a price for an unmatched local model pick; only one API 34 emulator was tested; historic source vintages are absent; no forecast outcomes have settled yet.
