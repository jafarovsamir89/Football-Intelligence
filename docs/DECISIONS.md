# Decision log

## D-001: Keep the product local-first and no-cost

The product remains a native Android app with device-local user data. No backend, subscription, paid API, shared API secret, or betting-account automation is required.

## D-002: OpenLigaDB is an optional score/fixture source

OpenLigaDB has a documented unauthenticated JSON API and an explicit 60 requests/minute/IP limit. Refresh is user-triggered for now. Its data is stated under ODbL and must be attributed. It does not supply bookmaker lines.

## D-003: Manual entry remains an honest fallback

Until a free and permitted live odds source is confirmed, bookmaker lines and picks are entered by the user. The app labels them unverified and timestamps local capture. It does not imply that a quoted line was executable.

## D-004: Keep analyses market-specific

Quotes remain three outcomes from one bookmaker, one match, one 1X2 market, and one timestamp. Cross-book best prices are displayed only as separate price-shopping references with an explicit warning; they are not silently combined into one ticket.

## D-005: Pure Kotlin math before ML

Market de-vigging, score-probability math, and proper scores are isolated from Android UI and covered by unit tests. No ML model is selected until timestamped, permitted historical data and independent walk-forward results exist.

## D-006: Guard settled match records

Use update/insert semantics rather than SQLite REPLACE, so refreshing provider fixtures does not delete/recreate rows or erase a prior score when an incoming match is incomplete.

## D-007: Do not automate crowd site ingestion without permission

Reviewed platform terms do not establish a right to scrape/replicate Tipstrr or Pikkit; no public licensed Blogabet prediction feed was identified. Store user-provided records only, with an unverified status, unless the source offers permission and a compatible data export/API.

## D-008: Gate the first Elo forecast on pre-match local history

Use only completed results stored for the same league and before kickoff. The initial baseline uses fixed Elo defaults and a smoothed draw rate. Suppress 1X2 output until the history contains at least 10 league matches and 3 appearances per team. Keep it experimental and uncalibrated until live forecasts are frozen, performance is checked on a suitable future sample, and a timestamped market baseline can be compared.

## D-009: Import historical scores through explicit file selection

Do not crawl or schedule downloads from Football-Data.co.uk. Support an explicit user-selected CSV under that provider's personal-use policy, record the source filename and import time, and keep imported results in their own tables for a standalone walk-forward reader. Ignore odds columns in this first importer. Because the date has no trusted timezone, mark a result usable only at UTC midnight two days after the listed calendar date. This errs on the side of excluding data that might not yet have been available before kickoff. Do not map these rows into live fixtures until an explicit competition/team mapping is added.
