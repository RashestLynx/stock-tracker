import Foundation

struct ScoredStock: Codable, Identifiable {
    var ticker: String
    var name: String
    var sector: String
    var score: Int          // 0-100
    var price: Double
    var facts: [String]     // short plain-English reasons, e.g. "Sales +32% in a year"
    var id: String { ticker }
}

struct ScoresResult: Codable {
    var priceDate: String
    var fiscalYear: Int
    var screened: Int       // how many companies were scored
    var growth: [ScoredStock]
    var income: [ScoredStock]
    var wealth: [ScoredStock]
    var fetchedAt: Date
}

struct ScoreCompany {
    let cik: Int
    let ticker: String
    let name: String
}

struct ScoreCandidate {
    let company: ScoreCompany
    let score: Double
    let price: Double
    let facts: [String]
    let profitPayout: Double?   // dividends as a share of profit (income list only)
}

struct ScoreRanking {
    let growth: [ScoreCandidate]
    let income: [ScoreCandidate]
    let wealth: [ScoreCandidate]
    let screened: Int
}

typealias ScorePrices = [String: (close: Double, volume: Double)]

fileprivate final class ScoreRow {
    let c: ScoreCompany
    let price: Double
    var marketCap = 0.0
    var dollarVolume = 0.0
    var momentum: Double?       // price change from a year ago to a month ago
    var change12: Double?       // price change over the last year
    var rev: [Int: Double] = [:]
    var ni: [Int: Double] = [:]
    var div: [Int: Double] = [:]
    var revGrowth1: Double?
    var revCagr3: Double?
    var revCagr5: Double?
    var epsGrowth: Double?
    var marginTrend: Double?
    var fcf: Double?
    var fcfMargin: Double?
    var fcfConversion: Double?
    var roe: Double?
    var roa: Double?
    var grossProfitability: Double?
    var equityRatio: Double?
    var marginSwing: Double?
    var niCagr5: Double?
    var consistency: Double?
    var dilution: Double?
    var profitableYears = 0
    var yieldPct: Double?
    var divCagr5: Double?
    var divStreak: Double?
    var divYears = 0
    var fcfPayout: Double?
    var profitPayout: Double?
    var fcfToLiabilities: Double?
    var divVsAverage: Double?

    init(_ c: ScoreCompany, price: Double) {
        self.c = c
        self.price = price
    }
}

fileprivate final class ScoreCounter { var n = 0 }

fileprivate struct ScoreMetric {
    let weight: Double
    let higherIsBetter: Bool
    let required: Bool
    let get: (ScoreRow) -> Double?

    init(_ weight: Double, _ higherIsBetter: Bool, _ required: Bool, _ get: @escaping (ScoreRow) -> Double?) {
        self.weight = weight
        self.higherIsBetter = higherIsBetter
        self.required = required
        self.get = get
    }
}

/// Scores every U.S. company three ways and ranks them:
///  - Growth: sales and profit rising fast, margins improving, price trending up.
///  - Income: a solid dividend that has been paid for 10 years, is covered by cash, and keeps growing.
///  - Wealth: steady compounders: high profitability, low debt, reliable growth year after year.
///
/// Each measure is turned into a 0-100 percentile against every other company that passed the
/// basic filters, then combined with fixed weights. The filters keep out penny stocks, tiny or
/// thinly traded companies, shells and obvious data errors. Mirrors Scores.kt in the Android app.
enum ScoreEngine {
    static let revenueTags = ["Revenues", "RevenueFromContractWithCustomerExcludingAssessedTax"]
    static let dividendTags = ["PaymentsOfDividendsCommonStock", "PaymentsOfDividends"]
    private static let million = 1_000_000.0

    /// `annual` is keyed "Tag/2025" and holds one full-year number per SEC company number;
    /// `balance` is keyed by tag and holds the latest balance-sheet number.
    static func rank(year y: Int, companies: [ScoreCompany],
                     annual: [String: [Int: Double]], balance: [String: [Int: Double]],
                     now: ScorePrices, monthAgo: ScorePrices, yearAgo: ScorePrices) -> ScoreRanking {
        func one(_ tag: String, _ year: Int, _ cik: Int) -> Double? {
            guard let v = annual["\(tag)/\(year)"]?[cik], v.isFinite else { return nil }
            return v
        }
        /// Year -> value, using whichever tag the company reports most consistently.
        func series(_ tags: [String], _ cik: Int, _ years: ClosedRange<Int>) -> [Int: Double] {
            var best: [Int: Double] = [:]
            for tag in tags {
                var m: [Int: Double] = [:]
                for yr in years { if let v = one(tag, yr, cik) { m[yr] = v } }
                if m.count > best.count { best = m }
            }
            return best
        }
        let assets = balance["Assets"] ?? [:]
        let equity = balance["StockholdersEquity"] ?? [:]

        var rows: [ScoreRow] = []
        for c in companies {
            let key = c.ticker.replacingOccurrences(of: "-", with: ".")
            guard let px = now[key] else { continue }
            let close = px.close
            if close < 5 { continue }
            guard let month = monthAgo[key] else { continue }   // skips brand-new listings
            let r = ScoreRow(c, price: close)
            r.dollarVolume = min(close * px.volume, month.close * month.volume)
            if let old = yearAgo[key]?.close, old > 0 {
                r.momentum = month.close / old - 1
                r.change12 = close / old - 1
            }

            r.rev = series(revenueTags, c.cik, (y - 5)...y)
            r.ni = series(["NetIncomeLoss"], c.cik, (y - 5)...y)
            let rev = r.rev[y]
            guard let ni = r.ni[y] else { continue }
            // A profit far bigger than sales is almost always a mislabeled number in the filing.
            if let rv = rev, abs(ni) > 1.5 * abs(rv) { continue }

            let eps = one("EarningsPerShareDiluted", y, c.cik)
            let epsPrev = one("EarningsPerShareDiluted", y - 1, c.cik)
            let sharesNow = one("WeightedAverageNumberOfDilutedSharesOutstanding", y, c.cik)
            var implied: Double? = nil
            if let e = eps, e != 0, ni / e > 0 { implied = ni / e }
            var shares = sharesNow
            if shares == nil || shares! <= 0 {
                shares = implied
            } else if let i = implied, shares! / i > 2 || shares! / i < 0.5 {
                shares = i
            }
            guard let sh = shares, sh > 0 else { continue }
            r.marketCap = sh * close
            // A company "worth" less than two years of profit means the share count or profit is wrong.
            if ni > 0 && r.marketCap < 2 * ni { continue }

            // ---- growth ----
            if let rv = rev, let prev = r.rev[y - 1], prev > 0 { r.revGrowth1 = rv / prev - 1 }
            r.revCagr3 = cagr(r.rev[y - 3], rev, 3)
            r.revCagr5 = cagr(r.rev[y - 5], rev, 5)
            if let e = eps, let p = epsPrev {
                if e <= 0 {
                    r.epsGrowth = -1
                } else if p <= 0 {
                    r.epsGrowth = 0.5   // swung from a loss to a profit
                } else {
                    r.epsGrowth = min(max(e / p - 1, -1), 3)
                }
            }
            if let op = one("OperatingIncomeLoss", y, c.cik), let opOld = one("OperatingIncomeLoss", y - 2, c.cik),
               let rv = rev, let rvOld = r.rev[y - 2], rv > 0, rvOld > 0 {
                r.marginTrend = op / rv - opOld / rvOld
            }

            // ---- cash flow ----
            var fcfSum = 0.0
            var niSum = 0.0
            var fcfYears = 0
            for yr in (y - 2)...y {
                guard let ocf = one("NetCashProvidedByUsedInOperatingActivities", yr, c.cik) else { continue }
                let capex = one("PaymentsToAcquirePropertyPlantAndEquipment", yr, c.cik) ?? 0
                guard let n = r.ni[yr] else { continue }
                if yr == y { r.fcf = ocf - abs(capex) }
                fcfSum += ocf - abs(capex)
                niSum += n
                fcfYears += 1
            }
            let fcf = r.fcf
            if let f = fcf, let rv = rev, rv > 0 { r.fcfMargin = f / rv }
            if fcfYears == 3 && niSum > 0 { r.fcfConversion = min(max(fcfSum / niSum, -1), 1.5) }

            // ---- balance sheet ----
            if let a = assets[c.cik], a > 0 {
                r.roa = ni / a
                if let e = equity[c.cik] {
                    r.equityRatio = e / a
                    // Return on equity is meaningless when equity is close to zero or negative.
                    if e / a >= 0.05 { r.roe = ni / e }
                    let liabilities = a - e
                    if let f = fcf { r.fcfToLiabilities = liabilities <= 0 ? 10 : min(f / liabilities, 10) }
                }
                var gp = one("GrossProfit", y, c.cik)
                if gp == nil, let rv = rev,
                   let cost = one("CostOfRevenue", y, c.cik) ?? one("CostOfGoodsAndServicesSold", y, c.cik) {
                    gp = rv - cost
                }
                if let g = gp { r.grossProfitability = g / a }
            }

            // ---- steadiness over the last six years ----
            r.profitableYears = r.ni.values.filter { $0 > 0 }.count
            r.niCagr5 = cagr(r.ni[y - 5], ni, 5)
            var margins: [Double] = []
            for yr in (y - 5)...y {
                if let rv = r.rev[yr], let n = r.ni[yr], rv > 0 { margins.append(n / rv) }
            }
            if margins.count >= 5 { r.marginSwing = stdev(margins) }
            var steps = 0
            var ups = 0
            for yr in (y - 4)...y {
                if let r0 = r.rev[yr - 1], let r1 = r.rev[yr] { steps += 1; if r1 > r0 { ups += 1 } }
                if let n0 = r.ni[yr - 1], let n1 = r.ni[yr] { steps += 1; if n1 > n0 { ups += 1 } }
            }
            if steps >= 8 { r.consistency = Double(ups) / Double(steps) }
            if let prev = one("WeightedAverageNumberOfDilutedSharesOutstanding", y - 1, c.cik), let cur = sharesNow, prev > 0 {
                let change = cur / prev - 1
                if change > -0.3 && change < 0.4 { r.dilution = change }   // bigger jumps are stock splits
            }

            // ---- dividends (total cash paid, so stock splits don't distort it) ----
            r.div = series(dividendTags, c.cik, (y - 9)...y).mapValues { abs($0) }
            if let d = r.div[y], d > 0 {
                r.yieldPct = d / r.marketCap
                r.divYears = r.div.values.filter { $0 > 0 }.count
                if r.div.values.contains(where: { $0 <= 0 }) { r.divYears = 0 }
                r.divCagr5 = cagr(r.div[y - 5], d, 5)
                var streak = 0
                var yr = y
                while yr >= y - 8 {
                    guard let cur = r.div[yr], let prev = r.div[yr - 1], cur >= prev * 0.97 else { break }
                    streak += 1
                    yr -= 1
                }
                r.divStreak = Double(streak)
                var earlier: [Double] = []
                for k in (y - 4)...(y - 1) { if let v = r.div[k] { earlier.append(v) } }
                if !earlier.isEmpty { r.divVsAverage = d / (earlier.reduce(0, +) / Double(earlier.count)) }
                if let f = fcf, f > 0 { r.fcfPayout = d / f }
                if ni > 0 { r.profitPayout = d / ni }
            }
            rows.append(r)
        }

        // ---------------- Growth ----------------
        let growthRows = rows.filter { r in
            guard let prev = r.rev[y - 1], let g = r.revGrowth1 else { return false }
            return r.marketCap >= 300 * million && r.dollarVolume >= 2 * million && prev >= 50 * million && g > 0
        }
        let growth: [ScoreCandidate] = composite(growthRows, [
            ScoreMetric(25, true, true) { $0.revGrowth1 },
            ScoreMetric(20, true, false) { $0.revCagr3 },
            ScoreMetric(15, true, false) { $0.epsGrowth },
            ScoreMetric(10, true, false) { $0.marginTrend },
            ScoreMetric(20, true, false) { $0.momentum },
            ScoreMetric(10, true, false) { $0.fcfMargin },
        ]).map { pair in
            let r = pair.0
            var facts: [String] = []
            if let v = r.revGrowth1 { facts.append("Sales \(pct(v)) in a year") }
            if let v = r.revCagr3 { facts.append("\(pct(v)) a year over 3 years") }
            if let v = r.change12 { facts.append("Price \(pct(v)) in 12 months") }
            return ScoreCandidate(company: r.c, score: pair.1, price: r.price, facts: facts, profitPayout: nil)
        }

        // ---------------- Income ----------------
        let incomeRows = rows.filter { r in
            guard let yld = r.yieldPct, let payout = r.fcfPayout, let vsAvg = r.divVsAverage else { return false }
            if let change = r.change12, change <= -0.35 { return false }
            return r.marketCap >= 1000 * million && r.dollarVolume >= 3 * million
                && yld >= 0.015 && yld <= 0.10      // above 10% is usually a dividend about to be cut
                && r.divYears >= 9                  // paid every year for a decade (one gap in the data allowed)
                && (r.ni[y] ?? 0) > 0
                && payout <= 1.0                    // covered by the cash the business makes
                && vsAvg >= 0.9                     // not recently cut
        }
        let income: [ScoreCandidate] = composite(incomeRows, [
            ScoreMetric(25, true, true) { $0.yieldPct },
            ScoreMetric(20, true, false) { $0.divCagr5 },
            ScoreMetric(15, true, false) { $0.divStreak },
            ScoreMetric(15, false, false) { $0.fcfPayout },
            ScoreMetric(15, true, false) { $0.fcfToLiabilities },
            ScoreMetric(10, true, false) { $0.roe },
        ]).map { pair in
            let r = pair.0
            var facts: [String] = []
            if let v = r.yieldPct { facts.append(String(format: "Yield %.1f%%", v * 100)) }
            if let v = r.fcfPayout { facts.append(String(format: "Pays out %.0f%% of its cash flow", v * 100)) }
            if let v = r.divCagr5 { facts.append("Dividend \(pct(v)) a year") }
            return ScoreCandidate(company: r.c, score: pair.1, price: r.price, facts: facts, profitPayout: r.profitPayout)
        }

        // ---------------- Wealth ----------------
        let wealthRows = rows.filter { r in
            guard let cur = r.rev[y], let old = r.rev[y - 5] else { return false }
            return r.marketCap >= 2000 * million && r.dollarVolume >= 5 * million
                && r.ni.count >= 6 && r.profitableYears >= 5 && (r.ni[y] ?? 0) > 0
                && r.rev.count >= 6 && cur > old && cur >= 50 * million
                && (r.equityRatio ?? 0) > 0
        }
        let wealth: [ScoreCandidate] = composite(wealthRows, [
            ScoreMetric(15, true, true) { $0.roe },
            ScoreMetric(10, true, false) { $0.roa },
            ScoreMetric(10, true, false) { $0.grossProfitability },
            ScoreMetric(10, false, false) { $0.marginSwing },
            ScoreMetric(10, true, false) { $0.equityRatio },
            ScoreMetric(10, true, false) { $0.revCagr5 },
            ScoreMetric(10, true, false) { $0.niCagr5 },
            ScoreMetric(10, true, false) { $0.consistency },
            ScoreMetric(10, true, false) { $0.fcfConversion },
            ScoreMetric(5, false, false) { $0.dilution },
        ]).map { pair in
            let r = pair.0
            var facts: [String] = []
            if let v = r.roe { facts.append(String(format: "Earns %.0f%% on shareholders' money", v * 100)) }
            if let v = r.revCagr5 { facts.append("Sales \(pct(v)) a year over 5 years") }
            facts.append("Profitable \(r.profitableYears) of the last \(r.ni.count) years")
            return ScoreCandidate(company: r.c, score: pair.1, price: r.price, facts: facts, profitPayout: nil)
        }
        return ScoreRanking(growth: growth, income: income, wealth: wealth, screened: rows.count)
    }

    /// Picks the final 10 per list. A stock appears on one list only (wealth is filled first, then
    /// income, then growth), and no more than 3 per list come from the same industry group.
    /// `industries` maps an SEC company number to its industry code and name.
    static func select(_ ranking: ScoreRanking, industries: [Int: (code: Int, name: String)])
        -> (growth: [ScoredStock], income: [ScoredStock], wealth: [ScoredStock]) {
        var taken = Set<Int>()
        func pick(_ list: [ScoreCandidate], isIncome: Bool) -> [ScoredStock] {
            var out: [ScoredStock] = []
            var perGroup: [Int: Int] = [:]
            for c in list {
                if out.count == 10 { break }
                if taken.contains(c.company.cik) { continue }
                let sic = industries[c.company.cik]
                let code = sic?.code ?? 0
                if code == 6770 { continue }   // blank-check shell companies
                // Property trusts are allowed to pay out more than their accounting profit.
                if isIncome, code != 6798, let payout = c.profitPayout, payout > 0.9 { continue }
                let group = code / 100
                if group > 0 && (perGroup[group] ?? 0) >= 3 { continue }
                perGroup[group] = (perGroup[group] ?? 0) + 1
                taken.insert(c.company.cik)
                let score = min(max(Int(c.score.rounded()), 0), 100)
                out.append(ScoredStock(ticker: c.company.ticker, name: c.company.name, sector: sic?.name ?? "",
                                       score: score, price: c.price, facts: c.facts))
            }
            return out
        }
        let wealth = pick(ranking.wealth, isIncome: false)
        let income = pick(ranking.income, isIncome: true)
        let growth = pick(ranking.growth, isIncome: false)
        return (growth, income, wealth)
    }

    /// Weighted average of each measure's percentile (0-100) among `rows`, best first.
    private static func composite(_ rows: [ScoreRow], _ metrics: [ScoreMetric]) -> [(ScoreRow, Double)] {
        var sum = [Double](repeating: 0, count: rows.count)
        var weight = [Double](repeating: 0, count: rows.count)
        var ok = [Bool](repeating: true, count: rows.count)
        for m in metrics {
            var vals: [(Int, Double)] = []
            for i in rows.indices {
                if let v = m.get(rows[i]), v.isFinite { vals.append((i, v)) }
            }
            vals.sort { $0.1 < $1.1 }
            var has = [Bool](repeating: false, count: rows.count)
            for (k, item) in vals.enumerated() {
                var p = vals.count == 1 ? 50.0 : Double(k) * 100.0 / Double(vals.count - 1)
                if !m.higherIsBetter { p = 100 - p }
                sum[item.0] += p * m.weight
                weight[item.0] += m.weight
                has[item.0] = true
            }
            if m.required {
                for i in rows.indices where !has[i] { ok[i] = false }
            }
        }
        let total = metrics.reduce(0.0) { $0 + $1.weight }
        // Needs most of the measures present; a missing number is never counted as zero.
        var out: [(ScoreRow, Double)] = []
        for i in rows.indices where ok[i] && weight[i] >= 0.7 * total {
            out.append((rows[i], sum[i] / weight[i]))
        }
        out.sort { $0.1 > $1.1 }
        return out
    }

    private static func cagr(_ from: Double?, _ to: Double?, _ years: Int) -> Double? {
        guard let f = from, let t = to, f > 0, t > 0 else { return nil }
        return pow(t / f, 1.0 / Double(years)) - 1
    }

    private static func stdev(_ v: [Double]) -> Double {
        let mean = v.reduce(0, +) / Double(v.count)
        let sq = v.reduce(0.0) { $0 + ($1 - mean) * ($1 - mean) }
        return (sq / Double(v.count)).squareRoot()
    }

    private static func pct(_ v: Double) -> String { String(format: "%+.0f%%", v * 100) }
}

/// Downloads what `ScoreEngine` needs, straight from the phone:
///  - company financials from the SEC's free "frames" data (one request returns one number for
///    every U.S. company, so about 57 requests cover the whole market; kept for a week),
///  - closing prices from Massive (three requests: latest day, a month ago, a year ago).
enum Scores {
    static let frameCount = 57
    private static let week: TimeInterval = 7 * 86_400

    static func load(key: String, ua: String, progress: @escaping (String) -> Void) async throws -> ScoresResult {
        guard !key.isEmpty else { throw AppError("Add a free Massive key first") }
        guard ua.contains("@") else { throw AppError("Add your email in Settings first") }
        let cal = Calendar(identifier: .gregorian)

        progress("Getting prices…")
        Movers.resetCalls()
        guard let latest = try await Movers.findDay(from: Date(), key: key, maxTries: 7) else {
            throw AppError("No recent market data available")
        }
        let latestDate = Movers.dayFormatter.date(from: latest.date) ?? Date()
        let monthStart = cal.date(byAdding: .day, value: -30, to: latestDate) ?? latestDate
        let yearStart = cal.date(byAdding: .day, value: -365, to: latestDate) ?? latestDate
        guard let month = try await Movers.findDay(from: monthStart, key: key, maxTries: 5) else {
            throw AppError("No market data for last month")
        }
        guard let year = try await Movers.findDay(from: yearStart, key: key, maxTries: 5) else {
            throw AppError("No market data for last year")
        }

        progress("Getting the company list…")
        let companies = try await loadCompanies(ua: ua)

        let done = ScoreCounter()
        func fetch(_ tag: String, _ period: String) async throws -> [Int: Double] {
            let unit: String
            switch tag {
            case "EarningsPerShareDiluted": unit = "USD-per-shares"
            case "WeightedAverageNumberOfDilutedSharesOutstanding": unit = "shares"
            default: unit = "USD"
            }
            let m = try await frame(ua: ua, tag: tag, unit: unit, period: period)
            done.n += 1
            progress("Reading company financials… \(min(done.n, frameCount)) of \(frameCount)")
            return m
        }

        // Use last year's annual reports once most companies have filed them, otherwise the year before.
        var fy = cal.component(.year, from: Date()) - 1
        if try await fetch("NetIncomeLoss", "CY\(fy)").count < 2500 { fy -= 1; done.n = 0 }
        let y = fy

        var wanted: [(String, Int)] = []
        for yr in (y - 5)...y {
            wanted.append(("NetIncomeLoss", yr))
            for tag in ScoreEngine.revenueTags { wanted.append((tag, yr)) }
        }
        for yr in (y - 9)...y {
            for tag in ScoreEngine.dividendTags { wanted.append((tag, yr)) }
        }
        for yr in (y - 2)...y {
            wanted.append(("NetCashProvidedByUsedInOperatingActivities", yr))
            wanted.append(("PaymentsToAcquirePropertyPlantAndEquipment", yr))
        }
        for yr in [y - 1, y] {
            wanted.append(("EarningsPerShareDiluted", yr))
            wanted.append(("WeightedAverageNumberOfDilutedSharesOutstanding", yr))
        }
        wanted.append(("OperatingIncomeLoss", y - 2))
        wanted.append(("OperatingIncomeLoss", y))
        for tag in ["GrossProfit", "CostOfRevenue", "CostOfGoodsAndServicesSold"] { wanted.append((tag, y)) }

        var annual: [String: [Int: Double]] = [:]
        for (tag, yr) in wanted {
            annual["\(tag)/\(yr)"] = try await fetch(tag, "CY\(yr)")
        }

        // Latest quarter-end balance sheets that most companies have filed (about 2 months after quarter end).
        func quarter(_ d: Date) -> String {
            let c = cal.dateComponents([.year, .month], from: d)
            return "CY\(c.year ?? y)Q\(((c.month ?? 1) - 1) / 3 + 1)I"
        }
        let olderDate = cal.date(byAdding: .day, value: -150, to: Date()) ?? Date()
        let newerDate = cal.date(byAdding: .month, value: 3, to: olderDate) ?? olderDate
        var balance: [String: [Int: Double]] = [:]
        for tag in ["Assets", "StockholdersEquity"] {
            var m = try await fetch(tag, quarter(olderDate))
            let newer = try await fetch(tag, quarter(newerDate))
            for (k, v) in newer { m[k] = v }
            balance[tag] = m
        }

        let ranking = ScoreEngine.rank(year: y, companies: companies, annual: annual, balance: balance,
                                       now: latest.data, monthAgo: month.data, yearAgo: year.data)
        if ranking.screened < 500 { throw AppError("Couldn't read enough company data. Try again later.") }

        progress("Checking industries…")
        var industries = loadIndustries()
        var added = false
        var seen = Set<Int>()
        let finalists = Array(ranking.wealth.prefix(30)) + Array(ranking.income.prefix(30)) + Array(ranking.growth.prefix(30))
        for c in finalists {
            let cik = c.company.cik
            if !seen.insert(cik).inserted || industries[cik] != nil { continue }
            guard let data = try? await Edgar.get(String(format: "https://data.sec.gov/submissions/CIK%010d.json", cik), ua: ua),
                  let j = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { continue }
            let code = Int(j["sic"] as? String ?? "") ?? 0
            industries[cik] = (code, titleCase(j["sicDescription"] as? String ?? ""))
            added = true
        }
        if added { saveIndustries(industries) }

        let picked = ScoreEngine.select(ranking, industries: industries)
        return ScoresResult(priceDate: latest.date, fiscalYear: y, screened: ranking.screened,
                            growth: picked.growth, income: picked.income, wealth: picked.wealth, fetchedAt: Date())
    }

    // ---------------- SEC data ----------------
    private static func frame(ua: String, tag: String, unit: String, period: String) async throws -> [Int: Double] {
        let file = "frame_\(tag)_\(period).txt"
        var text = cachedText(file, maxAge: week)
        if text == nil {
            var lines: [String] = []
            do {
                let data = try await Edgar.get("https://data.sec.gov/api/xbrl/frames/us-gaap/\(tag)/\(unit)/\(period).json", ua: ua)
                let json = try JSONSerialization.jsonObject(with: data) as? [String: Any]
                for item in json?["data"] as? [[String: Any]] ?? [] {
                    guard let cik = (item["cik"] as? NSNumber)?.intValue,
                          let v = (item["val"] as? NSNumber)?.doubleValue else { continue }
                    lines.append("\(cik)=\(v)")
                }
            } catch {
                // The SEC answers 404 when no company has reported this number for the period yet.
                if !error.localizedDescription.contains("404") { throw error }
            }
            let joined = lines.joined(separator: "\n")
            saveText(file, joined)
            text = joined
        }
        var map: [Int: Double] = [:]
        for line in (text ?? "").split(separator: "\n") {
            let parts = line.split(separator: "=", maxSplits: 1)
            guard parts.count == 2, let cik = Int(parts[0]), let v = Double(parts[1]) else { continue }
            map[cik] = v
        }
        return map
    }

    /// Companies listed on the NYSE or Nasdaq, one (main) ticker each.
    private static func loadCompanies(ua: String) async throws -> [ScoreCompany] {
        let file = "score_companies.json"
        var data = cachedText(file, maxAge: week)?.data(using: .utf8)
        if data == nil {
            let fresh = try await Edgar.get("https://www.sec.gov/files/company_tickers_exchange.json", ua: ua)
            saveText(file, String(data: fresh, encoding: .utf8) ?? "")
            data = fresh
        }
        let json = try JSONSerialization.jsonObject(with: data ?? Data()) as? [String: Any]
        let fields = json?["fields"] as? [String] ?? []
        let iCik = fields.firstIndex(of: "cik") ?? 0
        let iName = fields.firstIndex(of: "name") ?? 1
        let iTicker = fields.firstIndex(of: "ticker") ?? 2
        let iExchange = fields.firstIndex(of: "exchange") ?? 3
        var seen = Set<Int>()
        var out: [ScoreCompany] = []
        for row in json?["data"] as? [[Any]] ?? [] {
            guard row.count > max(iCik, iName, iTicker, iExchange) else { continue }
            let exchange = (row[iExchange] as? String ?? "").uppercased()
            guard exchange == "NYSE" || exchange == "NASDAQ" else { continue }
            let ticker = (row[iTicker] as? String ?? "").uppercased()
            guard let cik = (row[iCik] as? NSNumber)?.intValue, validTicker(ticker) else { continue }
            if !seen.insert(cik).inserted { continue }
            out.append(ScoreCompany(cik: cik, ticker: ticker, name: titleCase(row[iName] as? String ?? "")))
        }
        return out
    }

    /// "AAPL" or "BRK-B": up to five letters, optionally a dash and a class letter.
    private static func validTicker(_ t: String) -> Bool {
        let parts = t.split(separator: "-", omittingEmptySubsequences: false)
        guard parts.count <= 2, let main = parts.first, (1...5).contains(main.count) else { return false }
        func letters(_ s: Substring) -> Bool { s.allSatisfy { $0.isASCII && $0.isLetter && $0.isUppercase } }
        if !letters(main) { return false }
        if parts.count == 2 { return parts[1].count == 1 && letters(parts[1]) }
        return true
    }

    private static func loadIndustries() -> [Int: (code: Int, name: String)] {
        var map: [Int: (code: Int, name: String)] = [:]
        guard let text = cachedText("score_industries.txt", maxAge: 365 * 86_400) else { return map }
        for line in text.split(separator: "\n") {
            let p = line.split(separator: "|", maxSplits: 2, omittingEmptySubsequences: false)
            guard p.count == 3, let cik = Int(p[0]) else { continue }
            map[cik] = (Int(p[1]) ?? 0, String(p[2]))
        }
        return map
    }

    private static func saveIndustries(_ map: [Int: (code: Int, name: String)]) {
        let text = map.map { "\($0.key)|\($0.value.code)|\($0.value.name.replacingOccurrences(of: "|", with: " "))" }
            .joined(separator: "\n")
        saveText("score_industries.txt", text)
    }

    private static func titleCase(_ s: String) -> String {
        s.contains(where: { $0.isLowercase }) ? s : s.capitalized
    }

    // ---------------- small text-file cache ----------------
    private static var dir: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("scores", isDirectory: true)
        try? FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
        return base
    }

    private static func cachedText(_ name: String, maxAge: TimeInterval) -> String? {
        let url = dir.appendingPathComponent(name)
        guard let attrs = try? FileManager.default.attributesOfItem(atPath: url.path),
              let modified = attrs[.modificationDate] as? Date,
              Date().timeIntervalSince(modified) < maxAge else { return nil }
        return try? String(contentsOf: url, encoding: .utf8)
    }

    private static func saveText(_ name: String, _ text: String) {
        try? text.write(to: dir.appendingPathComponent(name), atomically: true, encoding: .utf8)
    }
}
