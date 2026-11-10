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

The APK was installed on API 34 (`OBD_API_34`) and exercised after the initial clean-install sync. SQLite v8 contained 39,330 merged match rows, 30,180 completed results, 188 distinct competitions, and five seasons (`2022` through `2026-27`). OpenFootball reported 25,904 fetched rows and OpenLigaDB 18,050; provider overlap is merged in the app database. Twelve malformed 1970 placeholder records remain stored from the provider response but are excluded from match screens and walk-forward by season/date validation. Both source sync states were `OK`.

The normal WebViews stored eight Misli and 25 TOPAZ future snapshots. UEFA schedule checks confirmed five Misli Women's Champions League rows and the TOPAZ Türkiye–Belgium Nations League row. No odds row had an exact local fixture ID, so no model comparison was shown for those bookmaker matches. On the Backtest screen, the automatic source walk-forward returned 29,970 valid targets, 19,078 model forecasts, 10,892 skipped targets, mean Brier 0.6105, and mean Log Loss 1.0234. Its source-version and date-only limitations are displayed with the metrics. The result-history screen showed prospective saved picks; 40 had no settled outcome at inspection time.

Airplane-mode relaunch preserved the local schedule, source statuses and stored data. Quotes expire from the fresh display after 30 minutes. An existing v7→v8 update preserved the stored bookmaker snapshots during earlier install verification. No API 26 emulator or physical phone has yet been used.

## CI / APK

Earlier GitHub Actions runs [42711478723](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/42711478723) and [42714011747](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/42714011747) successfully ran tests, assembled a debug APK, and uploaded an artifact for prior commits. The current automatic-sync revision must be committed/pushed and its own Actions run checked before it is considered CI-verified.

## Remaining verification gaps

- SQLite v1–v6 upgrade preservation and cross-provider merge/score retention need instrumentation coverage.
- WorkManager unique enqueue, network constraint, 8-minute continuation/retry, HTTP ETag/304/offline cache, request pacing, and sync-lock expiry need integration tests.
- First-open API 34 sync is verified, but should be repeated on API 26/physical hardware.
- Misli and TOPAZ WebViews were cross-checked against UEFA upcoming fixtures; explicit permission to automate local odds reuse remains unconfirmed, and none of the captured bookmaker events joined an exact local forecast.
- Compose accessibility, layout on small screens, Android 8/API 26 execution, large database performance, and offline refresh behavior need device checks.
- The automatic-source walk-forward is retrospective on current files. Calibration, source vintages, timestamped historical odds, CLV, a market baseline, and confidence intervals remain unavailable.
