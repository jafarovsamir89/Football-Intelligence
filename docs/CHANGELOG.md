# Changelog

## Unreleased — добавить temperature scaling и ECE для исследовательской смеси (CI/API 26/API 34 passed)

- Расширить прежний выбор веса Elo/Poisson фиксированной сеткой multiclass temperature scaling; температуру подбирать только по validation Log Loss. Выводить one-vs-rest ECE компонентов и кандидата отдельно на validation и holdout. Оба порога улучшения и минимум 500 прогнозов на каждом срезе остаются обязательными; модель не подключается к live-пути.
- Регрессионные тесты проверяют преобразование, holdout-gate и отсутствие влияния holdout на выбор параметров. Первый прогон обнаружил, что температурная коррекция закономерно улучшает две одинаковые, но неверно калиброванные модели; регрессия уточнена на модели, чьи вероятности уже равны частотам исходов. Команда `./gradlew --no-daemon :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:assembleDebug :app:assemblePersonal` прошла: 190 JVM-тестов (0 failures/errors/skips), Android-test Kotlin compilation и обе APK-сборки. После обновления устаревшей Compose-подписи Actions [43401920604](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/43401920604) прошёл 63/63 API 34 и API 26 с 63 завершёнными тестами, одним offline-only skip и нулём ошибок, затем собрал, подписал и загрузил keyless APK `14026510973`. Подробные хэши и аудит ключа — в журнале. APK не устанавливался.

## Unreleased — исследовать смесь вероятностей Elo + Poisson (CI/API 26/API 34 passed)

- Подбирать девять заранее заданных весов Elo/Poisson по общим validation-прогнозам; требовать минимум 500 строк и превосходство обоих компонентов одновременно по Brier и Log Loss. Отдельный holdout получает только уже выбранный вес, без настройки на тестовой части, и проходит отдельный порог 500 прогнозов и те же два критерия улучшения. Добавлены метрики и прозрачные статусы обоих gate на экране сравнения моделей. Смесь остаётся исследовательской и не влияет на live-прогнозы/купоны.
- Добавлены unit-проверки отбора, минимальных выборок, отрицательного gate, нормировки вероятностей и неизменности validation-решения при изменении holdout-исходов; Compose проверяет отображение результатов validation/holdout.
- Локально прошли 189 JVM-тестов (0 failures/errors/skips), Android-test Kotlin compilation и обе APK-сборки. Actions [43396717001](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/43396717001) прошёл 63/63 API 34 теста и API 26 с 63 завершёнными тестами, одним offline-only skip и нулём failures; keyless builds, stable signing и artifact `14023542416` прошли. SHA-256 скачанного APK и DEX-аудит записаны в `docs/DEVELOPMENT_LOG.md`.

## Unreleased — объяснить назначение приложения на пустом первом запуске (CI/API 26/API 34 passed)

- Главный экран прямо описывает рабочий сценарий Football Intelligence. Если будущих матчей пока нет, он сообщает состояние локальной базы/первого импорта, показывает количество сохранённой истории, объясняет путь «Матчи → разбор → виртуальные купоны» и даёт быстрый переход к матчам и купонам. При пустой базе статусы источников раскрыты по умолчанию.
- Добавлена Compose-регрессия для пустой установки и перехода в основной сценарий. Локальный групповой прогон прошёл 184 JVM-теста без пропусков/ошибок, компиляцию Android-тестов и обе APK-сборки. Actions [43392442121](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/43392442121) прошёл 63/63 API 34 теста и 63 API 26 теста с одним offline-only skip и нулём ошибок; обе keyless-сборки, стабильная подпись и artifact `14021717980` успешны. CI debug APK SHA-256 `532e9055e6747e3a147e43773df1979fec541d9a7ab48d9d2c024392e709f659`, personal key отсутствует. Телефон в ADB пока не подключён, поэтому физическую синхронизацию подтвердить нельзя.

## Unreleased — wait for the first football and Elo baseline before international odds

- API 34 first-run acceptance showed the key-enabled Odds API Worker could spend a credit before multi-season sync completed and before a saved forecast existed. Gate the initial one-time request on an idle/unqueued football sync and at least one persisted forecast; keep the ordinary two-hour periodic schedule intact. A dedicated post-sync path queues only one-time work, so forecast refresh does not reset the periodic timer.
- The disposable API 34 runtime collected one league (309 accepted quote rows, 185 unique-date-only links, 0 exact-kickoff links, 124 unmatched) at a one-credit cost; 494/500 credits remained. Full sync finished with 38,845 matches / 29,612 results / 184 competitions and 125 future forecasts / 0 settlements. Local verification passed 184 JVM tests, Android-test Kotlin compilation and both APK assemblies. Actions [43389646589](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/43389646589) passed 62/62 API 34 tests and 62 API 26 tests with one offline-only skip and zero failures, then uploaded keyless artifact `14019918501`. Downloaded APK SHA-256 `b6444b0716bcc5504f4d40d7fb3d605ecdde0918e4994a6e54c2994831fa8871`; no personal key was present. The physical phone update-install remains pending ADB reconnection.

## Unreleased — redesign the Android analysis workspace (CI/API 26/API 34 passed)

- Add a dark Material 3 design, four-item bottom navigation, top-bar settings, Baku-local fixture times, compact home dashboard, date/league/quote filters, dedicated match analysis, and separated Misli/TOPAZ virtual coupon workspace. Move model stats and results into Analytics; keep source diagnostics and CSV under Settings. Collapse long model-comparison and source-detail text, and re-check displayed local/international prices against freshness while screens stay open.
- Add Compose regressions for navigation, dashboard states, coupon controls and Baku-date filters, plus Baku-time unit coverage. The grouped local run passed 182 JVM tests, Android-test Kotlin compilation, `assembleDebug`, `assemblePersonal`, signature verification and DEX audit. Debug APK SHA-256 `a9c786e4df03921fc8a8ca05d11ed9f8649d8aa64a1cca3f7608a366db4e33c8`; personal APK SHA-256 `47378d617e2fcc689c67ca30b188317708934463204295e231e70bb72695f2ff`; local signer SHA-256 `1d5bf81541d66c56c79c3a7c36bbeca06c353e4604abca671e2207ef88f7370`. DEX audit found the personal credential absent from debug and present only in the private personal APK. Actions [43334018542](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/43334018542) passed unit, API 34 and API 26 jobs, built both keyless variants and uploaded artifact `13996693439`; downloaded APK SHA-256 `b07cd32512f3f43eb2909b70d62812824ac3e43789cbbd03b17e8402da95898b`, CI signer SHA-256 `da1506fd2e896c67d3ceafc8b3ab7f33824e759e092adf938fdf11c52fa8d935`. The APK was later visually reviewed on a disposable API 34 AVD. A local personal variant was installed separately on the owner's physical Android 13 phone; see the 2026-11-13 runtime entry for worker, freshness and sync evidence.

## Unreleased — validate OpenLigaDB final-score semantics

- Settle imported 1X2 results only from matches marked finished and the semantic `After90Minutes` row. Do not treat extra-time, penalty-only or unfinished results as settled; retain result type ID 2 only for older payloads with no semantic-kind fields. Added regression coverage for mixed score rows, unfinished matches and legacy payloads. Cancellation remains unknown because the source schema has no cancellation field.
- Local grouped verification passed 181 JVM tests (zero failures/errors/skips), Android-test Kotlin compilation, debug/personal APK assembly, signature verification and DEX credential audit. Actions [43327716809](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/43327716809) passed unit tests, API 34 57/57, API 26 with one offline-only skip and zero failures, both keyless builds, signing and artifact upload `13993088983`. No APK was installed and no odds quota was used.

## Unreleased — разбить Elo walk-forward по лигам и сезонам

- Сохранять исходную метку сезона в walk-forward target и группировать уже рассчитанные строгие as-of Elo оценки по соревнованию и сезону. Пропущенные метки остаются неизвестными; даты не используются для их угадывания. Экран показывает Brier/Log Loss, coverage и предупреждение для n<30. Добавлены unit и Compose regression tests.
- Локальная групповая проверка прошла 179 JVM tests, Android-test Kotlin compilation и обе APK-сборки. Actions [43324379707](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/43324379707) прошёл API 34 57/57, API 26 с одним offline-only skip и без ошибок, keyless builds/signing/upload. Артефакт `13992013550`, SHA-256 и DEX key audit записаны в [development log](DEVELOPMENT_LOG.md).

## Unreleased — добавить парные интервалы сравнения моделей (CI/API 26/API 34 passed)

- Add deterministic 2,000-resample paired percentile-bootstrap 95% intervals for Elo-minus-Poisson and Elo-minus-Home/Away Brier/Log Loss differences on shared chronological holdout fixtures. Require at least 30 paired fixtures, label negative loss differences as favoring Elo, and document the independence limitation from recurring teams/rounds. Intervals are descriptive and never tune the holdout or live model. Local grouped verification passed 175 JVM tests, Android-test Kotlin compilation and both APK builds. Actions [43274830661](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/43274830661) passed the unit task, 56 API 34 tests, API 26 with one offline-only skip, keyless builds, signing and upload `13969583772`.

## Unreleased — compare international market de-vig methods (CI/API 26/API 34 passed)

- Expose Basic, Power and Shin equal-weight cross-book probability averages for the same deduplicated bookmaker set on an exactly linked fixture. Keep Shin unavailable for the whole event when any included quote is underround, rather than silently changing the sample. Label equal weights as descriptive and source independence as unproven; show the shared summary once in the representative quote card.
- Add unit coverage for all methods and the underround gate, plus Compose coverage for visible international method comparison. Grouped local verification before the final UI deduplication passed 177 JVM tests (0 failures/errors/skips), Android-test Kotlin compilation and debug/personal APK assembly. Actions [43317495201](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/43317495201) passed the unit task, 56 API 34 tests, API 26 with one offline-only skip, keyless builds, signing and upload `13988364519`. Downloaded APK SHA-256 `3702f3689f59c3441557095c19e7bc6ad1c267fa632384e3a84ce7e9bd193d6b`; stable signer SHA-256 `da1506fd2e896c67d3ceafc8b3ab7f33824e759e092adf938fdf11c52fa8d935`; DEX audit confirmed the personal key absent. No APK install occurred.

## Unreleased — добавить venue-split Home/Away baseline (CI/API 26/API 34 passed)

- Add a separate research model for home-team home scoring/conceding and away-team away scoring/conceding, shrink venue rates toward league rates, and gate forecasts on prior league and venue-specific sample sizes. Run it through strict availability walk-forward and report validation plus paired Elo holdout metrics. It does not alter live forecasts, virtual coupons, or model selection. Local grouped cycle passed 174 JVM tests, Android-test Kotlin compilation and both APK assemblies. Actions [43269913184](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/43269913184) passed 56 API 34 tests and all 56 discovered API 26 tests with one offline-only skip and zero failures; keyless builds succeeded and debug artifact `13968736908` was uploaded. Downloaded APK SHA-256 `d790d87706eab44cbb48196546327cf2aada6d7642849204552fcfb5d1bafc3b`; signer SHA-256 `da1506fd2e896c67d3ceafc8b3ab7f33824e759e092adf938fdf11c52fa8d935`; personal credential absent.

## Unreleased — add rate-limited automatic current-season match statistics (CI/API 26/API 34 passed)

- Add a Football-Data.co.uk worker which makes at most two requests in any rolling seven days, persists the request reservation before HTTP (including failures), rotates one current-season European division at a time, and saves only shots, shots on target, corners, fouls and cards for a unique exact local completed match. The results history shows source, capture time and availability; no CSV odds are parsed or saved, and no model input changes.
- Add parser, season rollover, rolling-quota, schema migration, fake-network/database integration and Compose display coverage. Local batch passed 169 JVM tests, Android-test Kotlin compilation, `assembleDebug` and `assemblePersonal`. Local APK hashes: debug `54d558c75c2bcbc8f851d778ccf2d94c5f964b37de205e8467005edda4844182`; private personal `896ec6366593dd67bc22236e1c34d4d8215323baf2f52bc547c1289e4a498b42`. Actions [43265092713](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/43265092713) passed 55/55 API 34 instrumentation tests and API 26 with one offline-only skip and zero failures; both variants built keyless. Debug artifact `13965318819` has APK SHA-256 `af132850e1e46abed9be68b0588f5618c2da21e3e77c01984408d8f8903457e0` and signer SHA-256 `da1506fd2e896c67d3ceafc8b3ab7f33824e759e092adf938fdf11c52fa8d935`. DEX inspection found no personal credential. No APK installation occurred.

## Unreleased — repair partially present v17 international odds tables (CI/API 26/API 34 passed)

- When upgrading a partial v17 database with history rows but no current-quote table, create the absent table, continue migrating existing history rows to the v18 Back/Lay schema, and rehydrate current quotes from the newest saved row per market/side. Add an API instrumentation regression that verifies row identity, source market, fixture link, new schema columns, current-row recovery and reads after migration.
- Local batch passed 163 JVM tests, Android-test Kotlin compilation and both APK builds. Actions [43252033127](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/43252033127) passed 51/51 API 34 tests and 51 completed API 26 tests with one network-dependent skip and zero failures. Keyless CI builds succeeded; only stable-signed debug artifact `13959732145` was uploaded. APK SHA-256 `a6fd4f6dfcdba5059e85d968f1056c19a4c574c0294f29b3b2ddba2515510844`; signer SHA-256 `da1506fd2e896c67d3ceafc8b3ab7f33824e759e092adf938fdf11c52fa8d935`; personal key absent. No APK installation occurred.

## Unreleased — retain The Odds API exchange Back/Lay markets (CI/API 26/API 34 passed)

- Parse both documented V4 exchange sides returned with `h2h`, request available `bet_limit` fields, and retain source market, Back/Lay side, capture timestamps and per-outcome limits in SQLite v18. Migrate v17 odds rows without dropping their identifiers or link precision.
- Keep Back and Lay separate in the World Market UI and history. Count only Back when forming independent-book consensus or prospective Elo comparison. Show a Lay−Back spread only for a fully matched same-response pair; label source limits as non-liquidity and state that Betfair commission is unavailable/not deducted.
- Add parser, spread, market-engine, Compose and v17→v18 migration regressions. The first Actions attempt found five API 34 test issues: three expectations needed the explicit side label/one spread row, and two older migration fixtures had no quote tables. Those are fixed; incomplete old databases now create only absent odds tables. The corrected grouped local cycle passed 163 JVM tests, Android-test Kotlin compilation and both local APK assemblies. Actions [43247865315](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/43247865315) passed the unit-test task and 50/50 API 34 instrumentation; API 26 completed 50/50 with one network-dependent test skipped and zero failures. Keyless builds succeeded and only stable-signed debug artifact `13958882301` was uploaded. Downloaded APK SHA-256 `fc6ab0857060de03e1e297816e26ff237a09719dd73303b5215e20d07e23ce96`; signer SHA-256 `da1506fd2e896c67d3ceafc8b3ab7f33824e759e092adf938fdf11c52fa8d935`. DEX audit found no personal key. No APK installation or data-bearing emulator change was made.

## Unreleased — move full-screen market and forecast diagnostics off the UI thread

- Calculate international consensus, paired market evaluation, and prospective forecast metrics together from one immutable screen snapshot on `Dispatchers.Default`.
- Keep the full prospective evaluation ledger as the scoring input while the recent-history panel stays capped at 100 rows. Hide prior aggregate metrics while the next snapshot is evaluated, show an updating message, and keep current source rows/history visible. Add regression tests for the full-ledger boundary and the updating state. The local combined cycle passed 161 JVM tests, Android-test Kotlin compilation and both APK builds. After fixing an offscreen assertion, Actions [43236311635](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/43236311635) passed 48 API 34 instrumentation tests and API 26 with 48/48 complete, one existing radio-dependent skip and zero failures. It built both variants keyless, stable-signed, and uploaded only debug artifact `13952234140` (archive SHA-256 `47dc47c6c65540d758f88a7f184e51a38550301928f15a2d92cb5e7d2f038e7b`). Downloaded APK SHA-256 `51afdb2936462c61983a99711d3acd779e48b9cf94db1c301ed9bf5d06355b46`; signer SHA-256 `da1506fd2e896c67d3ceafc8b3ab7f33824e759e092adf938fdf11c52fa8d935`; DEX contains no personal key.
- No database schema, scoring rule, forecast input, or cutoff changes. The grouped local cycle passed 161 JVM tests, Android-test Kotlin compilation, and both APK builds. Actions [43231061329](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/43231061329) passed its unit-test task, all 46 API 34 instrumentation tests, and API 26 with 46/46 complete, one existing radio-dependent skip and zero failures; it built and stable-signed both variants and uploaded only keyless artifact `13950613415`. The API 26 runner printed “Finished 47 tests” after the 46/46 count, while Gradle succeeded. No APK was installed.

## Distribute free Odds API refreshes across the day (CI/API 26/API 34 passed)

- Change from a once-daily batch of 12 leagues to one rotating league per two-hour WorkManager run. A synchronized persisted gate allows at most 12 attempts in any rolling 24-hour window, stores quota response headers, pauses when 20 or fewer credits remain until after the monthly reset window, and honors server `Retry-After` after HTTP 429/503 responses.
- On upgrade, wait out the old 24-hour batch window because the prior version stored only a batch timestamp, not one time per league request.
- Update existing periodic work with WorkManager `UPDATE` so installations migrate from the old 24-hour schedule. Network-connected and best-effort Android behavior remains in force.
- Add tests for two-hour staleness, rolling request limits, quota reserve and monthly reset, retry cooldown, one-league worker runs, and migration of an already-enqueued 24-hour periodic request. The final local cycle passed 160 JVM tests and both APK builds. Actions [43227026352](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/43227026352) passed 46 API 34 tests and API 26 with 46/46 complete, one existing radio-dependent skip and zero failures. Both variants built keyless in CI; only debug artifact `13948652972` was uploaded. Downloaded APK SHA-256 `e59393fb49b164c5c5145e06fae62ce1f9491a6b7dce14a58906815ec683f131`; signer SHA-256 `da1506fd2e896c67d3ceafc8b3ab7f33824e759e092adf938fdf11c52fa8d935`; no personal key found. Full hashes and local APK audit are in `docs/DEVELOPMENT_LOG.md`.

## Prefer freshest duplicate Odds API lines and validate catalog-cache clock (CI verified)

- For duplicate reports of the same bookmaker/event, retain the latest provider market timestamp (capture time breaks a tie) while counting one accepted line. Reject missing, expired or future-dated catalog-cache timestamps.
- Add parser and cache-window regressions. The grouped local run passed 158 JVM tests, Android-test Kotlin compilation and debug/personal APK assembly. Actions [43219694343](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/43219694343) passed 44 API 34 tests and API 26 with 44/44 complete, one existing radio-dependent skip and zero failures. Keyless variants built; only debug artifact `13945757789` was uploaded. Downloaded APK SHA-256 `d5aebaff03d8e7e3f84c554b7c19d7761f3e0343415f8750cdff1cf33bb38b07`; stable signer SHA-256 `da1506fd2e896c67d3ceafc8b3ab7f33824e759e092adf938fdf11c52fa8d935`; credential absent. No install was done.

## Unreleased — clarify paired market delta direction in the Android UI

- Explain which Elo-minus-market signs indicate lower Brier/Log Loss or higher top-1 accuracy, and keep the repeated-team/round independence limitation visible.
- Add Compose instrumentation coverage for interval values at the 30-match gate and the explicit unavailable message at 29 settled fixtures.
- The grouped local command passed 157 JVM tests (0 failures/errors/skips), Android-test Kotlin compilation and both APK assemblies. Commit `7500457` passed Actions [43216630036](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/43216630036): 44 API 34 tests, API 26 with 44/44 complete and one existing radio-dependent skip, zero failures, keyless builds and stable signing. Debug artifact `13943294870` has APK SHA-256 `f6d7fd7c458a7ec51ce2e69bba2da11ce15aea6f73f7b540dc07e8ff8ff06254` and signer SHA-256 `da1506fd2e896c67d3ceafc8b3ab7f33824e759e092adf938fdf11c52fa8d935`; inspection confirmed it contains no personal key. No APK was installed.

## Unreleased — add uncertainty intervals to paired international market comparison

- Calculate paired Elo-minus-market differences for Brier score, Log Loss and top-1 accuracy on the exact same settled fixture cohort. Run 2,000 deterministic percentile-bootstrap resamples per bookmaker and show 95% intervals only after 30 verified settled fixtures.
- Label the deltas as Elo minus market and disclose the independent-fixture assumption in the UI; repeated teams/competition rounds may make the interval optimistic. Keep the metrics descriptive, without a value, ROI or profit claim.
- Add unit coverage for determinism, expected sign/direction, finite ordered bounds and the minimum-sample gate. No database schema or migration change.
- The grouped local command passed 157 JVM tests (0 failures/errors/skips), Android-test Kotlin compilation and both APK assemblies. Debug APK SHA-256 `9c36f437d6cec419db11a1ac934048f0409c5098efa0750b79e44864472bea56`; private personal APK SHA-256 `c423fa0dc24b68c4c2691cc732a64a476aab71d4692975c4234855fd50dcbe96`. Both local APK signatures verified. Commit `0277341` passed Actions [43213479983](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/43213479983): 43 API 34 tests and API 26 with 43/43 complete (one existing radio skip, zero failures), both keyless variant builds, stable signing and debug artifact upload `13942886983`. Downloaded APK SHA-256 `c26345fae80dfb93e8473c39ee78bd7003f4271897398d133eafa277eb5b2163`, stable signer SHA-256 `da1506fd2e896c67d3ceafc8b3ab7f33824e759e092adf938fdf11c52fa8d935`; binary check found no personal key.

## Fix compact-screen World Market scrolling and add paired market scoring (CI verified)

- Compare the last retained fresh pre-kickoff international 1X2 quote per linked fixture and bookmaker with a saved current-Elo forecast available by quote capture. Keep bookmakers separate and show only Brier, log loss and top-outcome accuracy for a shared settled cohort.
- Exclude unmatched, stale, post-kickoff, unpaired and result-time-inconsistent rows. Use the existing 30-day/5,000-row quote history; no schema migration, CLV or ROI calculation is added.
- Put the full World Market header and quotes in one scrollable list so the header cannot consume the quote viewport on compact Android screens; instrumentation scrolls to the evaluation card, named bookmaker quote and provider attribution.
- The grouped local command passed 155 JVM tests, Android-test Kotlin compilation, and debug/personal APK assembly. `git diff --check` passed. Debug APK SHA-256 `9576237e823d7d7b15c9672ae2ac134613222c5a04d1254475ea26ead6ca29e0`; personal APK SHA-256 `3be1e7933eca13df69c063d74d7adf4c95f2a897fc18ee6195f26c22c96a232e`. Both signatures verified; payload audit found the private key absent from debug and present only in the owner-only personal APK. Commit `ea92f9a` passed Actions [43208538583](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/43208538583): 43/43 API 34 tests, API 26 with 42 passed + one radio-dependent skip, build/signing and artifact upload. Downloaded keyless APK artifact `13940088159` has SHA-256 `87c675edc1fb6337967bb39ab3fc8b7b0ed0e0b7cf28dc83216083db176c1573`, stable signer SHA-256 `da1506fd2e896c67d3ceafc8b3ab7f33824e759e092adf938fdf11c52fa8d935`; payload audit confirms it has no personal key.

## Track fixture-link precision for international odds (CI verified)

- Keep exact-kickoff matching, and allow a local date-only fixture only when normalized home/away teams and UTC date identify one unfinished local row. Reject same-day ambiguities, orientation changes, and wrong dates.
- Store and display whether the link used exact kickoff or a unique date-only fixture. When a later source adds kickoff time, relink the saved international current/history rows and upgrade the basis. Local bookmaker matching remains exact-kickoff-only.
- Migrate SQLite v16→v17 by adding nullable columns to both international odds tables; previously linked rows are labeled exact, and prices/history remain intact. Add parser, relink-upgrade, schema-migration and UI coverage.
- Local verification passed 150 JVM tests, Android-test Kotlin compilation and debug/personal APK assembly; after tightening exact-match precedence, all 150 unit tests passed again. Actions [43200257001](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/43200257001) passed all 43 API 34 tests and API 26 with 43 passed, one radio-dependent skip and zero failures, then built both variants keyless and uploaded the stable-signed debug artifact `13935831014`. APK hash/signature and the private local APK hash are recorded in `docs/DEVELOPMENT_LOG.md`. No install or persistent database change was made.

## Correct prospective evaluation sampling (CI verified)

- Score the full saved forecast ledger instead of only the latest 100 visible rows. Keep the recent screen list bounded at 100.
- Deduplicate daily forecasts to the latest valid pre-kickoff snapshot per fixture/model version; show settled and pending unique fixture counts and Brier/Log Loss/top-outcome metrics by version.
- Reject settlement records without a named result source, matching score/outcome, post-kickoff/post-forecast availability, and settlement at/after availability. No database migration is needed.
- Local grouped cycle passed 149 JVM tests, Android-test Kotlin compilation, and debug/personal APK builds. Actions [43195049041](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/43195049041) passed the unit-test task and 41 API 34 tests; API 26 passed 40 with one radio-dependent skip and zero failures. The uploaded keyless artifact is `13933137162`; APK and signer hashes are in `docs/DEVELOPMENT_LOG.md`. The instrumentation case covers 101 stored snapshots. No app install was performed.

## Add The Odds API V4 personal provider (CI verified)

- Add an independent EU h2h odds provider with fresh complete 1X2 validation, server/device clock checks, exact unique fixture links, event/book deduplication, quota-header display, a seven-day soccer-catalog cache, and a bounded rotating 12-league daily Worker. The key is read from ignored `local.properties` and sent in the documented `x-api-key` header. Only the local personal APK embeds it; standard/CI debug APKs stay keyless, and CI uploads only the keyless APK. The private APK must not be published.
- Show per-provider sync/history state, named lines, source and capture times, no-vig references and exact-link model/local comparisons in World Market. International odds do not enter Misli/TOPAZ coupons, and no price/EV is fabricated. No SQLite migration was required.
- Add parser, URL/header, Worker, quota-gate, provider-isolation, scheduler and UI regression tests. The grouped local cycle passed 147 JVM tests, Android-test Kotlin compilation, and debug/personal APK assemblies. Actions [43191151068](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/43191151068) passed 41 API 34 tests and API 26 with 40 passes, one network/radio-dependent skip, and zero failures; both variants built keyless in CI, and keyless artifact `13930344719` was stable-signed and uploaded. Downloaded APK SHA-256 is `30fba2445dbf0832a1693596b6b61857efedd13c81c3474b836d7a61cfb1f82a`; signer SHA-256 and local private APK hash are recorded in `docs/DEVELOPMENT_LOG.md`. The local personal APK embeds the user's key and must remain private. No install was performed.

## Extract market-analysis panels (CI verified)

- Move the selected-match Analysis and World Market panels into `FootballMarketTabs.kt` with explicit snapshot, forecast, worker-state, and action inputs. Keep age-consent persistence and scheduling at the screen host. No provider, pricing, model, coupon, or database behavior changes.
- The local grouped cycle passed 131 JVM tests, Android-test Kotlin compilation, APK assembly, and `git diff --check`; local APK SHA-256 `7d444c10ec3ae08e7b4610cf487f48f03e9ca343ae168ed819c839ca3ac40739`. Actions [43171418933](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/43171418933) passed unit tests, 32 API 34 instrumentation tests, API 26 instrumentation (one network-radio skip), APK build, stable signing and artifact upload `13923020589`. The API 26 runner printed 33 finished after 32 declared tests, with Gradle successful. Downloaded APK SHA-256 `c061f9df3b4512be0c8eb0a6a946a002667bda6d1159acdd7a92bdfdb2d483e5`; stable signer SHA-256 `da1506fd2e896c67d3ceafc8b3ab7f33824e759e092adf938fdf11c52fa8d935`.

## Add the screen persistence boundary (CI verified)

- Route screen reads and writes through `FootballScreenRepository`; its SQLite adapter confines operations to `Dispatchers.IO`. Keep background Worker and schema-migration access on the existing `Store`.
- Run both public bookmaker-page collectors in lifecycle-bound Compose coroutines, with visible-text parsing on `Dispatchers.Default`, cancellation handling and repository persistence for success and failure states.
- Extend isolated SQLite instrumentation coverage through the repository. The grouped local cycle passed 131 JVM tests, Android-test Kotlin compilation and APK assembly; APK SHA-256 `064f2dee85b3262b00e7c36853faa9a396fb42b1ebfb7fae44b0428820784816`. Actions [43164713268](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/43164713268) passed all 32 API 34 tests and API 26 instrumentation (one emulator-radio test skipped), then built, stable-signed and uploaded artifact `13919403768`. API 26 printed 33 finished after 32 declared tests; Gradle succeeded. Downloaded APK SHA-256 `349987dcfbdf5cb02ad459a25d327e51a83440160ac4a0b235bb2c1334fb6358`; signer SHA-256 `da1506fd2e896c67d3ceafc8b3ab7f33824e759e092adf938fdf11c52fa8d935`.

## Defer Room until a concrete migration need exists

- Keep the version-16 SQLite helper behind the repository seam. The observed UI-thread database access is addressed by IO dispatch; migrating the active, migration-tested user database to Room would add conversion and data-loss risk without a measured benefit. Revisit only with a concrete DAO/invalidation need and a tested backup/copy/rollback plan across API 26 and API 34.

## Move SQLite ownership out of the activity (CI verified)

- Move the existing `StoreWriteCoordinator` and `Store` implementation into `Store.kt` without changing the v16 schema, SQL, transaction behavior, or migrations. Add an isolated repository test that closes/reopens the database and checks the v16 version and saved fixture. The grouped local cycle passed 131 JVM tests, Android-test Kotlin compilation, and APK assembly (SHA-256 `1a1be257364c90b50e56ef8ffcccd2156f189cc82fd35090ac283b11e2d001f4`). Actions [43167074311](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/43167074311) passed 32 API 34 tests and API 26 instrumentation (one network-radio skip), APK build, stable signing, and artifact upload `13921076146`; downloaded APK SHA-256 `f10d3899668032b84e9870fd8fa0102fd449aaf126f9a8d8c80ec1837e889bbb`, stable signer SHA-256 `da1506fd2e896c67d3ceafc8b3ab7f33824e759e092adf938fdf11c52fa8d935`. The API 26 runner printed 33 finished after the 32 declared tests completed, while Gradle succeeded.

## Split the activity, screen, and bookmaker collectors (CI verified)

- Move `FootballScreen`, its supporting Compose UI and model types out of `MainActivity.kt`, and isolate the two visible-page collectors in their own file. The Android Activity becomes a thin lifecycle host; no user-facing behavior or source access changes. The grouped local cycle passed 131 JVM tests, Android-test Kotlin compilation, and APK assembly (SHA-256 `c3e355f713ef5ec132afa4b6813630d058557082049f36c22dc6a0c40df5c4b3`). Actions [43168786087](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/43168786087) passed 32 API 34 tests and API 26 instrumentation (one network-radio skip), APK build, stable signing and artifact upload `13922000243`. Downloaded APK SHA-256 `c605462a1adb3a354a3d7c65fe4af13ee36aca47793b27c28cb69449f9484862`; signer SHA-256 `da1506fd2e896c67d3ceafc8b3ab7f33824e759e092adf938fdf11c52fa8d935`. The API 26 runner printed 33 finished after its 32 declared tests completed; Gradle succeeded.

## Load the Compose screen read model asynchronously (CI verified)

- Group the screen's match, provider, odds, coupon and forecast-history queries in an immutable read model fetched on `Dispatchers.IO`. Compose keeps the last successful snapshot when a later read fails and shows the database error; bookmaker WebViews wait for the first stored freshness state so launch does not bypass the 30-minute capture gate.
- Add an isolated SQLite instrumentation test for loading a fixture and its selected quotes/picks. Local verification passed 131 JVM tests, Android-test Kotlin compilation and APK assembly. Actions [43162242684](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/43162242684) passed all 32 API 34 tests and API 26 with 1 radio test skipped/0 failures, then built and stable-signed artifact `13918259270`. The API 26 runner printed `33/32` after its `32/32` completion line; Gradle reported no test failure.

## Keep CSV import and backtests within the screen coroutine scope (CI verified)

- Replace the CSV import, imported walk-forward, and automatic walk-forward raw threads with Compose-scoped coroutines. CSV file I/O and database operations run on `Dispatchers.IO`; parsing and imported walk-forward calculation run on `Dispatchers.Default`. UI state updates stay on the main coroutine, and cancellation is rethrown instead of being shown as an import/backtest error.
- This changes execution/lifecycle handling only; SQLite schema, import validation, model cutoff rules, and stored results are unchanged. Local verification passed 131 JVM tests, Android-test Kotlin compilation, and debug APK assembly. Actions [43160327709](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/43160327709) passed all 31 API 34 tests, API 26 with 30 passed/1 radio-control skip, APK build and stable signing. Artifact `13917683770`; APK SHA-256 `f08b12dc23ffd0ec04b85e8713086c933843716bd978f93f886a1fc8c8d8ad2c`.

## API 26 instrumentation compatibility and CI matrix (verified)

- Add API 26 instrumentation alongside API 34 with one APK build/sign/upload stage. The first run exposed `AutoCloseable`/`Store` test compatibility; the second passed 30/31 API 26 tests after adding `Closeable`, with the remaining failure caused by the API 26 emulator retaining Wi-Fi and an active network. A third run then showed the API 34 offline test also requires its modern shell radio controls.
- Skip only the uncontrollable radio-dependent check on API 26, retain connected-constraint assertions at both APIs, and use `cmd connectivity` / `cmd wifi` on API 30+ where the offline check was verified. Actions [43158120648](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/43158120648) passed all 31 API 34 tests, API 26 with 30 passed and 1 skipped, unit tests, APK build, stable signing and upload. APK SHA-256 `598b0d1036b4c29c06da91d6ca962de2668565a109e68a45dec396b97d1d8bc0`; signer SHA-256 `da1506fd2e896c67d3ceafc8b3ab7f33824e759e092adf938fdf11c52fa8d935`. API 26 radio-off behavior is not verified.

## Unreleased — recheck free international odds pages

- Confirm that the requested public pages can display soccer 1X2 prices, but their terms do not permit the app's automated collection/reuse without consent. Oddspedia advertises free data feeds/widgets, but those require a registered, verified account and limit service use to controlled assets; this does not meet the no-account default requirement. MoviOdds remains the existing documented no-key source; no new source is claimed.

## Unreleased — prevent false international market consensus (local and Actions passed)

- Require every quote in a consensus event group to match each other on normalized home/away order and five-minute kickoff tolerance. Conflicting exact local match IDs cannot merge, and ambiguous rows compatible with multiple groups are kept separate.
- Add regression coverage for chained kickoff tolerances, midpoint ambiguity and conflicting fixture IDs.
- Recheck international sources: Football-Data.co.uk's limited personal-use automation did not produce upcoming 1X2 prices or a quote-level time in the observed file; 1.ML's terms require written authorization for automated collection and local archiving. No new live adapter is claimed.
- The grouped local cycle passed 131 JVM tests (0 failures/errors/skips), Android-test Kotlin compilation and debug APK assembly; SHA-256 `5379bfe5d89557030589451661fc7cb0e55585015de4c22ff9b0e7a63a055882`. Commit `d10f05b` passed Actions [43153122513](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/43153122513), including all 30 API 34 instrumentation tests, APK build, stable signing and upload `13914359935`; CI APK SHA-256 `0702d9d704701baf7c5093f36986a5807fbba490109eeb37dcad6fdbb45eeccb`, signer SHA-256 `da1506fd2e896c67d3ceafc8b3ab7f33824e759e092adf938fdf11c52fa8d935`.

## Unreleased — retain international source-attempt history

- Save MoviOdds request status, safe detail and available parser quality counts for 30 days. Show the five latest attempts on World Market; do not calculate an uptime/reliability percentage from this short, device-local sample.
- Add SQLite v15→v16 migration and coverage for preserving existing sync state, status ordering and absent counts on request failure. The first grouped run found and fixed a migration-test scope error. The successful combined local cycle passed 128 JVM tests, Android-test Kotlin compilation and APK assembly; local APK SHA-256 `50dd310c5a37337275484af6fb127ac30b10720d726a36258080c08f61387cae`. Commit `50001a1` passed Actions [43150950747](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/43150950747), including all 30 API 34 instrumentation tests, APK build, stable signing and artifact upload `13913099796`; downloaded APK SHA-256 `0474fd3bbb5945ec74c59c912272858f9df860c13d45e62466dc963510c73245`, signer SHA-256 `da1506fd2e896c67d3ceaf6c8b3ab7f33824e759e092adf938fdf11c52fa8d935`.

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
