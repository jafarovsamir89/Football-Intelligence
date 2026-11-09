# Testing

## Current checks

Automated JUnit 4 unit tests cover the extracted pure Kotlin analytics engine:

- Basic, Power, and Shin 1X2 margin removal.
- Invalid and non-finite decimal odds.
- Poisson and Dixon–Coles output normalization and invalid parameters.
- Brier score, log loss, and per-unit expected value.

Run with:

~~~sh
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
~~~

The repository includes the Gradle 8.11.1 wrapper with the distribution SHA-256 pinned. On 2026-11-09, JDK 17 and the installed Android SDK were used to run both commands above successfully: all 11 unit tests passed and the debug APK built locally. GitHub Actions run 42705882847 passed unit tests, built the APK, and uploaded football-intelligence-debug-apk with the updated Node 24 workflow. CI uses the same wrapper tasks.

The run returned one maintenance annotation: Gradle 8.11.1 is out of date. The project currently uses Android Gradle Plugin 8.7.3, whose official compatibility table sets Gradle 8.9 as the minimum. Gradle 8.11.1 builds successfully; update the Android plugin and wrapper together in a separately verified toolchain migration. [Android Gradle Plugin compatibility table](https://developer.android.com/build/releases/about-agp).

## Not yet covered

- SQLite migrations, preserving settled results, duplicate provider IDs, and repository behavior.
- OpenLigaDB parser fixtures and error/status handling.
- Timestamp parsing/UTC, stale/future price rejection, and DST cases.
- CSV/JSON imports, market mapping, and unsupported-market rejection.
- Ticket settlement, postponements, cancellations, and void rules.
- Compose UI, offline launch, database growth, and device API 26 behavior.
- Walk-forward validation/data-leakage checks, calibration, CLV, and confidence intervals.

No claim should be made that these cases passed until their tests exist and run.
