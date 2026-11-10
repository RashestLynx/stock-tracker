import SwiftUI

/// The feed, with one section (tab) per group: Senate, House, Insiders, Funds.
struct FeedView: View {
    @EnvironmentObject var model: AppModel
    let goToSettings: () -> Void

    @State private var section: Category = .senate
    @State private var filter = "All"
    @State private var query = ""

    private var inSection: [Alert] { model.alerts.filter { $0.category == section } }

    private var shown: [Alert] {
        let q = query.trimmingCharacters(in: .whitespaces)
        return inSection.filter { a in
            let f: Bool
            switch filter {
            case "Buys": f = a.buy || section == .fund || section == .charges
            case "Sells": f = a.sell || section == .fund || section == .charges
            default: f = true
            }
            let m = q.isEmpty || [a.ticker, a.company, a.headline].contains { $0.localizedCaseInsensitiveContains(q) }
                || a.lines.contains { $0.localizedCaseInsensitiveContains(q) }
            return f && m
        }
    }

    var body: some View {
        NavigationStack {
            List {
                // Today's buy & sale lives on Home now.

                Section {
                    PillTabs(options: Category.allCases.map { c in
                        let n = model.alerts.filter { $0.category == c }.count
                        return (c, (c == .charges ? "SEC" : c.label) + (n > 0 ? "  \(n)" : ""))
                    }, selection: $section)
                    .listRowBackground(Color.clear)
                    .listRowInsets(EdgeInsets(top: 4, leading: 0, bottom: 4, trailing: 0))

                    if section != .fund && section != .charges {
                        PillTabs(options: ["All", "Buys", "Sells"].map { ($0, $0) }, selection: $filter)
                        .listRowBackground(Color.clear)
                        .listRowInsets(EdgeInsets(top: 0, leading: 0, bottom: 4, trailing: 0))
                    }
                } footer: {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(model.status)
                        if !History.status.isEmpty { Text(History.status) }
                    }
                    .font(.caption)
                }

                if !model.isSetUp {
                    Section {
                        VStack(alignment: .leading, spacing: 8) {
                            Text("Finish setting up").font(.headline)
                            Text("Add your name and email so the SEC lets the app check new filings.")
                                .font(.subheadline).foregroundStyle(.secondary)
                            Button("Go to Settings", action: goToSettings).buttonStyle(.borderedProminent)
                        }
                        .padding(.vertical, 4)
                    }
                }

                if !model.isWatching(section) {
                    EmptyStateView(title: "\(section.label) is turned off",
                                   message: "Turn it back on in Settings → Sections.",
                                   systemImage: "bell.slash")
                        .listRowBackground(Color.clear)
                } else if shown.isEmpty {
                    EmptyStateView(title: inSection.isEmpty ? "No \(section.label.lowercased()) filings yet" : "Nothing matches",
                                   message: inSection.isEmpty ? hint : "Try a different search or filter.")
                        .listRowBackground(Color.clear)
                } else {
                    Section {
                        ForEach(shown) { AlertRow(alert: $0) }
                    }
                }

                if section == .senate || section == .house {
                    Section {
                        Link("Congress data from Bargo, built from official House and Senate filings",
                             destination: URL(string: Congress.sourceURL)!)
                            .font(.caption2)
                    }
                    .listRowBackground(Color.clear)
                }
            }
            .listStyle(.plain)
            .marketBackground()
            .navigationTitle("Sections")
            .searchable(text: $query, prompt: "Search \(section.label.lowercased())")
            .refreshable { await model.check() }
            .toolbar {
                ToolbarItem(placement: .topBarLeading) { SettingsButton() }
                ToolbarItem(placement: .topBarTrailing) {
                    if model.isChecking {
                        ProgressView()
                    } else {
                        Button { Task { await model.check() } } label: {
                            Image(systemName: "arrow.clockwise")
                        }
                    }
                }
            }
            .onChange(of: section) { _, _ in filter = "All" }
        }
    }

    private var hint: String {
        switch section {
        case .senate, .house:
            return "Congress members have up to 45 days to disclose trades. New ones are checked every few hours. Pull down to check now."
        case .insider:
            return "Insider trades usually show up within 2 business days. Pull down to check now."
        case .fund:
            return "Big funds file holdings every quarter, mostly near mid-Feb, May, Aug and Nov."
        case .charges:
            return "When the SEC charges someone with illegal insider trading, the case shows up here. Pull down to check now."
        }
    }
}
