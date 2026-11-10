# Football Intelligence — дорожная карта Android

## Цель

Личное Android-приложение с нулевой стоимостью и нулевым обязательным вводом: автоматически получает разрешённые футбольные расписания и результаты, хранит их локально, строит честные вероятности и сверяет сохранённые виртуальные прогнозы. Реальных ставок, аккаунтов и платных API нет.

## Что реализовано в активной ревизии

- OpenFootball football.json: обнаружение JSON-файлов в пяти европейских сезонах и всех доступных в каталоге соревнованиях; CC0-наборы, ETag, ежедневная проверка текущего сезона, ежемесячная проверка старых данных, безопасные date-only записи.
- OpenLigaDB: динамическое обнаружение футбольных лиг за пять календарных лет, полный исторический импорт с лимитом 48 запросов/мин, локальное кэширование, проверка текущей группы/last-change, недельное полное обновление, bounded Worker с продолжением в последовательной WorkManager-очереди; сетевые ошибки используют retry/backoff.
- WorkManager: уникальная периодическая задача раз в 24 часа и задача при запуске, если последний успех устарел на 8 часов. Android может отложить задачу; приложение сразу открывается с локальной базой.
- SQLite v11: provenance, source/capture/availability timestamps, сохранение результатов, conservative dedupe, virtual accumulator history и отдельный prospective forecast/result ledger с миграциями.
- Главный экран: синхронизация и свежесть источников, матчи и отдельная история результатов. Автоматический Elo использует только точные предматчевые kickoff и пригодную историю. Виртуальные одиночные и размерные купоны Misli/TOPAZ хранятся отдельно и сверяются с исходами.
- SQLite v11 добавляет неизменяемый журнал предматчевых прогнозов по версии Elo, матчу и UTC-дню; результат связывается отдельной записью после его локальной доступности. Исправления счёта не меняют прогноз. Live-модель использует только исходы, которые начались и стали доступны до момента расчёта. На экране Brier/Log Loss/top-pick считаются только по завершившимся prospective snapshots. Commit `22f88f5` прошёл Actions [42765702211](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/42765702211) (88 JVM + 5 API 34 instrumentation); CI APK обновил установленное приложение без потери базы. На устройстве: 62 будущих снимка, 0 завершённых результатов.
- Cross-provider merge keeps both source names/URLs and the prior final score on incomplete or conflicting duplicates, and upgrades date-only rows when exact kickoff/result data arrives. Commit `fab515a` passed Actions [42767934594](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/42767934594) with 88 JVM and 7 API 34 instrumentation tests; its stable-signed APK was update-installed without data clear. Offline queue gating then passed locally and in Actions [42769411532](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/42769411532) with 8 API 34 instrumentation tests. The real Worker failure-to-`Result.retry()` path is also CI-verified in [42773538883](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/42773538883), with all 9 API 34 instrumentation tests passing. Its stable-signed APK was update-installed without data clear; schema v11, 39,181 matches, 184 competitions, 350 odds snapshots, 69 accumulators/384 legs and 62 forecast snapshots were preserved. Commit `ee438ac` also passed all 10 API 34 instrumentation tests in Actions [42777428309](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/42777428309); the 30-second retry/backoff resumption test now runs in CI. The signed APK was update-installed without data clear and preserved schema v11, 39,181 matches, 184 competitions, 385 odds snapshots, 71 accumulators/402 legs and 62 forecast snapshots.
- MisliOddsProvider и TopazOddsProvider читают только видимый текст обычного публичного Android WebView. Последний API 34 проход 2026-11-10 05:41:38 UTC получил 9 линий Misli и 25 TOPAZ; 7 TOPAZ событий связаны с локальными fixtures, Misli-связей — 0. Portugal—Denmark → match 85257: цена TOPAZ H/D/A 1.45/4.31/5.29, Elo H 59.1%. Виртуальные купоны показали цены и диапазон Фреше для совместной вероятности. Котировки просрочиваются через 30 минут. WebView технически проверен на одном API 34 устройстве; права автоматического повторного использования обоими операторами не подтверждены.
- Котировки старше 30 минут, с будущей датой получения, без точного сопоставления или без полной 1/X/2 линии не используются для price-backed купона. Последний capture создал 3 TOPAZ купона (2/3/5 legs, same-book combined odds 1.75/2.09/26.87). Для model-only купонов без полной цены показываются маргинальные вероятности и границы Фреше, без fabricated odds или независимого произведения вероятностей. Купоны ещё не завершили исходы; проверенный ROI пока недоступен.
- eTopaz не указывает год события; парсер принимает следующий календарный occurrence лишь в пределах 90 дней и явно отмечает выведенный год. Türkiye–Belgium cross-check по времени прошёл через расписание Лиги наций УЕФА.
- API 34 first-run sync через WorkManager retries собрал 39 181 объединённую строку из 184 соревнований; OpenFootball 25 904 / OpenLigaDB 17 776. Найдены 566 legacy placeholder rows, которые сохраняются, но исключены из экранов, моделей и бэктеста. После фильтрации walk-forward: 29 588 целей, 19 185 прогнозов, 10 403 пропуска по истории, Brier 0.6106, Log Loss 1.0235. Это ретроспективная оценка текущих файлов, не подтверждение будущего качества или прибыли.
- Walk-forward показывает one-vs-rest reliability bins для П1/X/П2, среднее расхождение ECE и размеры выборок в занятых корзинах. Это описательная ретроспективная диагностика, без преобразования вероятностей.
- AndroidJUnitRunner проверяет миграции SQLite v1→v11, v9→v11 и v10→v11 на изолированных тестовых базах, сохранение исходов и виртуальных записей. Текущая локальная проверка покрывает 5 тестов, включая неизменность прогноза после исправления счёта; Actions ещё ожидается.
- AndroidJUnitRunner также проверяет WorkManager очередь: ограничение CONNECTED, период 24 часа, отсутствие дублей при повторном enqueue и пропуск разовой задачи при свежих данных. Локальный API 34 AVD и Actions [42761781971](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/42761781971) прошли тест. Разовый запуск отложен на час и удаляется после проверки, поэтому публичные источники не вызываются.
- CSV-импорт доступен только в настройках и не нужен основному сценарию. Walk-forward по CSV остаётся дополнительным; основной отчёт использует локальную автоматическую историю.

## Проверки текущей ревизии

- API 34 fresh install, многократная WorkManager sync и офлайн-перезапуск проверены ранее; новый SQLite v10 update install сохранил существующую базу.
- Commit `fb65bcc` прошёл Actions [42759573588](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/42759573588): CI API 34 запустил 2 SQLite instrumentation теста, собрал APK, подписал и загрузил artifact. Update-install сохранил 39 181 матч, 197 bookmaker snapshots, 59 accumulators и 328 legs.
- Commit `92836d8` прошёл Actions [42761781971](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/42761781971): 84 unit-теста, 3 API 34 instrumentation теста, APK, stable signing и artifact upload.
- Commit `e8c3a33` прошёл CI [42752522409](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/42752522409): 79 unit-тестов локально, APK и стабильная подпись в CI. Обновление того же APK на API 26 сохранило SQLite v10/32 680 исходных строк. Первый Worker завершился `SUCCESS` на границе 8 минут, созданный child-worker немедленно продолжил и тоже завершился `SUCCESS`. Финальная база API 26: 38 609 матчей, 29 588 результатов, 183 соревнования; OpenFootball `OK`/25 904, OpenLigaDB `OK`/17 202, обработано 214 league-season состояний. Оба bookmaker WebView на API 26 всё ещё показывают `ERROR` без видимых данных; свежих котировок нет и цены не подставляются.
- Commit `6faac0b` прошёл Actions [42757663129](https://github.com/jafarovsamir89/Football-Intelligence/actions/runs/42757663129): 83 теста, APK, стабильная подпись и artifact upload. На API 34 UI показал 19 185 ретроспективных прогнозов и one-vs-rest ECE: общий 2.2%, П1 3.1%, X 0.9%, П2 2.5%; SQLite сохранила 39 181 матч, 197 bookmaker snapshots, 59 accumulator rows при update-install.
- На capture 05:41:38 UTC составлены виртуальные same-book TOPAZ купоны и проверено сопоставление Portugal—Denmark. Снимок цены ограничен 30-минутной свежестью; статус `LIVE` означает только технический capture. Не обнаружена явная лицензия на повторное использование котировок.

## Оставшиеся инженерные задачи

1. Дождаться CI для теста реального Worker `Result.retry()` после отказа источников; отдельно проверить повторное планирование/backoff WorkManager, обновлённый API 26 и физический телефон, если устройство появится.
2. Проверить цены и сопоставление на дополнительных захватах/timezone и на актуальном Android WebView; условия автоматического reuse Misli/eTopaz остаются неясными. Не обходить защиту.
3. Собирать будущие снимки и результаты по мере завершения матчей; обсуждать калибровку и market baseline только после независимого периода.

## Далее

- Продолжать накапливать неизменяемые предматчевые прогнозы и оценивать их по будущим результатам; пока ни один локально проверенный снимок не завершился.
- Построить форму команд и Poisson/Dixon–Coles на достаточной истории только после валидации временного среза.
- Добавить Room/DAO + repository/ViewModel без потери пользовательской SQLite, резервное копирование и тесты API 26.
- Добавлять рынки только с независимой схемой рынка, точной стороной, source URL и timestamp.
- Показывать conditional EV диапазон только по полной свежей линии одного оператора и явно называть его экспериментальным; не выдавать его за прибыль и не смешивать котировки в купоне.

## Утверждения о качестве

Не показывать доходность/преимущество, пока прогнозы не заморожены заранее и не проверены на независимом временном диапазоне против timestamped market baseline. Не выдавать максимальную вероятность за вероятность выиграть весь купон.

## Документы

- [Требование zero-touch](ZERO_TOUCH_DATA_RU.md)
- [Проверка Misli/TOPAZ](AZERBAIJAN_BOOKMAKERS_RU.md)
- [Источники и доступ](DATA_SOURCES.md)
- [Архитектура](ARCHITECTURE.md)
- [Ограничения](LIMITATIONS.md)
- [Журнал работы](DEVELOPMENT_LOG.md)
