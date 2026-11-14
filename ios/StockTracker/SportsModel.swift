import Foundation

/// Nightly team ratings and side-by-side numbers (from sports.py), tested on seasons the model never saw. Mirrors SportsModel.kt.
struct WeatherSplit: Hashable {
    let condition: String, n: Int, wins: Int, expected: Double, z: Double
    var label: String { ["rain": "In rain", "cold": "At 40°F or colder", "heat": "At 85°F or hotter", "wind": "Wind 15+ mph"][condition] ?? "Mild, dry weather" }
    var verdict: String {
        z <= -3 ? "clearly worse than expected" : z <= -2 ? "worse than expected (could still be luck)"
            : z >= 3 ? "clearly better than expected" : z >= 2 ? "better than expected (could still be luck)" : "about as expected"
    }
}
struct RecentGame { let date: String, opp: String, pf: Int, pa: Int, home: Bool }
struct Injury: Hashable { let name: String, pos: String, status: String }
struct CalBin { let from: Int, to: Int, n: Int, said: Double, won: Double }
struct Upcoming { let home: String, away: String, venue: String, indoor: Bool, elo: Double, full: Double?, temp: Int?, wind: Int?, rain: Double?
    var weightClass: String = "", fighters: [String: Fighter] = [:], rounds: Int = 3, reasons: [FightReason] = [] }
/// One factor behind a UFC chance, in plain words: who it favors.
struct FightReason: Hashable { let favors: String, why: String, size: Double }
/// UFC: one fighter's profile going into a fight, plus recent injury / pull-out / weight-cut headlines.
struct Fighter { let age: Int?, reach: Double?, height: Double?, stance: String, ufcFights: Int, record: String,
                 layoffDays: Int?, last: String, classChange: Int, news: [FightNews]
                 var country = "", gym = "", home = false, timeZones: Double? = nil, statsFights = 0
                 var slpm: Double? = nil, sapm: Double? = nil, acc: Double? = nil, def: Double? = nil, td15: Double? = nil, tdDef: Double? = nil
                 var sub15: Double? = nil, kdTaken15: Double? = nil, koLosses = 0, active2y = 0, bookedDays: Int? = nil, newOpponentWas: String? = nil }
struct FightNews: Hashable { let title: String, link: String, date: String, kinds: [String]; var source = "news" }
struct MatchupTest { let version: String, logloss: Double, acc: Double }

struct TeamRow {
    let name: String, abbr: String, elo: Int, rank: Int?
    let w: Int, l: Int, t: Int, pf: Double?, pa: Double?, home: String, away: String, l10: String, streak: String
    let sos: Int?, trend: Int, games: [RecentGame], injuries: [Injury]
    var weather: [WeatherSplit] = []
    var record: String { t > 0 ? "\(w)-\(l)-\(t)" : "\(w)-\(l)" }
}

struct LeagueModel {
    let name: String, teams: [String: TeamRow], homeEdge: Int, drawRate: Double?
    let n: Int, acc: Double?, brier: Double?, seasons: Int, from: String, to: String
    let booksAcc: Double?, booksBrier: Double?, modelSameAcc: Double?, modelSameBrier: Double?
    let calibration: [CalBin]
    var upcoming: [Upcoming] = []
    var matchupUsed = "elo"
    var weatherHelped = false
    var matchupTests: [String: MatchupTest] = [:]

    /// Both sides of a game. A school name that fits several teams ("Colorado": Buffaloes, State, Mesa...) is settled
    /// by the schedule: the team that's actually playing the other side.
    func findPair(_ la: String, _ lb: String) -> (TeamRow, TeamRow)? {
        let a = find(la), b = find(lb)
        if let a, let b { return (a, b) }
        for u in upcoming {
            for (x, y) in [(u.home, u.away), (u.away, u.home)] {
                if (a == nil || a!.name == x), (b == nil || b!.name == y), SportsData.same(la, x), SportsData.same(lb, y),
                   let tx = teams[x], let ty = teams[y] { return (tx, ty) }
            }
        }
        return nil
    }

    func upcomingFor(_ a: TeamRow, _ b: TeamRow) -> Upcoming? {
        upcoming.first { ($0.home == a.name && $0.away == b.name) || ($0.home == b.name && $0.away == a.name) }
    }

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
            if let res = try? await URLSession.shared.data(for: req), (res.1 as? HTTPURLResponse)?.statusCode ?? 0 < 300,
               parse(res.0) != nil {
                try? res.0.write(to: file, options: .atomic)
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
                                      games: games, injuries: injuries,
                                      weather: ((t["weather"] as? [String: [String: Any]]) ?? [:]).map { c, x in
                                          WeatherSplit(condition: c, n: int(x["n"]) ?? 0, wins: int(x["w"]) ?? 0, expected: num(x["exp"]) ?? 0, z: num(x["z"]) ?? 0) })
            }
            let rec = lv["record"] as? [String: Any] ?? [:]
            let books = rec["books"] as? [String: Any]
            let same = rec["model_same_games"] as? [String: Any]
            let up = ((lv["upcoming"] as? [[String: Any]]) ?? []).map { u -> Upcoming in
                let wx = u["wx"] as? [String: Any]
                return Upcoming(home: u["home"] as? String ?? "", away: u["away"] as? String ?? "", venue: u["venue"] as? String ?? "",
                                indoor: u["indoor"] as? Bool ?? false, elo: num(u["elo"]) ?? 0.5, full: num(u["full"]),
                                temp: int(wx?["temp"]), wind: int(wx?["wind"]), rain: num(wx?["rain"]),
                                weightClass: u["class"] as? String ?? "",
                                fighters: ((u["fighters"] as? [String: [String: Any]]) ?? [:]).mapValues { f -> Fighter in
                                    var fr = Fighter(age: int(f["age"]), reach: num(f["reach"]), height: num(f["height"]), stance: f["stance"] as? String ?? "",
                                            ufcFights: int(f["ufc_fights"]) ?? 0, record: f["record"] as? String ?? "", layoffDays: int(f["layoff_days"]),
                                            last: f["last"] as? String ?? "", classChange: int(f["class_change"]) ?? 0,
                                            news: ((f["news"] as? [[String: Any]]) ?? []).map { x in
                                                FightNews(title: x["title"] as? String ?? "", link: x["link"] as? String ?? "",
                                                          date: x["date"] as? String ?? "", kinds: x["kinds"] as? [String] ?? [],
                                                          source: x["source"] as? String ?? "news") })
                                    fr.country = f["country"] as? String ?? ""; fr.gym = f["gym"] as? String ?? ""
                                    fr.home = f["home"] as? Bool ?? false; fr.timeZones = num(f["time_zones"]); fr.statsFights = int(f["stats_fights"]) ?? 0
                                    fr.slpm = num(f["slpm"]); fr.sapm = num(f["sapm"]); fr.acc = num(f["acc"]); fr.def = num(f["def"])
                                    fr.td15 = num(f["td15"]); fr.tdDef = num(f["tddef"]); fr.sub15 = num(f["sub15"]); fr.kdTaken15 = num(f["kd_taken15"])
                                    fr.koLosses = int(f["ko_losses"]) ?? 0; fr.active2y = int(f["active_2y"]) ?? 0
                                    fr.bookedDays = int(f["booked_days"]); fr.newOpponentWas = f["new_opponent_was"] as? String
                                    return fr
                                },
                                rounds: int(u["rounds"]) ?? 3,
                                reasons: ((u["reasons"] as? [[String: Any]]) ?? []).map { r in
                                    FightReason(favors: r["favors"] as? String ?? "", why: r["why"] as? String ?? "", size: num(r["size"]) ?? 0) })
            }
            let mj = rec["matchup"] as? [String: Any]
            var tests: [String: MatchupTest] = [:]
            for (k, v) in (mj?["tested"] as? [String: [String: Any]]) ?? [:] {
                tests[k] = MatchupTest(version: k, logloss: num(v["logloss"]) ?? 0, acc: num(v["acc"]) ?? 0)
            }
            leagues[key] = LeagueModel(name: lv["name"] as? String ?? key, teams: teams, homeEdge: int(lv["home_edge"]) ?? 0,
                                       drawRate: num(lv["draw_rate"]), n: int(rec["n"]) ?? 0, acc: num(rec["acc"]), brier: num(rec["brier"]),
                                       seasons: int(rec["tested_seasons"]) ?? 0, from: rec["from"] as? String ?? "", to: rec["to"] as? String ?? "",
                                       booksAcc: num(books?["acc"]), booksBrier: num(books?["brier"]),
                                       modelSameAcc: num(same?["acc"]), modelSameBrier: num(same?["brier"]),
                                       calibration: bins(rec["calibration"], "said"), upcoming: up,
                                       matchupUsed: mj?["used"] as? String ?? "elo", weatherHelped: mj?["weather_helped"] as? Bool ?? false,
                                       matchupTests: tests)
        }
        let k = o["kalshi"] as? [String: Any]
        return SportsModel(date: o["date"] as? String ?? "", leagues: leagues, kalshiBins: bins(k?["bins"], "price"), kalshiN: int(k?["n"]) ?? 0)
    }

    static func pct(_ x: Double) -> String { String(format: "%.0f%%", x * 100) }
}
