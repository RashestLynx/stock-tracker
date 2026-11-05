#!/usr/bin/env python3
"""
EDGAR filing tracker: watches SEC EDGAR for new Form 4 (insider trades)
and 13F-HR (institutional holdings) filings and alerts you when they appear.

No third-party packages needed (standard library only).

Setup:
  1. Set your SEC User-Agent (required by the SEC, must include a contact email):
       export SEC_USER_AGENT="YourName your@email.com"        (Mac/Linux)
       set SEC_USER_AGENT=YourName your@email.com              (Windows cmd)
  2. Optional phone push alerts via ntfy (free app for iOS/Android):
       export NTFY_TOPIC="pick-a-long-random-topic-name"
     Then subscribe to that same topic in the ntfy app.
  3. Optional Telegram alerts:
       export TELEGRAM_BOT_TOKEN="..."  and  export TELEGRAM_CHAT_ID="..."

Run:
  python edgar_tracker.py                     # loop forever, check every 5 min
  python edgar_tracker.py --once              # single check, then exit
  python edgar_tracker.py --buys-only         # only alert on open-market purchases
  python edgar_tracker.py --tickers AAPL,NVDA # only alert for these companies
  python edgar_tracker.py --min-value 100000  # only Form 4 trades >= $100k

The first run marks everything currently in the feed as "seen" without
alerting, so you don't get flooded. Use --alert-backlog to alert on it anyway.
"""

import argparse
import json
import os
import re
import sqlite3
import sys
import time
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET

# ================================================================
#  YOUR SETTINGS - edit these, then just run:  python edgar_tracker.py
#  (Command-line flags and environment variables still override these.)
# ================================================================
SETTINGS = {
    # Required by the SEC: your name and contact email.
    "sec_user_agent": "",            # e.g. "Carlos Smith you@example.com"

    # Which filings to watch: "4" = insider trades, "13F-HR" = big institutions
    "forms": ["4", "13F-HR"],

    # Form 4 filters
    "buys_only": False,              # True = only open-market purchases
    "tickers": [],                   # e.g. ["AAPL", "NVDA"]; empty = all companies
    "min_value": 0,                  # e.g. 100000 = skip trades under $100k

    # How often to check, in seconds (300 = every 5 minutes)
    "interval": 300,

    # Phone alerts (optional). Install the ntfy app, subscribe to this topic.
    "ntfy_topic": "",                # pick something long and random

    # Telegram alerts (optional)
    "telegram_bot_token": "",
    "telegram_chat_id": "",
}
# ================================================================

FEED_URL = ("https://www.sec.gov/cgi-bin/browse-edgar?action=getcurrent"
            "&type={form}&company=&dateb=&owner=include&start=0&count=100&output=atom")
ATOM = "{http://www.w3.org/2005/Atom}"
DB_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "edgar_seen.db")

# Form 4 transaction codes -> plain English
TX_CODES = {
    "P": "Bought (open market)", "S": "Sold (open market)",
    "A": "Award/grant", "M": "Option exercise", "F": "Tax withholding",
    "G": "Gift", "D": "Disposed to issuer", "C": "Conversion",
    "X": "Option exercise", "J": "Other",
}

def setting(env_name, key):
    """Environment variable wins; otherwise use the SETTINGS block."""
    return (os.environ.get(env_name) or str(SETTINGS.get(key) or "")).strip()


USER_AGENT = setting("SEC_USER_AGENT", "sec_user_agent")
_last_request = 0.0


# ---------------------------------------------------------------- HTTP
def http_get(url, accept="*/*"):
    """GET with SEC-required User-Agent and a polite rate limit (<10 req/s)."""
    global _last_request
    wait = 0.15 - (time.time() - _last_request)
    if wait > 0:
        time.sleep(wait)
    req = urllib.request.Request(url, headers={
        "User-Agent": USER_AGENT, "Accept": accept, "Accept-Encoding": "identity"})
    for attempt in range(3):
        try:
            with urllib.request.urlopen(req, timeout=30) as r:
                _last_request = time.time()
                return r.read()
        except urllib.error.HTTPError as e:
            if e.code in (429, 503) and attempt < 2:
                time.sleep(5 * (attempt + 1))
                continue
            raise
        except urllib.error.URLError:
            if attempt < 2:
                time.sleep(5)
                continue
            raise


# ---------------------------------------------------------------- storage
def db():
    con = sqlite3.connect(DB_PATH)
    con.execute("CREATE TABLE IF NOT EXISTS seen (accession TEXT PRIMARY KEY,"
                " form TEXT, seen_at INTEGER)")
    return con


def is_seen(con, acc):
    return con.execute("SELECT 1 FROM seen WHERE accession=?", (acc,)).fetchone() is not None


def mark_seen(con, acc, form):
    con.execute("INSERT OR IGNORE INTO seen VALUES (?,?,?)", (acc, form, int(time.time())))
    con.commit()


# ---------------------------------------------------------------- feed
def fetch_feed(form):
    """Return list of unique filings: {accession, form, title, link, updated}."""
    raw = http_get(FEED_URL.format(form=urllib.parse.quote(form)), "application/atom+xml")
    root = ET.fromstring(raw)
    filings = {}
    for e in root.findall(f"{ATOM}entry"):
        id_text = e.findtext(f"{ATOM}id", "")
        m = re.search(r"accession-number=([\d-]+)", id_text)
        if not m:
            continue
        acc = m.group(1)
        cat = e.find(f"{ATOM}category")
        ftype = cat.get("term") if cat is not None else form
        if ftype != form:          # skip amendments like 4/A unless asked
            continue
        link_el = e.find(f"{ATOM}link")
        link = link_el.get("href") if link_el is not None else ""
        title = e.findtext(f"{ATOM}title", "")
        # Each Form 4 appears twice (Reporting + Issuer); keep the first.
        filings.setdefault(acc, {"accession": acc, "form": ftype, "title": title,
                                 "link": link, "updated": e.findtext(f"{ATOM}updated", "")})
    return list(filings.values())


def folder_url(link):
    """Filing index link -> filing folder URL."""
    return link.rsplit("/", 1)[0] + "/"


# ---------------------------------------------------------------- Form 4 parsing
def _val(node, path):
    el = node.find(path)
    if el is None:
        return None
    v = el.findtext("value")
    return v.strip() if v else (el.text or "").strip() or None


def parse_form4(filing):
    """Download the Form 4 XML and pull out who traded what."""
    folder = folder_url(filing["link"])
    idx = json.loads(http_get(folder + "index.json", "application/json"))
    xml_name = next((i["name"] for i in idx["directory"]["item"]
                     if i["name"].lower().endswith(".xml")), None)
    if not xml_name:
        return None
    root = ET.fromstring(http_get(folder + xml_name, "application/xml"))

    owners = [o.findtext("reportingOwnerId/rptOwnerName", "").strip()
              for o in root.findall("reportingOwner")]
    titles = []
    for o in root.findall("reportingOwner"):
        rel = o.find("reportingOwnerRelationship")
        if rel is None:
            continue
        t = rel.findtext("officerTitle")
        if t:
            titles.append(t.strip())
        elif (rel.findtext("isDirector") or "").strip() in ("1", "true"):
            titles.append("Director")
        elif (rel.findtext("isTenPercentOwner") or "").strip() in ("1", "true"):
            titles.append("10% owner")

    trades = []
    for t in root.findall("nonDerivativeTable/nonDerivativeTransaction"):
        code = t.findtext("transactionCoding/transactionCode", "").strip()
        shares = _float(_val(t, "transactionAmounts/transactionShares"))
        price = _float(_val(t, "transactionAmounts/transactionPricePerShare"))
        ad = _val(t, "transactionAmounts/transactionAcquiredDisposedCode")
        trades.append({"code": code, "shares": shares, "price": price, "ad": ad,
                       "value": (shares or 0) * (price or 0)})

    return {
        "ticker": (root.findtext("issuer/issuerTradingSymbol") or "").strip().upper(),
        "issuer": (root.findtext("issuer/issuerName") or "").strip(),
        "owners": owners, "titles": titles, "trades": trades,
    }


def _float(s):
    try:
        return float(s)
    except (TypeError, ValueError):
        return None


def summarize_form4(info):
    """Collapse trades by transaction code into readable lines."""
    by_code = {}
    for t in info["trades"]:
        agg = by_code.setdefault(t["code"], {"shares": 0.0, "value": 0.0})
        agg["shares"] += t["shares"] or 0
        agg["value"] += t["value"]
    lines = []
    for code, agg in by_code.items():
        label = TX_CODES.get(code, f"Code {code}")
        avg = agg["value"] / agg["shares"] if agg["shares"] and agg["value"] else 0
        price = f" @ ~${avg:,.2f} (${agg['value']:,.0f})" if avg else ""
        lines.append(f"  {label}: {agg['shares']:,.0f} shares{price}")
    return lines


# ---------------------------------------------------------------- alerts
def notify(title, body, url=None):
    print(f"\n=== {title} ===\n{body}" + (f"\n{url}" if url else ""), flush=True)

    topic = setting("NTFY_TOPIC", "ntfy_topic")
    if topic:
        try:
            headers = {"Title": title.encode("ascii", "ignore").decode()}
            if url:
                headers["Click"] = url
            req = urllib.request.Request(f"https://ntfy.sh/{topic}",
                                         data=body.encode(), headers=headers)
            urllib.request.urlopen(req, timeout=15).close()
        except Exception as e:
            print(f"  (ntfy failed: {e})", file=sys.stderr)

    token = setting("TELEGRAM_BOT_TOKEN", "telegram_bot_token")
    chat = setting("TELEGRAM_CHAT_ID", "telegram_chat_id")
    if token and chat:
        try:
            data = urllib.parse.urlencode({
                "chat_id": chat, "text": f"{title}\n{body}" + (f"\n{url}" if url else ""),
                "disable_web_page_preview": "true"}).encode()
            urllib.request.urlopen(
                f"https://api.telegram.org/bot{token}/sendMessage", data, timeout=15).close()
        except Exception as e:
            print(f"  (telegram failed: {e})", file=sys.stderr)


# ---------------------------------------------------------------- main loop
def handle_form4(filing, args):
    info = parse_form4(filing)
    if not info:
        return
    if args.tickers and info["ticker"] not in args.tickers:
        return
    trades = info["trades"]
    if args.buys_only:
        trades = [t for t in trades if t["code"] == "P"]
    if args.min_value:
        trades = [t for t in trades if t["value"] >= args.min_value]
    if not trades and (args.buys_only or args.min_value):
        return
    info["trades"] = trades
    who = ", ".join(info["owners"]) or "Unknown"
    role = f" ({', '.join(info['titles'])})" if info["titles"] else ""
    lines = summarize_form4(info) or ["  (no common-stock transactions; derivatives only)"]
    notify(f"Form 4: {info['ticker'] or info['issuer']}",
           f"{who}{role}\n{info['issuer']}\n" + "\n".join(lines), filing["link"])


def handle_13f(filing, args):
    name = re.sub(r"^13F-HR\s*-\s*", "", filing["title"])
    name = re.sub(r"\s*\(\d+\)\s*\(Filer\)\s*$", "", name)
    notify("New 13F filing", f"{name}\nQuarterly holdings report posted.", filing["link"])


def check_once(con, args, first_run):
    handlers = {"4": handle_form4, "13F-HR": handle_13f}
    for form in args.forms:
        try:
            filings = fetch_feed(form)
        except Exception as e:
            print(f"[{time.strftime('%H:%M:%S')}] feed error for {form}: {e}", file=sys.stderr)
            continue
        new = [f for f in filings if not is_seen(con, f["accession"])]
        print(f"[{time.strftime('%H:%M:%S')}] {form}: {len(new)} new of {len(filings)}", flush=True)
        for f in reversed(new):  # oldest first
            if not (first_run and not args.alert_backlog):
                try:
                    handlers[form](f, args)
                except Exception as e:
                    print(f"  error on {f['accession']}: {e}", file=sys.stderr)
            mark_seen(con, f["accession"], form)


def main():
    p = argparse.ArgumentParser(description="Watch SEC EDGAR for Form 4 and 13F filings.")
    p.add_argument("--once", action="store_true", help="check once and exit")
    p.add_argument("--interval", type=int, default=int(SETTINGS["interval"]),
                   help="seconds between checks")
    p.add_argument("--forms", default=",".join(SETTINGS["forms"]), help="comma list: 4,13F-HR")
    p.add_argument("--tickers", default=",".join(SETTINGS["tickers"]),
                   help="only alert for these tickers, e.g. AAPL,NVDA")
    p.add_argument("--buys-only", action="store_true", default=bool(SETTINGS["buys_only"]),
                   help="Form 4: only open-market purchases")
    p.add_argument("--min-value", type=float, default=float(SETTINGS["min_value"]),
                   help="Form 4: minimum $ value per trade")
    p.add_argument("--alert-backlog", action="store_true", help="alert on filings found on first run")
    args = p.parse_args()
    args.forms = [f.strip() for f in args.forms.split(",") if f.strip()]
    args.tickers = {t.strip().upper() for t in args.tickers.split(",") if t.strip()}

    if not USER_AGENT or "@" not in USER_AGENT:
        sys.exit('Fill in "sec_user_agent" in the SETTINGS block at the top of this file\n'
                 '(e.g. "YourName your@email.com"). The SEC requires it.')

    con = db()
    first_run = con.execute("SELECT COUNT(*) FROM seen").fetchone()[0] == 0
    if first_run and not args.alert_backlog:
        print("First run: recording current filings as seen (no alerts this pass).")

    while True:
        check_once(con, args, first_run)
        first_run = False
        if args.once:
            break
        time.sleep(args.interval)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\nStopped.")
