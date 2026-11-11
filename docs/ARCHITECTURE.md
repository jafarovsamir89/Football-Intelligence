# Architecture

## Current implementation (native Android, local-first)

```text
MainActivity / Compose
  ├── WorkManager scheduler (first-open stale check + daily periodic)
  │     └── AutomaticFootballSyncWorker (network constraint, unique lock, retry/backoff)
  │            ├── OpenFootballSync → GitHub directory discovery + public raw CC0 JSON
  │            └── OpenLigaDbSync → official API catalog, matches, group/change polling
  ├── Misli and TOPAZ public-page WebViews (foreground, visible DOM only, gated capture/reload at 30-minute freshness)
  ├── Store / SQLite v11 with WAL (matches, source cache, bookmaker snapshots, single and accumulator forecasts/history)
  ├── EloEngine → exact-kickoff forecasts with safe-availability cutoff
  └── VirtualCouponBuilder → separate model-ranked and same-bookmaker fresh-price accumulator drafts
```

No server, account, secret, paid API, or bookmaker action exists. WorkManager runs best-effort; Android controls exact scheduling. Public source rows and HTTP metadata are persisted for offline use. CSV remains an optional Settings feature.

## Provider and persistence rules

- `ProviderMatch` keeps stable provider ID, competition/season, event date, exact-vs-date-only kickoff, source URL, capture time, and safe result-availability time.
- `OpenFootballJsonParser` consumes repository catalog JSON and match files. Paths are validated; duplicate rows within a file are removed; missing competition files remain absent instead of being invented. A date-only fixture never gets an estimated kickoff.
- `OpenLigaDbParser` discovers only football leagues, accepts final-result type records, and parses explicit UTC kickoff. Requests are spaced 1.25 seconds apart (48/min), under the published 60/minute limit. Current-year leagues use current group and last-change checks; older catalogs refresh weekly. Each Worker reserves up to three minutes for OpenFootball, then lets OpenLigaDB use the remaining window under a seven-minute total source deadline. It appends a continuation to the unique one-time WorkManager chain and succeeds so Android does not apply exponential retry backoff to normal progress. `APPEND_OR_REPLACE` starts a clean chain after any failed/cancelled prerequisite. Actual provider/network failures still use WorkManager retry/backoff.
- SQLite upserts are stable by provider source key. An incomplete observation cannot erase a stored score. Cross-provider merging requires one strict same-date, same-home/away normalized candidate; if there are zero or multiple candidates, the app does not guess. A more precise kickoff/result can upgrade the existing row while preserving source URLs and combining source labels.
- Store enables SQLite WAL so foreground reads can use the last committed snapshot while WorkManager writes a provider batch. The foreground does not launch a duplicate unmatched-quote relink at startup; the Worker relinks after each provider sync segment. An API 34 instrumentation test covers reader visibility during an open provider write transaction.
- Each model pick and accumulator is saved before kickoff. Accumulators use supported sizes 2/3/5/8/10 and rank eligible legs by model marginal. A separate price-backed coupon is generated only when each fixture has a complete fresh quote from the same provider; prices are never mixed. Identical price/outcome sets are not duplicated in accumulator history, while the quote table independently preserves every observed quote timestamp.
- A full same-provider price product may be displayed as combined decimal odds. Since cross-match dependence is not modeled, joint model probability is shown only as Fréchet bounds (`max(0, sum(p)-n+1)` to `min(p)`). Conditional EV is only an experimental interval when every price exists; it is not a profit estimate. Settlement compares actual local results, flags schedule changes over 30 minutes, and omits ROI without a fully settled same-provider priced coupon.
- `TeamDataQuality` rejects known placeholder identities and same-team fixtures. Legacy rows remain in SQLite for ID/migration safety, but reads, fixture matching, models, and evaluations exclude them; parser and write paths prevent new placeholder rows.
- `MisliOddsProvider` parses only complete 1/X/2 rows from the visible `document.body.innerText` of the public `/idman-novleri/futbol` route. It requires a future event, visible date/time, device timezone, valid prices, and exact reviewed team aliases. It links an event to local history only for one exact date/home/away/kickoff match. Each quote snapshot retains its raw names, source URL, time, market and capture timestamp; UI freshness expires after 30 minutes. The WebView is loaded on app open and while foreground; WorkManager does not run this browser collector.
- `TopazOddsProvider` reads visible text from eTopaz's normal public sports tab, requires complete future 1X2 rows, and marks its inferred year. Neither provider has bypass or login logic; live observations are rejected.
- `ForegroundBookmakerCaptureGate` coalesces repeated `onPageFinished` notifications and permits one capture per 30-minute interval. If a fresh quote state is present on app open, a delayed foreground WebView load starts at the freshness boundary; one delayed reload follows each completed attempt. This does not run in WorkManager or when the app is not open.
- After commit `01706e9` passed Actions [42895248921](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/42895248921), its stable-signed APK update-installed over the data-bearing API 34 app and captured 9 Misli visible lines/0 exact local links and 25 TOPAZ/7 exact links at 20:33 UTC without another SQLite lock. Portugal—Denmark / local match 85257 had TOPAZ H/D/A 1.47/4.24/5.14. Those prices expire after 30 minutes. Misli robots and reviewed eTopaz terms do not establish explicit odds-reuse permission. The app stores snapshots locally; `LIVE` means technical capture, not legal authorization, continued availability or profitability.
- The local Elo walk-forward is computed per competition using strict saved availability cutoffs. It runs in a background thread from the Backtest tab. Date-only OpenFootball targets use a date boundary, scored results are considered available two days later, and later-captured OpenLigaDB results cannot enter earlier training. Source files are current snapshots rather than archived historical vintages, so its metrics are retrospective research, not proof of reliable future probabilities.
- `CalibrationDiagnostics` computes equal-width one-vs-rest reliability bins from walk-forward predictions and observed outcomes. The Backtest screen shows the macro mean calibration gap, per-outcome counts/ECE, and occupied-bin predicted-vs-observed frequencies. This is descriptive retrospective diagnostics; it neither fits a calibrator nor establishes prospective accuracy.

## Forecasting

`EloEngine.kt` fits same-league results whose safe availability time is strictly before the forecast cutoff. It uses initial rating 1500, K=20, home advantage +65, and a 25% draw prior weighted as four matches. Forecast display requires 10 league matches and 3 games per team. These defaults are transparent, not fitted or calibrated. The UI excludes date-only upcoming rows from kickoff-based forecasts.

`EloWalkForwardEngine.kt` and the automatic-source evaluator apply conservative `available_at` bounds. Legacy placeholder fixtures are excluded. Current source files are not archived vintages, so retrospective scores cannot establish historical real-time knowledge.

`ProbabilityEngine.kt` also contains tested 1X2 de-vigging and Poisson/Dixon–Coles mathematics. No live expected-goals feature model or market baseline is connected. These primitives do not validate betting advantage.

## Next architecture work

1. SQLite instrumentation covers upgrades through v11 and cross-provider score/provenance retention on API 34. Run update-preservation checks on API 26 and a physical Android device when available.
2. Collect future results for the immutable prospective ledger; currently captured predictions are all pending, so prospective accuracy is not available yet.
3. Extract provider/store/repository boundaries from `MainActivity.kt`; move to Room + repository/Flow when migration tests are in place.
4. WorkManager instrumentation verifies app-open enqueue constraints, uniqueness, freshness, zero-delay queueing while offline and delayed exponential retry. The network-proof Worker failure test and WAL/read-concurrency regression passed Actions [42895248921](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/42895248921): 88 JVM + 11 API 34 instrumentation tests. Rate-limit pacing coverage remains open, and the API 26 runtime continuation check remains complementary evidence.
5. Verify both collectors across timezones and more events; clarify their odds-reuse terms. Do not use stealth, hidden endpoints, login, CAPTCHA solving, or restricted detail paths.
