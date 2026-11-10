# Testing

## Local checks

```sh
./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug
```

Commit `e8c3a33` passed GitHub Actions run [42752522409](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/42752522409): the local suite passed **79 JUnit tests**, and CI passed APK build, stable signing and artifact upload. The downloaded APK signer SHA-256 `da1506fd2e896c67d3ceafc8b3ab7f33824e759e092adf938fdf11c52fa8d935` matched the stable key. The artifact update-installed on API 26 and preserved SQLite v10 and its existing match rows.

Coverage includes:

- Basic/Power/Shin 1X2 de-vigging and validation; Poisson/Dixon–Coles distributions and forecast metrics.
- Elo cutoff, safe availability, same-time batching, sample-size thresholds, and walk-forward score aggregation.
- Football-Data CSV parsing/date availability for the optional settings import.
- OpenFootball season planning, safe catalog paths, date-only vs scored records, duplicate rows, and catalog discovery.
- OpenLigaDB football-only catalog filtering, kickoff/result parsing, current group and update timestamp parsing.
- Conservative cross-provider same-date/team/home-away dedupe identity.
- Misli visible-DOM 1X2 parsing, local timezone conversion, strict alias/orientation/date/kickoff fixture mapping, and rejection of expired, live-score, incomplete, ambiguous and conflicting rows.
- Independent Misli/TOPAZ visible-page parsers, eTopaz inferred-year rules, exact alias/date/kickoff matching, operator isolation, expiry, and no fabricated odds.
- Provider status freshness and latest-snapshot-per-event selection after repeated WebView reloads.
- Coupon draft separation, most-probable outcome selection, stable match dedupe/order, same-provider pricing, quote timestamps and fresh-only accumulator construction, stale/future quote rejection, Fréchet bounds, settlement and price-backed performance summaries.
- Rejection of placeholder team identities in source parsers, read-side lists and historical evaluation.
- Streaming chronological walk-forward equivalence with independent as-of Elo fits, shared availability batches, and future-result exclusion.

The API 34 database has 39,181 merged rows, 30,090 completed-result rows, 184 competitions, season keys `2022` through `2026-27`; provider source counts are OpenFootball 25,904 and OpenLigaDB 17,776. Current code rejects 566 previously stored placeholder-identity rows from reads, model input and backtests (kept in the database for ID/migration safety). The installed app recomputed post-filter automatic walk-forward: 29,588 targets, 19,185 forecasts and 10,403 insufficient-history skips. Older unfiltered figures are obsolete.

After installing `e8c3a33` on API 34, a standard WebView capture at 2026-11-10 04:21 UTC stored 9 Misli and 25 TOPAZ future lines; 7 TOPAZ snapshots matched exact local fixtures, 0 Misli lines did. Portugal—Denmark maps to fixture 85257 with 1.45/4.31/5.29. The UI showed a two-leg TOPAZ virtual candidate using England—Croatia 1.21 and Portugal—Denmark 1.45, with per-leg Elo marginals, captured timestamps and Fréchet bounds for joint probability. No Misli price-backed candidate was created because no Misli event matched a local fixture. These captures have a 30-minute freshness limit. Older 2/3/5-leg prices 1.75/2.09/26.87 are historical observations only. No coupon outcome had settled at inspection time.

After placeholder exclusion, the installed Backtest screen reported 29,588 targets, 19,185 forecasts, 10,403 insufficient-history skips, Brier 0.6106 and Log Loss 1.0235. These are still retrospective scores from current source snapshots, not archived vintages or future calibration evidence.

Offline relaunch was checked with Wi-Fi and mobile data disabled; `ping 1.1.1.1` returned `Network is unreachable`, while cached fixtures and provider statuses remained visible. Stored odds expire from the fresh display after 30 minutes.

Repeated this offline check after installing the `e8c3a33` CI APK on API 34: disabling Wi-Fi and mobile data made `ping 1.1.1.1` fail as expected, while the Compose UI hierarchy still contained cached match rows and source statuses. SQLite retained 39,181 matches, 72 Misli quote snapshots, 125 TOPAZ snapshots and 59 accumulators. Networking was restored after the check.

The stable-signed CI artifact for `e8c3a33` was update-installed on Android 8 / API 26 and opened without a fatal exception. SQLite v10 and 32,680 existing match rows survived. WorkManager ran the resumed eight-minute request to `SUCCESS`, then immediately ran its appended continuation, which also finished `SUCCESS`. The final DB contained 38,609 matches, 29,588 completed results, 183 competitions, OpenFootball `OK`/25,904 and OpenLigaDB `OK`/17,202. All 214 cached OpenLigaDB league-season entries had state. UI hierarchy checks showed both `OK` providers, cached upcoming fixtures, completed result history, an Elo forecast and separate model-only virtual coupons. Both bookmaker WebViews returned no visible text and remained `ERROR` with zero fresh quotes; the app showed no fabricated prices. No physical handset has been tested.

## CI / APK

GitHub Actions run [42729262549](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/42729262549) passed unit tests, `assembleDebug`, and artifact upload for commit `4edec14`. The downloaded artifact (`football-intelligence-debug-apk`, 17,140,940 bytes) reports package `az.simplesoft.footballintelligence`, version `0.1.0`, minSdk 26 and targetSdk 35. It installed after a clean uninstall on API 34, opened the main screen, started first-open sync, and captured 8 Misli plus 25 eTopaz rows. Its background history sync was still running at the first inspection (5,394 rows, 15 competitions); the equivalent local build had already been followed through to completion above.

The first CI artifact used the runner's generated debug certificate, so Android correctly rejected updating a different-key installation. A private test signing key is stored only in repository Actions Secrets; the workflow re-signs CI artifacts with it. Latest successful run [42744967721](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/42744967721) passed 75 tests, build, stable signing, and artifact upload for `0bff2f6`. Its certificate SHA-256 `da1506fd2e896c67d3ceafc8b3ab7f33824e759e092adf938fdf11c52fa8d935` matched prior CI APKs; update-install on API 34 preserved 39,181 matches, 120 quote snapshots and 59 accumulator records. The key is not checked into Git.

## Remaining verification gaps

- SQLite v1–v10 upgrade preservation and cross-provider merge/score retention still need dedicated instrumentation coverage, despite local v10 update-install preservation.
- WorkManager lock contention/retry is covered by existing logic; pure routing tests cover success, continuation and retry decisions. API 26 verified the actual enqueue and completion path across a normal runtime boundary. Dedicated WorkManager integration tests for network constraints, ETag/304, pacing and lock expiry remain absent.
- First-open full sync is verified on API 34 and API 26 emulators. API 26 bookmaker WebViews returned no visible text; use a current physical handset/WebView when available.
- Misli and TOPAZ WebViews were cross-checked against UEFA fixtures; explicit automated-reuse permission remains unconfirmed. Seven TOPAZ events joined local fixtures on one API 34 run; Misli had no exact links on that capture. More dates/timezones/devices remain untested.
- Compose accessibility and physical-device layout remain unverified. API 26 cold start skipped 44 frames during initial JIT compilation on the latest CI APK. On a local API 34 cold start after moving the 39k match-list query to IO, 68 frames were skipped during JIT startup, the activity reached `Displayed` in 3.6s, and its Compose controls were present in the UI hierarchy; no fresh ANR or SQLite-helper leak was logged. Repeat cold/warm measurements remain open.
- The automatic-source walk-forward is retrospective on current files. Calibration, source vintages, timestamped historical odds, CLV, a market baseline, and confidence intervals remain unavailable.
