import Foundation

/// One person's activity in one stock. side: "buy", "sell" or "hold".
struct WhoEntry: Codable, Hashable, Identifiable {
    var who: String; var side: String; var action: String; var detail: String; var date: String; var link: String
    var id: String { who + action + date + link }
}

/// For every stock: who you follow has bought, sold or holds it.
/// Sources: funds' latest two 13F filings, followed people's SEC Form 4s, and their known/disclosed holdings.
/// Heavy (many SEC requests), so it's cached for 12 hours.
enum WhoTraded {
    private struct Saved: Codable { var who: String; var time: Date; var map: [String: [WhoEntry]] }
    private static var file: URL {
        FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("who_traded.json")
    }
    private static func sig(_ f: [Followed]) -> String { f.map(\.name).joined(separator: "|") }

    static func cached(_ followed: [Followed]) -> [String: [WhoEntry]]? {
        guard let d = try? Data(contentsOf: file), let s = try? JSONDecoder().decode(Saved.self, from: d),
              s.who == sig(followed), Date().timeIntervalSince(s.time) < 12 * 3600 else { return nil }
        return s.map
    }

    @MainActor
    static func build(_ followed: [Followed], model: AppModel) async -> [String: [WhoEntry]] {
        var out: [String: [WhoEntry]] = [:]
        func add(_ t: String, _ e: WhoEntry) { if !t.isEmpty { out[t.uppercased(), default: []].append(e) } }
        let ua = model.userAgent
        let names = await model.tickerMap(ua: ua)

        let dayFmt = DateFormatter(); dayFmt.locale = Locale(identifier: "en_US_POSIX"); dayFmt.dateFormat = "yyyy-MM-dd"
        for f in followed {
            // Congress members followed from their profile: their captured trades.
            if let p = f.person, p.hasPrefix("cg:") {
                for m in model.moves where m.person == p {
                    add(m.ticker, WhoEntry(who: f.name, side: m.side, action: m.side == "buy" ? "Bought" : "Sold",
                                           detail: m.valueLabel, date: dayFmt.string(from: m.time), link: m.link))
                }
            }
            if let k = Known.people[f.name] {
                for h in k.owns { add(h.ticker, WhoEntry(who: f.name, side: "hold", action: "Owns", detail: h.note, date: "", link: h.sourceURL)) }
                if !k.disclosureFeed.isEmpty {
                    for h in await Known.disclosures(feed: k.disclosureFeed, name: f.name) {
                        add(h.ticker, WhoEntry(who: f.name, side: "hold", action: "Disclosed owning", detail: h.note, date: "", link: h.sourceURL))
                    }
                }
            }
            if let ins = f.insider, !ins.isEmpty {
                for x in await Following.insiderFilings(f, ua: ua, max: 12) {
                    let text = x.lines.joined(separator: " · ")
                    let side = text.contains("Sold") || text.contains("Disposed") ? "sell" : text.contains("Bought") ? "buy" : "hold"
                    let action = side == "sell" ? "Sold" : side == "buy" ? "Bought" : "Filed"
                    let owns = x.ownedAfter > 0 ? " · owns \(Format.number(x.ownedAfter, decimals: 0)) shares after" : ""
                    add(x.ticker, WhoEntry(who: f.name, side: side, action: action, detail: text + owns, date: x.date, link: x.link))
                }
            }
            if let cik = f.cik, !cik.isEmpty, let filings = try? await Edgar.latest13Fs(cik: cik, ua: ua, count: 2), let first = filings.first {
                let (now, period) = first
                func byTicker(_ rows: [Edgar.Row13F]) -> [String: (Double, Double)] {
                    var m: [String: (Double, Double)] = [:]
                    for r in rows where !r.option {
                        guard let t = names[Edgar.normalizeName(r.name)] else { continue }
                        let o = m[t] ?? (0, 0); m[t] = (o.0 + r.value, o.1 + r.shares)
                    }
                    return m
                }
                let a = byTicker((try? await Edgar.rows13F(now, ua: ua)) ?? [])
                var b: [String: (Double, Double)] = [:]
                if filings.count > 1 { b = byTicker((try? await Edgar.rows13F(filings[1].0, ua: ua)) ?? []) }
                for (t, v) in a {
                    var side = "hold", action = "Holds"
                    if !b.isEmpty {
                        if let old = b[t] {
                            if old.1 > 0 {
                                let ch = (v.1 - old.1) / old.1
                                if ch > 0.01 { side = "buy"; action = String(format: "Added %.0f%%", ch * 100) }
                                else if ch < -0.01 { side = "sell"; action = String(format: "Cut %.0f%%", -ch * 100) }
                            }
                        } else { side = "buy"; action = "New position" }
                    }
                    add(t, WhoEntry(who: f.name, side: side, action: action, detail: "\(money(v.0)) in 13F (quarter ending \(period))", date: period, link: now.link))
                }
                for (t, v) in b where a[t] == nil {
                    add(t, WhoEntry(who: f.name, side: "sell", action: "Sold out",
                                    detail: "Had \(money(v.0)) the quarter before; gone in the 13F for \(period)", date: period, link: now.link))
                }
            }
        }
        var map: [String: [WhoEntry]] = [:]
        for (t, l) in out {
            var seen = Set<String>()
            map[t] = l.filter { seen.insert($0.who + $0.action + $0.date).inserted }.sorted { $0.date > $1.date }
        }
        if let d = try? JSONEncoder().encode(Saved(who: sig(followed), time: Date(), map: map)) { try? d.write(to: file) }
        return map
    }

    /// Everyone else the app tracks (Congress, insiders from alerts) for one ticker.
    static func others(_ moves: [Move], ticker: String, followed: Set<String>) -> [WhoEntry] {
        let f = DateFormatter(); f.dateFormat = "yyyy-MM-dd"
        var seen = Set<String>()
        return moves.filter { $0.ticker == ticker && !followed.contains($0.who) }
            .sorted { $0.time > $1.time }
            .map { m in WhoEntry(who: m.who, side: m.side, action: m.side == "sell" ? "Sold" : "Bought",
                                 detail: [m.role, m.valueLabel].filter { !$0.isEmpty }.joined(separator: " · "), date: f.string(from: m.time), link: m.link) }
            .filter { seen.insert($0.who + $0.action + $0.date).inserted }
    }

    /// Anyone else who bought or sold this stock, looked up per ticker (cached 12 hours):
    /// members of Congress (Bargo, filtered to this ticker) and the company's own insiders (SEC Form 4).
    @MainActor
    static func lookup(_ ticker: String, model: AppModel) async -> [WhoEntry] {
        let t = ticker.uppercased()
        let url = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("others_" + t.map { $0.isLetter || $0.isNumber ? String($0) : "_" }.joined() + ".json")
        if let attrs = try? FileManager.default.attributesOfItem(atPath: url.path),
           let mod = attrs[.modificationDate] as? Date, Date().timeIntervalSince(mod) < 12 * 3600,
           let d = try? Data(contentsOf: url), let l = try? JSONDecoder().decode([WhoEntry].self, from: d) { return l }
        var out: [WhoEntry] = []
        if let trades = try? await Congress.recent(limit: 15, apiKey: model.bargoKey, ticker: t) {
            for c in trades where c.ticker == t {
                let sell = c.isSell
                let who = (c.chamber == "senate" ? "Senator" : "Representative") + (c.state.isEmpty ? "" : " (\(c.state))")
                out.append(WhoEntry(who: c.member, side: sell ? "sell" : "buy", action: sell ? "Sold" : "Bought",
                                    detail: [who, c.amountRange].filter { !$0.isEmpty }.joined(separator: " · "),
                                    date: c.transactionDate, link: Congress.sourceURL))
            }
        }
        let ua = model.userAgent
        if let cik = await model.cikMap()[t],
           let data = try? await Edgar.get(String(format: "https://data.sec.gov/submissions/CIK%010d.json", cik), ua: ua),
           let j = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
           let r = (j["filings"] as? [String: Any])?["recent"] as? [String: Any],
           let forms = r["form"] as? [String], let accs = r["accessionNumber"] as? [String], let dates = r["filingDate"] as? [String] {
            var checked = 0, found = 0
            for i in forms.indices where forms[i] == "4" {
                checked += 1
                if checked > 15 || found >= 6 { break }
                let acc = accs[i]
                let link = "https://www.sec.gov/Archives/edgar/data/\(cik)/\(acc.replacingOccurrences(of: "-", with: ""))/\(acc)-index.htm"
                guard let f4 = try? await Edgar.parseForm4(Filing(accession: acc, form: "4", title: "", link: link), ua: ua) else { continue }
                let buys = f4.trades.filter { $0.code == "P" }, sells = f4.trades.filter { $0.code == "S" }
                if buys.isEmpty && sells.isEmpty { continue }   // skip grants, tax withholding etc.
                found += 1
                let sell = !sells.isEmpty && sells.reduce(0) { $0 + $1.value } >= buys.reduce(0) { $0 + $1.value }
                let role = f4.roles.first.flatMap { $0.isEmpty ? nil : "Company insider: \($0)" } ?? "Company insider"
                out.append(WhoEntry(who: f4.owners.first.map(prettyName) ?? "Insider", side: sell ? "sell" : "buy", action: sell ? "Sold" : "Bought",
                                    detail: ([role] + Edgar.summarize(buys + sells)).joined(separator: " · "), date: i < dates.count ? dates[i] : "", link: link))
            }
        }
        out.sort { $0.date > $1.date }
        if let d = try? JSONEncoder().encode(out) { try? d.write(to: url) }
        return out
    }

    /// "MUSK ELON" -> "Elon Musk".
    private static func prettyName(_ raw: String) -> String {
        let parts = raw.split(separator: " ").map { $0.lowercased().capitalized }
        return parts.count >= 2 ? (parts.dropFirst() + [parts[0]]).joined(separator: " ") : parts.joined(separator: " ")
    }

    static func money(_ v: Double) -> String {
        if v >= 1e9 { return String(format: "$%.1fB", v / 1e9) }
        if v >= 1e6 { return String(format: "$%.1fM", v / 1e6) }
        if v >= 1e3 { return String(format: "$%.0fK", v / 1e3) }
        return String(format: "$%.0f", v)
    }
}
