#!/usr/bin/env python3
"""Checks that each game's Kalshi button opens that exact game (prints a GitHub notice)."""
import json
import time
import urllib.request

UA = {"User-Agent": "StockTracker-Android/2.0"}


def get(url):
    return json.loads(urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=25).read())


def notice(title, lines):
    msg = "%0A".join(l.replace("%", "pct").replace("\n", " ")[:300] for l in lines)
    print(f"::notice title={title}::{msg}")


# Links: does a Kalshi event page show the right game?
lines = []
for t in ("KXNFLGAME", "KXNBAGAME", "KXUFCFIGHT"):
    time.sleep(3)
    try:
        ev = get(f"https://external-api.kalshi.com/trade-api/v2/events?series_ticker={t}&status=open&with_nested_markets=true&limit=5").get("events", [])
        if not ev:
            lines.append(f"{t}: no open events"); continue
        e = ev[0]
        url = "https://kalshi.com/events/" + e["event_ticker"].lower()
        req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
        resp = urllib.request.urlopen(req, timeout=25)
        html = resp.read().decode("utf-8", "replace")
        labels = [m.get("yes_sub_title", "") for m in e.get("markets", [])]
        found = [l for l in labels if l and l in html]
        lines.append(f"{t}: {url} -> HTTP {resp.status}, final {resp.geturl()}, page mentions {found} of {labels}; ticker in page: {e['event_ticker'] in html.upper()}")
    except Exception as ex:
        lines.append(f"{t}: failed {ex}")
notice("Links", lines)
