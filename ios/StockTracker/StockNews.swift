import Foundation

/// A headline about one stock, from Massive's or Finnhub's news feed (keys from Settings). Mirrors StockNews.kt.
struct Headline: Identifiable {
    let title: String, source: String, time: Date?, url: String, sentiment: String
    var id: String { url }
}

enum StockNews {
    private static var memo: [String: (Date, [Headline])] = [:]

    static func get(_ ticker: String, massiveKey: String) async -> [Headline] {
        let t = ticker.uppercased()
        if let m = memo[t], Date().timeIntervalSince(m.0) < 15 * 60 { return m.1 }
        var all: [Headline] = []
        let iso = ISO8601DateFormatter()
        if !massiveKey.isEmpty, let u = URL(string: "https://api.massive.com/v2/reference/news?ticker=\(t)&limit=20&order=desc&sort=published_utc&apiKey=\(massiveKey)"),
           let res = try? await URLSession.shared.data(from: u),
           let o = try? JSONSerialization.jsonObject(with: res.0) as? [String: Any] {
            for r in (o["results"] as? [[String: Any]]) ?? [] {
                let ins = (r["insights"] as? [[String: Any]])?.first { ($0["ticker"] as? String)?.uppercased() == t }
                all.append(Headline(title: r["title"] as? String ?? "", source: (r["publisher"] as? [String: Any])?["name"] as? String ?? "",
                                    time: iso.date(from: r["published_utc"] as? String ?? ""), url: r["article_url"] as? String ?? "",
                                    sentiment: ins?["sentiment"] as? String ?? ""))
            }
        }
        let fk = Live.key
        if !fk.isEmpty {
            let f = DateFormatter(); f.dateFormat = "yyyy-MM-dd"; f.locale = Locale(identifier: "en_US_POSIX")
            let to = f.string(from: Date()), from = f.string(from: Date().addingTimeInterval(-10 * 86400))
            if let u = URL(string: "https://finnhub.io/api/v1/company-news?symbol=\(t)&from=\(from)&to=\(to)&token=\(fk)"),
               let res = try? await URLSession.shared.data(from: u),
               let arr = try? JSONSerialization.jsonObject(with: res.0) as? [[String: Any]] {
                for r in arr.prefix(30) {
                    let ts = (r["datetime"] as? NSNumber)?.doubleValue
                    all.append(Headline(title: r["headline"] as? String ?? "", source: r["source"] as? String ?? "",
                                        time: ts.map { Date(timeIntervalSince1970: $0) }, url: r["url"] as? String ?? "", sentiment: ""))
                }
            }
        }
        var seen = Set<String>()
        let out = all.filter { !$0.title.isEmpty && $0.url.hasPrefix("http") }
            .sorted { ($0.time ?? .distantPast) > ($1.time ?? .distantPast) }
            .filter { seen.insert(String($0.title.lowercased().filter { $0.isLetter || $0.isNumber }.prefix(60))).inserted }
        let top = Array(out.prefix(10))
        memo[t] = (Date(), top)
        return top
    }
}
