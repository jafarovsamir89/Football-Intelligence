# Changelog

## Unreleased — verify WorkManager scheduling without network side effects

- Reuse the production freshness scheduling path in instrumentation and assert the 24-hour periodic request, CONNECTED network constraints, no duplicate unique work on repeated app open, and no immediate request for fresh source data.
- Add an eight-hour stale boundary unit test. The instrumentation request has a one-hour initial delay and is cancelled afterward, so the test verifies persisted WorkManager state without triggering a public-data import.
- Verification: 84 JUnit and 3 API 34 instrumentation tests passed locally; Actions [42761781971](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/42761781971) passed tests, APK build, stable signing and artifact upload.

## Unreleased — isolate and test SQLite upgrade paths

- Allow a test-specific Store database name while retaining the unchanged production default.
- Add AndroidJUnitRunner integration coverage for v1→v10 and v9→v10 migrations, preservation of settled results and user records, and the v10 selection-basis default. Tests were executed on an isolated API 34 emulator.
- Add API 34 Android Emulator Runner instrumentation execution to GitHub Actions. Both migration tests passed locally and in Actions run [42759573588](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/42759573588). The stable-signed artifact update-installed on API 34; database counts stayed at 39,181 matches, 197 bookmaker snapshots, 59 accumulators and 328 legs.

## Unreleased — retrospective Elo reliability diagnostics

- Add tested one-vs-rest reliability bins for home/draw/away forecasts, including sample sizes, mean predicted probability, observed frequency, and mean ECE.
- Show the diagnostics and occupied bins for automatic and optional imported walk-forward reports; explicitly label them retrospective and not a calibration transform.
- Local verification: 83 JUnit tests and `assembleDebug` passed. Actions run [42757663129](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/42757663129) passed tests, APK, stable signing and upload. API 34 update-install preserved SQLite and the Backtest screen rendered 19,185 predictions with macro ECE 2.2% and expanded reliability bins.

## Unreleased — queue bounded sync continuation without retry backoff

- Separate the normal eight-minute work budget from actual provider/network failures. At the budget boundary, append another connected one-time Worker to the unique chain with `APPEND_OR_REPLACE` and complete the current Worker; failed requests still return `Result.retry()` and keep WorkManager backoff.
- Add unit coverage for continuation/success/retry routing. Locally verified 79 unit tests and debug APK assembly; Actions run [42752522409](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/42752522409) passed and uploaded the stable-signed artifact. Update-install and full data sync were verified on Android 8/API 26 without clearing the existing SQLite database.

## Unreleased — close sync database helpers and clarify queued status

- Close the temporary SQLite helper used to read provider freshness, close the worker's database after each attempt (including a lock-contention retry), and close the activity-owned helper on destroy.
- Describe a queued sync as waiting on WorkManager/system scheduling or network constraints; the queue can be caused by retry backoff, so it must not imply that the network is necessarily offline.

## Unreleased — keep large history calculations off the UI thread

- Load the 39k match list, recent results and full league history from SQLite on `Dispatchers.IO`, and build automatic fixture forecasts / selected-match Elo on `Dispatchers.Default` so WorkManager progress refreshes do not freeze Compose.
- Cache league history across sync progress revisions and reload it after a provider reports a new successful sync.
- On API 26 emulator, the preceding CI APK opened but emitted skipped-frame warnings during cold-start history reads; a local same-key build with this change relaunched with only a brief initial JIT skip in the observed log. CI artifact recheck remains pending.
- No change to forecast inputs or model parameters; this is a responsiveness and database-read scheduling change.

## Unreleased — timestamped same-bookmaker accumulators and source-quality filtering

- Added separately selected TOPAZ/Misli virtual accumulators in sizes 2/3/5/8/10; actual combined odds are displayed only when each leg has a complete fresh quote from that same provider and an exact local fixture.
- Record per-leg quote capture time, model marginals, coupon selection basis, combined actual decimal price when evidenced, and Fréchet bounds for joint probability without assuming independent matches.
- Persist accumulator history and settle only against local match results; postponement/kickoff changes are not treated as losses. ROI is omitted unless the full same-provider coupon price and final results exist.
- Place price-backed drafts first. Reject stale (>30-minute), future-dated, untimestamped, invalid, or unmapped lines at the coupon-builder boundary.
- Exclude known placeholder team names from provider writes, match lists, fixture links, predictions and performance calculations; reject new placeholder parser rows while keeping legacy IDs/data intact.
- Use v10 SQLite migration to add selection basis and preserve existing database contents.
- At that milestone's API 34 capture, 10 Misli/25 TOPAZ visible rows were observed; seven TOPAZ fixtures joined local matches. A later `e8c3a33` capture is recorded in `docs/DEVELOPMENT_LOG.md`. No reuse license or profitability is claimed.
- Added regression tests for accumulator dimensions, per-provider isolation, timestamps, stale/future quotes, placeholder rejection, settlement and performance reports.

## Unreleased — zero-touch multi-source data, Misli/TOPAZ, and automatic model checks

- Added API 34 verified first-open OpenFootball/OpenLigaDB sync across five seasons and dynamically discovered competitions, with local cache/status presentation.
- Added ordinary Android WebView collectors for public Misli and eTopaz pages. They parse visible future 1X2 lines, preserve timestamps/source URLs, and hide prices older than 30 minutes. No login, private endpoint, or protection bypass is used.
- Added separate operator adapters, exact fixture matching and event-year inference for eTopaz dates without a year. SQLite v8 stores bookmaker snapshots, sync state, and year-inferred provenance.
- Repeated page refreshes now retain quote history but display/use only the newest fresh snapshot for each event and operator; stale capture status is distinguished from fresh upcoming lines.
- Added automatic competition-specific walk-forward metrics for cached results; streaming availability batches preserve strict as-of cutoffs and make the 30k-row dataset practical.
- Added tests for both visible-row formats, aliases, timezones, future/past/live handling, duplicates, inferred year, coupon isolation and chronological engine equivalence.
- API 34 observed 39,330 merged matches/30,180 scores over 188 competitions and five seasons; the automatic walk-forward returned 19,078 forecasts (Brier 0.6105, Log Loss 1.0234) from 29,970 targets.
- Documented that bookmaker source permission is not confirmed, no captured odds matched an exact local model fixture, and retrospective scores use current files rather than archived historical vintages.
- Added a private stable personal-test signing key in repository Actions Secrets so future CI APK updates can preserve the locally stored database. The key is not stored in the repository or emitted to build logs.

## Unreleased — 2026-11-09

- Re-linked seven stored Misli/TOPAZ quote snapshots to unique local fixtures using reviewed German and other explicit aliases, exact team orientation, local event date, and a 20-minute kickoff tolerance. TOPAZ Türkiye—Belgium now maps to OpenLigaDB match 85220. The captured price is stale and the latest public page check remains unavailable; no current line is claimed.
- Added a best-effort rematching pass after fixture sync and on app open so quote snapshots captured before a fixture arrives can be linked later. Added a regression test for the exact Türkiye/Türkei—Belçika/Belgien mapping.

- Final API 34 verification: GitHub Actions 42731992205 passed tests/build/stable signing; update install preserved SQLite and bounded retries completed both football providers.
- Final sync stored 39,181 merged matches/30,090 scored results across 184 competitions; walk-forward returned 19,185 forecasts (Brier 0.6106, Log Loss 1.0235) from 30,090 targets.
- Verified offline relaunch with Wi-Fi/mobile data disabled. Last WebView check showed 9 Misli rows and TOPAZ/eTopaz unavailable on its public home page; unmatched or unavailable prices are never presented as model-comparable.

- Added WorkManager first-open and 24-hour best-effort refresh with network constraints, unique work, retry/backoff, DB sync lease and OpenLigaDB request pacing.
- Added OpenFootball five-season automatic JSON discovery/download with ETag/cache; added dynamic OpenLigaDB catalogs for five calendar-year seasons, change checks and bounded continuation.
- Added provider freshness/error/provenance storage, capture and safe-availability timestamps, date-only kickoff handling, conservative cross-source fixture dedupe and SQLite migrations through v6.
- Moved CSV import to Settings → Additional; normal first launch no longer requires files, odds entry, tipster input or a refresh tap.
- Added separate Misli and TOPAZ provider status adapters for public WebView captures; quote reuse permission remains unconfirmed.
- Added separate model-only Misli/TOPAZ coupon drafts and one-time pre-match pick logs with automatic result checking. No combined price, payout or joint probability is fabricated.
- Added a completed-match results history and main-screen per-source check/success freshness.
- Added unit tests for source parsers, dedup identity, unavailable bookmaker providers and model-only coupon construction.
- Added a standalone date-bucketed Elo walk-forward screen over automatic sources and imported results, with safe-availability filtering and mean Brier/Log Loss. It explicitly does not claim a market baseline or validated edge.
- Added explicit Android document-picker import for Football-Data-style CSV completed results, a v1→v2 SQLite migration, import provenance log, duplicate suppression, and a conservative two-day availability bound for date-only rows.
- Added CSV parsing tests for quoted/semicolon files, UTF-8 and Windows-1252 decoding, invalid/unfinished rows, duplicate rows, two-digit years, and date-only availability.
- Added an experimental Elo 1X2 baseline fitted only from same-league results strictly before kickoff; suppresses forecasts below the documented history thresholds and labels the model uncalibrated.
- Added Elo unit tests for cutoff filtering, future leakage, home advantage, draw prior, and sample-size gating.
- Added pure Kotlin 1X2 market de-vigging with basic, Power, and Shin methods.
- Added independent Poisson score-grid and optional Dixon–Coles low-score adjustment primitives.
- Added Brier, log-loss, and per-unit expected-value calculations with JUnit coverage.
- Added Gradle 8.11.1 wrapper with checksum and CI unit-test execution.
- Guarded match upserts so incomplete refreshes cannot replace a previously settled result.
- Normalized zone-less provider timestamps to UTC and preserved explicit offsets.
- Corrected ROI and ticket history to resolve all stored ticket matches, independently of the 250-row match list.
- Rejected future-dated quote snapshots for freshness/virtual-price selection.
- Labeled market consensus as a simple average of user-entered lines and cross-book maxima as separate prices, not one available coupon.
- Added source audit, architecture, research, testing, limitations, decision, and development-log documentation.

## 0.1.0 — initial prototype

- Android Compose shell, local SQLite, selected OpenLigaDB match refresh, manual 1X2 odds and pick entry, virtual single tickets, and settled-ticket ROI display.
