import Foundation

/// A public figure the user follows, such as a finance YouTuber. Private investors don't have to
/// report their trades, so this tracks what they say publicly: their YouTube videos and news.
struct Followed: Codable, Identifiable, Hashable {
    var name: String
    var youtube: String          // YouTube @handle, without the @
    var newsQuery: String
    var businesses: [String]
    var links: [[String]]        // [label, url]
    var id: String { name }
}

struct Video: Identifiable {
    let title: String
    let url: String
    let time: Date
    let id: String
}

enum Following {
    static let defaults: [Followed] = [
        Followed(name: "Jaspreet Singh", youtube: "minoritymindset",
                 newsQuery: "\"Jaspreet Singh\" (\"Minority Mindset\" OR \"Market Briefs\")",
                 businesses: ["Founder of Minority Mindset, a financial education media company and YouTube channel",
                              "CEO of Briefs Media, publisher of the free daily newsletter Market Briefs"],
                 links: [["Minority Mindset", "https://theminoritymindset.com"], ["Market Briefs", "https://www.briefs.co"]]),
        Followed(name: "Mark Tilbury", youtube: "marktilbury", newsQuery: "\"Mark Tilbury\"",
                 businesses: ["Model World Ltd, a UK company selling remote-controlled toys",
                              "Century UK Ltd",
                              "Mark Tilbury Coaching Ltd, business and investing coaching"],
                 links: [["Website", "https://www.marktilbury.com"],
                         ["UK company register", "https://find-and-update.company-information.service.gov.uk/search?q=Mark+Tilbury"]]),
    ]

    static func load() -> [Followed] {
        guard let d = UserDefaults.standard.data(forKey: "following"),
              let list = try? JSONDecoder().decode([Followed].self, from: d) else { return defaults }
        return list
    }

    static func save(_ list: [Followed]) {
        if let d = try? JSONEncoder().encode(list) { UserDefaults.standard.set(d, forKey: "following") }
    }

    /// Latest uploads from their YouTube channel (public RSS feed).
    static func videos(_ f: Followed) async -> [Video] {
        let handle = f.youtube.trimmingCharacters(in: .whitespaces).replacingOccurrences(of: "@", with: "")
        guard !handle.isEmpty else { return [] }
        let key = "yt_channel_" + handle
        var channel = UserDefaults.standard.string(forKey: key)
        if channel == nil, let page = await getText("https://www.youtube.com/@\(handle)") {
            for pattern in [#""(?:channelId|externalId)":"(UC[\w-]{22})""#, #"youtube\.com/channel/(UC[\w-]{22})"#] {
                if let r = page.range(of: pattern, options: .regularExpression) {
                    let m = String(page[r])
                    if let u = m.range(of: #"UC[\w-]{22}"#, options: .regularExpression) { channel = String(m[u]); break }
                }
            }
            if let c = channel { UserDefaults.standard.set(c, forKey: key) }
        }
        let feed = channel.map { "https://www.youtube.com/feeds/videos.xml?channel_id=\($0)" }
            ?? "https://www.youtube.com/feeds/videos.xml?user=\(handle)"
        guard let data = await getData(feed), let root = XNode.parse(data) else { return [] }
        let iso = ISO8601DateFormatter()
        return root.all("entry").compactMap { e in
            let title = (e.child("title")?.text ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            let link = e.child("link")?.attrs["href"] ?? ""
            guard !title.isEmpty, !link.isEmpty else { return nil }
            let vid = (e.child("videoId")?.text ?? link).trimmingCharacters(in: .whitespacesAndNewlines)
            return Video(title: title, url: link, time: iso.date(from: e.child("published")?.text ?? "") ?? Date(),
                         id: "yt:" + vid)
        }
    }

    private static func getData(_ url: String) async -> Data? {
        guard let u = URL(string: url) else { return nil }
        var req = URLRequest(url: u)
        req.setValue("Mozilla/5.0 (iPhone) StockTracker/1.0", forHTTPHeaderField: "User-Agent")
        req.setValue("en-US", forHTTPHeaderField: "Accept-Language")
        req.timeoutInterval = 20
        guard let (d, r) = try? await URLSession.shared.data(for: req),
              (r as? HTTPURLResponse)?.statusCode ?? 0 < 300 else { return nil }
        return d
    }

    private static func getText(_ url: String) async -> String? {
        await getData(url).flatMap { String(data: $0, encoding: .utf8) }
    }
}
