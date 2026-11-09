# Architecture

## Current architecture (v0.1 baseline)

The application is a single-module native Android app. Compose screens in MainActivity.kt read and write a local SQLiteOpenHelper store directly. The same file owns OpenLigaDB HTTP calls, JSON parsing, basic probability math, ticket settlement, and presentation state.

~~~text
Compose UI (MainActivity)
  ├── OpenLigaDB HTTPS request → JSON parser
  ├── SQLiteOpenHelper v2 (matches, quotes, picks, tickets, imported results and import log)
  └── analytics (margin removal, experimental as-of Elo baseline, settlement)
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

`EloEngine.kt` fits a simple Elo rating sequence from locally stored completed results in the selected league. It filters strictly to kickoff times before the selected match, uses defaults of initial rating 1500, K=20, home advantage +65 rating points, and a 25% draw prior with weight 4 matches, and emits 1X2 only after at least 10 league matches and 3 appearances for each team. It is marked experimental and uncalibrated. There is no immutable forecast log or walk-forward score yet, so this is not evidence that the model predicts better than market odds.

The database version 2 migration adds a separate `historical_imports` log and deduplicated `historical_results` table without rewriting existing matches, quotes, picks, or tickets. The matches screen uses Android's system document picker for a user-selected CSV file; [Android's Storage Access Framework](https://developer.android.com/training/data-storage/shared/documents-files) grants access to the chosen URI. The parser accepts Football-Data-style `Div`, `Date`, `HomeTeam`, `AwayTeam`, and `FTHG`/`HG`, `FTAG`/`AG` fields, records the filename, import time, and an unverified format/source label, and deliberately ignores odds. Source dates are local calendar dates without a trusted timezone; each row stores an availability boundary of UTC midnight two days later for future analysis. This conservative bound avoids same-day leakage across civil time zones. Imported competitions remain separate from OpenLigaDB league IDs until a future explicit mapping is implemented.

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
4. Extend the initial Storage Access Framework CSV result import with explicit competition mapping, then add JSON import/export. Keep provider IDs and canonical IDs distinct.
5. Store immutable forecasts and add chronological walk-forward evaluation before presenting any model as validated; keep imported historical data tied to its source and license.

Do not introduce a backend, paid dependency, embedded shared API secret, or real wagering integration.
