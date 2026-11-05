# Stock Tracker

Android and iPhone apps that watch public trade disclosures and alert you, with each group in its own section:

- **Senate**: U.S. senators' stock trades (STOCK Act)
- **House**: U.S. representatives' stock trades (STOCK Act)
- **Insiders**: trades by company executives, directors and 10% owners (SEC Form 4)
- **Funds**: quarterly holdings of big investment managers (SEC 13F)
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
- **Common picks** matches 13F holdings to tickers by company name using the SEC's official company list, so a few holdings may not match.
- Senior federal officials and state legislators aren't included: their disclosures are PDFs with no live public feed.

Background checks run about every 15 minutes on Android and whenever iOS allows on iPhone (often every hour or few). Battery saving can delay them on both.

## Desktop

A Python version for PCs (insiders and funds only) is in `desktop/edgar_tracker.py`.
