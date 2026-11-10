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

The zero-touch implementation, provider collection and user-requested test APK have been built and verified for the emulator acceptance scope. Latest code commit `fb65bcc` passed Actions [42759573588](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/42759573588): 83 JVM tests, two migration instrumentation tests, APK build, stable signing and upload. Its signer SHA-256 is `da1506fd2e896c67d3ceafc8b3ab7f33824e759e092adf938fdf11c52fa8d935`; update-install on API 34 retained SQLite v10 with 39,181 matches, 197 bookmaker snapshots, 59 accumulators and 328 legs. The active local change adds WorkManager queue instrumentation. On API 34, 84 JVM tests and all three instrumentation tests pass, including the new scheduling test; the WorkManager/APK Actions run is pending. The prior sync-continuation code commit `e8c3a33` passed Actions [42752522409](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/42752522409) and completed the eight-minute WorkManager continuation chain on API 26. Earlier verification is in commit history.

The current model diagnostics include retrospective one-vs-rest calibration bins and ECE on both automatic and imported Elo walk-forward reports. The `6faac0b` API 34 screen showed 19,185 forecasts, macro mean ECE 2.2%, home 3.1%, draw 0.9%, away 2.5%, and expandable occupied bins. These figures describe the retrospective current-source sample; no calibration transform or future accuracy claim is made. The latest pushed code commit `fb65bcc` passed Actions [42759573588](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/42759573588), including both SQLite migration tests. The active WorkManager queue milestone passes locally (84 JVM + 3 API 34 instrumentation tests); its CI result is pending. After CI, expand worker retry/offline tests and implement immutable versioned forecast snapshots. Follow the newest [`docs/DEVELOPMENT_LOG.md`](docs/DEVELOPMENT_LOG.md) entry for details.

Current API 34 database: 39,181 merged rows from 184 competitions, 30,090 completed-score rows before placeholder exclusion, provider counts OpenFootball 25,904 and OpenLigaDB 17,776. There are 566 legacy rows with dummy/placeholder team names. They remain in SQLite for ID/migration safety, but are filtered from list/history, fixture matching, forecasts, coupon generation and walk-forward, and new parser/upsert rows are rejected. Post-filter walk-forward was run in the installed APK: 29,588 targets, 19,185 forecasts, 10,403 skipped for insufficient history, Brier 0.6106, Log Loss 1.0235. The older unfiltered target count must not be repeated. Scores remain retrospective and uncalibrated.

The latest ordinary WebView API 34 capture was made after the `e8c3a33` CI APK update at 2026-11-10 04:21 UTC: 9 visible future Misli rows and 25 TOPAZ rows. Seven TOPAZ quotes matched local fixtures; Misli matched zero. Portugal—Denmark maps to match 85257, with a captured 1.45 / 4.31 / 5.29 line and 59.1% Elo home marginal (basic no-margin market home probability 62.1%). The same capture showed a TOPAZ two-leg candidate using England—Croatia 1.21 and Portugal—Denmark 1.45; joint probability is displayed as bounds. Quotes expire from fresh use after 30 minutes. These are time-bound technical observations, not license or profit evidence. No explicit automated odds-reuse permission is established. Never bypass protection or fabricate quotes. WorkManager does not run the WebView; odds are foreground-only.

On API 34, the `fb65bcc` artifact retained schema v10 and 39,181 matches, with 197 bookmaker snapshots, 59 accumulators and 328 legs after update-install. Offline cold launch was verified with Wi-Fi/mobile data disabled: cached fixtures, source statuses and saved quote history remained visible, while network ping failed. The same stable-signed artifact is installed on API 26 without uninstalling or clearing data. Final API 26 state at 2026-11-10 04:12 UTC: schema v10, 38,609 matches, 29,588 completed scores, 183 competitions, 10 season values, OpenFootball `OK`/25,904, OpenLigaDB `OK`/17,202, and all 214 discovered league-season entries processed. The one-time parent and continuation both finished `SUCCESS`; no sync lock remains. API 26 returned no visible public bookmaker page text, so both statuses are `ERROR`, there are zero fresh quote snapshots, and no real prices are shown. No physical handset is available; actual offline gating, retry instrumentation and immutable forecast snapshots remain on the roadmap. See [`docs/DEVELOPMENT_LOG.md`](docs/DEVELOPMENT_LOG.md).
