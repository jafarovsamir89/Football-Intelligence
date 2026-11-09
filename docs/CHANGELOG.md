# Changelog

## Unreleased — 2026-11-09

- Added a standalone date-bucketed Elo walk-forward screen over imported results, with safe-availability filtering and mean Brier/Log Loss. It explicitly does not claim a market baseline or validated edge.
- Added explicit Android document-picker import for Football-Data-style CSV completed results, a v1→v2 SQLite migration, import provenance log, duplicate suppression, and a conservative two-day availability bound for date-only rows.
- Added CSV parsing tests for quoted/semicolon files, UTF-8 and Windows-1252 decoding, invalid/unfinished rows, duplicate rows, two-digit years, and date-only availability.
- Added an experimental Elo 1X2 baseline fitted only from same-league results strictly before kickoff; suppresses forecasts below the documented history thresholds and labels the model uncalibrated.
- Added Elo unit tests for cutoff filtering, future leakage, home advantage, draw prior, and sample-size gating.
- Added pure Kotlin 1X2 market de-vigging with basic, Power, and Shin methods.
- Added independent Poisson score-grid and optional Dixon–Coles low-score adjustment primitives.
- Added Brier, log-loss, and per-unit expected-value calculations with JUnit coverage.
- Added Gradle 8.11.1 wrapper with checksum and CI unit-test execution.
- Guarded match upserts so incomplete refreshes cannot replace a previously settled result.
- Normalized zone-less provider timestamps to UTC and preserved explicit offsets.
- Corrected ROI and ticket history to resolve all stored ticket matches, independently of the 250-row match list.
- Rejected future-dated quote snapshots for freshness/virtual-price selection.
- Labeled market consensus as a simple average of user-entered lines and cross-book maxima as separate prices, not one available coupon.
- Added source audit, architecture, research, testing, limitations, decision, and development-log documentation.

## 0.1.0 — initial prototype

- Android Compose shell, local SQLite, selected OpenLigaDB match refresh, manual 1X2 odds and pick entry, virtual single tickets, and settled-ticket ROI display.
