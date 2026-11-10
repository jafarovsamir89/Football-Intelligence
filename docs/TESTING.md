# Testing

## Local checks

```sh
./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug
```

Commit `c7bf03c` passed GitHub Actions run [42749108983](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/42749108983): **75 JUnit tests**, APK build, stable signing and artifact upload. Downloaded CI APK signer SHA-256 matched the stable key and update-install on API 34 preserved the database. The later documentation-only commit `0fac233` also passed full Actions run [42749894995]. The tested app code closes temporary SQLite helpers, moves match/recent-result list queries to IO, and improves queued-sync status text.

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

The previous CI APK was update-installed on API 34 (`OBD_API_34`) without uninstalling the prior same-key app. The latest local schema v10 update also preserved SQLite. The complete automatic sync took multiple WorkManager attempts: the worker recorded `PARTIAL` and retried at the 8-minute runtime boundary, then completed with both providers `OK`. SQLite totals are 39,181 merged rows, 30,090 completed-result rows, 184 competitions, season keys `2022` through `2026-27`; provider source counts are OpenFootball 25,904 and OpenLigaDB 17,776. Current code rejects 566 previously stored placeholder-identity rows from reads, model input and backtests (kept in the database for ID/migration safety). Recompute the post-filter automatic walk-forward before reporting performance; older metrics are obsolete.

The latest standard WebView capture on API 34 stored 10 Misli and 25 TOPAZ future lines; 7 TOPAZ snapshots matched exact local fixtures, 0 Misli lines did. Portugal—Denmark maps to fixture 85257 with 1.45/4.31/5.29; latest capture time was 2026-11-10 02:36:36 UTC and is now over 30 minutes old. TOPAZ price-backed virtual coupons for 2/3/5 legs stored same-provider combined prices 1.75/2.09/26.87 and per-leg quote capture times. UI screenshots confirmed quote details are collapsed by default, separate model-only and price-backed bases, per-leg model marginals, no independence assumption, and withheld combined odds without a complete fresh line. No coupon outcomes had settled at inspection time.

After placeholder exclusion, the installed Backtest screen reported 29,588 targets, 19,185 forecasts, 10,403 insufficient-history skips, Brier 0.6106 and Log Loss 1.0235. These are still retrospective scores from current source snapshots, not archived vintages or future calibration evidence.

Offline relaunch was checked with Wi-Fi and mobile data disabled; `ping 1.1.1.1` returned `Network is unreachable`, while cached fixtures and provider statuses remained visible. Stored odds expire from the fresh display after 30 minutes.

Repeated this offline check after installing the `c7bf03c` CI APK on API 34: disabling Wi-Fi and mobile data made `ping 1.1.1.1` fail as expected, while the Compose UI hierarchy still contained cached match rows and source statuses. Networking was restored after the check.

The CI-signed artifact for `3cdcd96` was update-installed on Android 8 / API 26 and opened without a fatal exception. The update interrupted a prior worker; the nine-minute sync lease and WorkManager retry backoff delayed continuation despite validated emulator network connectivity. By 2026-11-10 03:47 UTC the existing app had 32,680 total local rows, OpenFootball `OK`/25,904 and OpenLigaDB `PARTIAL`/9,392, with 114 league states. The lock was released; WorkManager had another attempt scheduled about nine minutes later. A code fix now appends normal eight-minute continuation segments to the one-time WorkManager chain instead of returning `Result.retry()`; actual provider errors still use retry/backoff. Its CI artifact is not yet installed or verified on API 26. Both bookmaker WebViews reported no visible page text and status `ERROR` on this emulator. The CI cold launch skipped 44 frames once during JIT startup; on the later local build the main match sort moved to IO. No physical handset has been tested.

## CI / APK

GitHub Actions run [42729262549](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/42729262549) passed unit tests, `assembleDebug`, and artifact upload for commit `4edec14`. The downloaded artifact (`football-intelligence-debug-apk`, 17,140,940 bytes) reports package `az.simplesoft.footballintelligence`, version `0.1.0`, minSdk 26 and targetSdk 35. It installed after a clean uninstall on API 34, opened the main screen, started first-open sync, and captured 8 Misli plus 25 eTopaz rows. Its background history sync was still running at the first inspection (5,394 rows, 15 competitions); the equivalent local build had already been followed through to completion above.

The first CI artifact used the runner's generated debug certificate, so Android correctly rejected updating a different-key installation. A private test signing key is stored only in repository Actions Secrets; the workflow re-signs CI artifacts with it. Latest successful run [42744967721](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/42744967721) passed 75 tests, build, stable signing, and artifact upload for `0bff2f6`. Its certificate SHA-256 `da1506fd2e896c67d3ceafc8b3ab7f33824e759e092adf938fdf11c52fa8d935` matched prior CI APKs; update-install on API 34 preserved 39,181 matches, 120 quote snapshots and 59 accumulator records. The key is not checked into Git.

## Remaining verification gaps

- SQLite v1–v10 upgrade preservation and cross-provider merge/score retention still need dedicated instrumentation coverage, despite local v10 update-install preservation.
- WorkManager lock contention/retry is covered by existing logic; pure routing tests now cover success, continuation and retry decisions. The new queued-continuation implementation still needs device verification. Dedicated WorkManager integration tests for actual enqueue, network constraints, ETag/304, pacing, and lock expiry are still absent.
- First-open API 34 full sync is verified. API 26 remains partial on its old APK; install the new CI artifact when ready and verify continued progress without clearing its cache. Bookmaker WebView returned no visible text on API 26. Use a physical handset when available.
- Misli and TOPAZ WebViews were cross-checked against UEFA fixtures; explicit automated-reuse permission remains unconfirmed. Seven TOPAZ events joined local fixtures on one API 34 run; Misli had no exact links on that capture. More dates/timezones/devices remain untested.
- Compose accessibility and physical-device layout remain unverified. API 26 cold start skipped 44 frames during initial JIT compilation on the latest CI APK. On a local API 34 cold start after moving the 39k match-list query to IO, 68 frames were skipped during JIT startup, the activity reached `Displayed` in 3.6s, and its Compose controls were present in the UI hierarchy; no fresh ANR or SQLite-helper leak was logged. Repeat cold/warm measurements remain open.
- The automatic-source walk-forward is retrospective on current files. Calibration, source vintages, timestamped historical odds, CLV, a market baseline, and confidence intervals remain unavailable.
