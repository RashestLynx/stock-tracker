import Foundation

struct CongressTrade {
    let member: String
    let memberSlug: String
    let chamber: String
    let state: String
    let ticker: String
    let asset: String
    let type: String
    let amountLow: Double
    let amountHigh: Double
    let estPrice: Double
    let amountRange: String
    let transactionDate: String
    let disclosureDate: String
    let portal: String

    var isBuy: Bool { let t = type.lowercased(); return t.contains("buy") || t.contains("purchase") }
    var isSell: Bool { let t = type.lowercased(); return t.contains("sell") || t.contains("sale") }
    /// Midpoint of the disclosed range (members report ranges, not exact amounts).
    var estValue: Double { amountHigh > amountLow ? (amountLow + amountHigh) / 2 : amountLow }
    var key: String { "cg:\(memberSlug)|\(ticker)|\(transactionDate)|\(type)|\(amountRange)" }
}

/// U.S. House and Senate STOCK Act trades via the free Bargo Congress Trades API
/// (built from official House Clerk and Senate eFD filings).
/// Keyless: 30 requests and 100 rows per day. A free key raises it to 100 requests / 1,000 rows.
enum Congress {
    static let sourceURL = "https://www.bargo.ai/free-apis/congress"

    /// Newest trades first. `page` 2 and up loads older trades.
    static func recent(limit: Int, apiKey: String, ticker: String = "", page: Int = 1) async throws -> [CongressTrade] {
        let q = (ticker.isEmpty ? "" : "&ticker=" + (ticker.uppercased().addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? ""))
            + (page > 1 ? "&page=\(page)" : "")
        guard let url = URL(string: "https://www.bargo.ai/free-apis/congress/v1/trades?limit=\(limit)\(q)") else { return [] }
        var req = URLRequest(url: url)
        req.setValue("StockTracker-iOS/1.0", forHTTPHeaderField: "User-Agent")
        req.setValue("application/json", forHTTPHeaderField: "Accept")
        if !apiKey.isEmpty { req.setValue(apiKey, forHTTPHeaderField: "X-Api-Key") }
        req.timeoutInterval = 30
        let (data, resp) = try await URLSession.shared.data(for: req)
        let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
        if code == 429 { throw AppError("Congress data daily limit reached; will retry later") }
        guard (200..<300).contains(code) else { throw AppError("Congress data returned HTTP \(code)") }

        let json = try JSONSerialization.jsonObject(with: data) as? [String: Any]
        let rows = json?["trades"] as? [[String: Any]] ?? []
        func s(_ o: [String: Any], _ k: String) -> String {
            (o[k] as? String ?? "").trimmingCharacters(in: .whitespaces)
        }
        return rows.map { o in
            var ticker = s(o, "ticker").uppercased()
            if ticker == "--" || ticker == "N/A" { ticker = "" }
            return CongressTrade(
                member: s(o, "member"),
                memberSlug: s(o, "member_slug").isEmpty ? s(o, "member") : s(o, "member_slug"),
                chamber: s(o, "chamber").lowercased(),
                state: s(o, "state"),
                ticker: ticker,
                asset: s(o, "asset"),
                type: s(o, "type"),
                amountLow: (o["amount_low"] as? NSNumber)?.doubleValue ?? 0,
                amountHigh: (o["amount_high"] as? NSNumber)?.doubleValue ?? 0,
                estPrice: (o["est_price"] as? NSNumber)?.doubleValue ?? 0,
                amountRange: s(o, "amount_range"),
                transactionDate: s(o, "transaction_date"),
                disclosureDate: s(o, "disclosure_date"),
                portal: s(o, "filing_portal")
            )
        }
    }
}
