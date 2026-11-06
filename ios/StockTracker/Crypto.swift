import Foundation

struct CoinPrice { let usd: Double; let change24h: Double }
struct TreasuryCompany: Identifiable { let name: String; let symbol: String; let country: String; let coins: Double; let valueUSD: Double; var id: String { name } }

/// A fund's Bitcoin/Ethereum fund position seen in a 13F.
struct CryptoPos: Codable, Identifiable {
    var fund: String
    var asset: String
    var product: String
    var value: Double
    var time: Date
    var id: String { fund + "|" + product }
}

enum CryptoStore {
    static func all() -> [CryptoPos] {
        guard let d = UserDefaults.standard.data(forKey: "crypto_pos"),
              let l = try? JSONDecoder().decode([CryptoPos].self, from: d) else { return [] }
        return l
    }

    static func add(_ new: [CryptoPos]) {
        guard !new.isEmpty else { return }
        var seen = Set<String>()
        let merged = (new + all()).filter { seen.insert($0.id).inserted }.sorted { $0.value > $1.value }.prefix(1500)
        if let d = try? JSONEncoder().encode(Array(merged)) { UserDefaults.standard.set(d, forKey: "crypto_pos") }
    }
}

/// Bitcoin and Ethereum: prices, and how to spot crypto in filings and trades.
enum Crypto {
    static let btcTickers: Set<String> = ["IBIT", "FBTC", "GBTC", "BTC", "BITB", "ARKB", "HODL", "BRRR", "EZBC", "BTCO", "BTCW", "BITO"]
    static let ethTickers: Set<String> = ["ETHA", "FETH", "ETHE", "ETH", "ETHW", "CETH", "EZET", "QETH", "ETHV"]
    static let stocks: [String: String] = ["MSTR": "Holds Bitcoin as its main treasury asset", "COIN": "Crypto exchange",
                                           "MARA": "Bitcoin miner", "RIOT": "Bitcoin miner"]

    /// "BTC" or "ETH" if a 13F issuer name is a Bitcoin or Ethereum fund.
    static func assetOf(_ issuer: String) -> String? {
        let n = issuer.uppercased()
        if n.contains("BITCOIN") { return "BTC" }
        if n.range(of: #"\bETHER(EUM)?\b"#, options: .regularExpression) != nil { return "ETH" }
        return nil
    }

    static func tickerAsset(_ t: String) -> String? {
        let u = t.uppercased()
        if btcTickers.contains(u) { return "BTC" }
        if ethTickers.contains(u) { return "ETH" }
        if stocks[u] != nil { return "STOCK" }
        return nil
    }

    private static func json(_ url: String) async -> [String: Any]? {
        guard let u = URL(string: url) else { return nil }
        var req = URLRequest(url: u)
        req.setValue("StockTracker-iOS/1.0", forHTTPHeaderField: "User-Agent")
        req.setValue("application/json", forHTTPHeaderField: "Accept")
        req.timeoutInterval = 20
        guard let (d, r) = try? await URLSession.shared.data(for: req), (r as? HTTPURLResponse)?.statusCode ?? 0 < 300 else { return nil }
        return try? JSONSerialization.jsonObject(with: d) as? [String: Any]
    }

    /// Live prices from CoinGecko's free public API.
    static func prices() async -> [String: CoinPrice] {
        guard let j = await json("https://api.coingecko.com/api/v3/simple/price?ids=bitcoin,ethereum&vs_currencies=usd&include_24hr_change=true") else { return [:] }
        var out: [String: CoinPrice] = [:]
        for (id, sym) in [("bitcoin", "BTC"), ("ethereum", "ETH")] {
            if let o = j[id] as? [String: Any], let usd = (o["usd"] as? NSNumber)?.doubleValue {
                out[sym] = CoinPrice(usd: usd, change24h: (o["usd_24h_change"] as? NSNumber)?.doubleValue ?? 0)
            }
        }
        return out
    }

    /// Public companies holding the coin ("bitcoin" or "ethereum"), from CoinGecko.
    static func treasuries(_ coin: String) async -> [TreasuryCompany] {
        guard let j = await json("https://api.coingecko.com/api/v3/companies/public_treasury/\(coin)"),
              let arr = j["companies"] as? [[String: Any]] else { return [] }
        return arr.map { o in
            TreasuryCompany(name: o["name"] as? String ?? "", symbol: o["symbol"] as? String ?? "", country: o["country"] as? String ?? "",
                            coins: (o["total_holdings"] as? NSNumber)?.doubleValue ?? 0,
                            valueUSD: (o["total_current_value_usd"] as? NSNumber)?.doubleValue ?? 0)
        }.sorted { $0.coins > $1.coins }
    }
}
