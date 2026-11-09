# Azerbaijan-first: Misli and TOPAZ odds without paid APIs

## User mandate (2026-11-09)
Personal Android APK for Azerbaijan. Automatically retrieve real public prematch odds from Misli.az and TOPAZ/eTopaz for private analysis, without manual CSV files, manual coefficient entry, API registration, paid API or server. An official API is not a requirement. Use standard public website rendering and openly accessible data only where allowed. Do not claim a live integration until verified on real devices.

## Research
- Misli official: https://www.misli.az/ . Public text extractor did not see odds on the homepage (likely JS-rendered interface); ordinary Android browser verification required.
- TOPAZ official: https://etopaz.az/ and https://topaz.az/ . Public pages can contain visible market/odds text; indexed snippets are not live quotes. Validate against directly displayed, current pages.
- Existing open source reference: https://github.com/aliabbasov99/ai-football-prediction , backend/scripts/scrape_misli_all.py. It uses Selenium/BeautifulSoup, a team alias map and an undetected browser. Study ONLY data model and matching ideas; do not port anti-bot evasion or assume its CSS still works.

## Implementation sequence
1. First inspect public football prematch pages of both bookmakers via ordinary browser without login. Record whether odds are accessible; check terms, robots restrictions and regional eligibility.
2. Try ordinary Android WebView loading and reading user-visible public DOM after JS rendering, if permitted, with no stealth technique. Prefer a clearly published, permission-compatible public HTML/feed if one exists.
3. If public sources are blocked or forbid automated access, DO NOT circumvent CAPTCHA, paywalls, auth, rate limits, IP bans or anti-bot measures. Mark operator odds unavailable, keep model/probability analysis automated.
4. Implement isolated MisliOddsProvider and TopazOddsProvider with honest statuses: LIVE, STALE, UNAVAILABLE, BLOCKED, ERROR; no counterfeit demo values in real feed.
5. Map Azerbaijan/English team names, kickoff UTC/Asia-Baku, home/away orientation, league and market. Strictly separate 1X2, double chance, totals, BTTS, handicaps, half-time and live prices.
6. Persist each odds snapshot with operator, source URL, capture UTC time, raw labels, market/outcome, selected decimal odds, confidence of match mapping and original source event id.
7. Keep short TTL and only compare fresh confirmed prematch odds. Reject suspicious odds, mismatches, suspended or closed markets and stale prices. Never label generic international odds as bookmaker prices from Misli/TOPAZ.
8. Show two operator cards and separate virtual coupons. A Misli ticket uses only Misli prices; a TOPAZ ticket uses only TOPAZ prices. Never multiply best prices across operators as a single real coupon.
9. Validate parsed values visually against source website for several exact matches and demonstrate GitHub CI passing + debug APK before claiming functionality.

## Statistical honesty
Model win probability and actual available bookmaker decimal odds are different objects. Estimate EV only when source-specific quote exists and model is reasonably calibrated. Otherwise show probabilities and a missing market price, not an invented payoff or betting recommendation.

## Product behavior
Zero actions required by user on first launch beyond granting network access; periodic refresh best effort under WorkManager restrictions; local cache offline. Manual entry can remain under advanced settings only, never the default workflow. No login, no account or payment actions.