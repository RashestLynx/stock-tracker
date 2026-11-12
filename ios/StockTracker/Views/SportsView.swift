import SwiftUI

/// Sports: pick a league from the menu. NFL adds sportsbook odds and the math model; the rest use Kalshi and Polymarket.
struct SportsView: View {
    @State private var leagueName = "NFL"
    private var league: League { SportsData.leagues.first { $0.name == leagueName } ?? SportsData.leagues[0] }

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Menu {
                    ForEach(SportsData.leagues, id: \.name) { l in
                        Button(l.name) { leagueName = l.name }
                    }
                } label: {
                    Label("League: \(league.name)", systemImage: "chevron.down").font(.subheadline.weight(.semibold))
                }
                .buttonStyle(.bordered)
                Spacer()
            }
            .padding(.horizontal).padding(.bottom, 4)
            if league.name == "NFL" { NflOddsView() } else { LeagueView(league: league).id(league.name) }
        }
    }
}

struct LeagueView: View {
    let league: League
    @Environment(\.openURL) private var openURL
    @State private var games: [SportGame]?
    @State private var loading = false
    @State private var error: String?
    @State private var picking: SportGame?
    @State private var comparing: SportGame?
    @State private var books: [BookGame] = []
    @State private var booksError: String?
    @State private var logged = ""

    private func pct(_ v: Double?) -> String { v.map { String(format: "%.0f%%", $0 * 100) } ?? "–" }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    EmptyView()
                } footer: {
                    Text("Each side's chance to win from Kalshi and Polymarket, with the best estimate combining them. " +
                         (league.hasDraw ? "Soccer games can end in a draw, so it's listed too. " : "") +
                         "When the two sites differ by 3 points or more, one side is cheaper on one of them.")
                }
                if !logged.isEmpty { Section { Text("\(logged). See it under My picks.").foregroundStyle(.green) } }
                if let e = booksError { Section { Text("Sportsbook odds: \(e)").font(.footnote).foregroundStyle(.red) } }
                if let e = error { Section { Text(e).foregroundStyle(.red) } }
                if loading && games == nil { Section { ProgressView().frame(maxWidth: .infinity) } }
                if let g = games, g.isEmpty {
                    Section { Text("No \(league.name) games open right now. The season may be over or not started yet.").foregroundStyle(.secondary) }
                }
                ForEach(games ?? []) { g in Section { card(g) } }
                Section {
                    EmptyView()
                } footer: {
                    Text("Prices are each site's current chance, adjusted so a game's sides add up to 100%. Only bet money you can afford to lose. Not financial advice.")
                }
            }
            .listStyle(.insetGrouped)
            .marketBackground()
            .navigationTitle(league.name)
            .navigationBarTitleDisplayMode(.inline)
            .sheet(item: $comparing) { g in CompareView(league: league, game: g) }
            .sheet(item: $picking) { g in
                NavigationStack {
                    Form {
                        Section {
                            EmptyView()
                        } footer: {
                            Text("Who wins? Pick a side and a site. It's logged at that price and followed under My picks. Nothing is bought.")
                        }
                        ForEach([("Kalshi", Optional(g.kalshiEvent)), ("Polymarket", g.polyEvent)], id: \.0) { item in
                            if let ev = item.1 {
                                Section(item.0) {
                                    ForEach(g.sides) { side in
                                        if let o = item.0 == "Kalshi" ? side.kalshi : side.poly, !o.ref.isEmpty {
                                            NavigationLink(String(format: "%@  %.0f¢", side.label, o.prob * 100)) {
                                                BetSheetView(target: BetTarget(event: ev, outcome: o, side: "yes", estimate: side.best,
                                                    estimateNote: String(format: "Best estimate for %@: %.0f%%, from %@.", side.label, side.best * 100,
                                                                         side.polyP != nil ? "Kalshi and Polymarket combined" : "Kalshi's price"))) {
                                                    logged = $0; picking = nil
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    .navigationTitle(g.title)
                    .navigationBarTitleDisplayMode(.inline)
                    .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { picking = nil } } }
                }
                .presentationDetents([.medium, .large])
            }
            .refreshable { await load(force: true) }
            .task { await load(force: false) }
        }
    }

    @ViewBuilder
    private func card(_ g: SportGame) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(g.title).font(.headline).lineLimit(2)
                Spacer()
                if !g.date.isEmpty { Text(g.date).font(.caption2).foregroundStyle(.secondary) }
            }
            HStack {
                Spacer()
                ForEach(["Best", "Kalshi", "Poly"], id: \.self) { Text($0).font(.caption2).foregroundStyle(.secondary).frame(width: 52, alignment: .trailing) }
            }
            ForEach(g.sides) { s in
                HStack {
                    Text(s.label).lineLimit(1)
                    Spacer()
                    Text(pct(s.best)).fontWeight(.bold).frame(width: 52, alignment: .trailing)
                    Text(pct(s.kalshiP)).font(.caption).frame(width: 52, alignment: .trailing)
                    Text(pct(s.polyP)).font(.caption).frame(width: 52, alignment: .trailing)
                }
            }
            if let bg = BookOdds.find(books, g.sides.map(\.label)) {
                ForEach(g.sides.filter { !SportsData.isDraw($0.label) }) { s in
                    let lines = bg.sideLines(s.label)
                    if !lines.isEmpty {
                        Text("\(s.label): " + lines.map { "\($0.book) \($0.oddsText) (\(pct($0.fair)))" }.joined(separator: " · ")
                             + (lines.count > 1 ? " · pays most: \(bg.best(s.label)?.book ?? "")" : ""))
                            .font(.caption2).foregroundStyle(.secondary)
                    }
                }
            }
            if g.gap >= 3, let s = g.widest {
                let cheaper = (s.kalshiP ?? 0) < (s.polyP ?? 0) ? "Kalshi" : "Polymarket"
                Text(String(format: "The sites differ by %.0f points on %@. It's cheaper on %@.", g.gap, s.label, cheaper))
                    .font(.footnote).foregroundStyle(.green)
            }
            HStack(spacing: 16) {
                Button("Compare") { comparing = g }
                Button("Log my pick") { picking = g }
                Button("Kalshi") { if let u = URL(string: g.kalshiEvent.url) { openURL(u) } }
                if let pe = g.polyEvent { Button("Polymarket") { if let u = URL(string: pe.url) { openURL(u) } } }
            }
            .buttonStyle(.borderless).font(.caption)
        }
        .padding(.vertical, 4)
    }

    private func load(force: Bool) async {
        if loading { return }
        loading = true
        error = nil
        do { games = try await SportsData.load(league, force: force) } catch { self.error = error.localizedDescription }
        loading = false
        do { books = try await BookOdds.league(league.polyTag, force: force); booksError = nil } catch { booksError = error.localizedDescription }
    }
}
