import SwiftUI

/// Analyst ratings and price targets for one stock (like Robinhood's).
struct AnalystCard: View {
    let ticker: String
    let price: Double?
    @State private var view: AnalystView?

    private let holdColor = Color(red: 0.98, green: 0.66, blue: 0.15)

    var body: some View {
        Group {
            if let v = view {
                VStack(alignment: .leading, spacing: 10) {
                    Text("\(v.total) analyst ratings").font(.headline)
                    if v.total > 0 { ratings(v) }
                    if let low = v.low, let avg = v.avg, let high = v.high, high > low { targets(low: low, avg: avg, high: high) }
                    Text(sourceLine(v)).font(.caption2).foregroundStyle(.secondary)
                    ForEach(v.all.filter { $0.name != v.ratings?.name && $0.total > 0 }, id: \.name) { o in
                        Text("\(o.name): \(o.total) analysts, \(o.pct(o.buy))% buy, \(o.pct(o.hold))% hold, \(o.pct(o.sell))% sell")
                            .font(.caption2).foregroundStyle(.secondary)
                    }
                    Text("Analysts lean optimistic and targets are often missed; use this as one input.")
                        .font(.caption2).foregroundStyle(.secondary)
                }
                .padding()
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 16))
            }
        }
        .task(id: ticker) { view = await Analysts.get(ticker) }
    }

    private func sourceLine(_ v: AnalystView) -> String {
        var s = "Ratings from \(v.ratings?.name ?? "–")"
        if let tg = v.targets {
            s += tg.name == v.ratings?.name ? " (targets too)" : "; targets from \(tg.name)" + (tg.targetAnalysts > 0 ? " (\(tg.targetAnalysts) analysts)" : "")
        }
        return s + "."
    }

    private func ratings(_ v: AnalystView) -> some View {
        let rows: [(String, Int, Color)] = [("Buy", v.buy, .green), ("Hold", v.hold, holdColor), ("Sell", v.sell, .red)]
        let top = rows.max { $0.1 < $1.1 }!
        return HStack(spacing: 14) {
            Text("\(v.pct(top.1))%").font(.headline).foregroundStyle(.white)
                .frame(width: 64, height: 64).background(top.2, in: Circle())
            VStack(spacing: 6) {
                ForEach(rows, id: \.0) { row in
                    HStack {
                        GeometryReader { g in
                            ZStack(alignment: .leading) {
                                Capsule().fill(Color(.tertiarySystemFill))
                                Capsule().fill(row.2).frame(width: g.size.width * CGFloat(v.pct(row.1)) / 100)
                            }
                        }.frame(height: 8)
                        Text("\(v.pct(row.1))% \(row.0)").font(.caption).foregroundStyle(row.2).frame(width: 76, alignment: .leading)
                    }
                }
            }
        }
    }

    private func targets(low: Double, avg: Double, high: Double) -> some View {
        let lo = min(low, price ?? low), hi = max(high, price ?? high)
        func at(_ x: Double) -> CGFloat { CGFloat(max(0, min(1, (x - lo) / (hi - lo)))) }
        return VStack(alignment: .leading, spacing: 8) {
            Text("Price targets").fontWeight(.semibold)
            GeometryReader { g in
                let w = g.size.width
                ZStack(alignment: .leading) {
                    Capsule().fill(Color(.tertiarySystemFill)).frame(height: 6)
                    Capsule().fill(Color.green.opacity(0.35)).frame(width: w * (at(high) - at(low)), height: 6).offset(x: w * at(low))
                    Rectangle().fill(Color.primary).frame(width: 3, height: 18).offset(x: max(0, w * at(avg) - 1.5))
                    if let p = price {
                        Circle().fill(p < avg ? Color.red : Color.green).frame(width: 12, height: 12)
                            .offset(x: min(max(0, w * at(p) - 6), w - 12))
                    }
                }
            }.frame(height: 22)
            HStack {
                VStack(alignment: .leading) { Text("Lowest").font(.caption2); Text(String(format: "$%.2f", low)).fontWeight(.semibold) }
                Spacer()
                VStack { Text("Average").font(.caption2); Text(String(format: "$%.2f", avg)).fontWeight(.semibold) }
                Spacer()
                VStack(alignment: .trailing) { Text("Highest").font(.caption2); Text(String(format: "$%.2f", high)).fontWeight(.semibold) }
            }
            if let p = price {
                let diff = (p / avg - 1) * 100
                Text(String(format: "Current price $%.2f is %.1f%% %@ the average 12-month target of $%.2f.", p, abs(diff), diff < 0 ? "below" : "above", avg))
                    .font(.footnote)
            }
        }
    }
}
