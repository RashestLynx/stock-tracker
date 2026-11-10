// Stock Tracker live alerts: runs on Cloudflare every minute, checks the same sources as the app, and pushes each
// new filing to your phone through ntfy (free app for iPhone and Android). See ../README.md for setup.

import { parseSecFeed, parseRss, parseForm4, form4Alert, congressAlert, congressKey } from "./parse.js";

const FEED = (form) =>
  `https://www.sec.gov/cgi-bin/browse-edgar?action=getcurrent&type=${encodeURIComponent(form)}&company=&dateb=&owner=include&start=0&count=100&output=atom`;
const CHARGE_FEEDS = [
  "https://www.sec.gov/enforcement-litigation/litigation-releases/rss",
  "https://www.sec.gov/news/pressreleases.rss",
];
// The famous funds and countries the app follows (13F filer numbers). FUNDS = "all" alerts on every 13F instead.
const DEFAULT_FUNDS = {
  "1067983": "Berkshire Hathaway", "1336528": "Pershing Square (Bill Ackman)", "1536411": "Duquesne (Stanley Druckenmiller)",
  "1350694": "Bridgewater (Ray Dalio)", "1166559": "Gates Foundation Trust", "1374170": "Norway (Norges Bank)",
  "1767640": "Saudi Arabia (PIF)", "1704268": "United Arab Emirates (Mubadala)", "1582202": "Switzerland (Swiss National Bank)",
  "1441689": "South Korea (Korea Investment Corp)", "1283718": "Canada (CPP Investments)",
};

// Cloudflare's free plan allows 50 outside requests per run; stay well under it.
const MAX_FORM4_PER_RUN = 12;
const MAX_PUSHES_PER_RUN = 8;

export default {
  async scheduled(event, env, ctx) {
    ctx.waitUntil(run(env, new Date(event.scheduledTime)));
  },
  // Visiting the worker's address shows its status, and /test sends a test notification.
  async fetch(req, env) {
    const url = new URL(req.url);
    if (url.pathname === "/test") {
      await push(env, { title: "Stock Tracker live alerts are working", body: "You'll get a notification here for each new filing.", tags: "white_check_mark" });
      return new Response("Test notification sent.\n");
    }
    await setup(env);
    const row = await env.DB.prepare("SELECT COUNT(*) AS n, MAX(t) AS last FROM seen").first();
    return new Response(`Stock Tracker live alerts are running.\nFilings seen: ${row.n}\nLast new filing: ${row.last ? new Date(row.last).toISOString() : "none yet"}\n`);
  },
};

async function setup(env) {
  await env.DB.exec("CREATE TABLE IF NOT EXISTS seen (key TEXT PRIMARY KEY, t INTEGER)");
}

function options(env) {
  const list = (s) => new Set(String(s || "").split(",").map((x) => x.trim().toUpperCase()).filter(Boolean));
  const sections = new Set(String(env.SECTIONS || "insiders,funds,charges,congress").split(",").map((s) => s.trim().toLowerCase()));
  return {
    sections,
    tickers: list(env.TICKERS),
    allTrades: String(env.INSIDER_TRADES || "").toLowerCase() === "all",
    minValue: Number(env.MIN_VALUE || 0) || 0,
    allFunds: String(env.FUNDS || "").toLowerCase() === "all",
  };
}

async function run(env, now) {
  if (!env.SEC_USER_AGENT || !env.NTFY_TOPIC) {
    console.log("Set SEC_USER_AGENT and NTFY_TOPIC (see README).");
    return;
  }
  await setup(env);
  const opts = options(env);
  const minute = now.getUTCMinutes();
  // First run: remember what's already out there without notifying, so you aren't flooded.
  const first = !(await env.DB.prepare("SELECT 1 FROM seen LIMIT 1").first());
  const alerts = [];
  const newKeys = [];

  const unseen = async (keys) => {
    if (!keys.length) return new Set();
    const found = new Set();
    for (let i = 0; i < keys.length; i += 90) {
      const chunk = keys.slice(i, i + 90);
      const rs = await env.DB.prepare(`SELECT key FROM seen WHERE key IN (${chunk.map(() => "?").join(",")})`).bind(...chunk).all();
      for (const r of rs.results) found.add(r.key);
    }
    return new Set(keys.filter((k) => !found.has(k)));
  };

  // ---- Insiders: every minute
  if (opts.sections.has("insiders")) {
    try {
      const feed = parseSecFeed(await get(FEED("4"), env)).filter((f) => f.form === "4" || f.form === "4/A");
      const fresh = await unseen(feed.map((f) => f.accession));
      // Oldest first, a few per run; the rest wait for the next minute.
      const todo = feed.filter((f) => fresh.has(f.accession)).reverse();
      if (first) newKeys.push(...todo.map((f) => f.accession));
      else for (const f of todo.slice(0, MAX_FORM4_PER_RUN)) {
        newKeys.push(f.accession);
        try {
          const xml = await form4Xml(f.link, env);
          const a = xml && form4Alert(parseForm4(xml), f.link, opts);
          if (a) alerts.push(a);
        } catch (e) { console.log("form4", f.accession, e.message); }
      }
    } catch (e) { console.log("insiders", e.message); }
  }

  // ---- Funds and countries (13F): every 5 minutes
  if (opts.sections.has("funds") && (first || minute % 5 === 0)) {
    try {
      const feed = parseSecFeed(await get(FEED("13F-HR"), env));
      const fresh = await unseen(feed.map((f) => f.accession));
      for (const f of feed.filter((x) => fresh.has(x.accession))) {
        newKeys.push(f.accession);
        if (first) continue;
        const cik = ((f.title.match(/\((\d+)\)/) || [])[1] || "").replace(/^0+/, "");
        const name = DEFAULT_FUNDS[cik] || f.title.replace(/^13F-HR\s*-\s*/, "").replace(/\s*\(\d+\)\s*\(Filer\)\s*$/, "");
        if (!opts.allFunds && !DEFAULT_FUNDS[cik]) continue;
        alerts.push({ title: `New 13F: ${name}`, body: "Filed its quarterly stock holdings. Open the app to see what changed.", click: f.link, tags: "bank" });
      }
    } catch (e) { console.log("funds", e.message); }
  }

  // ---- SEC insider trading charges: every 5 minutes
  if (opts.sections.has("charges") && (first || minute % 5 === 2)) {
    for (const url of CHARGE_FEEDS) {
      try {
        const items = parseRss(await get(url, env))
          .filter((c) => /insider[ -]trading/i.test(c.title + " " + c.summary));
        const keyOf = (c) => "sec:" + (c.guid || c.link || c.title);
        const fresh = await unseen(items.map(keyOf));
        for (const c of items.filter((x) => fresh.has(keyOf(x)))) {
          newKeys.push(keyOf(c));
          if (!first) alerts.push({ title: "SEC insider trading case", body: [c.title, c.summary.slice(0, 220)].filter(Boolean).join("\n"), click: c.link, tags: "rotating_light", priority: 4 });
        }
      } catch (e) { console.log("charges", e.message); }
    }
  }

  // ---- Congress: hourly (Bargo's free daily limit is shared with the app)
  if (opts.sections.has("congress") && (first || minute === 7)) {
    try {
      const headers = { "User-Agent": "StockTracker-Live/1.0", Accept: "application/json" };
      if (env.BARGO_KEY) headers["X-Api-Key"] = env.BARGO_KEY;
      const r = await fetch(`https://www.bargo.ai/free-apis/congress/v1/trades?limit=${env.BARGO_KEY ? 15 : 4}`, { headers });
      if (r.ok) {
        const trades = (await r.json()).trades || [];
        const fresh = await unseen(trades.map(congressKey));
        for (const t of trades.filter((x) => fresh.has(congressKey(x)))) {
          newKeys.push(congressKey(t));
          const a = !first && congressAlert(t, opts);
          if (a) alerts.push(a);
        }
      } else console.log("congress HTTP", r.status);
    } catch (e) { console.log("congress", e.message); }
  }

  // Remember everything handled this run.
  if (newKeys.length) {
    const t = Date.now();
    const stmt = env.DB.prepare("INSERT OR IGNORE INTO seen (key, t) VALUES (?, ?)");
    for (let i = 0; i < newKeys.length; i += 100) await env.DB.batch(newKeys.slice(i, i + 100).map((k) => stmt.bind(k, t)));
  }
  // Once a day, forget items older than 60 days.
  if (now.getUTCHours() === 9 && minute === 30) {
    await env.DB.prepare("DELETE FROM seen WHERE t < ?").bind(Date.now() - 60 * 86_400_000).run();
  }

  if (first) {
    await push(env, { title: "Stock Tracker live alerts are on", body: "You'll get a notification here within a minute or two of each new filing.", tags: "white_check_mark" });
    return;
  }
  // Send up to 8 one by one; if there are more, the rest go in one summary.
  for (const a of alerts.slice(0, MAX_PUSHES_PER_RUN)) await push(env, a);
  if (alerts.length > MAX_PUSHES_PER_RUN) {
    const rest = alerts.slice(MAX_PUSHES_PER_RUN);
    await push(env, { title: `${rest.length} more new filings`, body: rest.slice(0, 12).map((a) => a.title).join("\n"), tags: "inbox_tray" });
  }
}

async function get(url, env) {
  const r = await fetch(url, { headers: { "User-Agent": env.SEC_USER_AGENT, "Accept-Encoding": "gzip, deflate" } });
  if (!r.ok) throw new Error(`HTTP ${r.status} from ${new URL(url).host}`);
  return r.text();
}

/** The Form 4 XML inside a filing (2 requests: the folder listing, then the XML). */
async function form4Xml(indexLink, env) {
  const folder = indexLink.slice(0, indexLink.lastIndexOf("/") + 1);
  const list = JSON.parse(await get(folder + "index.json", env));
  const item = (list.directory?.item || []).find((i) => /\.xml$/i.test(i.name));
  return item ? get(folder + item.name, env) : null;
}

// Published as JSON so titles can hold any characters.
async function push(env, a) {
  const msg = {
    topic: env.NTFY_TOPIC,
    title: a.title,
    message: a.body || "",
    tags: String(a.tags || "").split(",").filter(Boolean),
    priority: a.priority || 3,
  };
  if (a.click) msg.click = a.click;
  const headers = { "Content-Type": "application/json" };
  if (env.NTFY_TOKEN) headers.Authorization = `Bearer ${env.NTFY_TOKEN}`;
  const server = (env.NTFY_SERVER || "https://ntfy.sh").replace(/\/$/, "");
  const r = await fetch(server + "/", { method: "POST", headers, body: JSON.stringify(msg) });
  if (!r.ok) console.log("ntfy HTTP", r.status, await r.text());
}
