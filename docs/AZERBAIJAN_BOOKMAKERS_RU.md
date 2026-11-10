# Azerbaijan-first: Misli and TOPAZ odds without paid APIs

## User mandate (2026-11-09)
Personal Android APK for Azerbaijan. Automatically retrieve real public prematch odds from Misli.az and TOPAZ/eTopaz for private analysis, without manual CSV files, manual coefficient entry, API registration, paid API or server. An official API is not a requirement. Use standard public website rendering and openly accessible data only where allowed. Do not claim a live integration until verified on real devices.

## Research
- Misli official: https://www.misli.az/ . A standard Android API 34 WebView rendered the public football page, including future dates, local kickoff times, team labels and the complete 1/X/2 market. The app's parser reads only `document.body.innerText` from that public page; no private endpoint or account is used.
- Misli [robots.txt](https://www.misli.az/robots.txt), checked 2026-11-09, says `Allow: /` while disallowing account, shared-coupon, and live-market-detail path patterns. This permits crawler paths at the root level only; it does not establish a license or affirmative right to automate reuse of odds. No explicit public reuse permission was found.
- TOPAZ official: https://etopaz.az/ and https://topaz.az/ . The ordinary public home page has an `İdman` tab. Selecting that visible tab in a standard Android WebView showed future 1X2 football events; the app performs the same normal tab click and reads visible page text. The [official terms](https://etopaz.az/terms-conditions) define a subscriber/account and wagering agreement but did not grant public automated odds-reuse permission. `https://etopaz.az/robots.txt` was not retrievable in the web check. Do not infer permission or a technical block from that failure.
- Existing open source reference: https://github.com/aliabbasov99/ai-football-prediction , backend/scripts/scrape_misli_all.py. The examined scraper uses `undetected_chromedriver`, stealth flags, scrolling/sleeps, BeautifulSoup selectors, aliases and fuzzy string similarity. Only the high-level ideas of explicit aliases and careful matching were studied. No evasion or source code was copied; no root reuse license was identified.

### Proof-of-concept outcome (2026-11-09)

| Operator | Public page evidence | Prematch validation | Current app status |
|---|---|---|---|
| Misli.az | Standard Android WebView on API 34 rendered the public football page and captured 9 future 1X2 rows at 2026-11-10 03:07:26 UTC. | Latest pass had 0 exact matches against local fixtures; no Elo comparison is attached. | `MisliOddsProvider` parses visible future 1X2 rows; snapshots are local/timestamped and only eligible for 30 minutes. Technical retrieval is not an affirmative reuse license. |
| TOPAZ/eTopaz | Standard Android WebView selected the visible `İdman` tab and captured 25 future 1X2 rows at 2026-11-10 03:07:28 UTC. | Latest capture linked 7 events to local fixtures. Portugal—Denmark maps to local match 85257, with visible 1/X/2 1.45/4.31/5.29. Previous Elo home marginal was 59.1%; basic normalized market home probability was 62.1%. The earlier Türkiye—Belgium 3.30/3.64/1.88 row mapped to match 85220. eTopaz omits event year; the app infers the next occurrence within 90 days and marks that inference. | `TopazOddsProvider` accepts complete future 1X2 rows from visible page text. Earlier verified same-book TOPAZ drafts had sizes 2/3/5 with actual combined prices 1.75/2.09/26.87; new snapshot prices expire after 30 minutes. `LIVE` is a timestamped technical observation, not an always-on line. |

The browser checks did not use login, place bets, access account/detail paths, solve a challenge, or bypass controls. Misli's public `/idman-novleri/futbol` path is allowed by its robots file, but that file is not a license; eTopaz's robots file was not retrieved. Explicit automated odds-reuse permission was not found for either operator. The app keeps captures local and does not redistribute them. `LIVE` means the last foreground WebView capture contained future visible lines; it is not legal authorization, a guarantee that prices remain open, or a claim that a model is profitable. Android may defer or stop page rendering.

## Implementation and open requirements

- Both independent provider adapters are implemented and use only the ordinary public WebView and visible page text; no hidden API, login, stealth, CAPTCHA solving or protection bypass is used.
- Both parsers require a future event, local date/time and device timezone, a complete labeled 1/X/2 sequence, prices in range and a public route. Misli has an explicit date; eTopaz's year is inferred only within 90 days and explicitly flagged. Live-score, incomplete/suspicious prices, past events and conflicting duplicates are rejected. Team matching uses reviewed exact aliases and preserves home/away orientation; fuzzy matching is not used.
- Each snapshot stores provider, source URL, UTC capture time, local date/timezone, raw labels, market/prices and optional unique exact local match ID. Unmatched quotes are not compared with Elo.
- Freshness is 30 minutes, checked both when loading rows from SQLite and inside the coupon builder; future-dated captures are rejected. Each provider's accumulator uses only its own complete, exact fresh lines. If none exist, model probabilities remain available with no price/payoff.
- Still open: no explicit automated reuse permission was found in the operator terms. Check any new terms before enabling broader automated use, and do not redistribute quotes. Verify timezones/devices beyond API 34. If either source restricts access, keep it unavailable and do not evade restrictions.

## Statistical honesty
Model win probability and actual available bookmaker decimal odds are different objects. Estimate EV only when source-specific quote exists and model is reasonably calibrated. Otherwise show probabilities and a missing market price, not an invented payoff or betting recommendation.

## Product behavior
Zero actions required by user on first launch beyond granting network access; result sync is best effort under WorkManager restrictions. Both bookmaker WebViews load automatically on app open and retry every 30 minutes while foreground; WorkManager does not run an offscreen browser. Local quotes/results remain stored offline, but quotes older than 30 minutes are not presented as fresh. Manual entry can remain under advanced settings only. No login, no account or payment actions.
