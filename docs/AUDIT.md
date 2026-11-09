# Repository audit

**Audit date:** 2026-11-09

**Repository:** [jafarovsamir89/Football-Intelligence](https://github.com/jafarovsamir89/Football-Intelligence)
**Audited revision:** b09f96d (main)

## Summary

This is a small, native Android Compose prototype, not yet the full football research system described in the master prompt. The existing work is worth keeping: it establishes a no-account, local-first product direction, one public results provider, manual odds/pick entry, and paper single bets. No reason was found to restart the project.

## What exists and works in the audited revision

- Kotlin Android app, Compose UI, minSdk 26, compileSdk/targetSdk 35, Java 17.
- One MainActivity.kt contains UI, data classes, direct OpenLigaDB HTTP loading, JSON parsing, SQLite storage, settlement, and simple analysis.
- OpenLigaDB fixture/results refresh for hard-coded Bundesliga and 2. Bundesliga seasons. Previously downloaded rows are read from SQLite offline.
- Manual three-way 1X2 bookmaker snapshots and manual, unverified source picks.
- Basic reciprocal-probability normalization, recent-price filtering, virtual single bets, and ROI over settled tickets.
- A GitHub Actions workflow builds and uploads a debug APK. No Gradle wrapper is present.
- README and Russian roadmap state the zero-cost, no-real-bets constraints.

## CI and commit history

The repository history contains the initial Android bootstrap, offline fixtures/manual entry, Android/CI fixes, and roadmap documentation. The most recent run on the audited revision succeeded, produced the football-intelligence-debug-apk artifact, and completed the debug APK build: [run 42698704826](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/42698704826). Four immediately preceding runs failed in environment setup before compiling the app: two could not run android-actions/setup-android@v3, and two called sdkmanager when it was not on PATH. The latest workflow removed that brittle setup and uses the runner's installed SDK.

## Findings and risks

1. **No automated tests.** Probability calculations, result parsing, database behavior, time cutoffs, and settlement had no test coverage.
2. **Single-file architecture.** Network, persistence, domain calculations, and Compose UI are coupled in one 328-line activity file. There are no repository/domain/presentation boundaries, Room, Coroutines/Flow, WorkManager, import/export, or dependency injection.
3. **Settlement data could be erased.** INSERT OR REPLACE deletes and reinserts a match row. A later incomplete API response could clear a previously recorded result; SQLite REPLACE also has delete semantics. The first implementation change replaces that write with a guarded update/insert.
4. **Cross-book price display was ambiguous.** The UI displayed per-outcome maxima without saying each outcome could come from a different bookmaker. This is not a single available line or accumulator price. The display is relabeled, and each saved bookmaker snapshot remains a separate 1X2 market.
5. **Freshness accepted future timestamps.** A negative quote age passed the old “less than 10 minutes” check. New freshness selection requires a timestamp no later than the device clock.
6. **Market modeling was basic only.** The original calculation normalized reciprocal 1X2 prices. No Power/Shin comparison, consensus, football model, calibration, scoring, or confidence intervals existed.
7. **Data integrity is incomplete.** There is no canonical match identity across providers, odds market schema/versioning, source-captured timestamp separate from ingestion, CSV/JSON import, deduplication across feeds, retries/backoff, or schema migration.
8. **Manual data is not verified.** User-entered odds and picks have no external availability or provenance verification; picks lack an explicit publication time beyond local insertion time. The UI must not describe them as confirmed.
9. **Settlement scope is narrow.** Only a final 1X2 home/draw/away outcome and ordinary single tickets are handled. Postponements, voids, extra time/penalties, Asian lines, and accumulators are unsupported.
10. **Build reproducibility is weaker than desired.** CI pins Gradle 8.11 through setup action, but the repository does not include a Gradle wrapper. Local build depends on a globally installed Gradle/Android Studio.
11. **At audit time, the worktree environment had JDK 17 and Android SDK but no gradle command.** The follow-up milestone adds a wrapper and verifies local tasks; see the development log.

## Components to keep

- Kotlin/Compose Android shell and application ID.
- OpenLigaDB as an optional fixtures/results source, with careful request frequency and ODbL attribution.
- Manual entry as an honest fallback for bookmaker lines and crowd picks.
- Local SQLite persistence and explicit paper-bet-only semantics.
- Latest GitHub Actions strategy using the preinstalled Android SDK.

## Work started after audit

The first follow-up milestone extracts tested, Android-free calculations for market margin removal (basic, Power, Shin), Poisson/Dixon–Coles score probabilities, Brier score, log loss, and unit-stake expected value. It also protects settled match rows from incomplete refresh data and labels cross-book maxima as separate prices. See DEVELOPMENT_LOG.md and CHANGELOG.md for verification status.

## Not yet audited

No end-to-end Android device interaction, Play-distribution readiness, legal review of every dataset, historical walk-forward experiment, or APK installation on a physical device has been performed. “Successful build” refers only to GitHub Actions' debug APK build on the audited revision.
