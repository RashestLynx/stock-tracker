#!/usr/bin/env python3
"""
Daily check that every free data source the app relies on still answers in the shape the app expects.
Run by .github/workflows/daily-check.yml. Exits with an error (and GitHub emails you) if any source breaks.
"""
import json
import sys
import time
import urllib.request

UA = {"User-Agent": "StockTracker-HealthCheck/1.0 (personal app)"}
results = []


def get(url, raw=False):
    req = urllib.request.Request(url, headers=UA)
    data = urllib.request.urlopen(req, timeout=30).read()
    return data.decode("utf-8", "replace") if raw else json.loads(data)


def check(name, used_for, fn):
    try:
        detail = fn()
        results.append((True, name, used_for, detail))
    except Exception as e:
        results.append((False, name, used_for, str(e)[:200]))
    time.sleep(2)


def kalshi():
    for base in ("https://external-api.kalshi.com/trade-api/v2", "https://api.elections.kalshi.com/trade-api/v2"):
        try:
            ev = get(f"{base}/events?status=open&with_nested_markets=true&limit=20").get("events", [])
            assert ev and ev[0].get("markets"), "no events"
            m = ev[0]["markets"][0]
            assert any(k in m for k in ("yes_bid_dollars", "yes_bid", "last_price_dollars")), f"price fields missing: {list(m)[:12]}"
            return f"{len(ev)} events"
        except urllib.error.HTTPError as e:
            if e.code == 429:
                return "busy (rate limited), but answering"
            last = e
        except Exception as e:
            last = e
    raise last


def polymarket():
    ev = get("https://gamma-api.polymarket.com/events?closed=false&active=true&limit=20")
    assert ev and "markets" in ev[0], "no events"
    m = ev[0]["markets"][0]
    assert "outcomePrices" in m and "outcomes" in m, f"fields missing: {list(m)[:12]}"
    return f"{len(ev)} events"


def yahoo():
    meta = get("https://query1.finance.yahoo.com/v8/finance/chart/SPY?range=1d&interval=1d")["chart"]["result"][0]["meta"]
    assert meta.get("regularMarketPrice") and (meta.get("chartPreviousClose") or meta.get("previousClose")), "no price"
    return f"SPY {meta['regularMarketPrice']}"


def cboe():
    d = get("https://cdn.cboe.com/api/global/delayed_quotes/quotes/SPY.json")["data"]
    assert d.get("current_price") and d.get("prev_day_close"), "no price"
    return f"SPY {d['current_price']}"


def sec():
    j = get("https://www.sec.gov/files/company_tickers_exchange.json")
    assert "fields" in j and len(j.get("data", [])) > 5000, "ticker list looks wrong"
    f = get("https://data.sec.gov/api/xbrl/frames/us-gaap/NetIncomeLoss/USD/CY2024.json")
    assert len(f.get("data", [])) > 2000, "frames look thin"
    return f"{len(j['data'])} tickers, {len(f['data'])} companies in a frame"


def nflverse():
    text = get("https://raw.githubusercontent.com/nflverse/nfldata/master/data/games.csv", raw=True)
    head = text.split("\n", 1)[0].split(",")
    for col in ("season", "gameday", "away_team", "home_team", "away_score", "home_score", "away_moneyline", "home_moneyline"):
        assert col in head, f"column {col} missing"
    return f"{text.count(chr(10))} games"


def nflverse_ok():
    return nflverse()


UA_SEC = {"User-Agent": "StockTracker-HealthCheck admin@example.com"}
check("Kalshi", "Predictions, Sports, My picks", kalshi)
check("Polymarket", "Predictions, Sports, My picks", polymarket)
check("Yahoo Finance", "Green/red market background (no Finnhub key)", yahoo)
check("Cboe delayed quotes", "Green/red market background (no Finnhub key)", cboe)
UA.update(UA_SEC)
check("SEC EDGAR", "Filings feed, Top 30 scores", sec)
check("nflverse", "NFL odds check and math model", nflverse_ok)

ok = all(r[0] for r in results)
lines = [f"{'OK  ' if r[0] else 'FAIL'} {r[1]} ({r[2]}): {r[3]}" for r in results]
print("\n".join(lines))
print(("::notice title=Daily data check::" if ok else "::error title=Daily data check::") + "%0A".join(lines))
# Market background needs only one of the two price sources.
critical = [r for r in results if not r[0] and r[1] not in ("Yahoo Finance", "Cboe delayed quotes")]
both_prices_down = not any(r[0] for r in results if r[1] in ("Yahoo Finance", "Cboe delayed quotes"))
sys.exit(1 if critical or both_prices_down else 0)
