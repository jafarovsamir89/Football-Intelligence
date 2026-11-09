# Current limitations

The current release is a research notebook and paper-bet tracker, not a validated prediction product.

- Only selected OpenLigaDB Bundesliga seasons are wired into the UI; availability, completeness, and updates depend on the community provider.
- Odds and picks are manually entered, unverified, and stored only from entry onward. Tipster publication time, full history, source independence, and actual price availability are not verified.
- The supported market is 1X2 only. The current snapshot table has no explicit market/provider schema fields, and no other market may be combined with these odds.
- Basic/Power/Shin calculations remove margin from an entered three-way line; they do not establish value or beat a bookmaker.
- The UI has an experimental Elo baseline fitted from saved completed results in the same league before the selected kickoff. It uses fixed defaults and is shown only after at least 10 league matches and 3 matches per team. A separate date-bucketed walk-forward evaluator reports Brier/Log Loss for imported results with a conservative `available_at` delay. The evaluation lacks historical source vintages and odds, so it is not a market comparison or proof of predictive edge. Elo is not calibrated; Poisson/Dixon–Coles remain mathematical score-grid primitives, with no expected-goal feature dataset connected.
- There is no frozen live-forecast log, CLV, confidence interval, drawdown, or strategy comparison. Walk-forward scores are available only for imported result datasets and use no historical market baseline.
- No accumulator builder or joint-probability/correlation model is implemented. The app supports virtual single bets only.
- Manual Football-Data-style CSV import stores completed score rows and provenance in separate tables. It ignores odds, date-only records become available only after a conservative two-day delay, and imported competitions are not mapped into live fixtures. JSON import/export, backup/restore, WorkManager refresh, and cloud sync do not exist. SQLite has a v1→v2 migration, but no dedicated instrumentation migration test.
- The current GitHub Actions run builds an unsigned release-grade debug APK signed by the debug key; no release signing or Play Store distribution is configured.
- Some data providers' terms, quotas, and access vary by country/plan and must be rechecked. No restricted site scraping or paid API is enabled.
