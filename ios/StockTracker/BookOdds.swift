import Foundation

/// One sportsbook's moneyline on one side: American odds and the chance it implies (after removing the book's cut).
struct BookLine {
    let book: String, label: String, american: Int, fair: Double
    var payout: Double { american > 0 ? Double(american) / 100 : 100 / Double(-american) }
    var oddsText: String { american > 0 ? "+\(american)" : "\(american)" }
}

struct BookGame {
    let home: String, away: String, lines: [BookLine]
    func sideLines(_ label: String) -> [BookLine] { lines.filter { SportsData.same(label, $0.label) || $0.label == label } }
    func best(_ label: String) -> BookLine? { sideLines(label).max { $0.payout < $1.payout } }
}

/// FanDuel and DraftKings odds from The Odds API (your own free key, kept in the Keychain). Mirrors BookOdds.kt.
enum BookOdds {
    static let signup = "https://the-odds-api.com/"
    private static let sport = ["nfl": "americanfootball_nfl", "nba": "basketball_nba", "nhl": "icehockey_nhl", "mlb": "baseball_mlb",
                                "cfb": "americanfootball_ncaaf", "cbb": "basketball_ncaab", "epl": "soccer_epl", "mls": "soccer_usa_mls",
                                "ucl": "soccer_uefa_champs_league", "ufc": "mma_mixed_martial_arts"]
    private static var memo: [String: (Date, [BookGame])] = [:]
    static var remaining = ""

    static var key: String {
        get { Keychain.get("odds_api_key") }
        set { Keychain.set("odds_api_key", newValue.trimmingCharacters(in: .whitespacesAndNewlines)) }
    }

    /// One lookup per league, cached 30 minutes (the free plan has 500 a month).
    static func league(_ polyTag: String, force: Bool = false) async throws -> [BookGame] {
        guard let sp = sport[polyTag], !key.isEmpty else { return [] }
        if !force, let m = memo[sp], Date().timeIntervalSince(m.0) < 1800 { return m.1 }
        guard let k = key.addingPercentEncoding(withAllowedCharacters: .alphanumerics),
              let u = URL(string: "https://api.the-odds-api.com/v4/sports/\(sp)/odds?regions=us&markets=h2h&oddsFormat=american&bookmakers=fanduel,draftkings&apiKey=\(k)")
        else { return [] }
        let res = try await URLSession.shared.data(from: u)
        let http = res.1 as? HTTPURLResponse
        remaining = http?.value(forHTTPHeaderField: "x-requests-remaining") ?? remaining
        switch http?.statusCode ?? 0 {
        case 200: break
        case 401: throw NSError(domain: "Odds", code: 401, userInfo: [NSLocalizedDescriptionKey: "The Odds API didn't accept the key. Check it in Settings."])
        case 429: throw NSError(domain: "Odds", code: 429, userInfo: [NSLocalizedDescriptionKey: "This month's free Odds API lookups are used up."])
        default: throw NSError(domain: "Odds", code: 1, userInfo: [NSLocalizedDescriptionKey: "The Odds API answered HTTP \(http?.statusCode ?? 0)"])
        }
        let games = parse(res.0)
        memo[sp] = (Date(), games)
        return games
    }

    static func parse(_ d: Data) -> [BookGame] {
        guard let arr = try? JSONSerialization.jsonObject(with: d) as? [[String: Any]] else { return [] }
        return arr.map { g in
            var lines: [BookLine] = []
            for bk in (g["bookmakers"] as? [[String: Any]]) ?? [] {
                for m in (bk["markets"] as? [[String: Any]]) ?? [] where (m["key"] as? String) == "h2h" {
                    let raw = ((m["outcomes"] as? [[String: Any]]) ?? []).map { ($0["name"] as? String ?? "", ($0["price"] as? NSNumber)?.intValue ?? 0) }
                    let implied = raw.map { $0.1 > 0 ? 100 / Double($0.1 + 100) : Double(-$0.1) / Double(-$0.1 + 100) }
                    let total = implied.reduce(0, +)
                    for (i, r) in raw.enumerated() {
                        lines.append(BookLine(book: bk["title"] as? String ?? "", label: r.0, american: r.1, fair: total > 0 ? implied[i] / total : 0))
                    }
                }
            }
            return BookGame(home: g["home_team"] as? String ?? "", away: g["away_team"] as? String ?? "", lines: lines)
        }
    }

    static func find(_ games: [BookGame], _ labels: [String]) -> BookGame? {
        let l = labels.filter { !SportsData.isDraw($0) }
        guard l.count >= 2 else { return nil }
        return games.first { g in l.allSatisfy { SportsData.same($0, g.home) || SportsData.same($0, g.away) } }
    }
}
