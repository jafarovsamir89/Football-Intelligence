# Changelog

## Unreleased — retain international source-attempt history

- Save MoviOdds request status, safe detail and available parser quality counts for 30 days. Show the five latest attempts on World Market; do not calculate an uptime/reliability percentage from this short, device-local sample.
- Add SQLite v15→v16 migration and coverage for preserving existing sync state, status ordering and absent counts on request failure. The first grouped run found and fixed a migration-test scope error. The successful combined local cycle passed 128 JVM tests, Android-test Kotlin compilation and APK assembly; local APK SHA-256 `50dd310c5a37337275484af6fb127ac30b10720d726a36258080c08f61387cae`. CI runtime verification is pending.

## Unreleased — expose the latest international quality-gate counts

- Persist received, accepted, rejected, stale, duplicate and unverified match counts from each parsed MoviOdds board and show them on the World Market screen. The counters describe only the latest JSON response; they are not a source reliability score.
- Add mutually exclusive parser accounting, API 34 v14→v15 migration and Worker persistence coverage. Alternative-feed research found The Odds Gap's keyless endpoints may not be repackaged into another product, while UK Odds API and The Odds API free tiers require accounts/keys; no additional feed was added.
- The first combined local cycle found and fixed a missing instrumentation-test import. The successful cycle passed 128 JVM tests, Android-test Kotlin compilation and APK assembly. Local APK SHA-256 `6607c46c3200d07444342ce39edadc90ad53e09a6c54969f40349b78a9632a00`. Commit `1c26c43` passed Actions [43149054713](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/43149054713), including all 29 API 34 instrumentation tests, stable signing and artifact upload `13912194538`; downloaded APK SHA-256 `d5869a0e06e8f2c6f47ae9ac44e79e2f07423a7c127db653cc49939c5c5e2124`, signer SHA-256 `da1506fd2e896c67d3ceafc8b3ab7f33824e759e092adf938fdf11c52fa8d935`.

## Unreleased — add same-book market-disagreement accumulator drafts

- Add a separate research-only accumulator basis that selects the largest positive difference between the current Elo marginal and proportional de-vigged probability from the same local bookmaker's full 1X2 line. It requires fresh prices and an exact linked local fixture; it never combines Misli and TOPAZ prices.
- Save the fair market probability and signed difference per leg in SQLite v14, with a migration that preserves existing coupon legs. Display the comparison in drafts/history and suppress EV, ROI, profit factor and drawdown for this basis.
- Add unit tests for ranking/market isolation (including split Misli/TOPAZ prices), zero-gap and stale-line rejection, disabled return metrics, plus API 34 migration and persistence round-trip coverage. The local cycle passed 128 JVM tests, Android-test Kotlin compilation and APK assembly. Actions [43146575115](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/43146575115) passed all 28 API 34 instrumentation tests, APK build, stable signing and artifact upload `13912041925`; CI APK SHA-256 `5ba558e02776225012524267b3ac08c7d074cdbec3d141e28d5d7e4e3bdd0734`.

## Unreleased — select Dixon–Coles rho without holdout tuning

- Evaluate a conservative five-value rho grid on the chronological validation window and use the selected rho only for the untouched final holdout. Selection requires at least 100 validation forecasts; otherwise the comparison retains rho=0.
- Keep rho selection diagnostic-only: it does not alter production Elo or immutable prospective forecasts. Show validation sample count, selection status and the selected value in the analysis screen.
- Add regression coverage proving that changing only holdout labels cannot change the selected rho, plus insufficient-validation fallback coverage.
- Local verification passed: 124 JVM tests, Android-test Kotlin compilation and APK assembly. Actions [43143303224](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/43143303224) passed all 26 API 34 instrumentation tests, APK build, stable signing and upload `13910697179`. Downloaded APK SHA-256 `60e9a877f62f5674f228188d4031d8acd6b12804222bb2dbeb91d4323636cbf0`; stable signer SHA-256 `da1506fd2e896c67d3ceafc8b3ab7f33824e759e092adf938fdf11c52fa8d935`.

## Unreleased — respect source `Retry-After` limits

- Parse HTTP `Retry-After` delta-seconds and RFC 1123 dates for provider 429/503 responses. Stop further catalog/file requests to that provider and queue one network-constrained retry after the longest supplied delay; invalid or absent headers keep WorkManager exponential backoff.
- Apply the same delayed retry behavior to the independent TipsAudit and MoviOdds workers. Add unit and API 34 instrumentation coverage for header parsing, real local HTTP 429 response metadata, request stopping, combined delays and queued WorkManager timing. The combined local cycle passed 122 JVM tests, Android-test Kotlin compilation and APK assembly. Actions [43141086329](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/43141086329) passed all 26 API 34 instrumentation tests, APK build, stable signing and artifact upload `13909553166` (16,858,416 bytes).
- Recheck the requested public odds pages and terms. Oddsbunch, BetExplorer, Oddspedia, OddsPortal and Oddschecker still fail the app's automated-reuse or regional-access gate; existing MoviOdds remains the permitted integrated source.

## Unreleased — verify conditional football-source cache reuse

- Add a loopback HTTP regression test for ETag/Last-Modified request headers and real 304 response metadata, plus isolated SQLite coverage proving cached five-season OpenFootball rows, validators and catalog bodies survive revalidation.
- The combined local cycle passed 120 JVM tests, Android-test Kotlin compilation and APK assembly. Actions [43137789034](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/43137789034) passed all 23 API 34 instrumentation tests, APK build, stable signing and artifact upload `13909145161` (16,855,250 bytes). Downloaded APK SHA-256 `e95fdd3ab9c3a27e7d070efa39e5d374c2d0c6b454d6c757cc967d40703b7350`; signer SHA-256 `da1506fd2e896c67d3ceafc8b3ab7f33824e759e092adf938fdf11c52fa8d935`.

## Unreleased — add a gated international 1X2 market feed (CI passed)

- Researched the requested public pages. Oddsbunch, BetExplorer, Oddspedia and OddsPortal terms do not permit automated reuse without consent; Oddschecker has regional limits and can block robotic access. No scraper, WebView collector or undocumented route was added for them.
- Integrated the documented free/no-key MoviOdds soccer group-40 feed as `Bet365ViaMoviOddsProvider`; the bookmaker label and intermediary attribution remain distinct. The single-book no-vig reference is not called consensus and its prices never enter local Misli/TOPAZ coupons.
- Added source market-poll and per-outcome price-change timestamps, complete prematch 1X2/suspension/duplicate/exact-fixture gates, a 15-minute freshness window, offline current state, 30-day price-change history, and app-open plus best-effort 30-minute WorkManager refresh.
- Added a one-time 18+ confirmation gate required by MoviOdds terms, including a Worker-side guard so queued work cannot fetch before consent. The OpenFootball/OpenLigaDB football-data flow is unaffected. Azerbaijan device access and prospective price/model value remain unverified.
- Direct POC at `2026-11-12T00:39:24Z` received 923 soccer rows; 904 passed the app-equivalent freshness and market-quality filter. These are historical POC observations, not current prices.
- Local combined verification passed: 119 JVM tests, Android-test Kotlin compilation and debug APK assembly. Actions [43135555477](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/43135555477) passed all 22 API 34 instrumentation tests, APK build, stable signing and artifact upload `13907840944` (16,855,155 bytes). CI APK SHA-256 `d80cd80c7caa9b4145526012f53bc0d0ad52c280c1959286dec9a32048cab9a1`; certificate SHA-256 `da1506fd2e896c67d3ceafc8b3ab7f33824e759e092adf938fdf11c52fa8d935`. The Xiaomi is disconnected; the APK was not installed, and emulator databases remain untouched. Public feed behavior on an Azerbaijan physical device is not yet verified.

## Unreleased — add a chronological goals-based Poisson baseline

- Estimate home/away goal rates from preceding same-competition scores using league-average smoothing and explicit league/team sample floors.
- Update goal features in strict effective-availability batches; reject result rows whose provider availability timestamp predates kickoff instead of guessing when they became usable.
- Compare Poisson with the validation-selected Elo on identical final-holdout fixtures where both have forecasts; show model coverage separately. The follow-up validation-only rho selection is recorded above; it does not change live probabilities.
- Synthetic-data regression tests passed in the combined local cycle: 107 JVM tests, Android-test Kotlin compilation and APK assembly. Actions [43126942380](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/43126942380) passed unit tests, 16 API 34 instrumentation tests, APK build, stable signing and artifact upload `13902324452`.

## Unreleased — batch Elo research and correct accumulator history

- Add a chronological 27-setting Elo experiment with 60% warm-up, 20% validation, and 20% isolated holdout windows. Select by validation Log Loss only when at least 100 validation forecasts exist; show the selected configuration and holdout comparison with current defaults without changing live forecasts.
- Mark an accumulator lost immediately after any incorrect finished leg, even while other legs remain pending.
- Report complete-price coverage for settled coupon groups and suppress ROI, profit factor, and drawdown unless every settled coupon has a complete combined price.
- Add regression coverage for holdout isolation, insufficient validation samples, a lost coupon with pending legs, and partial price coverage. The combined local run passed 102 JVM tests, Android-test Kotlin compilation and APK assembly; Actions [43124533252](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/43124533252) passed 16 API 34 instrumentation tests, APK build, stable signing and artifact upload.

## Unreleased — add an attributed international fair-market reference

- Researched official access conditions for Pinnacle, Betfair Exchange and bet365; none currently provides a verified free, no-account, permitted raw-odds path under the user's zero-touch constraints.
- Added a separate TipsAudit market-reference provider, SQLite v12 ledger, independent network-constrained WorkManager refresh (app-open after 15 minutes; 30-minute best-effort periodic), exact fixture matcher and visible source attribution.
- Accept only timestamped `fair_source=live` 1X2 observations no older than 30 minutes and before kickoff; reject stale/future timestamps, inconsistent fair odds/probabilities, duplicate or invalid rows and non-source URLs. Ignore best-soft prices and value flags.
- Show descriptive probability differences against fresh local Misli/TOPAZ lines and Elo when one exact fixture is available. Never use this derived reference for local-bookmaker coupons, payout odds, EV, or a multi-source consensus.
- The 2026-11-11 direct API POC returned 98 events, but Türkiye—Belgium's fair source read was ~47 minutes old; no current reference was eligible in that capture. The UI therefore needs to show unavailable until a genuinely fresh read is observed.
- Commit `4badae9` passed Actions [43121662746](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/43121662746): 16 API 34 instrumentation tests covered the v11→v12 migration, Worker scheduling/network constraints and socket-free failure path; APK build, stable signing and upload `13901058686` passed. The data-bearing local emulators were left untouched per the user's batching request.

## Unreleased — finish clean-install data and odds runtime verification

- The clean API 34 first-run Worker chain completed automatically across five OpenFootball seasons and five OpenLigaDB calendar-year catalogs: OpenFootball 25,904 records, OpenLigaDB 17,202 records / 214 league-season states, and SQLite 38,609 matches / 29,612 completed results / 183 competition labels / 120 prospective forecasts.
- The `9399fa4` stable-signed APK update-installed on the same clean-install emulator without clearing data or changing `firstInstallTime`. MainActivity resumed and retained all provider, fixture, result and forecast records.
- At 2026-11-11 07:37–07:38 UTC, the visible public WebViews captured 25 TOPAZ and 16 Misli prematch lines. Exact local links were 7 and 4; the Coupons tab displayed 6 provider-specific TOPAZ candidates and 2 Misli candidates with capture times. A second API 34 instance had no current public lines, so the app correctly displayed `UNAVAILABLE` there. Explicit operator permission for automated quote reuse remains unconfirmed.

## Unreleased — share Worker time across both football providers (CI verified)

- Reserve the first three minutes for OpenFootball and use a seven-minute total segment deadline, leaving OpenLigaDB time in the same WorkManager request. This prevents a long OpenFootball import from starving OpenLigaDB across every continuation.
- Stop starting extra catalog requests after the source deadline and preserve `DEFERRED` for sources that have only been queued for a later segment.
- Add regression coverage for the source budget and status rules. The bundled local run passed 92 JVM tests with no failures and assembled the debug APK. Actions [42979682129](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/42979682129) passed all 12 API 34 instrumentation tests, APK build, stable signing and artifact upload `13837271507`.

## Unreleased — explain provider sync progress during first-run imports (CI and local suite passed)

- Publish the active OpenFootball/OpenLigaDB stage to the main screen so long bounded WorkManager runs have a useful status message.
- Label OpenLigaDB `DEFERRED` when an automatic continuation is queued before the source begins importing leagues. Keep `ERROR` for actual no-progress failures and `PARTIAL` for completed work with provider errors.
- Add tests for provider stage text and deferred-vs-error status. The bundled local run passed 91 JVM tests with no failures and assembled the debug APK. Actions [42921473653](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/42921473653) passed 12 API 34 instrumentation tests, APK build, stable signing and artifact upload `13812041365`. Downloaded artifact SHA-256: `8bf19b2cda7958aaeaca4fcd2b96b01caaede1b1df38e7bdb1d5868d0ae80cbf`.

## Unreleased — delay the first periodic sync (CI and clean-install scheduling check passed)

- On a clean API 34 install, the new 24-hour periodic request started immediately alongside the stale-data app-open request. The one-time request then retried the provider lock while the first full import ran.
- Delay only the first periodic execution by 24 hours; the app-open request still imports immediately when source freshness is missing/stale. Add scheduler instrumentation asserting both interval and initial delay.
- Local verification passed 88 JVM + 12 API 34 tests and APK assembly. Actions [42917262103](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/42917262103) passed and uploaded stable-signed artifact `13809154100` (APK SHA-256 `5bd197486a89c8b262090db93d8b907d0482833a33f7ea32f45e6227b800655c`). On a clean API 34 install the periodic WorkSpec showed `initial_delay=86400000` and `interval_duration=86400000`; only the immediate worker started. Its first segment returned `SUCCESS` and automatically queued a continuation. At the latest check, first-run history import remained active with 7,340 matches / 3,340 completed scores; full provider totals are not yet known.

## Unreleased — serialize foreground and Worker database writes (CI and API 26 update-install passed)

- Reproduced an API 26 v11 startup crash after a data-preserving update: `SQLiteDatabaseLockedException` when the foreground forecast ledger wrote while the WorkManager provider transaction was active. WAL opened correctly and saved match/result rows remained intact.
- Serialize all `Store` mutations with a process-wide fair reentrant lock, and move forecast/coupon persistence off the main thread. Add an API 34 regression test that holds a background transaction and verifies the forecast write waits and commits.
- Commit `6425117` passed Actions [42912446156](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/42912446156): 88 JVM + 12 API 34 tests, APK build, stable signing and artifact `13807803388`. The APK SHA-256 is `5851eb6ec4482ea09bd85f9d325c14b2b8189bdbfe7c4a314a48bc0d9ddd9521`; signer SHA-256 is `da1506fd2e896c67d3ceafc8b3ab7f33824e759e092adf938fdf11c52fa8d935`.
- Stable-signed `adb install -r` update-installed over the data-bearing API 26 app. `firstInstallTime` stayed unchanged. After temporarily expiring only provider freshness timestamps in the test profile, a normal app open scheduled a real Worker; OpenFootball and OpenLigaDB completed `OK` at 22:27:11 / 22:27:32 UTC while the Activity remained resumed. The sync lock was released, counts remained 38,609 matches / 29,612 completed results, and no SQLite lock or fatal crash appeared. The Worker replaced the temporary timestamps with real successful check times.

## Unreleased — prevent duplicate bookmaker captures and refresh at expiry (CI and API 34 runtime passed)

- Coalesce repeated `onPageFinished` callbacks with a synchronized provider capture gate and one reload timer per WebView. If the app opens while odds are still fresh, schedule page loading when the 30-minute window expires, provided the app stays foregrounded.
- Add a unit regression for in-progress duplicate suppression and the interval boundary. Local verification: 88 JVM + 11 API 34 instrumentation tests and APK assembly passed. Actions [42904789554](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/42904789554) passed and uploaded stable-signed APK `13805066252`.
- Update-install preserved the API 34 SQLite profile. The automatic refresh fired once per site at 21:34:53 / 21:35:11 UTC. Both public pages had no verified upcoming prematch 1X2 rows, so status became `UNAVAILABLE` and no odds were added. No repeat, crash or SQLite contention appeared.

## Unreleased — protect foreground odds capture from SQLite read contention (CI and API 34 runtime passed)

- Reproduced a `SQLiteDatabaseLockedException` when first-open WorkManager sync and bookmaker WebView capture read/write the single-connection store at the same time.
- Enable SQLite WAL and remove the duplicate foreground startup relink; the Worker still relinks unmatched snapshots after each provider sync segment.
- Add a randomized SQLite concurrency instrumentation test. Local verification and Actions [42895248921](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/42895248921) passed 88 JVM + 11 API 34 instrumentation tests, APK assembly, stable signing and artifact upload for `01706e9`.
- Stable-signed APK update-installed with `adb install -r` on the data-bearing API 34 emulator. The first-install time and 39,181 matches / 30,114 completed results were preserved. New normal foreground capture succeeded: Misli 9 visible rows/0 exact local links and TOPAZ 25/7 at 20:33 UTC. Both statuses displayed `LIVE`; no SQLite lock error recurred. Prices expire in 30 minutes. A separate offline harness's `chromewebdata/` output is not evidence of bookmaker blocking.

## Unreleased — remove timing race in offline WorkManager instrumentation (CI and API 34 passed)

- Actions [42897976801](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/42897976801) found one flaky `.single()` read after a fixed two-second delay in the offline unique-work scheduling test. The app code was not implicated.
- The test now polls persisted WorkManager state for up to 30 seconds and includes observed states on timeout. Local API 34 instrumentation passes 11/11; commit `b58e655` passed all 88 JVM + 11 instrumentation tests, APK build/sign/upload in Actions [42899536981](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/42899536981).

## Unreleased — make Worker failure test network-proof (CI and API 34 update-install passed)

- Inject a fake `ProviderHttpClient` into the real Worker failure test. It records both public catalog URLs and throws locally without opening a socket, then verifies provider `ERROR`, WorkManager retry and lock release.
- The earlier proxy setup did not guarantee isolation; Actions rerun [42778440884](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/42778440884) observed a public `PARTIAL` response. The corrected commit `8d5ab00` passed 88 JVM tests, 10 API 34 instrumentation tests, APK build, stable signing and upload in Actions [42889711265](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/42889711265).
- Update-install on the API 34 primary emulator preserved the database byte-for-byte. A follow-up app-open runtime check uncovered the independent SQLite contention issue addressed above.

## Unreleased — verify WorkManager retry/backoff rescheduling (CI and API 34 update-install passed)

- Add a network-free instrumentation test using a unique test request and worker that returns `Result.retry()` once, stays queued through at least 20 seconds of a 30-second exponential backoff, then succeeds on its second attempt.
- Extend airplane-mode/Wi-Fi transition waits to 30 seconds after CI [42774937884](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/42774937884) exposed a transient timeout at the previous 10-second limit.
- Verification: 88 JVM tests and all 10 API 34 instrumentation tests passed locally and in Actions [42777428309](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/42777428309), with APK build, stable signing and artifact upload. Update-install without data clear preserved schema v11, 39,181 matches, 184 competitions, 385 quote snapshots, 71 accumulators/402 legs and 62 forecast snapshots/0 settlements.

## Unreleased — make provider-failure retry test network-proof (CI and API 34 passed)

- Run the real `AutomaticFootballSyncWorker` with an injected `ProviderHttpClient` that records OpenFootball and OpenLigaDB catalog URLs and always throws locally. It cannot open sockets; the test verifies persisted `ERROR` states, WorkManager `Result.retry()` and sync-lock release using a random isolated database.
- The old proxy-based implementation passed the first Actions run [42773538883](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/42773538883), but rerun [42778440884](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/42778440884) observed `PARTIAL`, proving public responses could still reach that test. The injected-client replacement passes all 10 API 34 tests locally; a new complete CI run is pending.

## Unreleased — retain provenance and scores across provider duplicates (CI and API 34 update-install passed)

- Merge source labels, URLs and capture time for naturally matched fixtures even when the incoming provider has no result or no more precise kickoff.
- Keep a settled score when an incomplete source arrives or a second provider reports a conflicting score. Promote date-only fixtures to an exact kickoff and final score when the incoming provider has valid timing/results.
- Add two isolated SQLite Android instrumentation tests. Local verification: 88 JVM + 7 API 34 instrumentation tests and APK build passed; Actions [42767934594](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/42767934594) passed. The stable-signed APK update-installed without data clear.

## Unreleased — freeze prospective forecast snapshots (CI and API 34 update-install passed)

- Add SQLite v11 tables for immutable model-version/match/UTC-day forecast snapshots and independently updated result settlements. Score corrections change only the linked result, never historical probabilities.
- Build live forecast history strictly from matches kicked off and available to this installation before the forecast cutoff. For imported/source rows, conservative availability is the later of source availability and local capture time; rows without a trustworthy timestamp are excluded.
- Display prospective settled-only Brier, Log Loss, top-outcome accuracy, frozen probabilities, history sample size, result source and availability time in the app. No profit metric is computed.
- Fix score corrections being silently rejected because Android SQLite bound a text `whereArgs` value compared to integer `1`; add a regression assertion that the corrected score reaches settlement while the forecast remains frozen.
- Verification: 88 JVM tests, 5 API 34 instrumentation tests, and debug APK assembly passed locally. Actions [42765702211](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/42765702211) passed tests, APK build, stable signing and artifact upload. API 34 update-install preserved all 39,181 matches, all 315 quote snapshots, and all previous 59 accumulators/328 legs; the app then created 10 new accumulator snapshots. The UI stored 62 future forecasts and showed 0 settled results.

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
