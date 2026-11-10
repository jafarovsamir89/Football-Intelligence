# Testing

## Local checks

```sh
./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug
```

The current local revision passed the full `:app:testDebugUnitTest :app:assembleDebug` command with **75 JUnit tests** and a successful APK build. The current MainActivity threading change is uncommitted; CI run [42744967721](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/42744967721) verifies preceding commit `0bff2f6`.

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

Offline relaunch was checked with Wi-Fi and mobile data disabled; `ping 1.1.1.1` returned `Network is unreachable`, while cached fixtures and provider statuses remained visible. Stored odds expire from the fresh display after 30 minutes. No API 26 emulator or physical phone has yet been used.

The CI-signed artifact for `0bff2f6` was installed on an Android 8 / API 26 emulator. The app opened and remained active without a fatal exception. First-run WorkManager sync was still partial at 10,445 rows when inspected; both bookmaker WebViews reported that no visible page text was returned. This does not verify full API 26 sync or current bookmaker capture on that old WebView. The subsequent local UI-thread change passes tests/build but is awaiting CI and repeat API 26 checks. No physical handset has been tested.

## CI / APK

GitHub Actions run [42729262549](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/42729262549) passed unit tests, `assembleDebug`, and artifact upload for commit `4edec14`. The downloaded artifact (`football-intelligence-debug-apk`, 17,140,940 bytes) reports package `az.simplesoft.footballintelligence`, version `0.1.0`, minSdk 26 and targetSdk 35. It installed after a clean uninstall on API 34, opened the main screen, started first-open sync, and captured 8 Misli plus 25 eTopaz rows. Its background history sync was still running at the first inspection (5,394 rows, 15 competitions); the equivalent local build had already been followed through to completion above.

The first CI artifact used the runner's generated debug certificate, so Android correctly rejected updating a different-key installation. A private test signing key is stored only in repository Actions Secrets; the workflow re-signs CI artifacts with it. Latest successful run [42744967721](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/42744967721) passed 75 tests, build, stable signing, and artifact upload for `0bff2f6`. Its certificate SHA-256 `da1506fd2e896c67d3ceafc8b3ab7f33824e759e092adf938fdf11c52fa8d935` matched prior CI APKs; update-install on API 34 preserved 39,181 matches, 120 quote snapshots and 59 accumulator records. The key is not checked into Git.

## Remaining verification gaps

- SQLite v1–v10 upgrade preservation and cross-provider merge/score retention still need dedicated instrumentation coverage, despite local v10 update-install preservation.
- WorkManager lock contention/retry has been corrected and end-to-end first-run continuation across the 8-minute boundary was observed on API 34; dedicated WorkManager integration tests for enqueue, network constraints, ETag/304, pacing, and lock expiry are still absent.
- First-open API 34 full sync is verified. API 26 artifact smoke test opened, but sync remained partial at inspection and bookmaker WebView returned no visible text. Recheck sync on a completed run and use a physical handset; current async-compute change still needs API 26 CI-artifact retest.
- Misli and TOPAZ WebViews were cross-checked against UEFA fixtures; explicit automated-reuse permission remains unconfirmed. Seven TOPAZ events joined local fixtures on one API 34 run; Misli had no exact links on that capture. More dates/timezones/devices remain untested.
- Compose accessibility and physical-device layout remain unverified. A cold API 26 launch previously skipped many frames; history/model calculations were moved off the UI thread, but repeat measurements on the current revision remain open.
- The automatic-source walk-forward is retrospective on current files. Calibration, source vintages, timestamped historical odds, CLV, a market baseline, and confidence intervals remain unavailable.
