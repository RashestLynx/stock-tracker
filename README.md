# Stock Tracker

## Download

- **Android:** [TradeTracker.apk](https://github.com/RashestLynx/stock-tracker/releases/download/latest/TradeTracker.apk)
- **iPhone:** [StockTracker.ipa](https://github.com/RashestLynx/stock-tracker/releases/download/latest/StockTracker.ipa) (install with Sideloadly, see below)

These links always point to the newest build. The repo is private, so sign in to GitHub in your browser first.

Android and iPhone apps that watch public trade disclosures and alert you, with each group in its own section:

- **Senate**: U.S. senators' stock trades (STOCK Act)
- **House**: U.S. representatives' stock trades (STOCK Act)
- **Insiders**: trades by company executives, directors and 10% owners (SEC Form 4)
- **Funds**: quarterly holdings of big investment managers (SEC 13F)
- **SEC charges**: SEC enforcement cases for illegal insider trading, from the SEC's official litigation and press release feeds
- **WSB**: the most-discussed and fastest-spiking stocks on r/wallstreetbets and other stock subreddits (via ApeWisdom), flagged when people you track are in the same stock
- **Today's buy & sale**: the first thing on the home screen, the day's most notable open-market buy and sale, ranked by size, who made it, and how many others also bought the stock
- **Top 30**: every U.S. stock scored 0 to 100 three ways, showing the top 10 for **growth** (sales and profits rising fast), the top 10 for **income** (a 10-year dividend that's covered by cash and still growing) and the top 10 for **wealth** (steady, very profitable, low-debt compounders). Runs entirely on the phone, no cloud setup
- **Predictions** (under Markets): live odds from Kalshi and Polymarket, both open in Illinois, for sports, politics and economics. Sorted by today's biggest moves or by trading volume; tap one to open it on that site. FanDuel and DraftKings don't publish their odds, so they aren't shown. **NFL odds check** puts each upcoming game's chances side by side from the sportsbooks, Kalshi, Polymarket and a simple rating model built from open nflverse data, flags where the markets disagree, and shows the model's track record against the sportsbooks
- **My accounts** (in the Watchlist tab): Robinhood linked read-only through SnapTrade Personal (free; your key stays encrypted on your phone), plus T. Rowe Price or any other funds typed in by hand. Shows your total, each holding's share of it and gain or loss, and a checkup that flags too much money in one stock, idle cash, big losses, insiders selling what you own, and holdings on the Top 30. Watchlist rows show how many shares you own
- **Movers**: the week's 10 best and 10 worst performing stocks, across the whole market or only stocks the people you track are in
- **Stock summaries**: tap any stock for what the company does, its industry and HQ, the latest price and change since the trade, and who else bought, sold or holds it
- **United States**: companies the U.S. government owns a stake in (Intel, MP Materials, Lithium Americas, USA Rare Earth, quantum and chip companies and more), shown on each stock's summary and kept in the Following list
- **Countries**: the U.S. stocks owned by other countries' government investment funds, from their quarterly SEC 13F filings: Norway (Norges Bank), Saudi Arabia (PIF), United Arab Emirates (Mubadala), Switzerland (Swiss National Bank), South Korea (Korea Investment Corporation) and Canada (CPP Investments)
- **Market color and dark mode**: the background turns green when the S&P 500 is up today and red when it's down (can be turned off), and Settings has System, Light and Dark appearance
- **Sources**: a tab listing every source the app uses, the live data feeds plus the article or filing behind each person's reported holdings, searchable and tappable
- **Common picks**: stocks that two or more of these people bought or hold in the last 90 days, with ones shared across different groups (say, a senator and a hedge fund) ranked first

Every push to `main` rebuilds the apps automatically (see the **Actions** tab) and posts them to **Releases → Latest build**.

## Install on Android

1. Sign in to GitHub in your phone's browser (the repo is private).
2. Open **Releases → Latest build** and download `TradeTracker.apk`.
3. Open it. Android asks once to allow installs from your browser.

## Install on iPhone

Apple only allows apps from the App Store or signed by a developer, so the iPhone build is installed with **Sideloadly** using your own free Apple ID:

1. On your Windows PC, install **iTunes** (the version from apple.com, not the Microsoft Store) and **Sideloadly** (sideloadly.io).
2. Download `StockTracker.ipa` from **Releases → Latest build**.
3. Plug your iPhone into the PC, open Sideloadly, drag in the `.ipa`, enter your Apple ID, and press **Start**.
4. On the iPhone, go to **Settings → General → VPN & Device Management**, tap your Apple ID and choose **Trust**.
5. On iOS 16 or later, also turn on **Settings → Privacy & Security → Developer Mode** and restart when asked.

With a free Apple ID, the app has to be reinstalled every 7 days (Sideloadly can do this automatically while your phone is on the same Wi-Fi as the PC). A paid Apple Developer account ($99/year) removes the 7-day limit and allows TestFlight.

## Setup (both apps)

1. Enter your name and email. The SEC requires every automated client to identify itself, and the app sends this only to sec.gov.
2. Pick which sections to follow, and optional alert filters: specific tickers, a minimum dollar amount, or purchases only.
3. Pull down on the feed to check now.

The first check loads recent filings into the app without sending notifications. After that you get a notification for each new filing that matches your filters, or a single summary when many arrive at once.

## Data sources and timing

- **Insiders and funds**: SEC EDGAR. Form 4s are filed within 2 business days of a trade; 13Fs up to 45 days after each quarter ends.
- **Congress**: the free [Bargo Congress Trades API](https://www.bargo.ai/free-apis/congress), built from official House Clerk and Senate eFD filings. Members have up to 45 days to disclose. Without a key, the app checks Congress about every 5 hours to stay inside the free limit (100 rows a day); a free Bargo key, entered in Settings, allows hourly checks.
- **Top 30**: company financials from the SEC's free XBRL "frames" data (about 57 requests cover every U.S. company; kept on the phone for a week) and prices from Massive. Penny stocks, tiny or thinly traded companies, shells and obvious data errors are filtered out; a stock appears on one list only, with at most 3 per industry. Scores are a starting point for research, not financial advice.
- **Movers**: end-of-day prices from Massive (formerly Polygon.io). Their free Basic plan is enough; paste the free API key into the app once.
- **Stock summaries**: company profiles from the SEC and Wikipedia; latest delayed price from Stooq.
- **Common picks** matches 13F holdings to tickers by company name using the SEC's official company list, so a few holdings may not match.
- Senior federal officials and state legislators aren't included: their disclosures are PDFs with no live public feed.

**Live alerts:** for notifications within a minute or two of each filing, 24/7 with your phone and PC off, set up the free cloud checker in [`cloud/`](cloud/README.md). It sends alerts through the free ntfy app on iPhone and Android.

**Live alerts in the app (Android):** Settings → Live alerts keeps checking the SEC's insider feed every second (or 5, 15 or 60 seconds), even with the app closed, and notifies you as soon as a new filing appears. Android shows a small "Live alerts on" notification while it runs. Outside SEC filing hours (weekdays 6 a.m. to 10 p.m. Eastern) it checks once a minute. Everything else is checked every 5 minutes. Checking every second uses noticeably more battery and data. On iPhone the app checks every second while it's open; iOS doesn't allow that in the background.

Both apps check for new filings when you open them and every 5 minutes while they're open, and send a notification for each new one.

In the background, Android checks about every 15 minutes, with a backup alarm that's allowed to run while the phone is idle. If Android's battery saving is limiting the app, **Settings → Background checks** shows a button to allow it. On iPhone, iOS decides when background checks run (often every hour or few, less if you rarely open the app), and Background App Refresh must be on. Instant alerts on iPhone would need Apple push notifications, which require a paid Apple Developer account and a server.

## Privacy and security

- **App lock** (on by default): the app asks for your fingerprint, face or PIN to open, and again after a minute away. While it's on, the app is hidden in recent apps and screenshots are blocked.
- **Your keys and holdings stay on your phone.** They're encrypted (Android Keystore, iPhone Keychain), never written into the code, and left out of phone backups.
- **Read-only:** linked accounts can be viewed, never traded or moved.
- **Security check:** every push runs a scan for leaked keys and common mobile-app mistakes (Actions → Security check). Dependabot opens pull requests when a library gets a security fix.
- **Never put API keys in the code.** Paste them into the app's Settings instead.

## Desktop

A Python version for PCs (insiders and funds only) is in `desktop/edgar_tracker.py`.
