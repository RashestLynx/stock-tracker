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
    /// Stocks and funds the user keeps an eye on (separate from the alert-only ticker filter).
    @Published var watchlist: [String] { didSet { d.set(watchlist, forKey: "watchlist") } }
    @Published var buysOnly: Bool { didSet { d.set(buysOnly, forKey: "buysOnly") } }
    @Published var minValue: Double { didSet { d.set(minValue, forKey: "minValue") } }
    @Published var watching: [String: Bool] { didSet { d.set(watching, forKey: "watching") } }
    @Published var backgroundOn: Bool { didSet { d.set(backgroundOn, forKey: "backgroundOn") } }
    @Published var bargoKey: String { didSet { d.set(bargoKey, forKey: "bargoKey") } }
    @Published var massiveKey: String { didSet { d.set(massiveKey, forKey: "massiveKey"); movers = nil } }
    @Published var movers: MoversResult?
    @Published var status: String { didSet { d.set(status, forKey: "status") } }
    private var lastCongressCheck: Date { didSet { d.set(lastCongressCheck, forKey: "lastCongress") } }

    // ---------------- data ----------------
    @Published private(set) var alerts: [Alert]
    @Published private(set) var picks: [Pick]
    @Published private(set) var moves: [Move]
    @Published private(set) var isChecking = false
    /// The stock whose summary sheet is open.
    @Published var openStock: StockTarget?
    /// The investor whose profile sheet is open.
    @Published var openPerson: PersonTarget?
    @Published var showSettings = false
    @Published var newsAlerts: Bool { didSet { d.set(newsAlerts, forKey: "newsAlerts") } }
    private var seen: [String]

    var isSetUp: Bool { userAgent.contains("@") }
    func isWatching(_ c: Category) -> Bool { watching[c.rawValue] ?? true }
    func setWatching(_ c: Category, _ on: Bool) { watching[c.rawValue] = on }

    init() {
        userAgent = d.string(forKey: "ua") ?? ""
        tickers = d.stringArray(forKey: "tickers") ?? []
        watchlist = d.stringArray(forKey: "watchlist") ?? []
        buysOnly = d.bool(forKey: "buysOnly")
        minValue = d.double(forKey: "minValue")
        watching = d.dictionary(forKey: "watching") as? [String: Bool] ?? [:]
        backgroundOn = d.object(forKey: "backgroundOn") as? Bool ?? true
        // The user's own Bargo key is built in (hourly Congress checks); a key saved in Settings replaces it.
        let savedBargo = d.string(forKey: "bargoKey") ?? ""
        bargoKey = savedBargo.isEmpty ? "fak_PAPE7zAnmQGjv-zKB7HlKR_tbVSP-cziSmE12TOfJ_6z" : savedBargo
        // The user's own Massive key is built in; a key saved in Settings replaces it.
        let savedMassive = d.string(forKey: "massiveKey") ?? ""
        massiveKey = savedMassive.isEmpty ? "0h37HkvcAnafUh9rx27J_3NePJpqmQpx" : savedMassive
        newsAlerts = d.object(forKey: "newsAlerts") as? Bool ?? true
        movers = Self.load("movers.json")
        status = d.string(forKey: "status") ?? "Not checked yet"
        lastCongressCheck = d.object(forKey: "lastCongress") as? Date ?? .distantPast
        alerts = Self.load("alerts.json") ?? []
        picks = Self.load("picks.json") ?? []
        moves = Self.load("moves.json") ?? []
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
        var newMoves: [Move] = []
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
                        let pk = People.congressKey(slug: t.memberSlug, chamber: t.chamber, state: t.state, member: t.member)
                        if t.isBuy && !t.ticker.isEmpty {
                            newPicks.append(Pick(time: Format.day(t.transactionDate), ticker: t.ticker,
                                                 company: t.asset, who: t.member, category: cat, action: "buy", person: pk))
                        }
                        if (t.isBuy || t.isSell) && !t.ticker.isEmpty && t.estValue > 0 {
                            newMoves.append(Move(
                                // Use the disclosure day so it counts as "today" when it's made public.
                                time: t.disclosureDate.isEmpty ? Date() : Format.day(t.disclosureDate),
                                category: cat, ticker: t.ticker, company: t.asset,
                                who: (cat == .senate ? "Sen. " : "Rep. ") + t.member,
                                role: cat == .senate ? "Senator" : "Representative",
                                side: t.isBuy ? "buy" : "sell", value: t.estValue,
                                valueLabel: t.amountRange.isEmpty ? Format.money(t.estValue) : t.amountRange,
                                link: t.portal.isEmpty ? Congress.sourceURL : t.portal, key: t.key,
                                price: t.estPrice > 0 ? t.estPrice : nil, person: pk))
                        }
                        guard isWatching(cat), passes(t.ticker, isBuy: t.isBuy, amount: t.amountLow) else { continue }
                        let action = t.isBuy ? "Bought" : t.isSell ? "Sold" : (t.type.isEmpty ? "Traded" : t.type.capitalized)
                        let who = (cat == .senate ? "Sen. " : "Rep. ") + t.member + (t.state.isEmpty ? "" : " · \(t.state)")
                        newAlerts.append(Alert(
                            time: Date(), category: cat, ticker: t.ticker, company: t.asset, headline: who,
                            lines: ["\(action) \(t.amountRange)".trimmingCharacters(in: .whitespaces),
                                    "Traded \(t.transactionDate) · disclosed \(t.disclosureDate)"],
                            link: t.portal.isEmpty ? Congress.sourceURL : t.portal, key: t.key, person: pk))
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
                    let pk = info.ownerCiks.first.map { "ins:\($0)" }
                    if info.trades.contains(where: { $0.code == "P" }) && !info.ticker.isEmpty {
                        newPicks.append(Pick(time: Date(), ticker: info.ticker, company: info.issuer,
                                             who: who, category: .insider, action: "buy", person: pk))
                    }
                    if !info.ticker.isEmpty {
                        for (code, side) in [("P", "buy"), ("S", "sell")] {
                            let these = info.trades.filter { $0.code == code }
                            let v = these.reduce(0) { $0 + $1.value }
                            let shares = these.reduce(0) { $0 + $1.shares }
                            if v > 0 {
                                newMoves.append(Move(
                                    time: Date(), category: .insider, ticker: info.ticker, company: info.issuer,
                                    who: who, role: info.roles.joined(separator: ", "), side: side, value: v,
                                    valueLabel: Format.money(v), link: f.link, key: "\(f.accession)|\(side)",
                                    price: shares > 0 ? v / shares : nil, person: pk))
                            }
                        }
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
                        link: f.link, key: f.accession, person: pk))
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
                        let fundCik = f.title.range(of: #"\((\d+)\)"#, options: .regularExpression)
                            .map { String(f.title[$0].dropFirst().dropLast().drop(while: { $0 == "0" })) }
                        let pk = fundCik.map { "fund:\($0)" }
                        let rows = (try? await Edgar.rows13F(f, ua: ua)) ?? []
                        let holdings = Edgar.top13F(rows)
                        var cryptoTotals: [String: Double] = [:]
                        for r in rows where !r.option && Crypto.assetOf(r.name) != nil { cryptoTotals[r.name, default: 0] += r.value }
                        CryptoStore.add(cryptoTotals.map { CryptoPos(fund: fund, asset: Crypto.assetOf($0.key)!, product: $0.key, value: $0.value, time: Date()) })
                        let total = holdings.reduce(0) { $0 + $1.1 }
                        let mapped: [(ticker: String, name: String)] = holdings.compactMap { h in
                            map[Edgar.normalizeName(h.0)].map { (ticker: $0, name: h.0.capitalized) }
                        }
                        for m in mapped {
                            newPicks.append(Pick(time: Date(), ticker: m.ticker, company: m.name,
                                                 who: fund, category: .fund, action: "hold", person: pk))
                        }
                        if !watch.isEmpty && !mapped.contains(where: { watch.contains($0.ticker) }) { continue }
                        var lines: [String] = []
                        if !mapped.isEmpty {
                            lines.append("Top holdings: " + mapped.prefix(6).map(\.ticker).joined(separator: ", "))
                        }
                        if total > 0 { lines.append("Top \(holdings.count) positions worth \(Format.money(total))") }
                        newAlerts.append(Alert(
                            time: Date(), category: .fund, ticker: "", company: "Quarterly holdings report",
                            headline: fund, lines: lines, link: f.link, key: f.accession, person: pk))
                    }
                }
            } catch {
                errors.append("Funds: \(error.localizedDescription)")
            }
        }

        // ----- SEC insider trading cases -----
        if isWatching(.charges) {
            do {
                for c in try await Enforcement.insiderTradingCases(ua: ua) where !seenSet.contains(c.key) {
                    markSeen(c.key)
                    newAlerts.append(Alert(
                        time: Date(), category: .charges, ticker: "",
                        company: "SEC enforcement action" + (c.date.isEmpty ? "" : " · \(c.date)"),
                        headline: c.title, lines: c.summary.isEmpty ? [] : [c.summary],
                        link: c.link.isEmpty ? "https://www.sec.gov/enforcement-litigation/litigation-releases" : c.link,
                        key: c.key))
                }
            } catch {
                errors.append("SEC charges: \(error.localizedDescription)")
            }
        }

        // ----- news about watchlist stocks -----
        var newsToPost: [NewsItem] = []
        let newsWatch = Set(tickers + watchlist)
        if newsAlerts && !newsWatch.isEmpty {
            var items: [NewsItem] = []
            let ciks = await cikMap()
            if let f = try? await News.filings(ua: ua, ciks: ciks) { items += f.filter { newsWatch.contains($0.ticker) } }
            if let n = try? await News.forTickers(Array(newsWatch)) { items += n.filter { !$0.ticker.isEmpty } }
            for n in items where !seenSet.contains("news:" + n.url) && Date().timeIntervalSince(n.time) < 6 * 3600 {
                markSeen("news:" + n.url)
                newsToPost.append(n)
            }
        }

        // ----- new videos from people you follow -----
        var videosToPost: [(String, Video)] = []
        for f in Following.load() {
            let vids = await Following.videos(f)
            let known = vids.contains { seenSet.contains($0.id) }
            for v in vids where !seenSet.contains(v.id) {
                markSeen(v.id)
                if known { videosToPost.append((f.name, v)) }
            }
        }

        // ----- save -----
        addMoves(newMoves)
        addPicks(newPicks)
        let newKeys = Set(newAlerts.map(\.key))
        alerts = Array((newAlerts + alerts.filter { !newKeys.contains($0.key) }).prefix(600))
        seen = Array((seen + newSeen).suffix(5000))
        Self.save(alerts, "alerts.json")
        Self.save(seen, "seen.json")
        if !firstRun && notify {
            await post(newAlerts)
            for (name, v) in videosToPost.prefix(3) {
                let c = UNMutableNotificationContent()
                c.title = "\(name) posted a new video"
                c.body = v.title
                c.sound = .default
                c.userInfo = ["link": v.url]
                try? await UNUserNotificationCenter.current().add(
                    UNNotificationRequest(identifier: v.id, content: c, trigger: nil))
            }
            for n in newsToPost.prefix(3) {
                let c = UNMutableNotificationContent()
                c.title = n.isFiling ? "\(n.ticker) filed with the SEC" : "\(n.ticker) in the news"
                c.body = n.isFiling ? "\(n.title)\n\(n.detail)" : "\(n.title)\n\(n.source)"
                c.sound = .default
                c.userInfo = ["link": n.url]
                try? await UNUserNotificationCenter.current().add(
                    UNNotificationRequest(identifier: "news:" + n.url, content: c, trigger: nil))
            }
        }

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

    private func addMoves(_ new: [Move]) {
        guard !new.isEmpty else { return }
        let cutoff = Date().addingTimeInterval(-7 * 86_400)
        var seenKeys = Set<String>()
        moves = (new + moves).filter { m in
            guard m.time >= cutoff, !seenKeys.contains(m.key) else { return false }
            seenKeys.insert(m.key)
            return true
        }
        moves = Array(moves.sorted { $0.time > $1.time }.prefix(3000))
        Self.save(moves, "moves.json")
    }

    /// Loads the week's best and worst performers (cached for 6 hours unless forced).
    func loadMovers(force: Bool) async throws {
        if !force, let m = movers, Date().timeIntervalSince(m.fetchedAt) < 6 * 3600 { return }
        let tracked = Set(picks.map(\.ticker) + moves.map(\.ticker))
        let names = await tickerNames()
        let r = try await Movers.load(key: massiveKey, names: names, tracked: tracked)
        movers = r
        Self.save(r, "movers.json")
    }

    private func tickerNames() async -> [String: String] {
        let url = Self.dir.appendingPathComponent("names.json")
        if let attrs = try? FileManager.default.attributesOfItem(atPath: url.path),
           let modified = attrs[.modificationDate] as? Date,
           Date().timeIntervalSince(modified) < 7 * 86_400,
           let cached: [String: String] = Self.load("names.json") {
            return cached
        }
        guard let map = try? await Edgar.tickerNames(ua: userAgent) else { return [:] }
        Self.save(map, "names.json")
        return map
    }

    /// Ticker -> SEC CIK number, cached for a week.
    func cik(for ticker: String) async -> Int? {
        await cikMap()[ticker.uppercased()]
    }

    func cikMap() async -> [String: Int] {
        let url = Self.dir.appendingPathComponent("ciks.json")
        if let attrs = try? FileManager.default.attributesOfItem(atPath: url.path),
           let modified = attrs[.modificationDate] as? Date,
           Date().timeIntervalSince(modified) < 7 * 86_400,
           let cached: [String: Int] = Self.load("ciks.json") {
            return cached
        }
        guard let map = try? await Edgar.tickerCiks(ua: userAgent) else { return [:] }
        Self.save(map, "ciks.json")
        return map
    }

    /// SEC name -> ticker map, cached for a week.
    func tickerMap(ua: String) async -> [String: String] {
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
