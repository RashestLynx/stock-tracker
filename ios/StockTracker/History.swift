import Foundation

/// Loads older filings a batch at a time, after each regular check, so every section fills in its
/// history instead of starting from the day the app was installed. Older items never send
/// notifications. Progress is saved, so it picks up where it left off.
///
/// - SEC charges: the SEC's press-release and litigation-release archives, searched for insider trading.
/// - Senate and House: older pages from the Bargo Congress Trades API.
/// - Insiders and Funds: the SEC's daily index of every filing, one day at a time, back one year.
enum History {
    private static let d = UserDefaults.standard

    static var chargesPage: Int { get { d.integer(forKey: "hist_charges_page") } set { d.set(newValue, forKey: "hist_charges_page") } }
    static var chargesDone: Bool { get { d.bool(forKey: "hist_charges_done") } set { d.set(newValue, forKey: "hist_charges_done") } }
    static var congressPage: Int {
        get { d.object(forKey: "hist_congress_page") as? Int ?? 2 }
        set { d.set(newValue, forKey: "hist_congress_page") }
    }
    static var congressDone: Bool { get { d.bool(forKey: "hist_congress_done") } set { d.set(newValue, forKey: "hist_congress_done") } }

    /// Older Congress pages loaded today (kept small to leave room in Bargo's daily limit for new trades).
    static var congressPagesToday: Int {
        d.string(forKey: "hist_congress_day") == today() ? d.integer(forKey: "hist_congress_count") : 0
    }
    static func countCongressPage() {
        let n = congressPagesToday + 1
        d.set(today(), forKey: "hist_congress_day")
        d.set(n, forKey: "hist_congress_count")
    }

    /// The day (yyyyMMdd) whose filings are being loaded, and how many of that day's entries are done.
    static var day: String {
        get {
            if let s = d.string(forKey: "hist_day") { return s }
            let s = shift(today(), -1); d.set(s, forKey: "hist_day"); return s
        }
        set { d.set(newValue, forKey: "hist_day") }
    }
    static var offset: Int { get { d.integer(forKey: "hist_offset") } set { d.set(newValue, forKey: "hist_offset") } }
    /// Oldest day to load filings for: one year before history loading started.
    static var stopDay: String {
        if let s = d.string(forKey: "hist_stop") { return s }
        let s = shift(today(), -365); d.set(s, forKey: "hist_stop"); return s
    }
    static var filingsDone: Bool { day < stopDay }

    /// Shown in the Sections tab.
    static var status: String {
        if filingsDone && chargesDone && congressDone { return "Full history loaded" }
        guard let date = fmt.date(from: day) else { return "" }
        return "Loading history: insider and fund filings back to \(date.formatted(date: .abbreviated, time: .omitted)) so far. Older filings keep loading with each check."
    }

    private static let fmt: DateFormatter = {
        let f = DateFormatter(); f.locale = Locale(identifier: "en_US_POSIX"); f.dateFormat = "yyyyMMdd"; return f
    }()
    static func today() -> String { fmt.string(from: Date()) }
    static func shift(_ day: String, _ days: Int) -> String {
        guard let date = fmt.date(from: day), let n = Calendar.current.date(byAdding: .day, value: days, to: date) else { return day }
        return fmt.string(from: n)
    }
    static func dayDate(_ day: String) -> Date { fmt.date(from: day).map { $0.addingTimeInterval(12 * 3600) } ?? Date() }

    /// One row of the SEC daily index.
    struct Entry { let form: String; let filing: Filing; let time: Date }

    private static let row = try! NSRegularExpression(
        pattern: #"^(\S+(?: \S+)*?)\s{2,}(.+?)\s{2,}(\d+)\s+(\d{8}|\d{4}-\d{2}-\d{2})\s+(edgar/data/\S+)\s*$"#)

    /// Every Form 4 and 13F-HR filed on `day` (yyyyMMdd). Nil when there's no index that day (weekends, holidays).
    static func dailyIndex(_ day: String, ua: String) async throws -> [Entry]? {
        let year = day.prefix(4)
        let qtr = ((Int(day.dropFirst(4).prefix(2)) ?? 1) - 1) / 3 + 1
        let data: Data
        do {
            data = try await Edgar.get("https://www.sec.gov/Archives/edgar/daily-index/\(year)/QTR\(qtr)/form.\(day).idx", ua: ua)
        } catch {
            let m = error.localizedDescription
            if m.contains("403") || m.contains("404") { return nil }
            throw error
        }
        let text = String(decoding: data, as: UTF8.self)
        let time = dayDate(day)
        var out: [Entry] = []
        var seen = Set<String>()
        for line in text.split(separator: "\n", omittingEmptySubsequences: true) {
            let s = String(line).trimmingCharacters(in: .newlines)
            guard let m = row.firstMatch(in: s, range: NSRange(s.startIndex..., in: s)) else { continue }
            func g(_ i: Int) -> String { Range(m.range(at: i), in: s).map { String(s[$0]) } ?? "" }
            let form = g(1)
            guard form == "4" || form == "13F-HR" else { continue }
            let company = g(2).trimmingCharacters(in: .whitespaces)
            let cik = g(3)
            let file = g(5)
            let acc = String(file.split(separator: "/").last ?? "").replacingOccurrences(of: ".txt", with: "")
            guard seen.insert(acc).inserted else { continue }   // Form 4s are listed under the company and the insider
            let cikTrim = String(cik.drop(while: { $0 == "0" }))
            let folder = "https://www.sec.gov/Archives/edgar/data/\(cikTrim)/\(acc.replacingOccurrences(of: "-", with: ""))/"
            let title = form == "13F-HR" ? "13F-HR - \(company) (\(cik)) (Filer)" : company
            out.append(Entry(form: form, filing: Filing(accession: acc, form: form, title: title, link: folder + "\(acc)-index.htm"), time: time))
        }
        return out
    }

    private static let anchor = try! NSRegularExpression(
        pattern: ##"href="((?:https://www\.sec\.gov)?/(?:newsroom/press-releases|enforcement-litigation/litigation-releases)/[^"#?]+)"[^>]*>(.*?)</a>"##,
        options: [.dotMatchesLineSeparators])
    private static let datetime = try! NSRegularExpression(pattern: #"datetime="(\d{4})-(\d{2})-(\d{2})"#)
    private static let months = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"]

    /// One page (0 = newest) of the SEC's press releases and litigation releases about insider trading.
    static func chargesPage(_ page: Int, ua: String) async throws -> [Charge] {
        let urls = [
            "https://www.sec.gov/newsroom/press-releases?combine=insider%20trading&year=All&month=All&page=\(page)",
            "https://www.sec.gov/enforcement-litigation/litigation-releases?combine=insider%20trading&year=All&month=All&page=\(page)",
        ]
        var out: [Charge] = []
        var seen = Set<String>()
        var anyWorked = false
        for url in urls {
            guard let data = try? await Edgar.get(url, ua: ua) else { continue }
            anyWorked = true
            let html = String(decoding: data, as: UTF8.self)
            var rows = html.components(separatedBy: "<tr").dropFirst().map { $0 }
            if rows.isEmpty { rows = html.components(separatedBy: "class=\"views-row").dropFirst().map { $0 } }
            if rows.isEmpty { rows = [html] }
            for r in rows {
                let ns = NSRange(r.startIndex..., in: r)
                guard let a = anchor.firstMatch(in: r, range: ns),
                      let hr = Range(a.range(at: 1), in: r), let tr = Range(a.range(at: 2), in: r) else { continue }
                var link = String(r[hr]); if link.hasPrefix("/") { link = "https://www.sec.gov" + link }
                let title = clean(String(r[tr]))
                let text = clean(r).lowercased()
                guard !title.isEmpty, text.contains("insider trading") || text.contains("insider-trading"),
                      seen.insert(link).inserted else { continue }
                var date = ""
                if let m = datetime.firstMatch(in: r, range: ns),
                   let y = Range(m.range(at: 1), in: r), let mo = Range(m.range(at: 2), in: r), let dd = Range(m.range(at: 3), in: r),
                   let mi = Int(r[mo]), let di = Int(r[dd]), (1...12).contains(mi) {
                    date = "\(months[mi - 1]) \(di), \(r[y])"
                }
                out.append(Charge(title: title, link: link, summary: "", date: date, key: "sec:" + link))
            }
        }
        if !anyWorked { throw AppError("SEC archive unavailable") }
        return out
    }

    /// "Nov 4, 2026" -> date (now if it can't be read).
    static func chargeDate(_ s: String) -> Date {
        let f = DateFormatter(); f.locale = Locale(identifier: "en_US_POSIX"); f.dateFormat = "MMM d, yyyy"
        return f.date(from: s) ?? Date()
    }

    private static func clean(_ s: String) -> String {
        s.replacingOccurrences(of: "<[^>]+>", with: " ", options: .regularExpression)
            .replacingOccurrences(of: "&amp;", with: "&").replacingOccurrences(of: "&#039;", with: "'")
            .replacingOccurrences(of: "&quot;", with: "\"").replacingOccurrences(of: "&nbsp;", with: " ")
            .replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression)
            .trimmingCharacters(in: .whitespaces)
    }
}
