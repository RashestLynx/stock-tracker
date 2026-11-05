import Foundation
import UserNotifications
import BackgroundTasks

/// Settings, saved data, and the logic that checks every source for new filings.
@MainActor
final class AppModel: ObservableObject {
    static let refreshTaskID = "com.rashestlynx.stocktracker.refresh"
    private let d = UserDefaults.standard

    // ---------------- settings ----------------
    @Published var userAgent: String { didSet { d.set(userAgent, forKey: "ua") } }
    @Published var tickers: [String] { didSet { d.set(tickers, forKey: "tickers") } }
    @Published var buysOnly: Bool { didSet { d.set(buysOnly, forKey: "buysOnly") } }
    @Published var minValue: Double { didSet { d.set(minValue, forKey: "minValue") } }
    @Published var watching: [String: Bool] { didSet { d.set(watching, forKey: "watching") } }
    @Published var backgroundOn: Bool { didSet { d.set(backgroundOn, forKey: "backgroundOn") } }
    @Published var bargoKey: String { didSet { d.set(bargoKey, forKey: "bargoKey") } }
    @Published var status: String { didSet { d.set(status, forKey: "status") } }
    private var lastCongressCheck: Date { didSet { d.set(lastCongressCheck, forKey: "lastCongress") } }

    // ---------------- data ----------------
    @Published private(set) var alerts: [Alert]
    @Published private(set) var picks: [Pick]
    @Published private(set) var isChecking = false
    private var seen: [String]

    var isSetUp: Bool { userAgent.contains("@") }
    func isWatching(_ c: Category) -> Bool { watching[c.rawValue] ?? true }
    func setWatching(_ c: Category, _ on: Bool) { watching[c.rawValue] = on }

    init() {
        userAgent = d.string(forKey: "ua") ?? ""
        tickers = d.stringArray(forKey: "tickers") ?? []
        buysOnly = d.bool(forKey: "buysOnly")
        minValue = d.double(forKey: "minValue")
        watching = d.dictionary(forKey: "watching") as? [String: Bool] ?? [:]
        backgroundOn = d.object(forKey: "backgroundOn") as? Bool ?? true
        bargoKey = d.string(forKey: "bargoKey") ?? ""
        status = d.string(forKey: "status") ?? "Not checked yet"
        lastCongressCheck = d.object(forKey: "lastCongress") as? Date ?? .distantPast
        alerts = Self.load("alerts.json") ?? []
        picks = Self.load("picks.json") ?? []
        seen = Self.load("seen.json") ?? []
    }

    func clearAlerts() {
        alerts = []
        Self.save(alerts, "alerts.json")
    }

    func resetCongressTimer() { lastCongressCheck = .distantPast }

    // ---------------- checking ----------------
    func check(notify: Bool = true) async {
        guard isSetUp else { status = "Add your email in Settings to start"; return }
        guard !isChecking else { return }
        isChecking = true
        defer { isChecking = false }
        status = "Checking…"

        let ua = userAgent
        // First run: load what's already posted into the app, but don't send notifications.
        let firstRun = seen.isEmpty
        var seenSet = Set(seen)
        var newAlerts: [Alert] = []
        var newPicks: [Pick] = []
        var newSeen: [String] = []
        var errors: [String] = []
        let watch = Set(tickers)

        func passes(_ ticker: String, isBuy: Bool, amount: Double) -> Bool {
            if !watch.isEmpty && !watch.contains(ticker) { return false }
            if buysOnly && !isBuy { return false }
            if minValue > 0 && amount < minValue { return false }
            return true
        }
        func markSeen(_ k: String) { seenSet.insert(k); newSeen.append(k) }

        // ----- Congress (throttled to stay inside the free daily limit) -----
        if isWatching(.senate) || isWatching(.house) {
            let hasKey = !bargoKey.isEmpty
            let every: TimeInterval = hasKey ? 3600 : 5 * 3600
            if Date().timeIntervalSince(lastCongressCheck) >= every {
                do {
                    let trades = try await Congress.recent(limit: hasKey ? 40 : 20, apiKey: bargoKey)
                    lastCongressCheck = Date()
                    for t in trades where !seenSet.contains(t.key) {
                        markSeen(t.key)
                        let cat: Category = t.chamber == "senate" ? .senate : .house
                        if t.isBuy && !t.ticker.isEmpty {
                            newPicks.append(Pick(time: Format.day(t.transactionDate), ticker: t.ticker,
                                                 company: t.asset, who: t.member, category: cat, action: "buy"))
                        }
                        guard isWatching(cat), passes(t.ticker, isBuy: t.isBuy, amount: t.amountLow) else { continue }
                        let action = t.isBuy ? "Bought" : t.isSell ? "Sold" : (t.type.isEmpty ? "Traded" : t.type.capitalized)
                        let who = (cat == .senate ? "Sen. " : "Rep. ") + t.member + (t.state.isEmpty ? "" : " · \(t.state)")
                        newAlerts.append(Alert(
                            time: Date(), category: cat, ticker: t.ticker, company: t.asset, headline: who,
                            lines: ["\(action) \(t.amountRange)".trimmingCharacters(in: .whitespaces),
                                    "Traded \(t.transactionDate) · disclosed \(t.disclosureDate)"],
                            link: t.portal.isEmpty ? Congress.sourceURL : t.portal, key: t.key))
                    }
                } catch {
                    errors.append(error.localizedDescription)
                }
            }
        }

        // ----- Insiders (Form 4) -----
        if isWatching(.insider) {
            do {
                let filings = try await Edgar.feed("4", ua: ua).filter { !seenSet.contains($0.accession) }
                for f in filings.prefix(firstRun ? 40 : 100) {
                    markSeen(f.accession)
                    guard let info = try? await Edgar.parseForm4(f, ua: ua) else { continue }
                    let who = info.owners.isEmpty ? "Unknown" : info.owners.joined(separator: ", ")
                    if info.trades.contains(where: { $0.code == "P" }) && !info.ticker.isEmpty {
                        newPicks.append(Pick(time: Date(), ticker: info.ticker, company: info.issuer,
                                             who: who, category: .insider, action: "buy"))
                    }
                    if !watch.isEmpty && !watch.contains(info.ticker) { continue }
                    var trades = info.trades
                    if buysOnly { trades = trades.filter { $0.code == "P" } }
                    if minValue > 0 { trades = trades.filter { $0.value >= minValue } }
                    if trades.isEmpty && (buysOnly || minValue > 0) { continue }
                    let role = info.roles.isEmpty ? "" : " · " + info.roles.joined(separator: ", ")
                    let lines = Edgar.summarize(trades)
                    newAlerts.append(Alert(
                        time: Date(), category: .insider, ticker: info.ticker, company: info.issuer,
                        headline: who + role, lines: lines.isEmpty ? ["Options/derivatives only"] : lines,
                        link: f.link, key: f.accession))
                }
            } catch {
                errors.append("Insiders: \(error.localizedDescription)")
            }
        }

        // ----- Funds (13F) -----
        if isWatching(.fund) {
            do {
                let filings = try await Edgar.feed("13F-HR", ua: ua).filter { !seenSet.contains($0.accession) }
                let fresh = Array(filings.prefix(firstRun ? 12 : 40))
                if !fresh.isEmpty {
                    let map = await tickerMap(ua: ua)
                    for f in fresh {
                        markSeen(f.accession)
                        let fund = f.title
                            .replacingOccurrences(of: #"^13F-HR\s*-\s*"#, with: "", options: .regularExpression)
                            .replacingOccurrences(of: #"\s*\(\d+\)\s*\(Filer\)\s*$"#, with: "", options: .regularExpression)
                        let holdings = (try? await Edgar.parse13FHoldings(f, ua: ua)) ?? []
                        let total = holdings.reduce(0) { $0 + $1.1 }
                        let mapped: [(ticker: String, name: String)] = holdings.compactMap { h in
                            map[Edgar.normalizeName(h.0)].map { (ticker: $0, name: h.0.capitalized) }
                        }
                        for m in mapped {
                            newPicks.append(Pick(time: Date(), ticker: m.ticker, company: m.name,
                                                 who: fund, category: .fund, action: "hold"))
                        }
                        if !watch.isEmpty && !mapped.contains(where: { watch.contains($0.ticker) }) { continue }
                        var lines: [String] = []
                        if !mapped.isEmpty {
                            lines.append("Top holdings: " + mapped.prefix(6).map(\.ticker).joined(separator: ", "))
                        }
                        if total > 0 { lines.append("Top \(holdings.count) positions worth \(Format.money(total))") }
                        newAlerts.append(Alert(
                            time: Date(), category: .fund, ticker: "", company: "Quarterly holdings report",
                            headline: fund, lines: lines, link: f.link, key: f.accession))
                    }
                }
            } catch {
                errors.append("Funds: \(error.localizedDescription)")
            }
        }

        // ----- save -----
        addPicks(newPicks)
        let newKeys = Set(newAlerts.map(\.key))
        alerts = Array((newAlerts + alerts.filter { !newKeys.contains($0.key) }).prefix(600))
        seen = Array((seen + newSeen).suffix(5000))
        Self.save(alerts, "alerts.json")
        Self.save(seen, "seen.json")
        if !firstRun && notify { await post(newAlerts) }

        let time = Date().formatted(date: .omitted, time: .shortened)
        if !errors.isEmpty && newAlerts.isEmpty { status = "Error at \(time): \(errors[0])" }
        else if firstRun { status = "Loaded \(newAlerts.count) recent filings · \(time)" }
        else if newAlerts.isEmpty { status = "Up to date · checked \(time)" }
        else { status = "\(newAlerts.count) new · checked \(time)" }
    }

    private func addPicks(_ new: [Pick]) {
        guard !new.isEmpty else { return }
        let cutoff = Date().addingTimeInterval(-90 * 86_400)
        var merged: [String: Pick] = [:]
        for p in new + picks where p.time >= cutoff && !p.ticker.isEmpty {
            let k = "\(p.who)|\(p.ticker)|\(p.action)"
            if let old = merged[k], old.time >= p.time { continue }
            merged[k] = p
        }
        picks = Array(merged.values.sorted { $0.time > $1.time }.prefix(6000))
        Self.save(picks, "picks.json")
    }

    /// SEC name -> ticker map, cached for a week.
    private func tickerMap(ua: String) async -> [String: String] {
        let url = Self.dir.appendingPathComponent("tickers.json")
        if let attrs = try? FileManager.default.attributesOfItem(atPath: url.path),
           let modified = attrs[.modificationDate] as? Date,
           Date().timeIntervalSince(modified) < 7 * 86_400,
           let cached: [String: String] = Self.load("tickers.json") {
            return cached
        }
        guard let map = try? await Edgar.companyTickers(ua: ua) else { return [:] }
        Self.save(map, "tickers.json")
        return map
    }

    // ---------------- notifications ----------------
    func requestNotificationPermission() {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { _, _ in }
    }

    private func post(_ new: [Alert]) async {
        guard !new.isEmpty else { return }
        let center = UNUserNotificationCenter.current()
        if new.count > 4 {
            // Too many to show one by one: send a single summary.
            var counts: [Category: Int] = [:]
            new.forEach { counts[$0.category, default: 0] += 1 }
            let content = UNMutableNotificationContent()
            content.title = "\(new.count) new filings"
            content.body = Category.allCases.compactMap { c in counts[c].map { "\(c.label) \($0)" } }
                .joined(separator: " · ")
            content.sound = .default
            try? await center.add(UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: nil))
            return
        }
        for a in new {
            let content = UNMutableNotificationContent()
            content.title = a.notificationTitle
            content.body = a.notificationBody
            content.sound = .default
            content.userInfo = ["link": a.link]
            try? await center.add(UNNotificationRequest(identifier: a.key, content: content, trigger: nil))
        }
    }

    // ---------------- background refresh ----------------
    /// Ask iOS to wake the app to check. iOS decides the actual timing (often every hour or few).
    func scheduleBackgroundRefresh() {
        guard backgroundOn && isSetUp else {
            BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: Self.refreshTaskID)
            return
        }
        let req = BGAppRefreshTaskRequest(identifier: Self.refreshTaskID)
        req.earliestBeginDate = Date().addingTimeInterval(15 * 60)
        try? BGTaskScheduler.shared.submit(req)
    }

    // ---------------- file storage ----------------
    private static var dir: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
        return base
    }

    private static func load<T: Decodable>(_ name: String) -> T? {
        guard let data = try? Data(contentsOf: dir.appendingPathComponent(name)) else { return nil }
        return try? JSONDecoder().decode(T.self, from: data)
    }

    private static func save<T: Encodable>(_ value: T, _ name: String) {
        guard let data = try? JSONEncoder().encode(value) else { return }
        try? data.write(to: dir.appendingPathComponent(name), options: .atomic)
    }
}
