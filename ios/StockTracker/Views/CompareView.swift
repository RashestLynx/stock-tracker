import SwiftUI

/// Two teams side by side, with the tested rating model. Mirrors CompareSheet in Ui.kt.
struct CompareView: View {
    let league: League
    let game: SportGame
    @Environment(\.dismiss) private var dismiss
    @State private var model: SportsModel?
    @State private var error: String?

    var body: some View {
        NavigationStack {
            List {
                if let e = error { Text(e).foregroundStyle(.secondary) }
                if let m = model {
                    if let lm = m.leagues[league.polyTag] { content(lm, m) } else { Text("No ratings for \(league.name) yet.").foregroundStyle(.secondary) }
                }
            }
            .navigationTitle(game.title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Done") { dismiss() } } }
            .task { do { model = try await SportsModels.load() } catch { self.error = error.localizedDescription } }
        }
    }

    @ViewBuilder
    private func content(_ lm: LeagueModel, _ m: SportsModel) -> some View {
        let sides = game.sides.filter { !SportsData.isDraw($0.label) }
        if sides.count >= 2, let a = lm.find(sides[0].label), let b = lm.find(sides[1].label) {
            let aHome = homeSide(sides)
            let p = lm.chance(a, b, aHome: aHome)
            Section("Who's more likely to win") {
                row(a.name, SportsModels.pct(p.0), SportsModels.pct(sides[0].best), header: false)
                row(b.name, SportsModels.pct(p.2), SportsModels.pct(sides[1].best), header: false)
                if league.hasDraw, let dr = game.sides.first(where: { SportsData.isDraw($0.label) }) {
                    row("Draw", SportsModels.pct(p.1), SportsModels.pct(dr.best), header: false)
                }
                Text(aHome == nil ? "Ratings vs. markets. No home edge used (neutral or unknown site)."
                     : "Ratings vs. markets. Includes a home edge of \(lm.homeEdge) rating points for \(aHome! ? a.name : b.name).")
                    .font(.caption2).foregroundStyle(.secondary)
            }
            Section("Side by side") {
                row("", a.abbr.isEmpty ? String(a.name.prefix(10)) : a.abbr, b.abbr.isEmpty ? String(b.name.prefix(10)) : b.abbr, header: true)
                row("Rating", "\(a.elo)" + (a.rank.map { " (#\($0))" } ?? ""), "\(b.elo)" + (b.rank.map { " (#\($0))" } ?? ""), header: false)
                if a.w + a.l + b.w + b.l > 0 {
                    row("Record", a.record, b.record, header: false)
                    row("Points for (per game)", a.pf.map { String($0) } ?? "–", b.pf.map { String($0) } ?? "–", header: false)
                    row("Points against", a.pa.map { String($0) } ?? "–", b.pa.map { String($0) } ?? "–", header: false)
                    row("Last 10", a.l10, b.l10, header: false)
                    row("Home", a.home, b.home, header: false)
                    row("Away", a.away, b.away, header: false)
                    row("Streak", a.streak, b.streak, header: false)
                    row("Opponents' avg rating", a.sos.map { String($0) } ?? "–", b.sos.map { String($0) } ?? "–", header: false)
                }
                row("Rating change, 30 days", String(format: "%+d", a.trend), String(format: "%+d", b.trend), header: false)
            }
            let h2h = Array(a.games.filter { SportsData.same($0.opp, b.name) || $0.opp == b.name }.prefix(6))
            if !h2h.isEmpty {
                Section("Recent meetings") {
                    ForEach(Array(h2h.enumerated()), id: \.offset) { _, g in
                        Text("\(g.date): \(a.name) \(g.pf > g.pa ? "won" : g.pf < g.pa ? "lost" : "tied") \(g.pf)-\(g.pa) \(g.home ? "at home" : "away")").font(.footnote)
                    }
                }
            }
            if !a.injuries.isEmpty || !b.injuries.isEmpty {
                Section {
                    ForEach([a, b], id: \.name) { t in
                        if !t.injuries.isEmpty {
                            Text(t.name).font(.subheadline.bold())
                            ForEach(Array(t.injuries.prefix(8)), id: \.self) { i in
                                Text("• \(i.name)\(i.pos.isEmpty ? "" : " (\(i.pos))"): \(i.status)").font(.footnote)
                            }
                        }
                    }
                } header: { Text("Injuries") } footer: { Text("The rating doesn't adjust for injuries yet; that's coming next.") }
            }
        } else {
            Text("Couldn't match \(sides.map(\.label).joined(separator: " and ")) to the ratings.").foregroundStyle(.secondary)
        }
        Section("How accurate are these ratings?") {
            if let acc = lm.acc {
                Text(String(format: "Tested on %d %@ seasons it never saw (%@ to %@, %@ games): the team it favored won %.0f%% of the time.",
                            lm.seasons, league.name, lm.from, lm.to, lm.n.formatted(), acc * 100)).font(.footnote)
            }
            if let ba = lm.booksAcc, let ma = lm.modelSameAcc {
                Text(String(format: "On the same games, the sportsbooks' favorite won %.0f%% (ratings: %.0f%%). Lower Brier score is better: books %.3f, ratings %.3f.",
                            ba * 100, ma * 100, lm.booksBrier ?? 0, lm.modelSameBrier ?? 0)).font(.footnote)
            }
            ForEach(Array(lm.calibration.filter { $0.n >= 50 && $0.from >= 50 }.suffix(3).enumerated()), id: \.offset) { _, c in
                Text(String(format: "When it said %.0f%%, that team won %.0f%% (%d games).", c.said * 100, c.won * 100, c.n))
                    .font(.caption2).foregroundStyle(.secondary)
            }
        }
        let kb = m.kalshiBins.filter { $0.from <= 20 }
        if !kb.isEmpty {
            Text("Kalshi check: " + kb.map { String(format: "sides priced %d-%d¢ won %.0f%% of the time (%d)", $0.from, $0.to, $0.won * 100, $0.n) }.joined(separator: "; ") + ".")
                .font(.caption2).foregroundStyle(.secondary)
        }
    }

    private func homeSide(_ sides: [SportSide]) -> Bool? {
        guard let r = game.title.range(of: " at ") else { return nil }
        let home = String(game.title[r.upperBound...])
        if SportsData.same(sides[0].label, home) { return true }
        if SportsData.same(sides[1].label, home) { return false }
        return nil
    }

    private func row(_ label: String, _ x: String, _ y: String, header: Bool) -> some View {
        HStack {
            Text(label).font(.footnote)
            Spacer()
            Text(x).font(header ? .footnote.bold() : .footnote).frame(width: 96, alignment: .trailing)
            Text(y).font(header ? .footnote.bold() : .footnote).frame(width: 96, alignment: .trailing)
        }
    }
}
