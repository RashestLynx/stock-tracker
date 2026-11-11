import SwiftUI

private func moodMark(_ mood: Int) -> some View {
    Text(mood > 0 ? "▲" : mood < 0 ? "▼" : "•").fontWeight(.bold)
        .foregroundStyle(mood > 0 ? Color.green : mood < 0 ? Color.red : Color.secondary)
}

/// Markets → Patterns: last night's scan of every U.S. stock, plus live price alerts.
struct NightlyPatternsView: View {
    @EnvironmentObject var model: AppModel
    @State private var view = 0          // 0 = signals, 1 = track records
    @State private var kind = 0
    @State private var dir = 0           // 0 = both, 1 = up, 2 = down
    @State private var result: NightlyResult?
    @State private var error: String?
    @State private var showN = 60

    private let kindName = ["", "Candlestick", "Chart shape", "Indicator", "Smart money"]

    private var signals: [NightlySignal] {
        (result?.signals() ?? []).filter { s in
            (kind == 0 || s.hit.pattern.kind == kindName[kind]) &&
                (dir == 0 || (dir == 1 && s.hit.pattern.mood > 0) || (dir == 2 && s.hit.pattern.mood < 0))
        }
    }

    private var records: [NightlyPattern] {
        (result?.patterns ?? []).filter { (kind == 0 || $0.kind == kindName[kind]) && $0.n > 0 }.sorted { $0.edge > $1.edge }
    }

    var body: some View {
        List {
            let recent = model.priceAlertLog.filter { Date().timeIntervalSince($0.time) < 3 * 86_400 }
            if !recent.isEmpty {
                Section("Live price alerts") {
                    ForEach(recent.prefix(8)) { a in
                        Button { model.openStock = StockTarget(ticker: a.ticker, company: "") } label: {
                            VStack(alignment: .leading, spacing: 2) {
                                HStack {
                                    moodMark(a.up ? 1 : -1)
                                    Text(a.title).fontWeight(.semibold)
                                    Spacer()
                                    Text(a.time, style: .relative).font(.caption2).foregroundStyle(.secondary)
                                }
                                if !a.text.isEmpty { Text(a.text).font(.footnote) }
                            }
                        }.buttonStyle(.plain)
                    }
                }
            }
            Section {
                PillTabs(options: [(0, "Signals"), (1, "Track records")], selection: $view)
                PillTabs(options: [(0, "All"), (1, "Candlesticks"), (2, "Chart shapes"), (3, "Indicators"), (4, "Smart money")], selection: $kind)
                if view == 0 { PillTabs(options: [(0, "Up and down"), (1, "Up signals"), (2, "Down signals")], selection: $dir) }
                Text(result.map { "Last night's scan of \($0.scanned.formatted()) U.S. stocks (prices from \($0.date)). The track record is every time each pattern showed up in any stock over the last 2 years. Live alerts check your watchlist and today's strongest setups while the market is open." }
                     ?? "Every U.S. stock is scanned each night for about 70 patterns.")
                    .font(.footnote).foregroundStyle(.secondary)
            }
            if let e = error, result == nil {
                EmptyStateView(title: "No scan yet", message: e, systemImage: "waveform.path.ecg")
            }
            if result != nil && view == 0 {
                let shown = signals
                if shown.isEmpty { Text("Nothing matches these filters today.").foregroundStyle(.secondary) }
                ForEach(shown.prefix(showN)) { s in
                    Button { model.openStock = StockTarget(ticker: s.stock.ticker, company: "") } label: { NightlySignalRow(signal: s) }
                        .buttonStyle(.plain)
                }
                if shown.count > showN { Button("Show more (\(shown.count - showN) left)") { showN += 60 } }
            }
            if result != nil && view == 1 {
                ForEach(records) { p in
                    VStack(alignment: .leading, spacing: 4) {
                        HStack {
                            moodMark(p.mood)
                            Text(p.name).fontWeight(.semibold)
                            Spacer()
                            Text(p.edge > 0 ? "Worked" : "Didn't work").font(.caption).foregroundStyle(p.edge > 0 ? .green : .red)
                        }
                        Text(p.desc).font(.footnote)
                        Text(p.record).font(.footnote).foregroundStyle(.secondary)
                    }
                }
            }
            Section {
                EmptyView()
            } footer: {
                Text("Patterns are hints, not predictions. \"Worked\" means it beat the S&P 500 on average in the direction it points; past results don't guarantee future ones.")
            }
        }
        .refreshable { await load(force: true); await model.checkPrices() }
        .task { await load(force: false) }
        .task {
            // While this screen is open, check prices every 5 minutes.
            while !Task.isCancelled {
                await model.checkPrices()
                try? await Task.sleep(nanoseconds: 300_000_000_000)
            }
        }
        .onChange(of: kind) { _, _ in showN = 60 }
        .onChange(of: dir) { _, _ in showN = 60 }
    }

    private func load(force: Bool) async {
        do { result = try await Nightly.load(force: force); error = nil } catch { self.error = error.localizedDescription }
    }
}

struct NightlySignalRow: View {
    let signal: NightlySignal
    var body: some View {
        let s = signal.stock, h = signal.hit
        VStack(alignment: .leading, spacing: 3) {
            HStack {
                Text(s.ticker).font(.headline)
                Text(String(format: "$%.2f  %+.1f%%", s.price, s.chg * 100)).font(.caption).foregroundStyle(s.chg >= 0 ? .green : .red)
                Spacer()
                Text(h.whenText).font(.caption2).foregroundStyle(.secondary)
            }
            HStack(spacing: 4) { moodMark(h.pattern.mood); Text(h.pattern.name).fontWeight(.semibold) }
            if !h.note.isEmpty { Text(h.note).font(.footnote) }
            if !h.pattern.record.isEmpty { Text(h.pattern.record).font(.footnote).foregroundStyle(.secondary) }
            if !h.ownRecord.isEmpty { Text(h.ownRecord).font(.footnote).foregroundStyle(.secondary) }
        }
        .contentShape(Rectangle())
    }
}

/// On a stock's page: what last night's scan found for it.
struct NightlyCard: View {
    let ticker: String
    @State private var stock: NightlyStock?
    @State private var date = ""

    var body: some View {
        Group {
            if let s = stock, !s.hits.isEmpty {
                VStack(alignment: .leading, spacing: 8) {
                    Text("Last night's scan").font(.headline)
                    ForEach(Array(s.hits.enumerated()), id: \.offset) { _, h in
                        VStack(alignment: .leading, spacing: 2) {
                            HStack(spacing: 4) {
                                moodMark(h.pattern.mood)
                                Text(h.pattern.name).fontWeight(.semibold)
                                Spacer()
                                Text(h.whenText).font(.caption2).foregroundStyle(.secondary)
                            }
                            Text(h.note.isEmpty ? h.pattern.desc : h.note).font(.footnote)
                            if !h.pattern.record.isEmpty { Text(h.pattern.record).font(.footnote).foregroundStyle(.secondary) }
                            if !h.ownRecord.isEmpty { Text(h.ownRecord).font(.footnote).foregroundStyle(.secondary) }
                        }
                    }
                    Text(String(format: "Live alerts fire if it breaks $%.2f (20-day high) or $%.2f (20-day low). Prices from %@.", s.hi20, s.lo20, date))
                        .font(.caption2).foregroundStyle(.secondary)
                }
                .padding()
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 16))
            }
        }
        .task(id: ticker) {
            let r: NightlyResult?
            if let c = Nightly.cached() { r = c } else { r = try? await Nightly.load() }
            stock = r?.stocks[ticker.uppercased()]
            date = r?.date ?? ""
        }
    }
}
