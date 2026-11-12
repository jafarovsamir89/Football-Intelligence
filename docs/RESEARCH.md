# Research notes

**Reviewed:** 2026-11-09. This document records candidate methods, not evidence of a profitable strategy.

## Forecasting and scoring

- **Market baseline:** convert the three decimal 1X2 prices to implied probabilities, then remove overround. The app now exposes basic proportional normalization, Power, and Shin calculations. A margin-free estimate is still a market estimate; the user-entered price may not be available and the book's margin model is not known.
- **International reference:** TipsAudit offers a free, no-key upcoming 1X2 API with visible attribution. It publishes a single derived fair estimate (sharp-market price after power devig where available, its model in some uncovered leagues), not independently enumerable raw Pinnacle/Betfair/bet365 lines. The Android adapter stores it outside bookmaker odds, accepts only fresh `fair_source=live` reads captured before kickoff, links exact fixtures, and uses it for descriptive probability differences only. It ignores the soft-book price/value signal, does not compute our EV, does not feed coupons, and does not create an independent market consensus. First direct capture returned 98 rows but the example Türkiye—Belgium read was ~47 minutes old, so no current reference was eligible at that check.
- **Poisson baseline:** independent home/away goal counts parameterized by expected goals generate a score matrix and internally consistent 1X2 probabilities. Expected goals are not fitted from team histories yet, so the app does not present this as a fixture forecast.
- **Dixon–Coles correction:** an optional rho changes the 0–0, 0–1, 1–0, and 1–1 cells to represent low-score dependence. The current implementation is a deterministic math primitive; rho is not estimated from data.
- **Evaluation:** Brier score and log loss are implemented for an observed one-hot 1X2 result and are reported by the retrospective automatic-source walk-forward. They are meaningful only as chronological historical diagnostics here: current source snapshots are not archived vintages, so they do not prove the information actually available at each past date or establish future accuracy.
- **Elo parameter experiment:** the app now evaluates a fixed grid over K={10,20,30}, home advantage={0,65,100}, and draw prior={0.22,0.25,0.28}. It partitions distinct cutoff timestamps into 60% warm-up, 20% validation and 20% final holdout; uses validation Log Loss for selection; requires at least 100 validation forecasts; and compares the selected parameters with the fixed production defaults only on holdout. Ratings remain isolated by competition and every target uses only history whose conservative availability is before its cutoff. This is one split of current source snapshots, not archived vintages, and the selected settings are diagnostic only: they do not replace the production model.
- **Expected value:** probability × decimalOdds − 1 is an algebraic conditional expectation per unit stake. It depends on a calibrated probability and an actually available price; those conditions are not established by the current prototype.

The [penaltyblog model documentation](https://penaltyblog.readthedocs.io/en/latest/models/overview.html) is a useful reference for goal models and market probabilities. It is Python software and is not embedded in Android. [Socceraction](https://github.com/ML-KULeuven/socceraction) is a research toolkit for event/action-value analysis, not a general match-result predictor or a source of licensed event data.

## Data and inference limits

- A result archive alone does not contain pre-match information timestamps. It can support retrospective form features only if each training row is cut strictly before kickoff.
- Football-Data.co.uk's current usage policy limits the free files to private personal betting/academic use and allows manual downloads or at most light automation (twice weekly maximum); it excludes commercial/data-training projects with heavy automated bots/scrapers/AI. The app only imports a file the user selected and does not fetch or redistribute it. Its first CSV importer keeps completed scores only and does not ingest odds. The `Date` column is treated as a source-local date with no trusted timezone; imported results are withheld until UTC midnight two days later. This conservative availability bound avoids using a same-day result at an earlier kickoff, at the cost of omitting some recent information.
- Closing odds are a useful benchmark but can leak future market movement if treated as known earlier. Opening, snapshot, and closing prices must remain distinct.
- The app's current manual picks do not establish when a tipster first published the pick and can omit losing/deleted tips. Do not calculate a credible historical ROI from a selectively entered sample.
- Multiple accounts may copy one original prediction. Distinct names do not imply independent evidence.
- The [Blogabet study](https://alexandria.unisg.ch/entities/publication/fe6e807f-18fa-45b5-9e7b-d49d20151c3a) reports 1,534,041 picks from 3,484 tipsters and an average yield in its historical platform sample. The raw event records were not located as an open, licensed dataset. That result motivates rigorous investigation; it is not a present-day product claim.
- A separate [OddsPortal crowd study](https://ueaeprints.uea.ac.uk/id/eprint/67529/) reports 1.317% average returns from a majority-prediction strategy across 68,339 events. It is a historical research result, not an independently reproduced estimate for this application.
- Average crowd consensus may simply restate the market or share common information. Any crowd contribution must be compared to a timestamp-matched, de-vigged market baseline on future data.

## Experimental policy

Before any strategy is promoted:

1. Freeze a dated dataset and record data licenses and capture timestamps.
2. Split training/validation/test by time and use walk-forward evaluation.
3. Compare against market-implied probability and simple Poisson/Elo baselines.
4. Report calibration, Brier, log loss, ROI, CLV, drawdown, sample count, uncertainty intervals, and stake assumptions.
5. Keep tuning out of the final test window; publish failures and versions in the experiment log.

The live reference ledger is not a historical odds archive: each saved fair-market snapshot expires from comparisons after 30 minutes, and the current API POC found no fresh row. A future market baseline evaluation needs timestamped coverage and a separate held-out interval; today's single-feed comparisons are descriptive only.

The app now has a date-bucketed Elo walk-forward evaluator for user-imported score CSVs, but no real historical file has been run through it in this workspace. It emits Brier and Log Loss only and has no market baseline. The project still has no full-coverage timestamped odds or licensed tipster dataset.
