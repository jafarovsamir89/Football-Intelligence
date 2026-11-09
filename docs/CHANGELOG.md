# Changelog

## Unreleased — 2026-11-09

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
