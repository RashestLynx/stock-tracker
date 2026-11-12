import Foundation

/// Nightly team ratings and side-by-side numbers (from sports.py), tested on seasons the model never saw. Mirrors SportsModel.kt.
struct RecentGame { let date: String, opp: String, pf: Int, pa: Int, home: Bool }
struct Injury: Hashable { let name: String, pos: String, status: String }
struct CalBin { let from: Int, to: Int, n: Int, said: Double, won: Double }

struct TeamRow {
    let name: String, abbr: String, elo: Int, rank: Int?
    let w: Int, l: Int, t: Int, pf: Double?, pa: Double?, home: String, away: String, l10: String, streak: String
    let sos: Int?, trend: Int, games: [RecentGame], injuries: [Injury]
    var record: String { t > 0 ? "\(w)-\(l)-\(t)" : "\(w)-\(l)" }
}

struct LeagueModel {
    let name: String, teams: [String: TeamRow], homeEdge: Int, drawRate: Double?
    let n: Int, acc: Double?, brier: Double?, seasons: Int, from: String, to: String
    let booksAcc: Double?, booksBrier: Double?, modelSameAcc: Double?, modelSameBrier: Double?
    let calibration: [CalBin]

    func find(_ label: String) -> TeamRow? {
        let l = label.trimmingCharacters(in: .whitespaces)
        if let t = teams.values.first(where: { $0.abbr.caseInsensitiveCompare(l) == .orderedSame }) { return t }
        let hits = teams.values.filter { SportsData.same(l, $0.name) }
        if hits.count == 1 { return hits[0] }
        if (2...3).contains(hits.count), hits.allSatisfy({ $0.name.localizedCaseInsensitiveContains(l) }) {
            return hits.min { $0.name.count < $1.name.count }
        }
        return nil
    }

    /// Chance a beats b (and draw for soccer); aHome nil = neutral.
    func chance(_ a: TeamRow, _ b: TeamRow, aHome: Bool?) -> (Double, Double, Double) {
        let edge = aHome == nil ? 0 : (aHome! ? homeEdge : -homeEdge)
        let e = 1 / (1 + pow(10, -Double(a.elo - b.elo + edge) / 400))
        let d = (drawRate ?? 0) / 0.95 * 4 * e * (1 - e)
        return (e - d / 2, d, 1 - e - d / 2)
    }
}

struct SportsModel { let date: String, leagues: [String: LeagueModel], kalshiBins: [CalBin], kalshiN: Int }

enum SportsModels {
    static let url = URL(string: "https://github.com/RashestLynx/stock-Tracker-patterns/releases/download/latest/sports.json.gz")!
    private static var memo: (Date, SportsModel)?
    private static var file: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
        return base.appendingPathComponent("sports.json.gz")
    }
    private static var savedAt: Date? { (try? FileManager.default.attributesOfItem(atPath: file.path))?[.modificationDate] as? Date }

    static func load() async throws -> SportsModel {
        if savedAt.map({ Date().timeIntervalSince($0) > 3 * 3600 }) ?? true {
            var req = URLRequest(url: url, timeoutInterval: 60)
            req.setValue("StockTracker", forHTTPHeaderField: "User-Agent")
            if let (data, resp) = try? await URLSession.shared.data(for: req), (resp as? HTTPURLResponse)?.statusCode ?? 0 < 300,
               parse(data) != nil {
                try? data.write(to: file, options: .atomic)
                memo = nil
            }
        }
        guard let at = savedAt else {
            throw NSError(domain: "Sports", code: 1, userInfo: [NSLocalizedDescriptionKey: "Team ratings aren't available yet. They're built each night."])
        }
        if let m = memo, m.0 == at { return m.1 }
        guard let d = try? Data(contentsOf: file), let m = parse(d) else { throw URLError(.cannotDecodeContentData) }
        memo = (at, m)
        return m
    }

    static func parse(_ gz: Data) -> SportsModel? {
        guard let text = Nightly.gunzip(gz) ?? (gz.first == UInt8(ascii: "{") ? gz : nil),
              let o = try? JSONSerialization.jsonObject(with: text) as? [String: Any] else { return nil }
        func num(_ x: Any?) -> Double? { (x as? NSNumber)?.doubleValue }
        func int(_ x: Any?) -> Int? { (x as? NSNumber)?.intValue }
        func bins(_ a: Any?, _ said: String) -> [CalBin] {
            ((a as? [[String: Any]]) ?? []).map { CalBin(from: int($0["from"]) ?? 0, to: int($0["to"]) ?? 0, n: int($0["n"]) ?? 0,
                                                         said: num($0[said]) ?? 0, won: num($0["won"]) ?? 0) }
        }
        var leagues: [String: LeagueModel] = [:]
        for (key, lv) in (o["leagues"] as? [String: [String: Any]]) ?? [:] {
            let inj = lv["injuries"] as? [String: [[String: Any]]] ?? [:]
            var teams: [String: TeamRow] = [:]
            for (name, t) in (lv["teams"] as? [String: [String: Any]]) ?? [:] {
                let games = ((t["games"] as? [[Any]]) ?? []).compactMap { g -> RecentGame? in
                    guard g.count >= 5 else { return nil }
                    return RecentGame(date: g[0] as? String ?? "", opp: g[1] as? String ?? "", pf: int(g[2]) ?? 0, pa: int(g[3]) ?? 0, home: int(g[4]) == 1)
                }
                let injuries = (inj[name] ?? []).map { Injury(name: $0["name"] as? String ?? "", pos: $0["pos"] as? String ?? "", status: $0["status"] as? String ?? "") }
                teams[name] = TeamRow(name: name, abbr: t["abbr"] as? String ?? "", elo: int(t["elo"]) ?? 1500, rank: int(t["rank"]),
                                      w: int(t["w"]) ?? 0, l: int(t["l"]) ?? 0, t: int(t["t"]) ?? 0, pf: num(t["pf"]), pa: num(t["pa"]),
                                      home: t["home"] as? String ?? "", away: t["away"] as? String ?? "", l10: t["l10"] as? String ?? "",
                                      streak: t["streak"] as? String ?? "", sos: int(t["sos"]), trend: int(t["trend"]) ?? 0,
                                      games: games, injuries: injuries)
            }
            let rec = lv["record"] as? [String: Any] ?? [:]
            let books = rec["books"] as? [String: Any]
            let same = rec["model_same_games"] as? [String: Any]
            leagues[key] = LeagueModel(name: lv["name"] as? String ?? key, teams: teams, homeEdge: int(lv["home_edge"]) ?? 0,
                                       drawRate: num(lv["draw_rate"]), n: int(rec["n"]) ?? 0, acc: num(rec["acc"]), brier: num(rec["brier"]),
                                       seasons: int(rec["tested_seasons"]) ?? 0, from: rec["from"] as? String ?? "", to: rec["to"] as? String ?? "",
                                       booksAcc: num(books?["acc"]), booksBrier: num(books?["brier"]),
                                       modelSameAcc: num(same?["acc"]), modelSameBrier: num(same?["brier"]),
                                       calibration: bins(rec["calibration"], "said"))
        }
        let k = o["kalshi"] as? [String: Any]
        return SportsModel(date: o["date"] as? String ?? "", leagues: leagues, kalshiBins: bins(k?["bins"], "price"), kalshiN: int(k?["n"]) ?? 0)
    }

    static func pct(_ x: Double) -> String { String(format: "%.0f%%", x * 100) }
}
