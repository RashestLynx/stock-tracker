import Foundation

/// One SEC enforcement case.
struct Charge {
    let title: String
    let link: String
    let summary: String
    let date: String
    let key: String
}

/// SEC enforcement actions about illegal insider trading, from the SEC's official RSS feeds
/// (litigation releases and press releases). These are cases the SEC has brought, not live trades.
enum Enforcement {
    private static let feeds = [
        "https://www.sec.gov/enforcement-litigation/litigation-releases/rss",
        "https://www.sec.gov/news/pressreleases.rss",
    ]

    static func insiderTradingCases(ua: String) async throws -> [Charge] {
        var out: [Charge] = []
        var keys = Set<String>()
        var anyWorked = false
        for url in feeds {
            guard let data = try? await Edgar.get(url, ua: ua), let root = XNode.parse(data) else { continue }
            anyWorked = true
            let items = (root.child("channel")?.all("item") ?? []) + root.all("entry")
            for it in items {
                let title = clean(it.child("title")?.text ?? "")
                let summary = firstSentences(clean(it.child("description")?.text ?? it.child("summary")?.text ?? ""), 2)
                let text = (title + " " + summary).lowercased()
                guard !title.isEmpty, text.contains("insider trading") || text.contains("insider-trading") else { continue }
                let link = (it.child("link")?.attrs["href"] ?? it.child("link")?.text ?? "")
                    .trimmingCharacters(in: .whitespacesAndNewlines)
                let guid = (it.child("guid")?.text ?? it.child("id")?.text ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
                let key = "sec:" + (guid.isEmpty ? (link.isEmpty ? title : link) : guid)
                guard keys.insert(key).inserted else { continue }
                let raw = it.child("pubDate")?.text ?? it.child("published")?.text ?? it.child("updated")?.text ?? ""
                out.append(Charge(title: title, link: link, summary: summary, date: shortDate(raw), key: key))
            }
        }
        if !anyWorked { throw AppError("SEC enforcement feed unavailable") }
        return out
    }

    /// Strip HTML tags and common entities from feed text.
    private static func clean(_ s: String) -> String {
        var t = s.replacingOccurrences(of: "<[^>]+>", with: " ", options: .regularExpression)
        for (e, r) in ["&amp;": "&", "&quot;": "\"", "&#39;": "'", "&apos;": "'", "&lt;": "<", "&gt;": ">", "&nbsp;": " "] {
            t = t.replacingOccurrences(of: e, with: r)
        }
        return t.replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression)
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private static func firstSentences(_ text: String, _ n: Int) -> String {
        var out: [String] = []
        text.enumerateSubstrings(in: text.startIndex..., options: .bySentences) { sub, _, _, stop in
            if let sub = sub { out.append(sub.trimmingCharacters(in: .whitespaces)) }
            if out.count >= n { stop = true }
        }
        return out.joined(separator: " ")
    }

    /// "Tue, 04 Nov 2026 10:15:00 -0500" -> "Nov 4, 2026".
    private static func shortDate(_ raw: String) -> String {
        guard let r = raw.range(of: #"(\d{1,2}) ([A-Za-z]{3}) (\d{4})"#, options: .regularExpression) else {
            return String(raw.prefix(10))
        }
        let parts = raw[r].split(separator: " ")
        return "\(parts[1]) \(Int(parts[0]) ?? 0), \(parts[2])"
    }
}
