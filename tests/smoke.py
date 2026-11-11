#!/usr/bin/env python3
"""
Smoke test on an Android emulator: installs the APK, opens the app, and taps through every tab and view,
first with no screen lock and then with a PIN set (so the app lock is exercised). Fails if the app crashes,
closes, or a screen doesn't appear. Each problem is printed as a GitHub annotation.

Usage: python3 tests/smoke.py path/to/app.apk
"""
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

PKG = "com.rashestlynx.tradetracker"
PIN = "1234"
problems = []


def adb(*args, check=False):
    r = subprocess.run(["adb", *args], capture_output=True, text=True, timeout=120)
    if check and r.returncode != 0:
        raise RuntimeError(f"adb {' '.join(args)} failed: {r.stderr.strip()}")
    return r.stdout


def fail(msg):
    print(f"::error::{msg}")
    problems.append(msg)


def note(msg):
    print(f"::notice::{msg}")


def alive():
    return adb("shell", "pidof", PKG).strip() != ""


def crash_text():
    log = adb("logcat", "-d", "-b", "crash")
    if PKG not in log and "FATAL EXCEPTION" not in log:
        return ""
    lines = [l for l in log.splitlines() if l.strip()]
    start = next((i for i, l in enumerate(lines) if "FATAL EXCEPTION" in l), 0)
    return " | ".join(re.sub(r"^.*?: ", "", l) for l in lines[start:start + 12])


def screen():
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    xml = adb("shell", "cat", "/sdcard/ui.xml")
    try:
        return ET.fromstring(xml[xml.index("<?xml"):] if "<?xml" in xml else xml)
    except Exception:
        return None


def find(root, text):
    if root is None:
        return None
    for n in root.iter("node"):
        if n.get("text") == text or n.get("content-desc") == text:
            m = re.findall(r"\d+", n.get("bounds", ""))
            if len(m) == 4:
                x1, y1, x2, y2 = map(int, m)
                return (x1 + x2) // 2, (y1 + y2) // 2
    return None


def find_prefix(root, prefix):
    if root is None:
        return None
    for n in root.iter("node"):
        t = n.get("text") or ""
        if t.startswith(prefix) and n.get("clickable") in ("true", None):
            m = re.findall(r"\d+", n.get("bounds", ""))
            if len(m) == 4:
                x1, y1, x2, y2 = map(int, m)
                return (x1 + x2) // 2, (y1 + y2) // 2
    # Text inside a button isn't clickable itself; tapping its middle still hits the button.
    for n in root.iter("node"):
        t = n.get("text") or ""
        if t.startswith(prefix):
            m = re.findall(r"\d+", n.get("bounds", ""))
            if len(m) == 4:
                x1, y1, x2, y2 = map(int, m)
                return (x1 + x2) // 2, (y1 + y2) // 2
    return None


def check_bet_sheet(label):
    """Opens Log a pick → Yes on the first market, checks the How much? screen, then cancels."""
    root = screen()
    if find(root, "Log a pick") is None:
        note(f"{label}: no market with 'Log a pick' on screen, so the bet screen wasn't checked")
        return
    if not tap("Log a pick", f"{label} / All markets"):
        return
    pos = find_prefix(screen(), "Yes ")
    if pos is None:
        fail(f"{label}: 'Log a pick' didn't show Yes/No choices. Visible: {', '.join(texts(screen())[:20])}")
        return
    adb("shell", "input", "tap", str(pos[0]), str(pos[1]))
    time.sleep(3)
    if not check_ok(f"{label} / Log a pick → Yes"):
        return
    root = screen()
    if find(root, "How much?") is None:
        fail(f"{label}: the How much? screen didn't open. Visible: {', '.join(texts(root)[:20])}")
        return
    shown = " | ".join(t for t in texts(root) if "edge" in t.lower() or "If it" in t or "budget" in t.lower())
    note(f"{label}: How much? screen opened: {shown[:250]}")
    tap("Cancel", f"{label} / How much?", wait=2)


def texts(root):
    return [] if root is None else [n.get("text") for n in root.iter("node") if n.get("text")]


def check_ok(where):
    if not alive():
        fail(f"App closed at: {where}. Crash: {crash_text() or 'no crash log'}")
        return False
    c = crash_text()
    if c:
        fail(f"Crash at: {where}: {c}")
        return False
    return True


def width():
    m = re.search(r"(\d+)x(\d+)", adb("shell", "wm", "size"))
    return int(m.group(1)) if m else 1080


def tap(text, where, wait=4, required=True, row=None):
    root = screen()
    pos = find(root, text)
    # Buttons further along a scrolling row are off-screen: swipe that row left and look again.
    tries = 0
    while pos is None and row and tries < 3:
        anchor = next((find(root, r) for r in row if find(root, r)), None)
        if anchor is None:
            break
        w = width()
        adb("shell", "input", "swipe", str(int(w * 0.85)), str(anchor[1]), str(int(w * 0.15)), str(anchor[1]), "300")
        time.sleep(1.5)
        root = screen()
        pos = find(root, text)
        tries += 1
    if pos is None:
        if required:
            fail(f"Couldn't find '{text}' on screen ({where}). Visible: {', '.join(texts(root)[:25])}")
        return False
    adb("shell", "input", "tap", str(pos[0]), str(pos[1]))
    time.sleep(wait)
    return check_ok(f"{where} → {text}")


def launch():
    adb("shell", "am", "force-stop", PKG)
    adb("logcat", "-c", "-b", "all")
    adb("shell", "monkey", "-p", PKG, "-c", "android.intent.category.LAUNCHER", "1")
    time.sleep(10)


def tour(label):
    """Taps every bottom tab and every view button inside it."""
    views = {
        "Markets": ["Top 30", "Movers", "Common picks", "WSB", "Crypto"],
        "Predictions": ["All markets", "NFL odds check", "My picks"],
        "Feed": ["Filings", "News"],
        "Portfolio": ["Watchlist", "My accounts", "Track record"],
        "Home": [],
    }
    for tab, inner in views.items():
        if not tap(tab, label):
            return
        for v in inner:
            if not tap(v, f"{label} / {tab}", wait=5, row=inner):
                return
            if tab == "Predictions" and v == "All markets" and label == "No screen lock":
                time.sleep(8)  # let the markets load
                check_bet_sheet(label)
    note(f"{label}: opened every tab and view without crashing")


def check_market_sources():
    """Reports which free market-price address works, for the green/red background without a key.
    Parses each one the same way the app does and prints today's S&P 500 move."""
    import json, urllib.request

    def get(url):
        req = urllib.request.Request(url, headers={"User-Agent": "StockTracker-Android/2.0"})
        return urllib.request.urlopen(req, timeout=20).read().decode("utf-8", "replace")

    try:
        d = json.loads(get("https://cdn.cboe.com/api/global/delayed_quotes/quotes/SPY.json")).get("data", {})
        now, prev = float(d.get("current_price") or 0), float(d.get("prev_day_close") or 0)
        note(f"Market source Cboe -> " + (f"OK: SPY {now} vs {prev} ({(now / prev - 1) * 100:+.2f}%)" if now > 0 and prev > 0
                                            else f"no usable price; keys: {list(d)[:15]}"))
    except Exception as e:
        note(f"Market source Cboe -> failed: {e}")
    try:
        meta = json.loads(get("https://query1.finance.yahoo.com/v8/finance/chart/SPY?range=1d&interval=1d"))["chart"]["result"][0]["meta"]
        now, prev = float(meta.get("regularMarketPrice") or 0), float(meta.get("chartPreviousClose") or meta.get("previousClose") or 0)
        note(f"Market source Yahoo -> " + (f"OK: SPY {now} vs {prev} ({(now / prev - 1) * 100:+.2f}%)" if now > 0 and prev > 0
                                             else f"no usable price; keys: {list(meta)[:15]}"))
    except Exception as e:
        note(f"Market source Yahoo -> failed: {e}")
    for url in ("https://stooq.com/q/l/?s=spy.us&f=sd2t2ohlcp&h&e=csv",
                "https://stooq.com/q/d/l/?s=spy.us&i=d",
                "https://stooq.com/q/l/?s=spy.us&f=sd2t2oc&h&e=csv"):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "StockTracker-Android/2.0"})
            text = urllib.request.urlopen(req, timeout=20).read().decode("utf-8", "replace").strip().splitlines()
            note(f"Market source {url} -> {' / '.join(text[:2])[:200]} ... ({len(text)} lines, last: {text[-1][:80] if text else ''})")
        except Exception as e:
            note(f"Market source {url} -> failed: {e}")


def check_market_color():
    """Takes a screenshot and checks that the background is tinted green (market up) or red (market down)."""
    try:
        from PIL import Image
        import io
    except ImportError:
        note("Market color: Pillow isn't installed, so the screenshot check was skipped")
        return
    tints = {"green (market up)": [(0xE6, 0xF6, 0xEA), (0x06, 0x18, 0x0C)],
             "red (market down)": [(0xFC, 0xE9, 0xE8), (0x1D, 0x08, 0x08)]}
    for attempt in range(4):
        png = subprocess.run(["adb", "exec-out", "screencap", "-p"], capture_output=True, timeout=60).stdout
        img = Image.open(io.BytesIO(png)).convert("RGB")
        small = img.resize((img.width // 8, img.height // 8))
        px = list(small.getdata())
        for name, colors in tints.items():
            share = sum(1 for p in px if any(all(abs(p[i] - c[i]) <= 3 for i in range(3)) for c in colors)) / len(px)
            if share > 0.05:
                note(f"Market color: background is {name} ({share:.0%} of the screen)")
                return
        time.sleep(8)
    common = {}
    for p in px:
        common[p] = common.get(p, 0) + 1
    top = sorted(common.items(), key=lambda kv: -kv[1])[:3]
    print("::warning::Market color: no green or red background found. Most common colors: " +
          ", ".join(f"#{c[0]:02X}{c[1]:02X}{c[2]:02X}" for c, _ in top))


def main(apk):
    check_market_sources()
    adb("wait-for-device")
    adb("install", "-r", apk, check=True)
    adb("shell", "pm", "grant", PKG, "android.permission.POST_NOTIFICATIONS")

    # 1) No screen lock: the app should open straight away.
    launch()
    if check_ok("first launch (no screen lock)"):
        root = screen()
        if find(root, "Markets") is None:
            fail("First launch (no screen lock): the tabs didn't appear. Visible: " + ", ".join(texts(root)[:25]))
        else:
            check_market_color()
            tour("No screen lock")

    # 2) With a PIN: the app lock should ask, then open after the PIN.
    adb("shell", "locksettings", "set-pin", PIN)
    time.sleep(2)
    launch()
    if check_ok("launch with a PIN set (app lock)"):
        root = screen()
        seen = texts(root)
        if find(root, "Markets") is not None:
            fail("With a PIN set, the app opened without asking to unlock.")
        else:
            adb("shell", "input", "text", PIN)
            adb("shell", "input", "keyevent", "66")
            time.sleep(6)
            if check_ok("after entering the PIN"):
                root = screen()
                if find(root, "Markets") is None:
                    # The prompt may not have had focus yet; use the Unlock button and try once more.
                    if find(root, "Unlock"):
                        tap("Unlock", "lock screen", wait=3)
                        adb("shell", "input", "text", PIN)
                        adb("shell", "input", "keyevent", "66")
                        time.sleep(6)
                        root = screen()
                if find(root, "Markets") is None:
                    fail("After entering the PIN, the app didn't open. Before: " + ", ".join(seen[:15]) +
                         ". After: " + ", ".join(texts(root)[:15]))
                else:
                    note("App lock: asked for the PIN and opened after it")
                    tour("With PIN")
    adb("shell", "locksettings", "clear", "--old", PIN)

    if problems:
        print(f"{len(problems)} problem(s) found")
        sys.exit(1)
    print("Smoke test passed")


if __name__ == "__main__":
    main(sys.argv[1])
