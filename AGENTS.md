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

The zero-touch implementation and real bookmaker WebView proof are active; continue until the user's requested app, acceptable test results, and APK verification are complete. Latest committed stage is `3cdcd96`; Actions run [42747055613](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/42747055613) passed 75 unit tests, APK build, stable signing and artifact upload. Its CI APK update-installed on API 34 and API 26 without uninstalling; API 34 preserved SQLite v10 and its data. Current uncommitted follow-up closes temporary SQLite helpers, closes the activity store on destroy, loads match/recent-result lists from SQLite on IO, and clarifies that queued WorkManager sync may be delayed by retry backoff as well as network constraints; it needs local verification, commit and CI.

Current API 34 database: 39,181 merged rows from 184 competitions, 30,090 completed-score rows before placeholder exclusion, provider counts OpenFootball 25,904 and OpenLigaDB 17,776. There are 566 legacy rows with dummy/placeholder team names. They remain in SQLite for ID/migration safety, but are filtered from list/history, fixture matching, forecasts, coupon generation and walk-forward, and new parser/upsert rows are rejected. Post-filter walk-forward was run in the installed APK: 29,588 targets, 19,185 forecasts, 10,403 skipped for insufficient history, Brier 0.6106, Log Loss 1.0235. The older unfiltered target count must not be repeated. Scores remain retrospective and uncalibrated.

Latest ordinary WebView API 34 capture after the `3cdcd96` CI APK update, at 2026-11-10 03:07 UTC, returned 9 visible future Misli rows and 25 TOPAZ rows. Seven TOPAZ quotes matched local fixtures; Misli matched zero. Portugal—Denmark maps to match 85257, with a captured 1.45 / 4.31 / 5.29 line and 59.1% Elo home marginal (basic no-margin market home probability 62.1%). The 03:07 UTC capture ages past the 30-minute freshness window. Same-book TOPAZ drafts for sizes 2/3/5 were stored previously with combined actual source prices 1.75 / 2.09 / 26.87. These are time-bound technical observations, not license or profit evidence. No explicit automated odds-reuse permission is established. Never bypass protection or fabricate quotes. WorkManager does not run the WebView; odds are foreground-only.

The latest committed stage's full local build and CI passed. CI artifact API 34 update-install preserved 39,181 matches, 120 quote snapshots, and 59 accumulator rows; provider states remained OpenFootball `OK`/25,904 and OpenLigaDB `OK`/17,776. A local follow-up build on API 34 now preserves schema v10 and 39,181 matches; at the latest capture it stored 163 quote snapshots and 59 accumulator rows. API 26 CI update-install preserved schema v10 and its existing cache; sync has since progressed to OpenFootball `OK`/25,904 and OpenLigaDB `PARTIAL`/306 with the lease active and WorkManager continuation pending. This remains incomplete. Both API 26 bookmaker WebViews return no visible page text and display `ERROR`. No physical handset or dedicated Android instrumentation run has yet been completed. The local follow-up moves the 39k match-list sort to IO and closes temporary Store lifetimes; it needs commit/CI and API 26 continuation must be checked without interrupting active work. See [`docs/DEVELOPMENT_LOG.md`](docs/DEVELOPMENT_LOG.md) for the continuation checklist.
