import Foundation

/// Opens a ticker in the user's brokerage. These are regular web links that iOS hands to the
/// brokerage's app when it's installed (Robinhood supports this), and to Safari otherwise.
enum Broker {
    struct Choice { let name: String; let stock: String; let crypto: String }

    static let choices = [
        Choice(name: "Robinhood", stock: "https://robinhood.com/stocks/{T}", crypto: "https://robinhood.com/crypto/{T}"),
        Choice(name: "Fidelity", stock: "https://digital.fidelity.com/prgw/digital/research/quote/dashboard/summary?symbol={T}", crypto: ""),
        Choice(name: "Yahoo Finance", stock: "https://finance.yahoo.com/quote/{T}", crypto: "https://finance.yahoo.com/quote/{T}-USD"),
    ]
    static let custom = "Custom"

    static var name: String {
        get { UserDefaults.standard.string(forKey: "broker_name") ?? "Robinhood" }
        set { UserDefaults.standard.set(newValue, forKey: "broker_name") }
    }

    static var customTemplate: String {
        get { UserDefaults.standard.string(forKey: "broker_custom") ?? "" }
        set { UserDefaults.standard.set(newValue.trimmingCharacters(in: .whitespaces), forKey: "broker_custom") }
    }

    /// Link for a stock ticker, or for "BTC"/"ETH" when crypto is true.
    static func link(_ ticker: String, crypto: Bool = false) -> URL? {
        let template = name == custom ? customTemplate
            : (choices.first { $0.name == name }.map { crypto ? $0.crypto : $0.stock } ?? "")
        guard template.contains("{T}") else { return nil }
        let t = ticker.uppercased().addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? ticker
        return URL(string: template.replacingOccurrences(of: "{T}", with: t))
    }
}
