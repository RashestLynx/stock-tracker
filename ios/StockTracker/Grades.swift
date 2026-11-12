import Foundation

/// Letter grades for every stock (growth, income, wealth preservation) from the nightly scan. Mirrors Grades.kt.
struct StockGrade: Identifiable {
    let ticker: String, name: String, capM: Int
    let growth: String, growthPct: Int, income: String, incomePct: Int, wealth: String, wealthPct: Int
    let whyGrowth: [String], whyIncome: [String], whyWealth: [String]
    var id: String { ticker }
    func grade(_ goal: Int) -> String { goal == 0 ? growth : goal == 1 ? income : wealth }
    func pct(_ goal: Int) -> Int { goal == 0 ? growthPct : goal == 1 ? incomePct : wealthPct }
    func why(_ goal: Int) -> [String] { goal == 0 ? whyGrowth : goal == 1 ? whyIncome : whyWealth }
}

struct RisingStar: Identifiable {
    let ticker: String, name: String, capM: Int, industry: String, why: String, growth: String
    var id: String { ticker }
}

struct GradeBucket: Identifiable {
    let letter: String, n: Int, ret: Double, beat: Double, drop: Double?
    var id: String { letter }
}

struct GradesResult {
    let date: String, fiscalYear: Int, graded: Int
    let grades: [String: StockGrade], stars: [RisingStar], record: [String: [GradeBucket]]
    let spy: Double?, spyDrop: Double?, recordFrom: String, starsRet: Double?, starsBeat: Double?
    var months: Int = 12

    func ranked(_ goal: Int) -> [StockGrade] {
        grades.values.filter { !$0.grade(goal).isEmpty && (goal != 1 || $0.incomePct >= 0) }.sorted { $0.pct(goal) > $1.pct(goal) }
    }
}

enum Grades {
    static let url = URL(string: "https://github.com/RashestLynx/stock-Tracker-patterns/releases/download/latest/grades.json.gz")!
    private static var memo: (Date, GradesResult)?

    private static var file: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
        return base.appendingPathComponent("grades.json.gz")
    }
    private static var savedAt: Date? { (try? FileManager.default.attributesOfItem(atPath: file.path))?[.modificationDate] as? Date }

    static func cached() -> GradesResult? {
        guard let at = savedAt else { return nil }
        if let m = memo, m.0 == at { return m.1 }
        guard let d = try? Data(contentsOf: file), let r = parse(d) else { return nil }
        memo = (at, r)
        return r
    }

    static func load(force: Bool = false) async throws -> GradesResult {
        if !force, let at = savedAt, Date().timeIntervalSince(at) < 3 * 3600, let r = cached() { return r }
        do {
            var req = URLRequest(url: url, timeoutInterval: 60)
            req.setValue("StockTracker", forHTTPHeaderField: "User-Agent")
            let (data, resp) = try await URLSession.shared.data(for: req)
            guard (resp as? HTTPURLResponse)?.statusCode ?? 0 < 300, parse(data) != nil else { throw URLError(.badServerResponse) }
            try data.write(to: file, options: .atomic)
            memo = nil
        } catch {
            if let r = cached() { return r }
            throw NSError(domain: "Grades", code: 1, userInfo: [NSLocalizedDescriptionKey:
                "Grades aren't available yet. They appear after the next nightly scan."])
        }
        guard let r = cached() else { throw URLError(.cannotDecodeContentData) }
        return r
    }

    static func parse(_ gz: Data) -> GradesResult? {
        guard let text = Nightly.gunzip(gz) ?? (gz.first == UInt8(ascii: "{") ? gz : nil),
              let o = try? JSONSerialization.jsonObject(with: text) as? [String: Any],
              let gj = o["grades"] as? [String: [String: Any]] else { return nil }
        func num(_ x: Any?) -> Double? { (x as? NSNumber)?.doubleValue }
        func int(_ x: Any?, _ d: Int = 0) -> Int { (x as? NSNumber)?.intValue ?? d }
        var grades: [String: StockGrade] = [:]
        for (t, g) in gj {
            let why = g["why"] as? [String: [String]] ?? [:]
            grades[t] = StockGrade(ticker: t, name: g["name"] as? String ?? "", capM: int(g["cap"]),
                                   growth: g["g"] as? String ?? "", growthPct: int(g["gp"], -1),
                                   income: g["i"] as? String ?? "", incomePct: int(g["ip"], -1),
                                   wealth: g["w"] as? String ?? "", wealthPct: int(g["wp"], -1),
                                   whyGrowth: why["g"] ?? [], whyIncome: why["i"] ?? [], whyWealth: why["w"] ?? [])
        }
        let stars = (o["stars"] as? [[String: Any]] ?? []).map { s in
            RisingStar(ticker: s["t"] as? String ?? "", name: s["name"] as? String ?? "", capM: int(s["cap"]),
                       industry: s["industry"] as? String ?? "", why: s["why"] as? String ?? "", growth: s["g"] as? String ?? "")
        }
        let rj = o["record"] as? [String: Any] ?? [:]
        var record: [String: [GradeBucket]] = [:]
        for goal in ["g", "i", "w"] {
            guard let b = rj[goal] as? [String: [String: Any]] else { continue }
            record[goal] = b.keys.sorted().map { k in
                let x = b[k]!
                return GradeBucket(letter: k, n: int(x["n"]), ret: num(x["ret"]) ?? 0, beat: num(x["beat"]) ?? 0, drop: num(x["dd"]))
            }
        }
        let st = rj["stars"] as? [String: Any]
        return GradesResult(date: o["date"] as? String ?? "", fiscalYear: int(o["fy"]), graded: int(o["graded"]),
                            grades: grades, stars: stars, record: record, spy: num(rj["spy"]), spyDrop: num(rj["spy_dd"]),
                            recordFrom: rj["from"] as? String ?? "", starsRet: num(st?["ret"]), starsBeat: num(st?["beat"]),
                            months: int(rj["months"], 12))
    }

    static func money(_ capM: Int) -> String {
        capM >= 1_000_000 ? String(format: "$%.1fT", Double(capM) / 1e6) : capM >= 1000 ? String(format: "$%.1fB", Double(capM) / 1e3) : "$\(capM)M"
    }
}
