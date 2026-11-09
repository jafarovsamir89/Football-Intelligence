# Testing

## Current checks

Automated JUnit 4 unit tests cover the extracted pure Kotlin analytics engine:

- Basic, Power, and Shin 1X2 margin removal.
- Invalid and non-finite decimal odds.
- Poisson and Dixon–Coles output normalization and invalid parameters.
- Brier score, log loss, and per-unit expected value.
- Elo safe-availability filtering, future-match leakage guard, same-availability batching, home advantage, smoothed draw prior, invalid rows, and minimum-history thresholds.
- Walk-forward availability cutoffs, shared same-date model snapshots, same-availability Elo batching, Brier/log-loss aggregation, and insufficient-history skips.
- CSV parsing for required/optional headers, quoted and semicolon fields, supported date forms, incomplete/invalid rows, duplicate suppression, UTF-8/Windows-1252, and the conservative date-only availability cutoff.

Run with:

~~~sh
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
~~~

The repository includes the Gradle 8.11.1 wrapper with the distribution SHA-256 pinned. On 2026-11-09, JDK 17 and the installed Android SDK ran `./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug` successfully: the CSV importer revision passed 26 tests locally, and the current walk-forward working tree passes 32 tests and builds the debug APK. GitHub Actions run 42711478723 passed the CSV importer revision and uploaded `football-intelligence-debug-apk`; CI for the walk-forward changes is still required. CI uses the same wrapper tasks.

The run returned one maintenance annotation: Gradle 8.11.1 is out of date. The project currently uses Android Gradle Plugin 8.7.3, whose official compatibility table sets Gradle 8.9 as the minimum. Gradle 8.11.1 builds successfully; update the Android plugin and wrapper together in a separately verified toolchain migration. [Android Gradle Plugin compatibility table](https://developer.android.com/build/releases/about-agp).

## Not yet covered

- SQLite migration preservation, duplicate provider IDs, and repository behavior.
- Runtime CSV-to-SQLite v1→v2 migration and transaction behavior, document-picker flow, and database-to-walk-forward integration.
- OpenLigaDB parser fixtures and error/status handling.
- Timestamp parsing/UTC, stale/future price rejection, and DST cases.
- JSON import/export, imported-competition mapping, historical market baseline comparison, and unsupported-market rejection.
- Ticket settlement, postponements, cancellations, and void rules.
- Compose UI, offline launch, database growth, and device API 26 behavior.
- Large-file performance, calibration, historical odds/market comparison, CLV, and confidence intervals.

No claim should be made that these cases passed until their tests exist and run.
