// Parsers for the sources the live checker watches. Plain functions, no Cloudflare APIs, so they can be tested with Node.

const ENTITIES = { "&amp;": "&", "&lt;": "<", "&gt;": ">", "&quot;": '"', "&#39;": "'", "&#039;": "'", "&apos;": "'", "&nbsp;": " " };

export function decode(s) {
  return (s || "")
    .replace(/<!\[CDATA\[([\s\S]*?)\]\]>/g, "$1")
    .replace(/&#(\d+);/g, (_, n) => String.fromCharCode(Number(n)))
    .replace(/&[a-z#0-9]+;/gi, (e) => ENTITIES[e] ?? e);
}

export function stripTags(s) {
  return decode(s).replace(/<[^>]+>/g, " ").replace(/\s+/g, " ").trim();
}

function tag(block, name) {
  const m = block.match(new RegExp(`<${name}(?:\\s[^>]*)?>([\\s\\S]*?)</${name}>`, "i"));
  return m ? decode(m[1]).trim() : "";
}

/** SEC "latest filings" Atom feed -> [{ accession, form, title, link, updated }], newest first. */
export function parseSecFeed(xml) {
  const out = [];
  const seen = new Set();
  for (const m of xml.matchAll(/<entry>([\s\S]*?)<\/entry>/g)) {
    const e = m[1];
    const link = (e.match(/<link[^>]*href="([^"]+)"/) || [])[1] || "";
    const id = tag(e, "id");
    const acc = (id.match(/accession-number=([\d-]+)/) || link.match(/(\d{10}-\d{2}-\d{6})/) || [])[1];
    if (!acc || seen.has(acc)) continue;
    seen.add(acc);
    const form = (e.match(/<category[^>]*term="([^"]+)"/) || [])[1] || "";
    out.push({ accession: acc, form, title: tag(e, "title"), link: decode(link), updated: tag(e, "updated") });
  }
  return out;
}

/** RSS (SEC litigation / press releases) -> [{ title, link, summary, date }]. */
export function parseRss(xml) {
  const out = [];
  for (const m of xml.matchAll(/<item>([\s\S]*?)<\/item>/g)) {
    const it = m[1];
    out.push({
      title: stripTags(tag(it, "title")),
      link: tag(it, "link"),
      summary: stripTags(tag(it, "description")),
      date: tag(it, "pubDate"),
      guid: tag(it, "guid"),
    });
  }
  return out;
}

const CODES = {
  P: "Bought (open market)", S: "Sold (open market)", A: "Award/grant", M: "Option exercise",
  F: "Tax withholding", G: "Gift", D: "Disposed to issuer", C: "Conversion", X: "Option exercise", J: "Other",
};

/** A Form 4 XML document -> { ticker, issuer, owner, role, trades: [{ code, shares, price }] }. */
export function parseForm4(xml) {
  const issuer = tag(xml, "issuer");
  const owner = tag(xml, "reportingOwner");
  const rel = tag(owner, "reportingOwnerRelationship");
  let role = tag(rel, "officerTitle");
  if (!role && /<isDirector>\s*(1|true)/i.test(rel)) role = "Director";
  if (!role && /<isTenPercentOwner>\s*(1|true)/i.test(rel)) role = "10% owner";
  const trades = [];
  for (const m of xml.matchAll(/<nonDerivativeTransaction>([\s\S]*?)<\/nonDerivativeTransaction>/g)) {
    const t = m[1];
    const code = tag(tag(t, "transactionCoding"), "transactionCode");
    const amounts = tag(t, "transactionAmounts");
    const shares = parseFloat(tag(tag(amounts, "transactionShares"), "value")) || 0;
    const price = parseFloat(tag(tag(amounts, "transactionPricePerShare"), "value")) || 0;
    trades.push({ code, shares, price });
  }
  return {
    ticker: tag(issuer, "issuerTradingSymbol").toUpperCase(),
    issuer: tag(issuer, "issuerName"),
    owner: tag(tag(owner, "reportingOwnerId"), "rptOwnerName"),
    role,
    trades,
  };
}

export function money(v) {
  if (v >= 1e9) return `$${(v / 1e9).toFixed(1)}B`;
  if (v >= 1e6) return `$${(v / 1e6).toFixed(1)}M`;
  if (v >= 1e3) return `$${Math.round(v / 1e3)}K`;
  return `$${Math.round(v)}`;
}

/** "Musk Elon" -> "Elon Musk" (SEC lists names last-first). */
export function personName(s) {
  const parts = (s || "").trim().split(/\s+/);
  if (parts.length < 2) return s;
  const cap = (w) => (w.length <= 3 && w === w.toUpperCase() && /[A-Z]/.test(w) && w.length > 1 ? w : w[0].toUpperCase() + w.slice(1).toLowerCase());
  const [last, ...rest] = parts;
  return [...rest, last].map(cap).join(" ");
}

/**
 * Turns a parsed Form 4 into a notification, or null when it's not worth one.
 * opts: { allTrades: bool, minValue: number, tickers: Set }
 */
export function form4Alert(f, link, opts) {
  if (!f.ticker) return null;
  if (opts.tickers.size && !opts.tickers.has(f.ticker)) return null;
  const pick = opts.allTrades ? f.trades : f.trades.filter((t) => t.code === "P" || t.code === "S");
  if (!pick.length) return null;
  const groups = {};
  for (const t of pick) {
    const g = (groups[t.code] ||= { shares: 0, value: 0 });
    g.shares += t.shares;
    g.value += t.shares * t.price;
  }
  const total = Object.values(groups).reduce((a, g) => a + g.value, 0);
  if (opts.minValue > 0 && total < opts.minValue) return null;
  const buy = groups.P && !groups.S;
  const sell = groups.S && !groups.P;
  const who = personName(f.owner) + (f.role ? ` (${f.role})` : "");
  const lines = Object.entries(groups).map(([code, g]) => {
    const label = CODES[code] || `Code ${code}`;
    const sh = Math.round(g.shares).toLocaleString("en-US");
    return g.value > 0 ? `${label}: ${sh} shares @ ~$${(g.value / g.shares).toFixed(2)} (${money(g.value)})` : `${label}: ${sh} shares`;
  });
  return {
    title: `${buy ? "BUY" : sell ? "SALE" : "Insider"} $${f.ticker} · ${who}`,
    body: [...lines, f.issuer].join("\n"),
    click: link,
    tags: buy ? "chart_with_upwards_trend" : sell ? "chart_with_downwards_trend" : "briefcase",
    priority: buy && total >= 1e6 ? 4 : 3,
  };
}

/** One Bargo Congress trade -> notification, or null. */
export function congressAlert(t, opts) {
  const ticker = String(t.ticker || "").toUpperCase().replace(/^(--|N\/A)$/, "");
  if (opts.tickers.size && !opts.tickers.has(ticker)) return null;
  const type = String(t.type || "").toLowerCase();
  const buy = type.includes("buy") || type.includes("purchase");
  const sell = type.includes("sell") || type.includes("sale");
  const senate = String(t.chamber || "").toLowerCase() === "senate";
  const who = `${senate ? "Sen." : "Rep."} ${t.member || "Member of Congress"}${t.state ? ` (${t.state})` : ""}`;
  const action = buy ? "bought" : sell ? "sold" : (t.type || "traded");
  return {
    title: `${who} ${action} ${ticker ? "$" + ticker : t.asset || "a stock"}`,
    body: [t.amount_range, t.asset, `Traded ${t.transaction_date || "?"} · disclosed ${t.disclosure_date || "?"}`].filter(Boolean).join("\n"),
    click: t.filing_portal || "https://www.bargo.ai/free-apis/congress",
    tags: buy ? "classical_building,chart_with_upwards_trend" : "classical_building",
    priority: 3,
  };
}

export function congressKey(t) {
  return `cg:${t.member_slug || t.member}|${t.ticker}|${t.transaction_date}|${t.type}|${t.amount_range}`;
}
