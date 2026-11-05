# Trade Tracker

An Android app that watches SEC EDGAR for new public trade disclosures and notifies you:

- **Form 4**: trades by corporate insiders (executives, directors, 10% owners)
- **13F**: quarterly holdings reports from large investment managers

## Install

1. On your phone, open this repo's **Releases** page and download `TradeTracker.apk` from **Latest build**.
2. Open it. Android will ask you to allow installs from your browser or file manager the first time.
3. Allow notifications when the app asks.

Every push to `main` rebuilds the APK automatically (see the **Actions** tab).

## Setup

1. Enter your name and email. The SEC requires every automated client to identify itself, and the app sends this only to sec.gov.
2. Optional filters: specific tickers, a minimum dollar value, or open-market purchases only.
3. Tap **Save & start**.

The first check only records what's already posted, so you don't get flooded. After that, you get a notification for each new filing that matches your filters. Tap a notification or alert to open the filing on sec.gov.

## Notes

- Android runs background checks about every 15 minutes at most, and battery saving can delay them. If alerts stop, exclude the app from battery optimization in your phone's settings.
- Filings appear when they're posted, which is usually within 2 business days of the trade for Form 4, and up to 45 days after the quarter ends for 13F.
- A Python version for PCs is in `desktop/edgar_tracker.py`.
