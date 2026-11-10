# Architecture

## Current implementation (native Android, local-first)

```text
MainActivity / Compose
  ├── WorkManager scheduler (first-open stale check + daily periodic)
  │     └── AutomaticFootballSyncWorker (network constraint, unique lock, retry/backoff)
  │            ├── OpenFootballSync → GitHub directory discovery + public raw CC0 JSON
  │            └── OpenLigaDbSync → official API catalog, matches, group/change polling
  ├── Misli public-page WebView (foreground, visible DOM only, 30-minute throttle)
  ├── Store / SQLite v8 (matches, source timestamps/cache, bookmaker snapshots/status, coupon forecasts)
  ├── EloEngine → exact-kickoff forecasts with safe-availability cutoff
  └── VirtualCouponBuilder → separate provider-specific model drafts and fresh matching quotes
```

No server, account, secret, paid API, or bookmaker action exists. WorkManager runs best-effort; Android controls exact scheduling. Public source rows and HTTP metadata are persisted for offline use. CSV remains an optional Settings feature.

## Provider and persistence rules

- `ProviderMatch` keeps stable provider ID, competition/season, event date, exact-vs-date-only kickoff, source URL, capture time, and safe result-availability time.
- `OpenFootballJsonParser` consumes repository catalog JSON and match files. Paths are validated; duplicate rows within a file are removed; missing competition files remain absent instead of being invented. A date-only fixture never gets an estimated kickoff.
- `OpenLigaDbParser` discovers only football leagues, accepts final-result type records, and parses explicit UTC kickoff. Requests are spaced 1.25 seconds apart (48/min), under the published 60/minute limit. Current-year leagues use current group and last-change checks; older catalogs refresh weekly. Each worker stops after eight minutes and retries.
- SQLite upserts are stable by provider source key. An incomplete observation cannot erase a stored score. Cross-provider merging requires one strict same-date, same-home/away normalized candidate; if there are zero or multiple candidates, the app does not guess. A more precise kickoff/result can upgrade the existing row while preserving source URLs and combining source labels.
- Each model coupon draft is inserted once per operator and match before kickoff. The probability is captured with the selection and later checked against the stored result. No price, combined decimal odds, or joint win probability is fabricated.
- `MisliOddsProvider` parses only complete 1/X/2 rows from the visible `document.body.innerText` of the public `/idman-novleri/futbol` route. It requires a future event, visible date/time, device timezone, valid prices, and exact reviewed team aliases. It links an event to local history only for one exact date/home/away/kickoff match. Each quote snapshot retains its raw names, source URL, time, market and capture timestamp; UI freshness expires after 30 minutes. The WebView is loaded on app open and while foreground; WorkManager does not run this browser collector.
- `TopazOddsProvider` reads visible text from eTopaz's normal public sports tab, requires complete future 1X2 rows, and marks its inferred year. Neither provider has bypass or login logic; live observations are rejected.
- On API 34, both collectors returned future public lines (8 Misli, 25 eTopaz). Misli robots rules and eTopaz's reviewed terms did not establish explicit odds-reuse permission. The app stores local snapshots and does not redistribute them; `LIVE` reports technical capture only, not legal authorization, continued line availability, or model profitability.
- The local Elo walk-forward is computed per competition using strict saved availability cutoffs. It runs in a background thread from the Backtest tab. Date-only OpenFootball targets use a date boundary, scored results are considered available two days later, and later-captured OpenLigaDB results cannot enter earlier training. Source files are current snapshots rather than archived historical vintages, so its metrics are retrospective research, not proof of reliable future probabilities.

## Forecasting

`EloEngine.kt` fits same-league results whose safe availability time is strictly before the forecast cutoff. It uses initial rating 1500, K=20, home advantage +65, and a 25% draw prior weighted as four matches. Forecast display requires 10 league matches and 3 games per team. These defaults are transparent, not fitted or calibrated. The UI excludes date-only upcoming rows from kickoff-based forecasts.

`EloWalkForwardEngine.kt` supports an optional CSV dataset; same-day targets share one as-of model snapshot and results observe the conservative `available_at` bound. Automatic-source history has not yet been run through a historical walk-forward evaluation, and current files do not preserve historical vintages.

`ProbabilityEngine.kt` also contains tested 1X2 de-vigging and Poisson/Dixon–Coles mathematics. No live expected-goals feature model or market baseline is connected. These primitives do not validate betting advantage.

## Next architecture work

1. Add instrumentation coverage for SQLite preservation and run on API 26 plus a physical Android device.
2. Extract provider/store/repository boundaries from `MainActivity.kt`; move to Room + repository/Flow when migration tests are in place.
3. Add WorkManager integration tests for first-open enqueue, retry, offline cache, uniqueness, and rate-limit request pacing.
4. Verify both collectors across timezones and more events; clarify their odds-reuse terms. Do not use stealth, hidden endpoints, login, CAPTCHA solving, or restricted detail paths.
5. Improve exact fixture coverage for bookmaker events; keep unmatched quotes separate from model forecasts.
