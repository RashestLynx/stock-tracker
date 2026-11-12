import Foundation

/// A plain-English call on one game side for someone new to prediction markets. Mirrors Coach.kt.
struct Verdict { let good: Bool, text: String }

enum Coach {
    private static let minEdge = 0.03

    static func verdict(_ side: SportSide, _ books: [BookLine]) -> Verdict? {
        let ests = books.map(\.fair) + [side.kalshiP, side.polyP].compactMap { $0 }
        guard ests.count >= 2 else { return nil }
        let est = ests.reduce(0, +) / Double(ests.count)
        var opts: [(String, Double, String)] = []
        if let p = side.kalshi?.prob, p > 0.01, p < 0.99 {
            let cost = p + Bets.feeRate("Kalshi", "Sports") * p * (1 - p)
            opts.append(("Kalshi", est / cost - 1, String(format: "%.0f¢", p * 100)))
        }
        if let p = side.poly?.prob, p > 0.01, p < 0.99 {
            let cost = p + Bets.feeRate("Polymarket", "Sports") * p * (1 - p)
            opts.append(("Polymarket", est / cost - 1, String(format: "%.0f¢", p * 100)))
        }
        for b in books { opts.append((b.book, est * (1 + b.payout) - 1, b.oddsText)) }
        guard let best = opts.max(by: { $0.1 < $1.1 }) else { return nil }
        return best.1 >= minEdge
            ? Verdict(good: true, text: String(format: "Possible value: %@ on %@ at %@. Best estimate %.0f%%, expected return about %+.0f%% per bet. Keep it small.",
                                               side.label, best.0, best.2, est * 100, best.1 * 100))
            : Verdict(good: false, text: String(format: "%@: no edge. Every site's price matches the best estimate (%.0f%%). Skip, or treat it as entertainment money.",
                                                side.label, est * 100))
    }

    static let guide: [(String, String)] = [
        ("What a price means", "A contract priced 62¢ pays $1 if it happens and $0 if not. The price is the crowd's chance: 62¢ ≈ 62%. Sportsbook odds say the same thing in another format: −150 ≈ 60%, +200 ≈ 33%."),
        ("How you win long-term", "Not by picking winners, but by paying less than the true chance. Backing a 70% favorite at 75¢ loses money over time; backing a 40% underdog at 33¢ makes money over time, even though it loses most bets."),
        ("Why most bets have no edge", "In this app's tests, the sportsbooks and Kalshi were more accurate than any rating model. So the price is usually right. The app only says 'possible value' when one site is clearly cheaper than the average of all of them, after fees."),
        ("Where edges come from", "1) Different sites disagreeing (shop for the best price; that's what 'pays most' shows). 2) News the price hasn't caught up to yet (injuries, lineups). 3) Kalshi longshots: research found contracts under about 15¢ win less often than their price, so cheap underdogs are usually a bad buy."),
        ("How much to bet", "Set a betting budget you can lose completely, and keep it separate. The 'How much?' screen suggests at most 5% of it per bet, using a quarter of the Kelly formula, and $0 when there's no edge. Never chase losses."),
        ("Fees and payouts", "Kalshi charges up to about 1.75¢ per contract (most near 50¢). Polymarket's US fee is a few percent of the win. Sportsbooks build their cut into the odds (about 4-5%). The app subtracts all of these before calling anything value."),
        ("Keep score", "Log every pick under 'My picks'. After 50+ picks, your record tells you whether you actually have an edge. Most people don't, and that's useful to know."),
        ("Getting help", "If betting stops being fun or you're betting more than you planned, call or text 1-800-GAMBLER (free, 24/7)."),
    ]
}
