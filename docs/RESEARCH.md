# Research notes

**Reviewed:** 2026-11-09. This document records candidate methods, not evidence of a profitable strategy.

## Forecasting and scoring

- **Market baseline:** convert the three decimal 1X2 prices to implied probabilities, then remove overround. The app now exposes basic proportional normalization, Power, and Shin calculations. A margin-free estimate is still a market estimate; the user-entered price may not be available and the book's margin model is not known.
- **Poisson baseline:** independent home/away goal counts parameterized by expected goals generate a score matrix and internally consistent 1X2 probabilities. Expected goals are not fitted from team histories yet, so the app does not present this as a fixture forecast.
- **Dixon–Coles correction:** an optional rho changes the 0–0, 0–1, 1–0, and 1–1 cells to represent low-score dependence. The current implementation is a deterministic math primitive; rho is not estimated from data.
- **Evaluation:** Brier score and log loss are implemented for an observed one-hot 1X2 result. They are only meaningful after chronological, out-of-sample predictions are stored. Neither score is currently calculated from application history.
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

The app now has a date-bucketed Elo walk-forward evaluator for user-imported score CSVs, but no real historical file has been run through it in this workspace. It emits Brier and Log Loss only and has no market baseline. The project still has no full-coverage timestamped odds or licensed tipster dataset.
