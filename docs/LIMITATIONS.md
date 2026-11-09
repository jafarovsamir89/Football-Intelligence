# Current limitations

The current release is a research notebook and paper-bet tracker, not a validated prediction product.

- Only selected OpenLigaDB Bundesliga seasons are wired into the UI; availability, completeness, and updates depend on the community provider.
- Odds and picks are manually entered, unverified, and stored only from entry onward. Tipster publication time, full history, source independence, and actual price availability are not verified.
- The supported market is 1X2 only. The current snapshot table has no explicit market/provider schema fields, and no other market may be combined with these odds.
- Basic/Power/Shin calculations remove margin from an entered three-way line; they do not establish value or beat a bookmaker.
- Poisson/Dixon–Coles currently expose mathematical score-grid primitives only. There is no team-strength fit, Elo rating history, expected-goal feature dataset, or calibrated match forecast in the UI.
- Brier/log-loss utilities are implemented, but there is no frozen forecast log, chronological backtest, walk-forward evaluation, CLV, confidence interval, drawdown, or strategy comparison.
- No accumulator builder or joint-probability/correlation model is implemented. The app supports virtual single bets only.
- No CSV/JSON import/export, backup/restore, WorkManager refresh, Room migration system, or account/cloud sync exists.
- The current GitHub Actions run builds an unsigned release-grade debug APK signed by the debug key; no release signing or Play Store distribution is configured.
- Some data providers' terms, quotas, and access vary by country/plan and must be rechecked. No restricted site scraping or paid API is enabled.
