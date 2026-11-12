import SwiftUI

func gradeColor(_ g: String) -> Color {
    switch g.first {
    case "A": return .green
    case "B": return Color(red: 0.49, green: 0.70, blue: 0.26)
    case "C": return Color(red: 0.98, green: 0.66, blue: 0.15)
    case "D": return .orange
    case "F": return .red
    default: return .secondary
    }
}

struct GradeBadge: View {
    let grade: String
    var label: String? = nil
    var body: some View {
        VStack(spacing: 2) {
            Text(grade.isEmpty ? "–" : grade).font(.headline).foregroundStyle(gradeColor(grade))
                .padding(.horizontal, 12).padding(.vertical, 6)
                .background(gradeColor(grade).opacity(0.15), in: RoundedRectangle(cornerRadius: 10))
            if let label { Text(label).font(.caption2).foregroundStyle(.secondary) }
        }
    }
}

private let goalAbout = [
    "Sales and profit rising fast (and speeding up), margins improving, and the stock beating the S&P 500.",
    "A dividend that's paid every year, covered by the cash the business makes, growing, and a fair yield. Non-payers get an F.",
    "Wealth preservation: steady, profitable, low-debt companies whose stock holds up when the market drops.",
    "Our own moat score from SEC filings: high gross profit vs assets, high return on equity, profits backed by cash, low debt, and a steady growth record. Tested like the others.",
    "Smaller companies ($300M to $15B) with sales up 20%+, growth speeding up, margins widening, and the stock already beating the market.",
]

/// Markets → Grades: every stock graded for growth, income and wealth, plus the "next big thing" list.
struct GradesView: View {
    @EnvironmentObject var model: AppModel
    @State private var goal = 0
    @State private var query = ""
    @State private var showN = 50
    @State private var result: GradesResult?
    @State private var error: String?

    var body: some View {
        List {
            Section {
                PillTabs(options: [(0, "Growth"), (1, "Income"), (2, "Wealth"), (3, "Moat"), (4, "Next big thing")], selection: $goal)
                Text(goalAbout[goal]).font(.footnote).foregroundStyle(.secondary)
            }
            if let e = error, result == nil {
                EmptyStateView(title: "No grades yet", message: e, systemImage: "graduationcap")
            }
            if let r = result {
                Section { GradeRecordView(r: r, goal: goal) }
                if goal < 4 {
                    Section {
                        TextField("Find a ticker", text: $query).textInputAutocapitalization(.characters).autocorrectionDisabled()
                        let list = r.ranked(goal).filter { query.isEmpty || $0.ticker.hasPrefix(query.uppercased()) }
                        ForEach(list.prefix(showN)) { g in
                            Button { model.openStock = StockTarget(ticker: g.ticker, company: g.name) } label: {
                                HStack {
                                    VStack(alignment: .leading, spacing: 2) {
                                        HStack(alignment: .firstTextBaseline, spacing: 6) {
                                            Text(g.ticker).font(.headline)
                                            Text(Grades.money(g.capM)).font(.caption2).foregroundStyle(.secondary)
                                        }
                                        Text(g.name).font(.footnote).lineLimit(1)
                                        if let w = g.why(goal).first { Text(w).font(.footnote).foregroundStyle(.secondary).lineLimit(1) }
                                    }
                                    Spacer()
                                    GradeBadge(grade: g.grade(goal), label: g.odds[goal].map { String(format: "%.0f%% odds", $0 * 100) })
                                }.contentShape(Rectangle())
                            }.buttonStyle(.plain)
                        }
                        if list.count > showN { Button("Show more (\(list.count - showN) left)") { showN += 50 } }
                    }
                } else {
                    Section {
                        if r.stars.isEmpty { Text("No company passed every test today.").foregroundStyle(.secondary) }
                        ForEach(r.stars) { s in
                            Button { model.openStock = StockTarget(ticker: s.ticker, company: s.name) } label: {
                                VStack(alignment: .leading, spacing: 3) {
                                    HStack {
                                        Text(s.ticker).font(.headline)
                                        Text([Grades.money(s.capM), s.industry].filter { !$0.isEmpty }.joined(separator: " · "))
                                            .font(.caption2).foregroundStyle(.secondary).lineLimit(1)
                                        Spacer()
                                        if !s.growth.isEmpty { GradeBadge(grade: s.growth, label: "growth") }
                                    }
                                    Text(s.name).font(.footnote)
                                    Text(String(s.why.prefix(1)).uppercased() + String(s.why.dropFirst()) + ".").font(.footnote)
                                }.contentShape(Rectangle())
                            }.buttonStyle(.plain)
                        }
                    }
                }
                Section {
                    EmptyView()
                } footer: {
                    Text("Grades are from \(r.date) using fiscal \(String(r.fiscalYear)) SEC filings and daily prices, ranked against \(r.graded.formatted()) U.S. stocks. They're a starting point for research, not advice.")
                }
            }
        }
        .refreshable { await load(force: true) }
        .task { await load(force: false) }
        .onChange(of: goal) { _, _ in showN = 50 }
    }

    private func load(force: Bool) async {
        do { result = try await Grades.load(force: force); error = nil } catch { self.error = error.localizedDescription }
    }
}

/// "Did it work?": the same rules run a year ago, and how each grade did since.
struct GradeRecordView: View {
    let r: GradesResult
    let goal: Int
    var body: some View {
        if let spy = r.spy {
            VStack(alignment: .leading, spacing: 6) {
                Text("Did it work?").font(.headline)
                Text(!r.note.isEmpty ? r.note : "Graded by the same rules on \(r.recordFrom), then held \(r.months == 12 ? "a year" : "\(r.months) months"). S&P 500: \(String(format: "%+.0f%%", spy * 100))" +
                     (goal == 2 && r.spyDrop != nil ? String(format: ", worst drop %.0f%%", r.spyDrop! * 100) : "") + ".")
                    .font(.footnote).foregroundStyle(.secondary)
                if goal == 4 {
                    if let ret = r.starsRet, let beat = r.starsBeat {
                        Text(r.learned ? String(format: "Past lists: %.0f%% of picks beat the S&P 500, median %+.1f%% vs it.", beat * 100, ret * 100)
                             : String(format: "That day's list: median %+.0f%%, %.0f%% of them beat the S&P 500.", ret * 100, beat * 100))
                    }
                } else {
                    ForEach(r.record[["g", "i", "w", "m"][min(goal, 3)]] ?? []) { b in
                        HStack {
                            Text(b.letter + "s").fontWeight(.bold).foregroundStyle(gradeColor(b.letter)).frame(width: 36, alignment: .leading)
                            Text(bucketText(b)).font(.footnote)
                            Spacer()
                            Text("\(b.n)").font(.caption2).foregroundStyle(.secondary)
                        }
                    }
                }
                Text("Only stocks still trading today are counted, which flatters every grade a little. Past results don't guarantee future ones.")
                    .font(.caption2).foregroundStyle(.secondary)
            }
        }
    }
}

extension GradeRecordView {
    func bucketText(_ b: GradeBucket) -> String {
        if goal == 2, let d = b.drop {
            return r.learned ? String(format: "typical worst drop %.0f%% · beat the S&P 500 %.0f%%", d * 100, b.beat * 100)
                             : String(format: "worst drop %.0f%% · return %+.0f%%", d * 100, b.ret * 100)
        }
        return r.learned ? String(format: "beat the S&P 500 %.0f%% · median %+.1f%% vs it", b.beat * 100, b.ret * 100)
                         : String(format: "median %+.0f%% · %.0f%% beat the S&P 500", b.ret * 100, b.beat * 100)
    }
}

/// On a stock's page: its three grades, with the reasons behind each.
struct GradeCard: View {
    let ticker: String
    @State private var grade: StockGrade?
    @State private var open = 0

    var body: some View {
        Group {
            if let g = grade {
                VStack(alignment: .leading, spacing: 8) {
                    Text("Grades").font(.headline)
                    HStack {
                        ForEach(0..<4, id: \.self) { i in
                            Spacer()
                            Button { open = i } label: { GradeBadge(grade: g.grade(i), label: ["Growth", "Income", "Wealth", "Moat"][i]) }
                                .buttonStyle(.plain)
                            Spacer()
                        }
                    }
                    Text(["Growth", "Income", "Wealth preservation", "Moat"][open] + ":").font(.footnote).fontWeight(.semibold)
                    if let o = g.odds[open] {
                        Text(String(format: "Measured odds: %.0f%% chance to beat the S&P 500 over 3 months", o * 100) +
                             (g.drops[open].map { String(format: ", typical worst drop %.0f%%", $0 * 100) } ?? "") + ".")
                            .font(.footnote).fontWeight(.semibold)
                    }
                    ForEach(g.why(open), id: \.self) { Text("• " + $0).font(.footnote) }
                    Text("Tap a grade to see why. Weights are learned from history and the odds are measured on months the model never saw. Odds aren't promises.")
                        .font(.caption2).foregroundStyle(.secondary)
                }
                .padding()
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 16))
            }
        }
        .task(id: ticker) {
            let r: GradesResult?
            if let c = Grades.cached() { r = c } else { r = try? await Grades.load() }
            grade = r?.grades[ticker.uppercased()]
        }
    }
}
