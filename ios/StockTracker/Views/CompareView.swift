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
        if sides.count >= 2, let pair = lm.findPair(sides[0].label, sides[1].label) {
            let a = pair.0, b = pair.1
            let aHome = homeSide(sides)
            let up = lm.upcomingFor(a, b)
            let p = adjusted(lm.chance(a, b, aHome: aHome), up, a)
            Section("Who's more likely to win") {
                row(a.name, SportsModels.pct(p.0), SportsModels.pct(sides[0].best), header: false)
                row(b.name, SportsModels.pct(p.2), SportsModels.pct(sides[1].best), header: false)
                if league.hasDraw, let dr = game.sides.first(where: { SportsData.isDraw($0.label) }) {
                    row("Draw", SportsModels.pct(p.1), SportsModels.pct(dr.best), header: false)
                }
                Text((aHome == nil ? "Ratings vs. markets. No home edge used (neutral or unknown site)."
                     : "Ratings vs. markets. Includes a home edge of \(lm.homeEdge) rating points for \(aHome! ? a.name : b.name).")
                     + (up?.full != nil ? " Adjusted for rest, recent form, this season's margins and head-to-head" + (lm.matchupUsed == "matchup+weather" ? ", and the weather forecast" : "") + "." : ""))
                    .font(.caption2).foregroundStyle(.secondary)
                if let u = up {
                    if u.indoor {
                        Text("Indoors\(u.venue.isEmpty ? "" : " at \(u.venue)"): weather doesn't matter.").font(.footnote)
                    } else if let t = u.temp {
                        Text("Forecast\(u.venue.isEmpty ? "" : " at \(u.venue)"): \(t)°F, wind \(u.wind ?? 0) mph" + ((u.rain ?? 0) > 0.1 ? String(format: ", rain %.1f mm", u.rain!) : "") + ".").font(.footnote)
                        if !lm.weatherHelped {
                            Text("In testing, weather didn't improve \(league.name) predictions, so it's shown but not used.").font(.caption2).foregroundStyle(.secondary)
                        }
                    }
                }
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
            if let u = up, !u.fighters.isEmpty {
                let fa = u.fighters[a.name] ?? u.fighters.values.first
                let fb = u.fighters[b.name] ?? Array(u.fighters.values).dropFirst().first
                let mv: (Int?) -> String = { c in guard let c else { return "–" }; return c > 0 ? "Up" : c < 0 ? "Down" : "Same" }
                let n0: (Double?) -> String = { v in v.map { String(format: "%.0f", $0) } ?? "–" }
                let n1: (Double?) -> String = { v in v.map { String(format: "%.1f", $0) } ?? "–" }
                let pc: (Double?) -> String = { v in v.map { "\(Int(($0 * 100).rounded()))%" } ?? "–" }
                let card: [String] = [(a.name, fa), (b.name, fb)].flatMap { n, f -> [String] in
                    var out: [String] = []
                    if let d = f?.bookedDays { out.append("\(n): fight booked only \(d) days out (short notice: less time to prepare and cut weight)") }
                    if let w = f?.newOpponentWas { out.append("\(n): opponent changed (was \(w))") }
                    return out
                }
                let news = [(a.name, fa), (b.name, fb)].flatMap { n, f in (f?.news ?? []).map { (n, $0) } }
                Section {
                    row("Age", fa?.age.map { String($0) } ?? "–", fb?.age.map { String($0) } ?? "–", header: false)
                    row("Reach (in)", n0(fa?.reach), n0(fb?.reach), header: false)
                    row("Height (in)", n0(fa?.height), n0(fb?.height), header: false)
                    row("Stance", fa?.stance ?? "–", fb?.stance ?? "–", header: false)
                    row("UFC record (7 yrs)", fa?.record ?? "–", fb?.record ?? "–", header: false)
                    row("Days since last fight", fa?.layoffDays.map { String($0) } ?? "–", fb?.layoffDays.map { String($0) } ?? "–", header: false)
                    row("Weight class move", mv(fa?.classChange), mv(fb?.classChange), header: false)
                    row("Country", fa.map { $0.country.isEmpty ? "–" : $0.country } ?? "–", fb.map { $0.country.isEmpty ? "–" : $0.country } ?? "–", header: false)
                    row("Home crowd", fa?.home == true ? "Yes" : "No", fb?.home == true ? "Yes" : "No", header: false)
                    row("Time zones traveled", n0(fa?.timeZones), n0(fb?.timeZones), header: false)
                    row("Strikes landed / min", n1(fa?.slpm), n1(fb?.slpm), header: false)
                    row("Strikes absorbed / min", n1(fa?.sapm), n1(fb?.sapm), header: false)
                    row("Striking accuracy", pc(fa?.acc), pc(fb?.acc), header: false)
                    row("Strikes avoided", pc(fa?.def), pc(fb?.def), header: false)
                    row("Takedowns / 15 min", n1(fa?.td15), n1(fb?.td15), header: false)
                    row("Takedowns stopped", pc(fa?.tdDef), pc(fb?.tdDef), header: false)
                    row("Submission tries / 15 min", n1(fa?.sub15), n1(fb?.sub15), header: false)
                    row("Career KO losses", fa.map { String($0.koLosses) } ?? "–", fb.map { String($0.koLosses) } ?? "–", header: false)
                    row("Fights, last 2 years", fa.map { String($0.active2y) } ?? "–", fb.map { String($0.active2y) } ?? "–", header: false)
                    ForEach([(a.name, fa), (b.name, fb)], id: \.0) { n, f in
                        if let f, !f.last.isEmpty { Text("\(n) last fight: \(f.last)").font(.footnote) }
                        if let f, !f.gym.isEmpty { Text("\(n) trains at \(f.gym)").font(.footnote).foregroundStyle(.secondary) }
                    }
                    if [fa, fb].contains(where: { ($0?.statsFights ?? 3) < 3 }) {
                        Text("One of them has fewer than 3 UFC fights with stats, so their numbers lean toward a typical fighter.").font(.caption2).foregroundStyle(.secondary)
                    }
                    if !u.reasons.isEmpty {
                        Text("What moved the chance").font(.subheadline.bold())
                        ForEach(u.reasons, id: \.self) { r in Text("• Favors \(r.favors): \(r.why)").font(.footnote) }
                    }
                    if !card.isEmpty {
                        Text("Card changes").font(.subheadline.bold()).foregroundStyle(.red)
                        ForEach(card, id: \.self) { Text("• \($0)").font(.footnote) }
                    }
                    if news.isEmpty {
                        Text("No injury, illness, pull-out, weight, visa, suspension or camp-change headlines in the last 3 weeks.").font(.footnote).foregroundStyle(.secondary)
                    } else {
                        Text("Red flags in the news (last 3 weeks)").font(.subheadline.bold()).foregroundStyle(.red)
                        ForEach(news, id: \.1) { n, x in
                            if let url = URL(string: x.link) {
                                Link("• \(x.kinds.joined(separator: "/")): \(x.title) (\(x.date)\(x.source == "news" ? "" : ", " + x.source))", destination: url).font(.footnote)
                            } else { Text("• \(x.kinds.joined(separator: "/")): \(x.title) (\(x.date))").font(.footnote) }
                        }
                    }
                } header: { Text("Fight check" + (u.weightClass.isEmpty ? "" : " (\(u.weightClass), \(u.rounds) rounds)")) } footer: {
                    Text("Everything in the table is tested on past fights (age, size, layoffs, striking and grappling stats, damage taken, home country, travel, 5 rounds, the small Apex cage) and used in the chance above only if it made predictions better. News and card changes can't be tested the same way, so they're flags for you to weigh, not in the percentage.")
                }
            }
            if !a.weather.isEmpty || !b.weather.isEmpty {
                Section {
                    ForEach([a, b], id: \.name) { t in
                        if !t.weather.isEmpty {
                            Text(t.name).font(.subheadline.bold())
                            ForEach(t.weather.sorted { $0.z < $1.z }, id: \.self) { w in
                                Text(String(format: "• %@: %d-%d, ratings expected %.1f wins: %@", w.label, w.wins, w.n - w.wins, w.expected, w.verdict))
                                    .font(.footnote).foregroundStyle(w.z <= -2 ? .red : w.z >= 2 ? .green : .primary)
                            }
                        }
                    }
                } header: { Text("Weather record (last 7 seasons)") } footer: {
                    Text("Compared with what each team's rating expected before those games, so a strong team winning in the cold doesn't count as a cold-weather team. With many teams and conditions, a few 'could be luck' results are expected by chance.")
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
            if let e = lm.matchupTests["elo"] {
                Text(lm.matchupUsed == "elo"
                     ? "Adding rest, form, head-to-head\(lm.matchupTests["matchup+weather"] != nil ? " and weather" : "") didn't beat the plain ratings on unseen seasons, so they're not used."
                     : String(format: "The matchup adjustments%@ beat the plain ratings on unseen seasons (favorite won %.1f%% vs %.1f%%).",
                              lm.matchupUsed == "matchup+weather" ? " and weather" : "", (lm.matchupTests[lm.matchupUsed]?.acc ?? 0) * 100, e.acc * 100))
                    .font(.footnote)
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

    /// The matchup-and-weather chance replaces the plain rating where it proved better on unseen seasons.
    private func adjusted(_ p: (Double, Double, Double), _ up: Upcoming?, _ a: TeamRow) -> (Double, Double, Double) {
        guard let full = up?.full else { return p }
        let eA = up!.home == a.name ? full : 1 - full
        return (eA - p.1 / 2, p.1, 1 - eA - p.1 / 2)
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
