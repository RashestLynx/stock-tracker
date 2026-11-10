import assert from "node:assert/strict";
import { parseSecFeed, parseRss, parseForm4, form4Alert, congressAlert, personName } from "../src/parse.js";

const feed = `<?xml version="1.0"?><feed xmlns="http://www.w3.org/2005/Atom">
<entry><title>4 - Huang Jen Hsun (0001197649) (Reporting)</title>
<link rel="alternate" type="text/html" href="https://www.sec.gov/Archives/edgar/data/1197649/000119764926000123/0001197649-26-000123-index.htm"/>
<summary type="html"> &lt;b&gt;Filed:&lt;/b&gt; 2026-11-09 </summary><updated>2026-11-09T16:30:00-05:00</updated>
<category scheme="https://www.sec.gov/" label="form type" term="4"/>
<id>urn:tag:sec.gov,2008:accession-number=0001197649-26-000123</id></entry>
<entry><title>4 - NVIDIA CORP (0001045810) (Issuer)</title>
<link rel="alternate" type="text/html" href="https://www.sec.gov/Archives/edgar/data/1045810/000119764926000123/0001197649-26-000123-index.htm"/>
<updated>2026-11-09T16:30:00-05:00</updated><category scheme="https://www.sec.gov/" label="form type" term="4"/>
<id>urn:tag:sec.gov,2008:accession-number=0001197649-26-000123</id></entry>
</feed>`;
const f = parseSecFeed(feed);
assert.equal(f.length, 1, "dedupes issuer/owner copies");
assert.equal(f[0].accession, "0001197649-26-000123");
assert.equal(f[0].form, "4");

const form4 = `<ownershipDocument><issuer><issuerCik>0001045810</issuerCik><issuerName>NVIDIA CORP</issuerName><issuerTradingSymbol>nvda</issuerTradingSymbol></issuer>
<reportingOwner><reportingOwnerId><rptOwnerCik>0001197649</rptOwnerCik><rptOwnerName>Huang Jen Hsun</rptOwnerName></reportingOwnerId>
<reportingOwnerRelationship><isDirector>1</isDirector><isOfficer>1</isOfficer><officerTitle>President and CEO</officerTitle></reportingOwnerRelationship></reportingOwner>
<nonDerivativeTable>
<nonDerivativeTransaction><transactionCoding><transactionFormType>4</transactionFormType><transactionCode>S</transactionCode></transactionCoding>
<transactionAmounts><transactionShares><value>100000</value></transactionShares><transactionPricePerShare><value>180.5</value></transactionPricePerShare><transactionAcquiredDisposedCode><value>D</value></transactionAcquiredDisposedCode></transactionAmounts></nonDerivativeTransaction>
<nonDerivativeTransaction><transactionCoding><transactionCode>F</transactionCode></transactionCoding>
<transactionAmounts><transactionShares><value>500</value></transactionShares><transactionPricePerShare><value>180</value></transactionPricePerShare></transactionAmounts></nonDerivativeTransaction>
</nonDerivativeTable></ownershipDocument>`;
const p = parseForm4(form4);
assert.equal(p.ticker, "NVDA");
assert.equal(p.role, "President and CEO");
assert.equal(p.trades.length, 2);
const opts = { tickers: new Set(), allTrades: false, minValue: 0 };
const a = form4Alert(p, "https://x", opts);
assert.match(a.title, /^SALE \$NVDA · Jen Hsun Huang \(President and CEO\)$/);
assert.match(a.body, /Sold \(open market\): 100,000 shares @ ~\$180.50 \(\$18.1M\)/);
assert.equal(form4Alert({ ...p, trades: [{ code: "F", shares: 1, price: 1 }] }, "x", opts), null, "skips non open-market");
assert.equal(form4Alert(p, "x", { ...opts, minValue: 1e9 }), null, "min value");
assert.equal(form4Alert(p, "x", { ...opts, tickers: new Set(["AAPL"]) }), null, "ticker filter");

const rss = `<rss><channel><item><title><![CDATA[SEC Charges Former Executive with Insider Trading]]></title><link>https://www.sec.gov/newsroom/press-releases/2026-120</link><description>&lt;p&gt;The SEC charged...&lt;/p&gt;</description><pubDate>Mon, 09 Nov 2026 10:00:00 -0500</pubDate><guid>2026-120</guid></item></channel></rss>`;
const r = parseRss(rss);
assert.equal(r[0].title, "SEC Charges Former Executive with Insider Trading");
assert.equal(r[0].summary, "The SEC charged...");

const c = congressAlert({ member: "Nancy Pelosi", chamber: "house", state: "CA", ticker: "NVDA", type: "Purchase", amount_range: "$1M - $5M", transaction_date: "2026-11-01", disclosure_date: "2026-11-09" }, opts);
assert.equal(c.title, "Rep. Nancy Pelosi (CA) bought $NVDA");
assert.equal(personName("Musk Elon"), "Elon Musk");
console.log("all parser tests passed");
