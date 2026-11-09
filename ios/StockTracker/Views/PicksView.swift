import SwiftUI

/// Stocks that two or more tracked people bought or hold, ranked by how many groups share them.
struct PicksView: View {
    @EnvironmentObject var model: AppModel
    @State private var filter = "Across groups"
    @State private var query = ""

    private let filters = ["Across groups", "All", "Congress", "Insiders"]

    private var overlaps: [Overlap] { computeOverlaps(model.picks) }

    private var shown: [Overlap] {
        let q = query.trimmingCharacters(in: .whitespaces)
        return overlaps.filter { o in
            let f: Bool
            switch filter {
            case "Across groups": f = o.perCategory.count >= 2
            case "Congress": f = (o.perCategory[.senate] ?? 0) + (o.perCategory[.house] ?? 0) >= 2
            case "Insiders": f = (o.perCategory[.insider] ?? 0) >= 2
            default: f = true
            }
            let m = q.isEmpty || o.ticker.localizedCaseInsensitiveContains(q)
                || o.company.localizedCaseInsensitiveContains(q)
                || o.people.contains { $0.who.localizedCaseInsensitiveContains(q) }
            return f && m
        }
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) {
                            ForEach(filters, id: \.self) { f in
                                Button(f) { filter = f }
                                    .buttonStyle(.bordered)
                                    .tint(filter == f ? .accentColor : .secondary)
                                    .controlSize(.small)
                            }
                        }
                    }
                    .listRowBackground(Color.clear)
                    .listRowInsets(EdgeInsets(top: 4, leading: 0, bottom: 4, trailing: 0))
                } footer: {
                    Text("Stocks that two or more of the people you track bought or hold in the last 90 days. Ones shared across different groups, like a senator and a fund, are listed first.")
                }

                if shown.isEmpty {
                    EmptyStateView(
                        title: overlaps.isEmpty ? "No common picks yet" : "Nothing matches",
                        message: overlaps.isEmpty
                            ? "As filings come in, stocks that several people buy will show up here."
                            : filter == "Across groups"
                                ? "No stock is shared across groups yet. Try \"All\" to see overlaps within a group."
                                : "Try a different search or filter.",
                        systemImage: "star")
                        .listRowBackground(Color.clear)
                } else {
                    ForEach(Array(shown.enumerated()), id: \.element.id) { i, o in
                        PickRow(rank: i + 1, overlap: o)
                    }
                }
            }
            .listStyle(.insetGrouped)
            .marketBackground()
            .navigationTitle("Common picks")
            .toolbar { ToolbarItem(placement: .topBarTrailing) { SettingsButton() } }
            .searchable(text: $query, prompt: "Search ticker, company or person")
            .refreshable { await model.check() }
        }
    }
}

struct PickRow: View {
    let rank: Int
    let overlap: Overlap
    @State private var expanded = false
    @EnvironmentObject var model: AppModel

    private var summary: String {
        var s = "\(overlap.people.count) people"
        if overlap.perCategory.count > 1 { s += " across \(overlap.perCategory.count) groups" }
        let holders = overlap.people.count - overlap.buyers
        if overlap.buyers > 0 { s += " · \(overlap.buyers) bought" }
        if holders > 0 { s += " · \(holders) hold" }
        return s
    }

    var body: some View {
        Section {
            DisclosureGroup(isExpanded: $expanded) {
                Button("Summary of \(overlap.ticker)") {
                    model.openStock = StockTarget(ticker: overlap.ticker, company: overlap.company)
                }
                ForEach(Category.allCases) { c in
                    let people = overlap.people.filter { $0.category == c }
                    if !people.isEmpty {
                        VStack(alignment: .leading, spacing: 4) {
                            Text(c.label).font(.subheadline.weight(.semibold)).foregroundStyle(c.color)
                            ForEach(people, id: \.who) { p in
                                Button("• \(p.who) (\(p.action == "hold" ? "holds" : "bought"))  ›") {
                                    model.openPerson = personOf(p)
                                }
                                .buttonStyle(.borderless)
                                .font(.footnote)
                            }
                        }
                        .padding(.vertical, 2)
                    }
                }
            } label: {
                VStack(alignment: .leading, spacing: 10) {
                    HStack(spacing: 12) {
                        TickerBadge(text: overlap.ticker, color: .green)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(overlap.ticker).font(.headline)
                            Text(overlap.company).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                        }
                        Spacer()
                        Text("#\(rank)").font(.subheadline.weight(.semibold)).foregroundStyle(.secondary)
                    }
                    Text(summary).font(.subheadline.weight(.medium))
                    HStack(spacing: 6) {
                        ForEach(Category.allCases) { c in
                            if let n = overlap.perCategory[c], n > 0 { CategoryPill(category: c, count: n) }
                        }
                    }
                }
                .padding(.vertical, 4)
            }
        }
    }
}
