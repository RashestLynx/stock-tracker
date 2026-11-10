# Live alerts

A small checker that runs on Cloudflare's servers **every minute, 24/7**, even when your phone and PC are off. It watches the same sources as the app and sends a notification to your phone, through the free **ntfy** app, within a minute or two of each new filing. It works on iPhone and Android and costs nothing.

You only need your PC once, for about 10 minutes, to set it up.

## What you get alerts for

| Section | How often it checks | What sends an alert |
|---|---|---|
| Insiders (SEC Form 4) | Every minute | Open-market buys and sales by company executives, directors and 10% owners |
| Funds and countries (SEC 13F) | Every 5 minutes | A new quarterly holdings report from Berkshire Hathaway, Pershing Square, Duquesne, Bridgewater, the Gates Foundation Trust, Norway, Saudi Arabia, the UAE, Switzerland, South Korea or Canada |
| SEC charges | Every 5 minutes | A new SEC insider trading case |
| Senate and House | Every hour | A new disclosed trade by a member of Congress |

Members of Congress only have to disclose trades up to 45 days after they make them, so an hourly check is enough to catch them as soon as they're public.

## Setup (Windows, about 10 minutes)

1. **Install Node.js:** go to [nodejs.org](https://nodejs.org), download the **LTS** version, and install it with the default options.
2. **Make a free Cloudflare account** at [dash.cloudflare.com/sign-up](https://dash.cloudflare.com/sign-up). No credit card is needed.
3. **Download this project:** on the repository's GitHub page, click **Code → Download ZIP**, then unzip it.
4. Open the unzipped folder, then the **cloud** folder inside it. Click the address bar, type `powershell` and press Enter.
5. In the PowerShell window, paste this and press Enter:
   ```
   powershell -ExecutionPolicy Bypass -File setup.ps1
   ```
   The script:
   - signs you in to Cloudflare (a browser window opens; click **Allow**)
   - creates a small database that remembers which filings were already sent, so nothing is sent twice
   - starts the checker. If it asks you to choose a `workers.dev` name, any name is fine.
   - asks for your name and email, which the SEC requires from anyone checking its filings. It's only sent to sec.gov.
   - asks for an optional Bargo key, which allows more Congress checks. You can press Enter to skip it.
   - makes up a private topic name for your alerts, shows it, and saves it in `my-ntfy-topic.txt`
6. **On your phone:** install the free **ntfy** app ([iPhone](https://apps.apple.com/app/ntfy/id1625396347), [Android](https://play.google.com/store/apps/details?id=io.heckel.ntfy)). Tap **+**, enter the topic name exactly as shown, and tap **Subscribe**.

Within a minute you'll get **"Stock Tracker live alerts are on."** After that, every new filing arrives on its own. Tapping a notification opens the filing.

Once live alerts work, you can turn off **Also alert from this app** in the Stock Tracker app's Settings so you don't get each alert twice. The app keeps updating its feed either way.

Keep the topic name private: anyone who knows it can read your alerts.

## Changing what you get alerts for

Open `wrangler.toml` in Notepad, change the settings under `[vars]`, save, and run `npx wrangler deploy` in the same PowerShell folder:

- `SECTIONS`: remove any of `insiders,funds,charges,congress` you don't want.
- `MIN_VALUE`: only insider trades worth at least this much, for example `"100000"`.
- `TICKERS`: only these stocks, for example `"NVDA,AAPL,TSLA"`.
- `INSIDER_TRADES = "all"`: also alert on grants, option exercises and gifts. This means a lot more alerts.
- `FUNDS = "all"`: alert on every fund's 13F. This can mean hundreds a day near the quarterly deadlines.

## Checking on it

- `npx wrangler tail` shows what it's doing, live.
- Visiting your worker's address (shown after `wrangler deploy`, ending in `.workers.dev`) shows how many filings it has seen.
- Adding `/test` to the end of that address sends a test notification.

## Limits

- **Insider filings:** Cloudflare's free plan allows about 50 outside requests per run, and each insider filing takes 2. The checker reads up to 12 insider filings a minute; on busy afternoons the rest wait a minute or two.
- **Notifications:** it sends up to 8 notifications a minute one by one. Any more arrive together in one summary notification.
- **Congress:** Congress checks share the Bargo key's daily limit with the app. That's why they're hourly.
