import SwiftUI

/// Investor profile: public-role facts, committees, recent trades and activity in the app.
struct PersonView: View {
    let target: PersonTarget
    @EnvironmentObject var model: AppModel
    @Environment(\.openURL) private var openURL
    @State private var profile: PersonProfile?

    private func mine(_ person: String?, _ who: String) -> Bool {
        if !target.key.isEmpty, let p = person, !p.isEmpty { return p == target.key }
        return cleanName(who).caseInsensitiveCompare(target.name) == .orderedSame
    }
    private var moves: [Move] { model.moves.filter { mine($0.person, $0.who) }.sorted { $0.time > $1.time } }
    private var holds: [String] {
        var seen = Set<String>()
        return model.picks.filter { $0.action == "hold" && mine($0.person, $0.who) }.map(\.ticker)
            .filter { seen.insert($0).inserted }
    }

    var body: some View {
        List {
            Section {
                HStack(spacing: 14) {
                    TickerBadge(text: target.category == .fund ? fundBadge(profile?.name ?? target.name) : initials(profile?.name ?? target.name),
                                color: target.category.color)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(profile?.name ?? target.name).font(.title3.weight(.bold))
                        Text(profile?.subtitle ?? target.category.label).font(.caption).foregroundStyle(.secondary)
                    }
                }
                if profile == nil { ProgressView().frame(maxWidth: .infinity) }
                if let note = profile?.note, !note.isEmpty { Text(note).font(.footnote).foregroundStyle(.secondary) }
            }

            if let p = profile, !p.facts.isEmpty {
                Section("Profile") {
                    ForEach(Array(p.facts.enumerated()), id: \.offset) { _, f in
                        LabeledContent(f.0, value: f.1)
                    }
                }
            }
            if let p = profile, !p.committees.isEmpty {
                Section {
                    ForEach(p.committees, id: \.self) { Text($0) }
                } header: {
                    Text("Committees")
                } footer: {
                    Text("Committees oversee specific industries, which can matter when a member trades stocks in them.")
                }
            }
            if let p = profile, !p.record.isEmpty {
                Section {
                    ForEach(p.record.prefix(15)) { r in
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text("\(r.ticker.isEmpty ? "—" : r.ticker) · \(r.type)").font(.subheadline.weight(.medium))
                                Text([r.amount, r.date].filter { !$0.isEmpty }.joined(separator: " · "))
                                    .font(.caption).foregroundStyle(.secondary)
                            }
                            Spacer()
                            if let pct = r.perfPct {
                                Text(String(format: "%+.1f%%", pct)).font(.subheadline.weight(.bold))
                                    .foregroundStyle(pct >= 0 ? .green : .red)
                            }
                        }
                    }
                } header: {
                    Text("Recent disclosed trades")
                } footer: {
                    Text("% = how the stock has moved since the trade date.")
                }
            }

            Section("In this app") {
                if moves.isEmpty && holds.isEmpty {
                    Text("No trades from them have been captured yet.").foregroundStyle(.secondary)
                }
                ForEach(Array(moves.prefix(12).enumerated()), id: \.offset) { _, m in
                    Button {
                        model.openPerson = nil
                        model.openStock = StockTarget(ticker: m.ticker, company: m.company)
                    } label: {
                        TradeLine(line: "\(m.side == "buy" ? "Bought" : "Sold") \(m.ticker) · \(m.valueLabel)")
                    }
                    .buttonStyle(.borderless)
                }
                if !holds.isEmpty {
                    Text("Top holdings: " + holds.prefix(20).joined(separator: ", ")).font(.footnote)
                }
            }

            if let p = profile, !p.links.isEmpty {
                Section("Official sources") {
                    ForEach(Array(p.links.enumerated()), id: \.offset) { _, l in
                        if let u = URL(string: l.1) { Link(l.0, destination: u) }
                    }
                }
            }
            Section {
                EmptyView()
            } footer: {
                Text("Only public information about their public role is shown. Personal details such as home addresses are left out on purpose.")
            }
        }
        .navigationTitle(target.category.label)
        .navigationBarTitleDisplayMode(.inline)
        .task(id: target.id) {
            profile = await People.load(target, ua: model.userAgent, bargoKey: model.bargoKey)
        }
    }

    private func initials(_ name: String) -> String {
        let w = name.split(separator: " ").filter { $0.first?.isLetter == true }
        let s = (w.first.map { String($0.prefix(1)) } ?? "") + (w.count > 1 ? String(w.last!.prefix(1)) : "")
        return s.isEmpty ? "?" : s.uppercased()
    }
}
