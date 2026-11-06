import Foundation

/// A named group inside the watchlist, with the user's own notes.
struct WatchGroup: Codable, Hashable, Identifiable {
    var name: String
    var tickers: [String] = []
    var notes: String = ""
    var id: String { name }
}

/// Plain-language checkup of a group: what's in it, how spread out it is, and stocks to research.
/// Built from SEC industry codes and what the people you track hold. General info, not advice.
enum Portfolio {
    static let broad: [String: String] = [
        "VOO": "S&P 500 index fund", "SPY": "S&P 500 index fund", "IVV": "S&P 500 index fund", "SPLG": "S&P 500 index fund",
        "RSP": "Equal-weight S&P 500 fund", "VTI": "Total US market fund", "ITOT": "Total US market fund", "SCHB": "Total US market fund",
        "QQQ": "Nasdaq-100 fund", "QQQM": "Nasdaq-100 fund", "DIA": "Dow 30 fund", "IWM": "Small-company fund (Russell 2000)",
        "VT": "Whole-world stock fund", "VXUS": "International stock fund", "VEA": "Developed-markets fund", "VWO": "Emerging-markets fund",
        "SCHD": "Dividend stock fund", "VYM": "Dividend stock fund", "VUG": "Large growth fund", "VTV": "Large value fund",
        "FXAIX": "S&P 500 index fund", "SWPPX": "S&P 500 index fund", "VFIAX": "S&P 500 index fund", "VTSAX": "Total US market fund",
    ]
    static let bonds: [String: String] = ["BND": "US bond fund", "AGG": "US bond fund", "TLT": "Long-term Treasury fund", "IEF": "7-10 yr Treasury fund",
        "SHY": "Short-term Treasury fund", "SGOV": "Treasury bill fund", "BIL": "Treasury bill fund", "VGIT": "Treasury fund"]
    static let crypto: [String: String] = ["IBIT": "Bitcoin fund", "FBTC": "Bitcoin fund", "GBTC": "Bitcoin fund", "BITO": "Bitcoin futures fund",
        "ETHA": "Ethereum fund", "ETHE": "Ethereum fund", "COIN": "Crypto exchange", "MSTR": "Bitcoin treasury company"]
    static let gold: [String: String] = ["GLD": "Gold fund", "IAU": "Gold fund", "SLV": "Silver fund"]

    static func sector(_ ticker: String, _ industry: String) -> String {
        let t = ticker.uppercased()
        if broad[t] != nil { return "Broad index funds" }
        if bonds[t] != nil { return "Bonds" }
        if crypto[t] != nil { return "Crypto" }
        if gold[t] != nil { return "Gold & metals" }
        let i = industry.lowercased()
        func any(_ w: [String]) -> Bool { w.contains { i.contains($0) } }
        if i.isEmpty { return "Other / unknown" }
        if i.contains("semiconductor") { return "Chips" }
        if any(["software", "computer", "data processing", "electronic", "internet", "business services"]) { return "Technology" }
        if any(["pharmaceutical", "biological", "medical", "surgical", "health", "hospital", "diagnostic"]) { return "Healthcare" }
        if any(["bank", "finance", "insurance", "credit", "security brokers", "investment", "savings"]) { return "Financials" }
        if any(["petroleum", "crude", "oil", "natural gas", "drilling", "coal"]) { return "Energy" }
        if any(["electric services", "gas & other", "water supply", "cogeneration"]) { return "Utilities" }
        if any(["real estate", "reit"]) { return "Real estate" }
        if any(["telephone", "communications", "radiotelephone", "cable", "television", "motion picture", "periodicals"]) { return "Communications & media" }
        if any(["motor vehicle", "auto"]) { return "Autos" }
        if any(["aircraft", "guided missile", "ordnance", "space"]) { return "Aerospace & defense" }
        if any(["retail", "stores", "eating", "restaurant", "apparel", "beverage", "food", "tobacco", "soap", "cosmetic", "hotels"]) { return "Consumer" }
        if any(["mining", "metal", "chemical", "paper", "steel", "gold"]) { return "Materials" }
        return "Industrials"
    }

    static func describe(_ ticker: String, _ s: StockSummary?) -> String {
        let t = ticker.uppercased()
        if let d = broad[t] ?? bonds[t] ?? crypto[t] ?? gold[t] { return d }
        guard let s else { return "Loading…" }
        let ind = s.industry.isEmpty ? "" : s.industry.lowercased().prefix(1).uppercased() + s.industry.lowercased().dropFirst()
        let line = [s.name, ind].filter { !$0.isEmpty }.joined(separator: " · ")
        return line.isEmpty ? "No details found" : line
    }

    struct Idea: Hashable { let ticker: String; let sector: String; let why: String }
    struct Checkup { var sectors: [(String, Int)] = []; var notes: [String] = []; var ideas: [Idea] = []; var dayMove: Double? }

    static func checkup(_ tickers: [String], info: [String: StockSummary], live: [String: LiveQuote], picks: [Pick]) -> Checkup {
        let n = tickers.count
        guard n > 0 else { return Checkup() }
        var sec: [String: String] = [:]
        for t in tickers { sec[t] = sector(t, info[t]?.industry ?? "") }
        var countMap: [String: Int] = [:]
        for s in sec.values { countMap[s, default: 0] += 1 }
        let counts = countMap.sorted { $0.value > $1.value }.map { ($0.key, $0.value) }
        var notes: [String] = []
        let hasBroad = sec.values.contains("Broad index funds")
        let singles = sec.values.filter { !["Broad index funds", "Bonds", "Gold & metals"].contains($0) }.count

        if let top = counts.first, n >= 3, top.1 * 2 > n, top.0 != "Broad index funds" {
            notes.append("⚠ Concentrated: \(top.1) of \(n) are in \(top.0). If that area has a bad stretch, this whole group drops together.")
        }
        if !hasBroad && (1...9).contains(singles) {
            notes.append("⚠ Only \(singles) individual \(singles == 1 ? "stock" : "stocks") and no broad index fund. One bad earnings report can swing the whole group. Many investors anchor with a fund like VOO or VTI.")
        }
        if hasBroad && singles >= 1 { notes.append("✓ Has a broad index fund, which already spreads money across hundreds of companies.") }
        if counts.count >= 5 { notes.append("✓ Spread across \(counts.count) different areas.") }
        if n >= 2 && sec.values.filter({ $0 == "Crypto" }).count * 3 > n { notes.append("⚠ A big share is crypto, which can move 5-10% in a day.") }
        if n >= 5 && !sec.values.contains("Bonds") {
            notes.append("• No bonds. Bonds usually move less than stocks and soften big drops; whether you need them depends on your timeline.")
        }
        let overlap = Set(broad.keys).intersection(tickers.map { $0.uppercased() }).sorted()
        if overlap.count >= 2 { notes.append("• \(overlap.joined(separator: " and ")) hold many of the same companies, so owning both adds less spread than it looks.") }
        let covered = tickers.filter { t in picks.contains { $0.ticker == t } }
        if !covered.isEmpty { notes.append("• People you track also hold or bought: \(covered.joined(separator: ", ")).") }

        let moves = tickers.compactMap { live[$0]?.changePct }
        let dayMove = moves.isEmpty ? nil : moves.reduce(0, +) / Double(moves.count)

        let coveredSectors = Set(sec.values)
        var seen = Set<String>()
        var ideas: [Idea] = []
        for (t, who) in grouped(picks, excluding: tickers) {
            let s = sector(t, info[t]?.industry ?? "")
            if coveredSectors.contains(s) || s == "Other / unknown" || seen.contains(s) { continue }
            seen.insert(s)
            ideas.append(Idea(ticker: t, sector: s, why: "\(who.count) you track: " + who.prefix(2).joined(separator: ", ") + (who.count > 2 ? " +\(who.count - 2)" : "")))
            if ideas.count == 4 { break }
        }
        return Checkup(sectors: counts, notes: notes, ideas: ideas, dayMove: dayMove)
    }

    private static func grouped(_ picks: [Pick], excluding: [String]) -> [(String, [String])] {
        var m: [String: [String]] = [:]
        for p in picks where !p.ticker.isEmpty && !excluding.contains(p.ticker) {
            if !(m[p.ticker]?.contains(p.who) ?? false) { m[p.ticker, default: []].append(p.who) }
        }
        return m.sorted { $0.value.count > $1.value.count }.map { ($0.key, $0.value) }
    }

    static func ideaCandidates(_ tickers: [String], picks: [Pick]) -> [String] {
        grouped(picks, excluding: tickers).prefix(12).map(\.0)
    }
}
