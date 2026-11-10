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

The zero-touch implementation is complete and pushed on `main` through `5a64c43`; the final verification notes are in `docs/DEVELOPMENT_LOG.md`. GitHub Actions run [42731992205](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/42731992205) passed unit tests, APK build, stable signing, and artifact upload. Its APK updated the previous same-key API 34 installation without uninstalling or losing SQLite v8.

The final API 34 first-run sync completed across WorkManager retries: OpenFootball `OK` with 25,904 rows; OpenLigaDB `OK` with 17,776. SQLite contains 39,181 merged matches, 30,090 scored results, and 184 competitions across data from `2022` through `2026-27`; no 1970 placeholders remained. Both source rows and last-success timestamps were inspected after completion. The 8-minute worker continuation and automatic retry were observed.

The final automatic walk-forward displayed 30,090 targets, 19,185 forecasts, 10,905 skipped for insufficient history, mean Brier 0.6106 and mean Log Loss 1.0235. This remains a retrospective score over current files with date-only availability assumptions and no source vintages; it is not evidence of future performance or profit. Forty virtual coupon picks were stored and none had settled at the last inspection.

Bookmaker technical proof remains limited and must be represented accurately: a normal public WebView earlier captured 8 Misli and 25 eTopaz rows, cross-checked selected fixtures against UEFA schedules. The latest foreground page check captured 9 Misli lines; eTopaz returned only the public home page and its state became `UNAVAILABLE`. Stored odds do not have exact local match IDs, so no model comparison is attached. Neither operator's explicit automated odds-reuse permission is established; snapshots remain local. Do not bypass page protections, claim licensing, invent odds, or remove the 30-minute freshness rule. WorkManager syncs football feeds; WebView access requires the app foreground.

Offline was verified with Wi-Fi and mobile data disabled and no network route (`ping 1.1.1.1` returned unreachable); the app still showed saved fixtures and provider state. 62 JUnit tests passed, and the CI artifact signature fingerprint is `da1506fd2e896c67d3ceafc8b3ab7f33824e759e092adf938fdf11c52fa8d935`. No API 26 or physical-device check was done. Dedicated SQLite migration and WorkManager integration tests remain useful future work; there is no unfinished implementation task blocking the user's acceptance criterion.
