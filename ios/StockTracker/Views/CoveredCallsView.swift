import SwiftUI

/// Covered calls on one stock: three calls to consider, every strike, warnings, and the 10-year history test. Mirrors OptionsCard in Ui.kt.
struct OptionsCard: View {
    @EnvironmentObject var model: AppModel
    let ticker: String
    let shares: Double
    var startOpen = false
    @State private var open = false
    @State private var chain: OptChain?
    @State private var events: StockEvents?
    @State private var grade: StockGrade?
    @State private var history: CoveredHistory?
    @State private var failed = false
    @State private var loading = false
    @State private var expiry: Date?
    @State private var chosen: CallIdea?
    @State private var showAll = false

    private var t: String { ticker.uppercased() }
    private let md: DateFormatter = { let f = DateFormatter(); f.dateFormat = "MMM d"; f.timeZone = TimeZone(identifier: "America/New_York"); return f }()

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Button { open.toggle() } label: {
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Covered calls on \(t)").font(.headline)
                        Text(shares >= 100 ? "You can sell \(safeInt(shares / 100)) call\(shares >= 200 ? "s" : "") (\(safeInt(shares)) shares)"
                                           : "Extra income from shares you own. Tap to see live prices.")
                            .font(.caption).foregroundStyle(.secondary)
                    }
                    Spacer()
                    Text(open ? "Hide" : "Show").fontWeight(.semibold)
                }
            }.buttonStyle(.plain)
            if open { content }
        }
        .padding()
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 16))
        .onAppear { if startOpen { open = true } }
        .task(id: open) { await load() }
    }

    private func load() async {
        guard open, chain == nil, !loading else { return }
        loading = true
        let g: GradesResult?
        if let c = Grades.cached() { g = c } else { g = try? await Grades.load() }
        grade = g?.grades[t]; history = g?.covered[t]
        let c = await Options.chain(t)
        chain = c; failed = c == nil
        expiry = c.flatMap { Options.defaultExpiry($0) }
        events = await Options.events(t, massiveKey: model.massiveKey)
        loading = false
    }

    @ViewBuilder private var content: some View {
        if loading { ProgressView().frame(maxWidth: .infinity) }
        if let h = history {
            let beat = h.balanced > h.hold
            Text("History test (\(safeInt(h.years.rounded())) years, a call every month): just holding \(Options.pct(h.hold))/yr vs. balanced calls \(Options.pct(h.balanced))/yr (safer \(Options.pct(h.safer))/yr). Called away \(safeInt((h.calledBalanced * 100).rounded()))% of months. Worst drop \(Options.pct(h.dropBalanced)) vs \(Options.pct(h.dropHold)) holding. " + (beat ? "On this stock, calls added return." : "On this stock, capping the upside cost more than the premiums paid."))
                .font(.footnote).foregroundStyle(beat ? .green : .red)
        }
        if failed && !loading {
            Text("No option prices found for \(t). It may not have options.").font(.footnote)
        } else if let c = chain {
            Text("Price \(Options.money(c.price)) · \(c.source) \(c.asOf)").font(.caption2).foregroundStyle(.secondary)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 6) {
                    ForEach(Options.goodExpiries(c), id: \.self) { e in
                        Button("\(md.string(from: e)) (\(Options.days(Date(), e)) days)") { expiry = e; chosen = nil }
                            .buttonStyle(.bordered).tint(e == expiry ? .accentColor : .gray).font(.caption)
                    }
                }
            }
            if let e = expiry { ideas(c, e) }
        }
    }

    @ViewBuilder private func ideas(_ c: OptChain, _ e: Date) -> some View {
        let all = Options.calls(c, expiry: e, shares: shares)
        let picks = Options.picks(all)
        let n = max(1, safeInt(shares / 100))
        if all.isEmpty {
            Text("No calls above today's price with a real bid for this date.").font(.footnote)
        } else {
            Text("Three to consider (tap one for details):").font(.subheadline.bold())
            ForEach(picks, id: \.self) { p in
                let sel = chosen?.q == p.q
                Button { chosen = sel ? nil : p } label: {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(p.label).fontWeight(.semibold)
                        Text("Sell the \(Options.money(p.q.strike)) call → collect \(Options.money(p.q.bid * 100 * Double(n))) " +
                             (shares >= 100 ? "for your \(n)" : "per") + " contract\(n > 1 ? "s" : "") now").font(.subheadline)
                        Text("\(Options.pct(p.yieldPct)) in \(p.days) days (\(Options.pct(p.annualPct)) a year if repeated) · about \(safeInt((p.chanceCalled * 100).rounded()))% chance your shares are called away · if called: +\(Options.pct(p.ifCalledPct))")
                            .font(.caption).foregroundStyle(.secondary)
                    }
                    .padding(10).frame(maxWidth: .infinity, alignment: .leading)
                    .background(sel ? Color.accentColor.opacity(0.18) : Color(.tertiarySystemBackground), in: RoundedRectangle(cornerRadius: 12))
                }.buttonStyle(.plain)
            }
            if let p = chosen {
                Text("Before you sell the \(Options.money(p.q.strike)) call").font(.subheadline.bold())
                Text("Bid \(Options.money(p.q.bid)) · ask \(Options.money(p.q.ask)) · break-even \(Options.money(p.breakeven)) · open contracts \(p.q.openInterest)").font(.footnote)
                ForEach(Options.warnings(c, p, events, grade, shares: shares), id: \.self) { Text("• " + $0).font(.footnote) }
            }
            Button(showAll ? "Hide all strikes" : "See every strike (\(all.count))") { showAll.toggle() }.font(.footnote)
            if showAll {
                Grid(alignment: .leading, horizontalSpacing: 10, verticalSpacing: 4) {
                    GridRow { Text("Strike"); Text("Bid"); Text("Chance called"); Text("Yearly rate") }.font(.caption.bold())
                    ForEach(all, id: \.self) { p in
                        GridRow {
                            Text(Options.money(p.q.strike)); Text(Options.money(p.q.bid))
                            Text("\(safeInt((p.chanceCalled * 100).rounded()))%"); Text(Options.pct(p.annualPct))
                        }.font(.caption).onTapGesture { chosen = p }
                    }
                }
            }
            Text("Prices are 15 minutes old. \"Chance called\" is the options market's own estimate (delta). Not advice: you place any trade yourself in your brokerage.")
                .font(.caption2).foregroundStyle(.secondary)
        }
    }
}

/// Portfolio → Covered calls: the beginner guide, your positions with 100+ shares, and a checker for any stock.
struct CoveredCallsView: View {
    @State private var summary: [String: Double] = [:]
    @State private var tick = ""
    @State private var sh = "100"
    @State private var checking = ""
    @State private var checkShares = 100.0
    @State private var openGuide = -1
    private var positions: [(String, Double)] {
        Accounts.owned().compactMap { t, v in v.units.map { (t, $0) } }.filter { $0.1 >= 100 }.sorted { $0.1 > $1.1 }
    }
    private func p(_ k: String) -> String { summary[k].map { "\(safeInt(($0 * 100).rounded()))%" } ?? "–" }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                VStack(alignment: .leading, spacing: 6) {
                    Text("Options & covered calls, start here").font(.headline)
                    Text("A covered call pays you cash now for agreeing to sell 100 of your shares at a higher price if the stock gets there. Tap a topic.").font(.footnote)
                    ForEach(Array(Options.guide.enumerated()), id: \.offset) { i, item in
                        Button { openGuide = openGuide == i ? -1 : i } label: {
                            Text((openGuide == i ? "▾ " : "▸ ") + item.0).fontWeight(.semibold).frame(maxWidth: .infinity, alignment: .leading)
                        }.buttonStyle(.plain).padding(.vertical, 2)
                        if openGuide == i { Text(item.1).font(.footnote) }
                    }
                }
                .padding().frame(maxWidth: .infinity, alignment: .leading)
                .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 16))

                if !summary.isEmpty {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Does it pay? The 10-year history test").font(.headline)
                        Text("Across \(Int(summary["stocks"] ?? 0)) stocks, selling a balanced call every month beat just holding on \(p("balanced_beat_hold")) of them. On slow, steady stocks it won on \(p("beat_hold_slow_stocks")); on fast growers (15%+ a year) on only \(p("beat_hold_fast_growers")). Shares were called away in about \(p("median_called_balanced")) of months, and the worst drop was smaller on \(p("smaller_drop_balanced")) of stocks.")
                            .font(.footnote)
                        Text("Simulated with estimated option prices; dividends left out of both sides.").font(.caption2).foregroundStyle(.secondary)
                    }
                    .padding().frame(maxWidth: .infinity, alignment: .leading)
                    .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 16))
                }

                VStack(alignment: .leading, spacing: 8) {
                    Text("Check any stock").font(.headline)
                    HStack {
                        TextField("Ticker", text: $tick).textInputAutocapitalization(.characters).autocorrectionDisabled().textFieldStyle(.roundedBorder)
                        TextField("Shares you own", text: $sh).keyboardType(.numberPad).textFieldStyle(.roundedBorder)
                    }
                    Button("Show covered calls") { checking = tick.trimmingCharacters(in: .whitespaces).uppercased(); checkShares = Double(sh) ?? 0 }
                        .buttonStyle(.borderedProminent).disabled(tick.trimmingCharacters(in: .whitespaces).isEmpty)
                }
                .padding().frame(maxWidth: .infinity, alignment: .leading)
                .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 16))

                if !checking.isEmpty { OptionsCard(ticker: checking, shares: checkShares, startOpen: true).id("\(checking)-\(checkShares)") }

                Text(positions.isEmpty ? "Your positions with 100+ shares show here once your brokerage is linked in My accounts." : "Your positions with 100+ shares")
                    .font(.subheadline.bold()).foregroundStyle(positions.isEmpty ? .secondary : .primary)
                ForEach(positions, id: \.0) { t, n in OptionsCard(ticker: t, shares: n) }
            }
            .padding()
        }
        .task {
            let g: GradesResult?
            if let c = Grades.cached() { g = c } else { g = try? await Grades.load() }
            summary = g?.coveredSummary ?? [:]
        }
    }
}
