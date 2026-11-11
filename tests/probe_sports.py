#!/usr/bin/env python3
"""Checks the free sports data sources from the build machine and prints what they return (as GitHub notices)."""
import json
import urllib.request
from datetime import datetime, timedelta, timezone

UA = {"User-Agent": "StockTracker-Android/2.0"}


def get(url):
    return json.loads(urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=25).read())


def notice(title, lines):
    msg = "%0A".join(l.replace("%", "pct").replace("\n", " ")[:300] for l in lines)
    print(f"::notice title={title}::{msg}")


leagues = {
    "NFL": "football/nfl", "College football": "football/college-football", "NBA": "basketball/nba",
    "College basketball": "basketball/mens-college-basketball", "NHL": "hockey/nhl", "MLB": "baseball/mlb",
    "MLS": "soccer/usa.1", "Premier League": "soccer/eng.1", "Champions League": "soccer/uefa.champions",
}
today = datetime.now(timezone.utc)
rng = today.strftime("%Y%m%d") + "-" + (today + timedelta(days=7)).strftime("%Y%m%d")
lines = []
for name, path in leagues.items():
    try:
        d = get(f"https://site.api.espn.com/apis/site/v2/sports/{path}/scoreboard?dates={rng}&limit=300")
        ev = d.get("events", [])
        line = f"{name}: {len(ev)} games"
        for e in ev[:40]:
            c = e["competitions"][0]
            odds = c.get("odds") or []
            if odds:
                t = {x["homeAway"]: x["team"] for x in c["competitors"]}
                o = odds[0]
                keys = sorted(o.keys())
                ml = o.get("moneyline")
                line += (f" | e.g. {t['away'].get('location')}/{t['away'].get('name')}/{t['away'].get('abbreviation')} at "
                         f"{t['home'].get('location')}/{t['home'].get('name')} odds keys={keys} "
                         f"homeTeamOdds={json.dumps(o.get('homeTeamOdds'))[:120]} moneyline={json.dumps(ml)[:200]} "
                         f"drawOdds={json.dumps(o.get('drawOdds'))[:80]}")
                break
        else:
            line += " | no odds on any listed game"
        lines.append(line)
    except Exception as e:
        lines.append(f"{name}: failed {e}")
notice("ESPN", lines)

# Kalshi: which sports series have single-game winner markets?
for base in ("https://external-api.kalshi.com/trade-api/v2", "https://api.elections.kalshi.com/trade-api/v2"):
    try:
        s = get(f"{base}/series?category=Sports")
        tickers = sorted(x["ticker"] for x in s.get("series", []) if "GAME" in x["ticker"])
        lines = [f"base {base}: {len(s.get('series', []))} sports series; GAME ones: {', '.join(tickers)[:1500]}"]
        for t in ("KXNFLGAME", "KXNCAAFGAME", "KXNBAGAME", "KXNCAAMBGAME", "KXNHLGAME", "KXMLBGAME", "KXMLSGAME", "KXEPLGAME", "KXUCLGAME"):
            try:
                ev = get(f"{base}/events?series_ticker={t}&status=open&with_nested_markets=true&limit=200").get("events", [])
                sample = ""
                if ev:
                    e = ev[0]
                    sample = f" e.g. '{e.get('title')}' outcomes: " + ", ".join(m.get("yes_sub_title", "") for m in e.get("markets", []))
                lines.append(f"{t}: {len(ev)} open{sample}")
            except Exception as ex:
                lines.append(f"{t}: failed {ex}")
        notice("Kalshi", lines)
        break
    except Exception as e:
        notice("Kalshi", [f"{base} failed: {e}"])

lines = []
for tag in ("nfl", "cfb", "nba", "ncaab", "cbb", "nhl", "mlb", "mls", "epl", "ucl", "soccer", "sports"):
    try:
        ev = get(f"https://gamma-api.polymarket.com/events?tag_slug={tag}&closed=false&active=true&limit=50")
        sample = ""
        for e in ev:
            ms = e.get("markets", [])
            if 1 <= len(ms) <= 4:
                sample = f" e.g. '{e.get('title')}': " + "; ".join(
                    f"{m.get('groupItemTitle') or m.get('question')} {m.get('outcomes')} type={m.get('sportsMarketType')}" for m in ms[:3])
                break
        lines.append(f"{tag}: {len(ev)} events{sample}")
    except Exception as e:
        lines.append(f"{tag}: failed {e}")
notice("Polymarket", lines)
