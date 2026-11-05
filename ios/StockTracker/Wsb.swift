import Foundation

struct WsbStock: Identifiable {
    let rank: Int
    let ticker: String
    let name: String
    let mentions: Int
    let mentions24hAgo: Int
    let upvotes: Int
    let rank24hAgo: Int
    var id: String { ticker }

    /// % change in mentions vs the previous 24 hours, or nil if it's new.
    var mentionChange: Double? {
        mentions24hAgo > 0 ? Double(mentions - mentions24hAgo) / Double(mentions24hAgo) * 100 : nil
    }
    /// Positive = climbed the rankings.
    var rankChange: Int { rank24hAgo > 0 ? rank24hAgo - rank : 0 }
}

/// Most-discussed stocks on Reddit (r/wallstreetbets and other stock subreddits), from the free
/// ApeWisdom API. Counts are mentions over the last 24 hours, updated about hourly.
enum Wsb {
    static let sourceURL = "https://apewisdom.io"

    /// filter: "wallstreetbets" or "all-stocks".
    static func trending(filter: String) async throws -> [WsbStock] {
        guard let url = URL(string: "https://apewisdom.io/api/v1.0/filter/\(filter)/page/1") else { return [] }
        var req = URLRequest(url: url)
        req.setValue("StockTracker-iOS/1.0 (personal app)", forHTTPHeaderField: "User-Agent")
        req.timeoutInterval = 20
        let (data, resp) = try await URLSession.shared.data(for: req)
        let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(code) else { throw AppError("Reddit data returned HTTP \(code)") }
        let json = try JSONSerialization.jsonObject(with: data) as? [String: Any]
        let rows = json?["results"] as? [[String: Any]] ?? []

        func int(_ v: Any?) -> Int {
            if let n = v as? NSNumber { return n.intValue }
            if let s = v as? String { return Int(s) ?? 0 }
            return 0
        }
        return rows.enumerated().compactMap { i, o in
            let ticker = (o["ticker"] as? String ?? "").uppercased()
            guard !ticker.isEmpty else { return nil }
            let name = (o["name"] as? String ?? "").replacingOccurrences(of: "&amp;", with: "&")
            return WsbStock(rank: int(o["rank"]) == 0 ? i + 1 : int(o["rank"]), ticker: ticker, name: name,
                            mentions: int(o["mentions"]), mentions24hAgo: int(o["mentions_24h_ago"]),
                            upvotes: int(o["upvotes"]), rank24hAgo: int(o["rank_24h_ago"]))
        }
    }
}
