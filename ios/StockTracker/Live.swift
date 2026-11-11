import Foundation

/// A live quote: price, change today in dollars and percent.
struct LiveQuote { let price: Double; let change: Double; let changePct: Double; var open: Double = 0; var prevClose: Double = 0 }

/// Live stock prices from Finnhub's free API (needs a free key; 60 requests a minute).
enum Live {
    static let signup = "https://finnhub.io/register"

    static var key: String {
        // Your own key, saved on this phone only (Settings → Live prices).
        get { UserDefaults.standard.string(forKey: "finnhub_key") ?? "" }
        set { UserDefaults.standard.set(newValue.trimmingCharacters(in: .whitespaces), forKey: "finnhub_key") }
    }

    static func quote(_ ticker: String) async -> LiveQuote? {
        guard !key.isEmpty,
              let t = ticker.uppercased().addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed),
              let k = key.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed),
              let url = URL(string: "https://finnhub.io/api/v1/quote?symbol=\(t)&token=\(k)"),
              let (d, r) = try? await URLSession.shared.data(from: url),
              (r as? HTTPURLResponse)?.statusCode ?? 0 < 300,
              let o = try? JSONSerialization.jsonObject(with: d) as? [String: Any],
              let c = (o["c"] as? NSNumber)?.doubleValue, c > 0 else { return nil }
        let q = LiveQuote(price: c, change: (o["d"] as? NSNumber)?.doubleValue ?? 0, changePct: (o["dp"] as? NSNumber)?.doubleValue ?? 0,
                          open: (o["o"] as? NSNumber)?.doubleValue ?? 0, prevClose: (o["pc"] as? NSNumber)?.doubleValue ?? 0)
        LiveTicks.add(ticker, q)
        return q
    }
}

/// "▲ +1.23 (+0.45%)"
func changeText(_ q: LiveQuote) -> String {
    String(format: "%@%.2f (%+.2f%%)", q.change >= 0 ? "▲ +" : "▼ ", q.change, q.changePct)
}
