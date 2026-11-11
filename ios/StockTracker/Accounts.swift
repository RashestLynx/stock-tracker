import CryptoKit
import Foundation
import Security

/// One thing you own. kind: "stock", "fund" or "cash".
struct OwnedHolding: Codable, Identifiable {
    var account: String
    var ticker: String
    var name: String
    var kind: String
    var value: Double
    var units: Double?
    var cost: Double?       // average price paid per share, if the brokerage knows it
    var id: String { account + "|" + ticker + "|" + name }
}

/// Something you typed in yourself, e.g. a T. Rowe Price retirement fund.
struct ManualHolding: Codable, Identifiable {
    var account: String
    var name: String
    var ticker: String
    var value: Double
    var updated: Date
    var id: String { account + "|" + name + "|" + ticker }
}

struct MyMoney {
    var holdings: [OwnedHolding]
    var error: String?
    var total: Double { holdings.reduce(0) { $0 + $1.value } }
}

/// A line in the checkup. good = true for reassuring notes, false for warnings.
struct CheckNote: Identifiable {
    let good: Bool
    let text: String
    var ticker = ""
    var id: String { text }
}

/// Your own accounts, read-only. Mirrors Accounts.kt.
///  - Robinhood through SnapTrade Personal (free for personal use). Your key is kept in the iPhone Keychain and only
///    used to sign requests to SnapTrade. The app can only read; it can't trade or move money.
///  - T. Rowe Price isn't supported by SnapTrade, so those funds are typed in by hand.
enum Accounts {
    static let signup = "https://dashboard.snaptrade.com/signup"
    private static let base = "https://api.snaptrade.com"

    // ---------------- saved settings ----------------
    static var clientId: String { Keychain.get("snap_client") }
    static var hasKey: Bool { !clientId.isEmpty && !Keychain.get("snap_key").isEmpty }

    static func saveKey(clientId: String, consumerKey: String) {
        Keychain.set("snap_client", clientId.trimmingCharacters(in: .whitespacesAndNewlines))
        Keychain.set("snap_key", consumerKey.trimmingCharacters(in: .whitespacesAndNewlines))
        UserDefaults.standard.removeObject(forKey: "accounts_cache")
    }

    static func forgetKey() { saveKey(clientId: "", consumerKey: "") }

    static var manual: [ManualHolding] {
        get {
            guard let d = UserDefaults.standard.data(forKey: "accounts_manual") else { return [] }
            return (try? JSONDecoder().decode([ManualHolding].self, from: d)) ?? []
        }
        set { UserDefaults.standard.set(try? JSONEncoder().encode(newValue), forKey: "accounts_manual") }
    }

    private static var cached: [OwnedHolding]? {
        get {
            guard let d = UserDefaults.standard.data(forKey: "accounts_cache") else { return nil }
            return try? JSONDecoder().decode([OwnedHolding].self, from: d)
        }
        set { UserDefaults.standard.set(try? JSONEncoder().encode(newValue), forKey: "accounts_cache") }
    }

    private static var cachedAt: Date {
        get { UserDefaults.standard.object(forKey: "accounts_cache_at") as? Date ?? .distantPast }
        set { UserDefaults.standard.set(newValue, forKey: "accounts_cache_at") }
    }

    // ---------------- loading ----------------
    /// Linked accounts are cached for 15 minutes unless forced.
    static func load(force: Bool) async -> MyMoney {
        let typed = manual.map {
            OwnedHolding(account: $0.account, ticker: $0.ticker.uppercased(), name: $0.name,
                    kind: $0.ticker.isEmpty ? "fund" : guessKind($0.ticker, $0.name), value: $0.value)
        }
        guard hasKey else { return MyMoney(holdings: typed) }
        if !force, Date().timeIntervalSince(cachedAt) < 900, let c = cached {
            return MyMoney(holdings: c + typed)
        }
        do {
            let linked = try await fetchLinked(clientId: clientId, key: Keychain.get("snap_key"))
            cached = linked
            cachedAt = Date()
            return MyMoney(holdings: linked + typed)
        } catch {
            return MyMoney(holdings: (cached ?? []) + typed, error: error.localizedDescription)
        }
    }

    /// Shares and value you hold of each ticker, from the last saved account data (no network).
    static func owned() -> [String: (units: Double?, value: Double)] {
        let linked = hasKey ? (cached ?? []) : []
        let typed = manual.filter { !$0.ticker.isEmpty }
            .map { OwnedHolding(account: $0.account, ticker: $0.ticker.uppercased(), name: $0.name, kind: "fund", value: $0.value) }
        var out: [String: (units: Double?, value: Double)] = [:]
        for (t, list) in Dictionary(grouping: (linked + typed).filter { !$0.ticker.isEmpty }, by: \.ticker) {
            let units: Double? = list.allSatisfy { $0.units != nil } ? list.reduce(0) { $0 + ($1.units ?? 0) } : nil
            out[t] = (units, list.reduce(0) { $0 + $1.value })
        }
        return out
    }

    private static func fetchLinked(clientId: String, key: String) async throws -> [OwnedHolding] {
        var out: [OwnedHolding] = []
        let accounts = try JSONSerialization.jsonObject(with: try await signedGet("/accounts", clientId: clientId, key: key)) as? [[String: Any]] ?? []
        for acct in accounts {
            guard let id = acct["id"] as? String else { continue }
            let inst = (acct["institution_name"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? "Brokerage"
            let name = acct["name"] as? String ?? ""
            let label = name.isEmpty || name.localizedCaseInsensitiveContains(inst) ? inst : "\(inst) \(name)"
            var path = "/accounts/\(id)/positions/all"
            for _ in 0..<10 {
                let page = try JSONSerialization.jsonObject(with: try await signedGet(path, clientId: clientId, key: key)) as? [String: Any] ?? [:]
                for p in page["results"] as? [[String: Any]] ?? [] {
                    let ins = p["instrument"] as? [String: Any] ?? [:]
                    guard let units = num(p["units"]), let price = num(p["price"]) else { continue }
                    var ticker = (ins["raw_symbol"] as? String) ?? ""
                    if ticker.isEmpty { ticker = ins["symbol"] as? String ?? "" }
                    ticker = ticker.uppercased()
                    let desc = ins["description"] as? String ?? ""
                    let kindRaw = (ins["kind"] as? String ?? "").lowercased()
                    let kind: String
                    if p["cash_equivalent"] as? Bool == true { kind = "cash" }
                    else if kindRaw.contains("fund") || kindRaw.contains("etf") { kind = "fund" }
                    else { kind = guessKind(ticker, desc) }
                    out.append(OwnedHolding(account: label, ticker: ticker, name: desc.isEmpty ? ticker : desc, kind: kind,
                                       value: units * price, units: units, cost: num(p["cost_basis"])))
                }
                guard let next = page["next"] as? String, !next.isEmpty, let u = URLComponents(string: next) else { break }
                path = u.path.hasPrefix("/api/v1") ? String(u.path.dropFirst(7)) : u.path
                let extra = (u.percentEncodedQuery ?? "").split(separator: "&")
                    .filter { !$0.hasPrefix("clientId=") && !$0.hasPrefix("timestamp=") }
                if !extra.isEmpty { path += "?" + extra.joined(separator: "&") }
            }
            // Uninvested cash.
            if let data = try? await signedGet("/accounts/\(id)/balances", clientId: clientId, key: key),
               let balances = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] {
                for b in balances {
                    if let cash = num(b["cash"]), cash > 0.5 {
                        out.append(OwnedHolding(account: label, ticker: "", name: "Cash", kind: "cash", value: cash))
                    }
                }
            }
        }
        return out
    }

    /// GET with SnapTrade's request signature: HMAC-SHA256 of {"content":null,"path":…,"query":…}.
    private static func signedGet(_ pathAndQuery: String, clientId: String, key: String) async throws -> Data {
        let parts = pathAndQuery.split(separator: "?", maxSplits: 1).map(String.init)
        let path = parts[0]
        let cid = clientId.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? clientId
        var query = "clientId=\(cid)&timestamp=\(Int(Date().timeIntervalSince1970))"
        if parts.count > 1 && !parts[1].isEmpty { query += "&" + parts[1] }
        let canonical = "{\"content\":null,\"path\":\(jsonString(path)),\"query\":\(jsonString(query))}"
        let mac = HMAC<SHA256>.authenticationCode(for: Data(canonical.utf8), using: SymmetricKey(data: Data(key.utf8)))
        guard let url = URL(string: base + path + "?" + query) else { throw AppError("Bad URL") }
        var req = URLRequest(url: url)
        req.setValue(Data(mac).base64EncodedString(), forHTTPHeaderField: "Signature")
        req.setValue("application/json", forHTTPHeaderField: "Accept")
        req.timeoutInterval = 40
        let (data, resp) = try await URLSession.shared.data(for: req)
        switch (resp as? HTTPURLResponse)?.statusCode ?? 0 {
        case 200..<300: return data
        case 401, 403: throw AppError("SnapTrade didn't accept your key. Check the Client ID and Consumer Key.")
        case 429: throw AppError("SnapTrade is busy. Try again in a minute.")
        case let code: throw AppError("SnapTrade returned HTTP \(code)")
        }
    }

    private static func jsonString(_ s: String) -> String {
        let d = try? JSONSerialization.data(withJSONObject: [s], options: [.withoutEscapingSlashes])
        let arr = d.flatMap { String(data: $0, encoding: .utf8) } ?? "[\"\"]"
        return String(arr.dropFirst().dropLast())
    }

    private static func num(_ v: Any?) -> Double? {
        let d: Double?
        if let n = v as? NSNumber { d = n.doubleValue } else if let s = v as? String { d = Double(s) } else { d = nil }
        guard let x = d, x.isFinite else { return nil }
        return x
    }

    private static func guessKind(_ ticker: String, _ name: String) -> String {
        let n = " " + name.lowercased()
        let words = [" fund", " etf", "index", "trust", "target", "retirement", "portfolio", "admiral", "s&p 500"]
        return words.contains(where: { n.contains($0) }) || (ticker.count == 5 && ticker.hasSuffix("X")) ? "fund" : "stock"
    }

    // ---------------- checkup ----------------
    static func checkup(_ p: MyMoney, moves: [Move], scores: ScoresResult?) -> [CheckNote] {
        let total = p.total
        guard total > 0 else { return [] }
        var notes: [CheckNote] = []
        func pct(_ v: Double) -> String { String(format: "%.0f%%", v / total * 100) }
        var byTicker: [String: Double] = [:]
        for h in p.holdings where !h.ticker.isEmpty { byTicker[h.ticker, default: 0] += h.value }
        let stocks = Set(p.holdings.filter { $0.kind == "stock" && !$0.ticker.isEmpty }.map(\.ticker))

        for (t, v) in byTicker.sorted(by: { $0.value > $1.value }) where stocks.contains(t) && v / total > 0.20 {
            notes.append(CheckNote(good: false, text: "\(t) is \(pct(v)) of your money. If one company that big drops 50%, you lose \(String(format: "%.0f%%", v / total * 50)) of everything.", ticker: t))
        }
        let stockValues = byTicker.filter { stocks.contains($0.key) }.values.sorted(by: >)
        let top3 = stockValues.prefix(3).reduce(0, +)
        if stockValues.count >= 3 && top3 / total > 0.5 {
            notes.append(CheckNote(good: false, text: "Your 3 biggest single stocks are \(pct(top3)) of your money. Index funds spread the same money across hundreds of companies."))
        }
        let cash = p.holdings.filter { $0.kind == "cash" }.reduce(0) { $0 + $1.value }
        if cash / total > 0.20 { notes.append(CheckNote(good: false, text: "\(pct(cash)) of your money is uninvested cash.")) }

        let since = Date().addingTimeInterval(-90 * 86_400)
        for t in byTicker.keys.sorted() {
            let recent = moves.filter { $0.ticker == t && $0.time >= since }
            let sold = recent.filter { $0.side == "sell" }, bought = recent.filter { $0.side == "buy" }
            let soldV = sold.reduce(0) { $0 + $1.value }, boughtV = bought.reduce(0) { $0 + $1.value }
            if sold.count >= 2 && soldV > 2 * boughtV {
                notes.append(CheckNote(good: false, text: "Insiders or others the app tracks sold \(t) \(sold.count) times in the last 90 days. Often routine, but worth a look.", ticker: t))
            } else if !bought.isEmpty && boughtV > soldV {
                notes.append(CheckNote(good: true, text: "\(bought.count) recent buy\(bought.count > 1 ? "s" : "") of \(t) by people the app tracks.", ticker: t))
            }
        }
        for h in p.holdings where h.kind == "stock" {
            guard let c = h.cost, let u = h.units, c > 0, u > 0, h.value / (c * u) < 0.7 else { continue }
            notes.append(CheckNote(good: false, text: "\(h.ticker) is down \(String(format: "%.0f%%", (1 - h.value / (c * u)) * 100)) from what you paid. Ask whether you'd buy it today; if not, why keep it?", ticker: h.ticker))
        }
        if let s = scores {
            for t in byTicker.keys.sorted() {
                for (name, list) in [("growth", s.growth), ("income", s.income), ("wealth", s.wealth)] {
                    if let hit = list.first(where: { $0.ticker == t }) {
                        notes.append(CheckNote(good: true, text: "\(t) is on the Top 30 for \(name) (score \(hit.score)).", ticker: t))
                    }
                }
            }
        }
        if !notes.contains(where: { !$0.good }) {
            notes.insert(CheckNote(good: true, text: "No big warning signs. Your money isn't piled into one stock."), at: 0)
        }
        return notes
    }
}

/// Tiny wrapper around the iPhone Keychain for small secrets. Items stay on this device only.
enum Keychain {
    private static let service = "com.rashestlynx.stocktracker"

    static func set(_ name: String, _ value: String) {
        let q: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
                                kSecAttrService as String: service, kSecAttrAccount as String: name]
        SecItemDelete(q as CFDictionary)
        guard !value.isEmpty else { return }
        var add = q
        add[kSecValueData as String] = Data(value.utf8)
        add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        SecItemAdd(add as CFDictionary, nil)
    }

    static func get(_ name: String) -> String {
        let q: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
                                kSecAttrAccount as String: name, kSecReturnData as String: true,
                                kSecMatchLimit as String: kSecMatchLimitOne]
        var out: CFTypeRef?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let d = out as? Data else { return "" }
        return String(data: d, encoding: .utf8) ?? ""
    }
}
