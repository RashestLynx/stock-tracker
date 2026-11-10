import assert from "node:assert/strict";
import worker from "../src/worker.js";

// In-memory stand-in for Cloudflare D1.
const rows = new Map();
const DB = {
  exec: async () => {},
  prepare(sql) {
    const st = { sql, args: [], bind(...a) { return { ...st, args: a }; },
      async first() { if (sql.includes("COUNT")) return { n: rows.size, last: null }; return rows.size ? { 1: 1 } : null; },
      async all() { return { results: this.args.filter((k) => rows.has(k)).map((key) => ({ key })) }; },
      async run() { return {}; } };
    return st;
  },
  async batch(list) { for (const s of list) rows.set(s.args[0], s.args[1]); },
};

let feedEntries = ["0000000001-26-000001"];
const pushes = [];
globalThis.fetch = async (url, init = {}) => {
  url = String(url);
  const ok = (body) => ({ ok: true, status: 200, text: async () => body, json: async () => JSON.parse(body) });
  if (url.includes("ntfy.sh")) { pushes.push(JSON.parse(init.body)); return ok("{}"); }
  if (url.includes("type=4&")) return ok(`<feed>${feedEntries.map((a) => `<entry><title>4 - X</title><link href="https://www.sec.gov/Archives/edgar/data/1/${a.replace(/-/g, "")}/${a}-index.htm"/><category term="4"/><id>accession-number=${a}</id></entry>`).join("")}</feed>`);
  if (url.endsWith("index.json")) return ok(JSON.stringify({ directory: { item: [{ name: "doc.xml" }] } }));
  if (url.endsWith("doc.xml")) return ok(`<issuer><issuerName>ACME</issuerName><issuerTradingSymbol>ACME</issuerTradingSymbol></issuer><reportingOwner><reportingOwnerId><rptOwnerName>Doe Jane</rptOwnerName></reportingOwnerId><reportingOwnerRelationship><officerTitle>CEO</officerTitle></reportingOwnerRelationship></reportingOwner><nonDerivativeTransaction><transactionCoding><transactionCode>P</transactionCode></transactionCoding><transactionAmounts><transactionShares><value>1000</value></transactionShares><transactionPricePerShare><value>50</value></transactionPricePerShare></transactionAmounts></nonDerivativeTransaction>`);
  if (url.includes("13F-HR")) return ok(`<feed><entry><title>13F-HR - BERKSHIRE HATHAWAY INC (0001067983) (Filer)</title><link href="https://x/0000000009-26-000009-index.htm"/><category term="13F-HR"/><id>accession-number=0000000009-26-000009</id></entry></feed>`);
  if (url.endsWith(".rss") || url.endsWith("/rss")) return ok("<rss></rss>");
  if (url.includes("bargo")) return ok(JSON.stringify({ trades: [] }));
  throw new Error("unexpected " + url);
};

const env = { DB, SEC_USER_AGENT: "Test test@example.com", NTFY_TOPIC: "t" };
const run = (min) => new Promise((res) => worker.scheduled({ scheduledTime: Date.UTC(2026, 10, 9, 15, min) }, env, { waitUntil: (p) => p.then(res) }));

await run(0);                       // first run: remember everything, one "alerts are on" message
assert.equal(pushes.length, 1);
assert.match(pushes[0].title, /alerts are on/);
await run(1);                       // nothing new
assert.equal(pushes.length, 1);
feedEntries = ["0000000002-26-000002", ...feedEntries];
await run(2);                       // one new insider buy
assert.equal(pushes.length, 2);
assert.equal(pushes[1].title, "BUY $ACME · Jane Doe (CEO)");
assert.equal(pushes[1].priority, 3);
await run(3);                       // not sent twice
assert.equal(pushes.length, 2);
console.log("worker run test passed:", pushes.map((p) => p.title));
