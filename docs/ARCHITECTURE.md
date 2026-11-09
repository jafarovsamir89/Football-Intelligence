# Architecture

## Current architecture (v0.1 baseline)

The application is a single-module native Android app. Compose screens in MainActivity.kt read and write a local SQLiteOpenHelper store directly. The same file owns OpenLigaDB HTTP calls, JSON parsing, basic probability math, ticket settlement, and presentation state.

~~~text
Compose UI (MainActivity)
  ├── OpenLigaDB HTTPS request → JSON parser
  ├── SQLiteOpenHelper (matches, quotes, picks, tickets)
  └── inline calculations (settlement, basic 1X2 margin removal)
~~~

There is no server, registration, cloud sync, bookmaker account integration, or real betting action. The app continues to display locally saved data when no network is available.

## First extraction

ProbabilityEngine.kt is a pure Kotlin domain module with no Android dependency. It currently contains:

- Three-way probability value object and decimal odds validation.
- Basic proportional normalization, Power, and Shin margin removal for 1X2 odds.
- Independent Poisson score grid and optional Dixon–Coles low-score correction.
- Multiclass Brier score, log loss, and per-unit expected value calculation.
- Provider timestamp normalization and quote freshness rules (UTC and no future snapshots).

These methods are mathematical utilities. They do not fit team parameters, fetch data, establish quote availability, or claim predictive advantage. Unit tests live under app/src/test.

## Target boundaries

The next safe refactors should split the monolith without changing the no-server constraint:

~~~text
Presentation (Compose + ViewModels)
          ↓ user actions / observable state
Domain (match, quote, pick, paper ticket, model use cases)
          ↓ repository interfaces
Data (Room/SQLite, OpenLigaDB adapter, manual SAF import, export)
          ↓
Public provider or device-local files
~~~

Recommended sequencing:

1. Extract data and domain models/calculations; add migrations before adding columns.
2. Introduce repository interfaces and a tested OpenLigaDB adapter with explicit UTC parsing and rate-aware caching.
3. Move persistence to Room and UI state to ViewModel + Flow; use WorkManager only for user-configured background refresh.
4. Add Storage Access Framework CSV/JSON import/export. Keep provider IDs and canonical IDs distinct.
5. Add historical model/backtest tools only after timestamped, legally usable historical datasets are available.

Do not introduce a backend, paid dependency, embedded shared API secret, or real wagering integration.
