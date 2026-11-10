# Testing

## Local checks

```sh
./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug
```

The current local revision passed this command on 2026-11-09. It completed **62 JUnit tests** and built `app/build/outputs/apk/debug/app-debug.apk`.

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
- Coupon draft separation, most-probable outcome selection, stable match dedupe/order, and null bookmaker price/combined odds.
- Streaming chronological walk-forward equivalence with independent as-of Elo fits, shared availability batches, and future-result exclusion.

The final CI APK was update-installed on API 34 (`OBD_API_34`) without uninstalling the prior same-key app. SQLite v8 was preserved. The complete automatic sync took multiple WorkManager attempts: the worker recorded `PARTIAL` and retried at the 8-minute runtime boundary, then completed with both providers `OK`. Final SQLite totals were 39,181 merged matches, 30,090 completed results, 184 competitions, with season keys from `2022` through `2026-27`. OpenFootball reported 25,904 rows and OpenLigaDB 17,776; provider overlap is merged locally. No invalid 1970 kickoff placeholders remained in the final database.

The WebView POC stored Misli and TOPAZ snapshots and UEFA schedule checks confirmed five Misli Women's Champions League rows and the TOPAZ Türkiye–Belgium Nations League row. The final screen inspection found nine current Misli lines and an `UNAVAILABLE` eTopaz status because the page returned only its public home page. Stored operator snapshots had no exact local fixture IDs; model comparisons were not attached. On the final Backtest screen, the automatic source walk-forward returned 30,090 targets, 19,185 forecasts, 10,905 skipped for insufficient history, mean Brier 0.6106, and mean Log Loss 1.0235. These retrospective metrics use current source files and date-only availability assumptions. Forty prospective model coupon picks remained unsettled.

Offline relaunch was checked with Wi-Fi and mobile data disabled; `ping 1.1.1.1` returned `Network is unreachable`, while cached fixtures and provider statuses remained visible. Stored odds expire from the fresh display after 30 minutes. No API 26 emulator or physical phone has yet been used.

## CI / APK

GitHub Actions run [42729262549](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/42729262549) passed unit tests, `assembleDebug`, and artifact upload for commit `4edec14`. The downloaded artifact (`football-intelligence-debug-apk`, 17,140,940 bytes) reports package `az.simplesoft.footballintelligence`, version `0.1.0`, minSdk 26 and targetSdk 35. It installed after a clean uninstall on API 34, opened the main screen, started first-open sync, and captured 8 Misli plus 25 eTopaz rows. Its background history sync was still running at the first inspection (5,394 rows, 15 competitions); the equivalent local build had already been followed through to completion above.

The first CI artifact used the runner's generated debug certificate, so Android correctly rejected updating a different-key installation. A private test signing key is stored only in repository Actions Secrets; the workflow re-signs CI artifacts with it. Final run [42731992205](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/42731992205) passed tests, build, stable signing, and artifact upload. The downloaded artifact's certificate SHA-256 `da1506fd2e896c67d3ceafc8b3ab7f33824e759e092adf938fdf11c52fa8d935` matched prior CI APKs and update-installed without uninstalling. The key is not checked into Git.

## Remaining verification gaps

- SQLite v1–v6 upgrade preservation and cross-provider merge/score retention need instrumentation coverage.
- WorkManager lock contention/retry has been corrected and end-to-end first-run continuation across the 8-minute boundary was observed on API 34; dedicated WorkManager integration tests for enqueue, network constraints, ETag/304, pacing, and lock expiry are still absent.
- First-open API 34 sync is verified, but should be repeated on API 26/physical hardware.
- Misli and TOPAZ WebViews were cross-checked against UEFA upcoming fixtures; explicit permission to automate local odds reuse remains unconfirmed, and none of the captured bookmaker events joined an exact local forecast.
- Compose accessibility, layout on small screens, Android 8/API 26 execution, large database performance, and offline refresh behavior need device checks.
- The automatic-source walk-forward is retrospective on current files. Calibration, source vintages, timestamped historical odds, CLV, a market baseline, and confidence intervals remain unavailable.
