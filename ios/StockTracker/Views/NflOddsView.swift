import SwiftUI

/// "All markets" and "NFL odds check" under Markets → Predictions.
struct PredictionsTabView: View {
    @State private var view = 0
    var body: some View {
        VStack(spacing: 0) {
            PillTabs(options: [(0, "All markets"), (1, "NFL odds check"), (2, "My picks")], selection: $view)
                .padding(.horizontal).padding(.vertical, 8)
            switch view {
            case 0: PredictionsView()
            case 1: NflOddsView()
            default: MyPredPicksView()
            }
        }
    }
}

/// Each upcoming NFL game's odds from sportsbooks, Kalshi, Polymarket and a math model, side by side.
struct NflOddsView: View {
    @Environment(\.openURL) private var openURL
    @State private var result: OddsResult?
    @State private var loading = false
    @State private var error: String?
    @State private var logging: OddsGame?
    @State private var logged = ""

    var body: some View {
        NavigationStack {
            List {
                if !logged.isEmpty {
                    Section { Text("\(logged). See it under My picks.").foregroundStyle(.green) }
                }
                Section {
                    EmptyView()
                } footer: {
                    Text("Each game's chance of the home team winning, from four sources. Sportsbooks are usually the sharpest estimate. When Kalshi or Polymarket is several points away from them, one side of that game is cheaper there. Gaps under about 3 points are mostly fees and noise.")
                }
                if let e = error {
                    Section { Text(e).foregroundStyle(.red) }
                }
                if loading && result == nil {
                    Section { ProgressView().frame(maxWidth: .infinity) }
                }
                if let r = result, r.games.isEmpty {
                    Section { Text("No NFL games in the next week.").foregroundStyle(.secondary) }
                }
                ForEach(result?.games ?? []) { g in
                    Section { card(g) }
                }
                if let r = result, r.tested > 0 {
                    Section {
                        EmptyView()
                    } footer: {
                        Text(String(format: "How the math model has done: since 2023 it picked the winner in %.0f%% of %d games. The sportsbook favorite won %.0f%% of the same games, so trust the sportsbooks more when the two disagree.", r.modelRight * 100, r.tested, r.booksRight * 100))
                    }
                }
                Section {
                    EmptyView()
                } footer: {
                    Text("Game results and sportsbook lines from nflverse (free, open data). Odds are estimates, not sure things. Only bet money you can afford to lose. Not financial advice.")
                }
            }
            .listStyle(.insetGrouped)
            .marketBackground()
            .navigationTitle("NFL odds check")
            .toolbar { ToolbarItem(placement: .topBarTrailing) { SettingsButton() } }
            .sheet(item: $logging) { g in
                NavigationStack {
                    Form {
                        Section {
                            EmptyView()
                        } footer: {
                            Text("Who wins? It's logged at that site's price today and followed under My picks. Nothing is bought.")
                        }
                        ForEach([("Kalshi", g.kalshiPick), ("Polymarket", g.polymarketPick)], id: \.0) { item in
                            if let m = item.1 {
                                Section(item.0) {
                                    ForEach([(g.away, m.away), (g.home, m.home)], id: \.0) { team in
                                        Button(String(format: "%@  %.0f¢", team.0, team.1.prob * 100)) {
                                            PredJournal.log(m.event, team.1, side: "yes")
                                            logged = "Logged: \(team.0) on \(item.0)"
                                            logging = nil
                                        }
                                    }
                                }
                            }
                        }
                    }
                    .navigationTitle("\(g.away) at \(g.home)")
                    .navigationBarTitleDisplayMode(.inline)
                    .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { logging = nil } } }
                }
                .presentationDetents([.medium])
            }
            .refreshable { await load(force: true) }
            .task { await load(force: false) }
        }
    }

    private func pct(_ v: Double?) -> String { v.map { String(format: "%.0f%%", $0 * 100) } ?? "–" }

    @ViewBuilder
    private func card(_ g: OddsGame) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text("\(g.away) at \(g.home)").font(.headline)
                Spacer()
                Text(g.kickoff).font(.caption2).foregroundStyle(.secondary)
            }
            Text("Chance the \(g.home) win").font(.caption).foregroundStyle(.secondary)
            HStack {
                ForEach([("Sportsbooks", g.books), ("Kalshi", g.kalshi), ("Polymarket", g.polymarket), ("Math model", Optional(g.model))], id: \.0) { item in
                    VStack(spacing: 2) {
                        Text(pct(item.1)).font(.title3.weight(.bold))
                        Text(item.0).font(.caption2).foregroundStyle(.secondary)
                    }
                    .frame(maxWidth: .infinity)
                }
            }
            if let note = note(g) {
                Text(note).font(.footnote).foregroundStyle(g.gap >= 3 ? .green : .secondary)
            }
            if g.kalshiPick != nil || g.polymarketPick != nil {
                Button("Log my pick") { logging = g }.buttonStyle(.borderless).font(.caption)
            }
            HStack(spacing: 12) {
                if let u = URL(string: g.kalshiURL), !g.kalshiURL.isEmpty {
                    Button("Kalshi") { openURL(u) }.buttonStyle(.bordered)
                }
                if let u = URL(string: g.polymarketURL), !g.polymarketURL.isEmpty {
                    Button("Polymarket") { openURL(u) }.buttonStyle(.bordered)
                }
            }
        }
        .padding(.vertical, 4)
    }

    /// One plain-English line about the biggest disagreement.
    private func note(_ g: OddsGame) -> String? {
        guard let ref = g.books else { return nil }
        let candidates = [g.kalshi.map { ("Kalshi", $0) }, g.polymarket.map { ("Polymarket", $0) }].compactMap { $0 }
        guard let best = candidates.max(by: { abs($0.1 - ref) < abs($1.1 - ref) }) else {
            return "Not listed on Kalshi or Polymarket yet."
        }
        let (site, v) = best
        let pts = (v - ref) * 100
        if abs(pts) < 3 { return "\(site) is in line with the sportsbooks." }
        let cheaper = pts > 0 ? g.away : g.home
        return String(format: "%@ has the %@ %.0f points %@ than the sportsbooks, so the %@ are the cheaper side there.",
                      site, g.home, abs(pts), pts > 0 ? "higher" : "lower", cheaper)
    }

    private func load(force: Bool) async {
        if loading { return }
        loading = true
        error = nil
        do { result = try await Odds.load(force: force) } catch { self.error = error.localizedDescription }
        loading = false
    }
}
