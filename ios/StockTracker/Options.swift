import Foundation

/// One option contract's quote. Prices are per share; one contract covers 100 shares. Mirrors Options.kt.
struct OptQuote: Hashable {
    let expiry: Date, call: Bool, strike: Double, bid: Double, ask: Double, iv: Double?, delta: Double?, openInterest: Int, volume: Int
    var mid: Double { ask > 0 ? (bid + ask) / 2 : bid }
}

struct OptChain {
    let ticker: String, price: Double, asOf: String, source: String, quotes: [OptQuote]
    var expiries: [Date] { Array(Set(quotes.map(\.expiry))).sorted() }
}

/// One covered call to consider: what you'd collect, and what can happen.
struct CallIdea: Hashable {
    var label: String
    let q: OptQuote, days: Int, contracts: Int
    let income: Double, yieldPct: Double, annualPct: Double, ifCalledPct: Double, chanceCalled: Double, breakeven: Double, wideSpread: Bool
}

struct StockEvents { let earnings: Date?, exDiv: Date?, dividend: Double?, exDivGuessed: Bool }

/// Covered calls: sell someone the right to buy your shares at the strike by a set date, and get paid now.
/// Free option prices from CBOE's delayed quotes (15 minutes old), Nasdaq as a backup. Nothing here places a trade.
enum Options {
    static let rate = 0.04
    private static var cal: Calendar { var c = Calendar(identifier: .gregorian); c.timeZone = TimeZone(identifier: "America/New_York")!; return c }
    static func today() -> Date { cal.startOfDay(for: Date()) }
    static func days(_ from: Date, _ to: Date) -> Int { cal.dateComponents([.day], from: cal.startOfDay(for: from), to: cal.startOfDay(for: to)).day ?? 0 }

    // ---------------- math ----------------
    static func ncdf(_ x: Double) -> Double { 0.5 * (1 + erf(x / 2.0.squareRoot())) }
    static func callPrice(_ s: Double, _ k: Double, _ sigma: Double, _ t: Double) -> Double {
        let sq = sigma * t.squareRoot()
        let d1 = (log(s / k) + (rate + sigma * sigma / 2) * t) / sq
        return s * ncdf(d1) - k * exp(-rate * t) * ncdf(d1 - sq)
    }
    static func callDelta(_ s: Double, _ k: Double, _ sigma: Double, _ t: Double) -> Double {
        ncdf((log(s / k) + (rate + sigma * sigma / 2) * t) / (sigma * t.squareRoot()))
    }
    static func impliedVol(_ price: Double, _ s: Double, _ k: Double, _ t: Double) -> Double? {
        guard price > 0, t > 0 else { return nil }
        var lo = 0.01, hi = 4.0
        if callPrice(s, k, hi, t) < price { return nil }
        for _ in 0..<60 { let m = (lo + hi) / 2; if callPrice(s, k, m, t) > price { hi = m } else { lo = m } }
        return (lo + hi) / 2
    }

    // ---------------- data ----------------
    private static func http(_ url: String) async -> Data? {
        guard let u = URL(string: url) else { return nil }
        var req = URLRequest(url: u, timeoutInterval: 30)
        req.setValue("Mozilla/5.0 (iPhone) StockTracker", forHTTPHeaderField: "User-Agent")
        req.setValue("application/json", forHTTPHeaderField: "Accept")
        guard let (d, r) = try? await URLSession.shared.data(for: req), (r as? HTTPURLResponse)?.statusCode == 200 else { return nil }
        return d
    }

    private static var memo: (Date, OptChain)?
    static func chain(_ ticker: String) async -> OptChain? {
        let t = ticker.uppercased().trimmingCharacters(in: .whitespaces)
        if let (at, c) = memo, c.ticker == t, Date().timeIntervalSince(at) < 300 { return c }
        var c = await cboe(t)
        if c == nil { c = await nasdaq(t) }
        if let c { memo = (Date(), c) }
        return c
    }

    private static func ymd(_ s: String, _ fmt: String) -> Date? {
        let f = DateFormatter(); f.locale = Locale(identifier: "en_US_POSIX"); f.timeZone = cal.timeZone; f.dateFormat = fmt
        return f.date(from: s)
    }

    private static func cboe(_ t: String) async -> OptChain? {
        guard let d = await http("https://cdn.cboe.com/api/global/delayed_quotes/options/\(t.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? t).json"),
              let o = try? JSONSerialization.jsonObject(with: d) as? [String: Any], let data = o["data"] as? [String: Any],
              let opts = data["options"] as? [[String: Any]] else { return nil }
        let price = (data["current_price"] as? Double).flatMap { $0 > 0 ? $0 : nil } ?? (data["close"] as? Double ?? 0)
        var out: [OptQuote] = []
        for x in opts {
            guard let sym = x["option"] as? String, sym.count >= 15 else { continue }
            let tail = String(sym.suffix(15))
            guard let exp = ymd("20" + tail.prefix(6), "yyyyMMdd"), let k = Double(tail.suffix(8)) else { continue }
            let iv = x["iv"] as? Double ?? 0
            out.append(OptQuote(expiry: exp, call: tail[tail.index(tail.startIndex, offsetBy: 6)] == "C", strike: k / 1000,
                                bid: x["bid"] as? Double ?? 0, ask: x["ask"] as? Double ?? 0, iv: iv > 0 ? iv : nil,
                                delta: iv > 0 ? x["delta"] as? Double : nil,
                                openInterest: Int(x["open_interest"] as? Double ?? 0), volume: Int(x["volume"] as? Double ?? 0)))
        }
        guard !out.isEmpty, price > 0 else { return nil }
        return OptChain(ticker: t, price: price, asOf: (o["timestamp"] as? String ?? "") + " UTC (15-min delay)", source: "CBOE", quotes: out)
    }

    private static func nasdaq(_ t: String) async -> OptChain? {
        guard let d = await http("https://api.nasdaq.com/api/quote/\(t)/option-chain?assetclass=stocks&limit=1000&fromdate=all&todate=undefined&excode=oprac&callput=callput&money=all&type=all"),
              let o = try? JSONSerialization.jsonObject(with: d) as? [String: Any], let data = o["data"] as? [String: Any],
              let rows = (data["table"] as? [String: Any])?["rows"] as? [[String: Any]] else { return nil }
        let last = data["lastTrade"] as? String ?? ""
        guard let r = last.range(of: #"\$([\d.,]+)"#, options: .regularExpression),
              let price = Double(last[r].dropFirst().replacingOccurrences(of: ",", with: "")) else { return nil }
        func num(_ v: Any?) -> Double { Double((v as? String ?? "").replacingOccurrences(of: ",", with: "")) ?? 0 }
        var exp: Date?
        var out: [OptQuote] = []
        for row in rows {
            if let g = row["expirygroup"] as? String, !g.isEmpty { exp = ymd(g, "MMMM d, yyyy"); continue }
            guard let e = exp else { continue }
            let k = num(row["strike"]); guard k > 0 else { continue }
            let bid = num(row["c_Bid"]), ask = num(row["c_Ask"])
            let years = Double(max(1, days(Date(), e))) / 365
            let iv = impliedVol(ask > 0 ? (bid + ask) / 2 : bid, price, k, years)
            out.append(OptQuote(expiry: e, call: true, strike: k, bid: bid, ask: ask, iv: iv, delta: iv.map { callDelta(price, k, $0, years) },
                                openInterest: Int(num(row["c_Openinterest"])), volume: Int(num(row["c_Volume"]))))
        }
        return out.isEmpty ? nil : OptChain(ticker: t, price: price, asOf: "Nasdaq (delayed)", source: "Nasdaq", quotes: out)
    }

    /// Next earnings report (Finnhub, with your key) and next ex-dividend day (Massive, with your key).
    static func events(_ ticker: String, massiveKey: String) async -> StockEvents {
        let t = ticker.uppercased()
        let now = today()
        let iso = { (d: Date) -> String in let f = DateFormatter(); f.locale = Locale(identifier: "en_US_POSIX"); f.timeZone = cal.timeZone; f.dateFormat = "yyyy-MM-dd"; return f.string(from: d) }
        var earnings: Date?
        if !Live.key.isEmpty, let d = await http("https://finnhub.io/api/v1/calendar/earnings?symbol=\(t)&from=\(iso(now))&to=\(iso(now.addingTimeInterval(120 * 86400)))&token=\(Live.key)"),
           let o = try? JSONSerialization.jsonObject(with: d) as? [String: Any], let a = o["earningsCalendar"] as? [[String: Any]] {
            earnings = a.compactMap { ($0["date"] as? String).flatMap { ymd($0, "yyyy-MM-dd") } }.filter { $0 >= now }.min()
        }
        var exDiv: Date?, amount: Double?, guessed = false
        if !massiveKey.isEmpty, let d = await http("https://api.massive.com/v3/reference/dividends?ticker=\(t)&order=desc&sort=ex_dividend_date&limit=6&apiKey=\(massiveKey)"),
           let o = try? JSONSerialization.jsonObject(with: d) as? [String: Any], let a = o["results"] as? [[String: Any]] {
            let list = a.compactMap { x -> (Date, Double)? in (x["ex_dividend_date"] as? String).flatMap { ymd($0, "yyyy-MM-dd") }.map { ($0, x["cash_amount"] as? Double ?? 0) } }
                .sorted { $0.0 < $1.0 }
            if let next = list.first(where: { $0.0 >= now }) { exDiv = next.0; amount = next.1 }
            else if list.count >= 2 {
                let gap = max(28, days(list[list.count - 2].0, list.last!.0))
                var g = list.last!.0
                while g < now { g = g.addingTimeInterval(Double(gap) * 86400) }
                exDiv = g; amount = list.last!.1; guessed = true
            }
        }
        return StockEvents(earnings: earnings, exDiv: exDiv, dividend: amount, exDivGuessed: guessed)
    }

    // ---------------- ideas ----------------
    static func goodExpiries(_ c: OptChain) -> [Date] {
        let now = Date()
        let all = c.expiries.filter { days(now, $0) >= 7 }
        let sweet = all.filter { (21...60).contains(days(now, $0)) }
        return sweet.isEmpty ? Array(all.prefix(3)) : sweet
    }
    static func defaultExpiry(_ c: OptChain) -> Date? { goodExpiries(c).min { abs(days(Date(), $0) - 35) < abs(days(Date(), $1) - 35) } }

    static func calls(_ c: OptChain, expiry: Date, shares: Double) -> [CallIdea] {
        let d = max(1, days(Date(), expiry))
        let contracts = max(0, Int(shares / 100))
        return c.quotes.filter { $0.call && $0.expiry == expiry && $0.strike > c.price && $0.bid >= 0.05 }
            .sorted { $0.strike < $1.strike }
            .map { q in
                let delta = q.delta ?? q.iv.map { callDelta(c.price, q.strike, $0, Double(d) / 365) } ?? 0
                let y = q.bid / c.price
                return CallIdea(label: "", q: q, days: d, contracts: contracts, income: q.bid * 100 * Double(contracts), yieldPct: y,
                                annualPct: y * 365 / Double(d), ifCalledPct: (q.strike - c.price + q.bid) / c.price, chanceCalled: delta,
                                breakeven: c.price - q.bid, wideSpread: q.ask > 0 && (q.ask - q.bid) / q.mid > 0.25)
            }
            .filter { (0.04...0.5).contains($0.chanceCalled) }
    }

    static func picks(_ all: [CallIdea]) -> [CallIdea] {
        let targets = [(0.15, "Safer: keep your shares more often"), (0.25, "Balanced"), (0.35, "More income, more likely called away")]
        var used = Set<Double>()
        return targets.compactMap { d, label in
            guard var best = all.filter({ !used.contains($0.q.strike) && abs($0.chanceCalled - d) <= 0.08 })
                .min(by: { abs($0.chanceCalled - d) < abs($1.chanceCalled - d) }) else { return nil }
            used.insert(best.q.strike); best.label = label
            return best
        }
    }

    static func warnings(_ c: OptChain, _ idea: CallIdea, _ ev: StockEvents?, _ grade: StockGrade?, shares: Double) -> [String] {
        var w: [String] = []
        let f = DateFormatter(); f.dateFormat = "MMM d"; f.timeZone = cal.timeZone
        if shares < 100 { w.append("You need 100 shares for each call. You have \(Int(shares)); 100 shares cost about \(money(c.price * 100)) today.") }
        if let e = ev?.earnings, e <= idea.q.expiry {
            w.append("Earnings come out \(f.string(from: e)), before this call expires. Big jumps often happen on earnings day; a jump above the strike means your shares get sold at the strike.")
        }
        if let ev, let x = ev.exDiv, x <= idea.q.expiry {
            let amt = ev.dividend.map { " (\(money($0)) a share)" } ?? ""
            w.append("Ex-dividend day \(ev.exDivGuessed ? "is usually around" : "is") \(f.string(from: x))\(amt), before expiry. You keep the dividend if you still own the shares that morning, but if the stock is above the strike by then, the buyer may take your shares the day before to get the dividend.")
        }
        if ev == nil || (ev?.earnings == nil && ev?.exDiv == nil) {
            w.append("Couldn't check earnings and dividend dates (add Finnhub and Massive keys in Settings). Look them up before selling.")
        }
        if let g = grade, g.growth.hasPrefix("A") {
            w.append("Your Growth grade for this stock is \(g.growth). Covered calls cap your gain at the strike, which costs the most on strong growers. A higher strike (\"Safer\") gives up less.")
        }
        if idea.wideSpread { w.append("The bid (\(money(idea.q.bid))) and ask (\(money(idea.q.ask))) are far apart. Use a limit order near the middle (\(money(idea.q.mid))) instead of selling at the market.") }
        if idea.q.openInterest < 50 { w.append("Few contracts are open at this strike (\(idea.q.openInterest)), so prices can be less fair.") }
        w.append("If the shares are called away, that's a sale: in a taxable account you may owe tax on the gain.")
        return w
    }

    static func money(_ v: Double) -> String { abs(v) >= 1000 ? "$" + Int(v.rounded()).formatted() : String(format: "$%.2f", v) }
    static func pct(_ v: Double) -> String { String(format: "%.1f%%", v * 100) }

    static let guide: [(String, String)] = [
        ("What's an option?", "A contract about 100 shares of a stock. A CALL gives its buyer the right to buy those 100 shares at a set price (the strike) until a set date (the expiration). A PUT gives the right to sell. The price of the contract is the premium, quoted per share: a $1.20 premium is $120 for one contract."),
        ("What's a covered call?", "You own 100 shares and SELL one call on them. The buyer pays you the premium today, and it's yours whatever happens. In return, you promise to sell your 100 shares at the strike if the buyer asks. \"Covered\" means you already own the shares, so you can always keep that promise."),
        ("What can happen by expiration", "1. The stock stays below the strike: the call expires worthless, you keep the premium AND your shares, and you can sell another call.\n2. The stock ends above the strike: your shares are sold at the strike (\"called away\"). You keep the premium plus the rise up to the strike, but you miss anything above it.\n3. The stock falls: you still own the shares and lose on them like any owner. The premium only softens the fall a little."),
        ("Picking a strike", "Higher strike = smaller premium but less chance of losing your shares. Lower strike = more premium, more likely called away. The app shows each strike's chance of being called away, using the options market's own estimate (called delta). Many people sell strikes with about a 15 to 30% chance."),
        ("Picking an expiration", "Calls 3 to 8 weeks out are the usual sweet spot: they pay more per day than far-out ones and give you a chance to adjust each month. Weekly calls pay little per trade and need constant attention."),
        ("Reading the numbers", "Income: the premium for all your contracts, at the bid (what buyers pay right now).\nYearly rate: the premium as a % of the stock price, scaled to a year, if you could repeat it every month. It's a rate, not a promise.\nIf called away: your total gain if your shares are sold at the strike.\nBreak-even: how far the stock can fall before you're behind, counting the premium."),
        ("The risks", "• You give up big gains above the strike, which hurts most on fast-rising stocks.\n• You still carry all of the stock's downside.\n• Your shares can be taken early, most often the day before an ex-dividend day.\n• Shares called away count as a sale for taxes.\n• Selling at the market price when bid and ask are far apart gives money away: use a limit order."),
        ("What the history test says", "The app tested selling a call every month for 10 years on each stock (see the history line on each stock). Calls tended to beat simply holding on slow, steady stocks, and almost never on fast growers, where capping the upside cost more than the premiums paid. They did lower the worst drops a little. It's a simulation with estimated option prices, not a record of real trades."),
        ("Ending a call early", "You don't have to wait for expiration. You can BUY THE CALL BACK (\"buy to close\") at any time: if it has lost most of its value, buying it back cheaply frees your shares to sell a new call. If the stock is near the strike and you want to keep your shares, you can buy it back and sell a later one (\"rolling\")."),
        ("Doing it on Robinhood", "1. Get options approval: Account → menu → Investing → Options trading. Covered calls are in Robinhood's lowest options level.\n2. Open the stock → Trade → Trade options.\n3. Choose Sell and Call, pick the expiration, then the strike.\n4. Use a limit price at or a bit below the middle of bid and ask, and quantity = number of 100-share lots you own.\n5. Review and submit. Robinhood holds 100 of your shares per call until it ends."),
        ("Not advice", "This explains how covered calls work and shows live prices. It isn't a recommendation to trade. Options carry real risk; read the Options Disclosure Document your broker gives you before trading."),
    ]
}
