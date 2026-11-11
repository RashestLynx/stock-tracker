import SwiftUI

/// A pick being sized: `estimate` is the chance the app (or you) gives `side` of `outcome`.
struct BetTarget {
    let event: PredEvent
    let outcome: PredOutcome
    let side: String
    let estimate: Double
    let estimateNote: String
}

/// How much to bet, and what you'd get back if it wins. Logs the pick with the amount. Never places a bet.
struct BetSheetView: View {
    let target: BetTarget
    let onDone: (String) -> Void
    @State private var budgetText = UserDefaults.standard.double(forKey: "bet_budget") > 0
        ? String(format: "%.0f", UserDefaults.standard.double(forKey: "bet_budget")) : ""
    @State private var estText = ""
    @State private var amountText = ""
    @State private var amountEdited = false

    private var price: Double { target.side == "yes" ? target.outcome.prob : 1 - target.outcome.prob }
    private var sideText: String {
        target.outcome.label + (target.side == "no" ? " – No" : target.outcome.label == "Yes" ? "" : " – Yes")
    }
    private func num(_ s: String) -> Double? { Double(s.replacingOccurrences(of: ",", with: "").replacingOccurrences(of: "$", with: "")) }
    private func money(_ v: Double) -> String { "$" + Format.number(v, decimals: 2) }

    var body: some View {
        let budget = num(budgetText) ?? 0
        let est = min(max((num(estText) ?? target.estimate * 100) / 100, 0), 1)
        let sug = Bets.suggest(budget: budget, estimate: est, price: price, source: target.event.source, category: target.event.category)
        let shown = amountEdited ? amountText : (sug.amount > 0 ? String(format: "%.0f", sug.amount) : "")
        let plan = Bets.plan(stake: num(shown) ?? 0, price: price, source: target.event.source, category: target.event.category)
        Form {
            Section {
                Text(target.event.title).font(.headline)
                Text(String(format: "%@ at %.0f¢ on %@", sideText, price * 100, target.event.source)).font(.subheadline)
            }
            Section {
                LabeledContent("Betting budget ($)") {
                    TextField("e.g. 200", text: $budgetText).keyboardType(.decimalPad).multilineTextAlignment(.trailing)
                }
                LabeledContent("Chance it happens (%)") {
                    TextField(String(format: "%.0f", target.estimate * 100), text: $estText).keyboardType(.numberPad).multilineTextAlignment(.trailing)
                }
            } footer: {
                Text(target.estimateNote)
            }
            Section {
                Text(sug.why).font(.footnote).foregroundStyle(sug.amount > 0 ? .green : .secondary)
                LabeledContent("Amount to bet ($)") {
                    TextField("0", text: Binding(get: { shown }, set: { amountText = $0; amountEdited = true }))
                        .keyboardType(.decimalPad).multilineTextAlignment(.trailing)
                }
                if plan.contracts > 0 {
                    Text("\(plan.contracts) contracts for \(money(plan.cost)) (includes \(money(plan.fee)) fee)").font(.footnote)
                    Text("If it wins: you get \(money(plan.payout)) back, \(money(plan.profit)) profit").fontWeight(.semibold).foregroundStyle(.green)
                    Text("If it loses: you lose \(money(plan.cost))").fontWeight(.semibold).foregroundStyle(.red)
                } else if (num(shown) ?? 0) > 0 {
                    Text("That's not enough for one contract at this price.").font(.footnote)
                }
            } footer: {
                Text("Only bet money you can afford to lose. The app never places bets; it just logs your pick.")
            }
            Section {
                Button("Log pick") {
                    if budget > 0 { UserDefaults.standard.set(budget, forKey: "bet_budget") }
                    PredJournal.log(target.event, target.outcome, side: target.side, stake: plan.cost > 0 ? plan.cost : nil)
                    onDone("Logged: \(sideText)" + (plan.cost > 0 ? " (\(money(plan.cost)) planned)" : ""))
                }
            }
        }
        .navigationTitle("How much?")
        .navigationBarTitleDisplayMode(.inline)
    }
}
