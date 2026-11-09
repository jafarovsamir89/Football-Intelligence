# AZERBAIJAN ODDS TOP PRIORITY (2026-11-09)

Read docs/AZERBAIJAN_BOOKMAKERS_RU.md before touching odds functionality. User demands zero-touch real Misli and TOPAZ prematch lines without mandatory official API, paid APIs, manual prices or CSV. Research normal public-page browser/WebView DOM and permitted public feeds, validate exact numbers and timestamps, create separate Android provider adapters and bookmaker-specific virtual coupons. Do not bypass access or bot protections, invent quotes or claim live prices if source not working. This is a mandatory addition to the existing ZERO_TOUCH_DATA requirement.

---

# TOP PRIORITY: ZERO MANUAL WORK (USER REQUIREMENT 2026-11-09)

The user rejected manual CSV import and any requirement to manually download datasets, enter odds, register for API services, or maintain data. **Implement a zero-touch experience; see [docs/ZERO_TOUCH_DATA_RU.md](docs/ZERO_TOUCH_DATA_RU.md) for detailed requirements and acceptance tests.** Build automatic keyless legal data sync first (OpenLigaDB + OpenFootball football.json CC0), local offline cache, best-effort WorkManager scheduled refresh, history and model analysis. Move CSV import out of the main user journey into advanced settings. Where bookmaker odds or independently verified crowd picks lack a free legitimate automated provider, show them as unavailable; do not synthesize fake bookmaker data or assert betting EV. This instruction supersedes any previous request that manual CSV be a primary milestone.

---

# Agent instructions — Football Intelligence

## Mission

Continue the user's master request in `docs/DEVELOPMENT_LOG.md` and the roadmap until the native Android project has a tested, honest implementation of its stated product goals. This is ongoing engineering work, not a request to stop after an audit, plan, or first APK. Inspect the current branch, the development log, the roadmap, recent commits, and CI before resuming. Record progress and the next concrete task in the development log so another session can continue without repeating work.

## Product constraints

- Keep the app native Android/Kotlin and local-first. Budget is $0: do not add paid APIs, subscriptions, hosted services, or a dependency that requires payment.
- Do not place real bets or connect bookmaker accounts. All app tickets are virtual.
- Use public data only under terms that allow the intended access. Do not scrape restricted services, bypass authentication/CAPTCHA, use leaked keys, or embed secrets.
- Attribute sources and record data provenance, event time, and capture time where relevant. Never invent fixtures, quotes, tipster records, or model quality.
- Prevent temporal leakage. A forecast may only use information available before its forecast cutoff. Label uncalibrated or unvalidated experimental estimates clearly.
- Preserve existing user data and the existing Android app unless a documented reason justifies a migration.

## Working practices

- Make progress directly in the repository; do not return only a plan while implementation remains feasible.
- For each substantial milestone, update the code, tests, relevant documentation, and `docs/DEVELOPMENT_LOG.md`; run the appropriate tests and APK build; inspect the result; then create an explanatory Git commit. The user authorized work on and pushes to the linked GitHub repository. Verify GitHub Actions before calling the milestone CI-verified.
- Keep `README.md`, `docs/ROADMAP_RU.md`, `docs/LIMITATIONS.md`, and `docs/CHANGELOG.md` aligned with actual behavior. Keep `docs/DATA_SOURCES.md` and `docs/RESEARCH.md` explicit about licensing, access, and evidence.
- Do not claim success for checks that were not run. Do not present ROI, consensus, EV, or model probabilities as evidence of future profit.
- Prefer small, reviewable changes that fit a single-device app. Extract presentation/domain/data boundaries incrementally; do not add infrastructure without need.

## Current continuation point

Read `docs/DEVELOPMENT_LOG.md` for the latest verified commits and active milestone. The CSV import is committed as `a4dc94f` and passed GitHub Actions run `42711478723` (26 tests + debug APK artifact). The active uncommitted milestone adds safe-availability-aware Elo walk-forward evaluation and a backtest screen; its local run passes 32 tests and builds the APK. Review cutoff logic and UI state, update documentation, commit/push, and verify GitHub Actions. Then continue with the master prompt's market baseline and import/provenance gaps. The CSV migration and picker still lack Android instrumentation tests.
