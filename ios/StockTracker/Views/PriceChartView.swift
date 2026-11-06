import SwiftUI
import Charts

/// Robinhood-style line chart: 1D-5Y, drag to read prices, green/red by direction, dashed previous close.
struct PriceChartView: View {
    let ticker: String
    var live: LiveQuote?
    @EnvironmentObject var model: AppModel
    @State private var range: ChartRange = .d1
    @State private var pts: [Pt]?
    @State private var error: String?
    @State private var selected: Pt?

    private var base: Double? {
        if range == .d1, let pc = live?.prevClose, pc > 0 { return pc }
        return pts?.first?.p
    }

    var body: some View {
        let data = pts ?? []
        let shown = selected ?? data.last
        let up = (shown?.p ?? 0) >= (base ?? 0)
        let color: Color = up ? .green : .red
        VStack(alignment: .leading, spacing: 6) {
            if let s = shown, let b = base, b > 0 {
                let ch = s.p - b
                Text("$" + Format.number(s.p, decimals: 2)).font(.title.weight(.bold))
                Text(String(format: "%@%.2f (%+.2f%%) ", ch >= 0 ? "▲ +" : "▼ ", ch, ch / b * 100)
                     + (selected != nil ? s.t.formatted(date: .abbreviated, time: .shortened) : range == .d1 ? "today" : "past \(range.rawValue)"))
                    .font(.subheadline.weight(.semibold)).foregroundStyle(color)
            }
            Group {
                if pts == nil {
                    ProgressView().frame(maxWidth: .infinity)
                } else if data.count < 2 {
                    Text(error ?? (range == .d1 ? "Today's chart draws live while the app is open during market hours." : "No chart data."))
                        .font(.caption).foregroundStyle(.secondary).multilineTextAlignment(.center).frame(maxWidth: .infinity)
                } else {
                    let lo = min(data.map(\.p).min() ?? 0, base ?? .greatestFiniteMagnitude)
                    let hi = max(data.map(\.p).max() ?? 0, base ?? -.greatestFiniteMagnitude)
                    Chart {
                        ForEach(data) { p in
                            LineMark(x: .value("Time", p.t), y: .value("Price", p.p))
                                .foregroundStyle(color).lineStyle(StrokeStyle(lineWidth: 2.5, lineCap: .round))
                        }
                        if let b = base {
                            RuleMark(y: .value("Previous", b)).foregroundStyle(.secondary)
                                .lineStyle(StrokeStyle(lineWidth: 1, dash: [3, 4]))
                        }
                        if let s = selected {
                            RuleMark(x: .value("Selected", s.t)).foregroundStyle(.secondary)
                            PointMark(x: .value("Time", s.t), y: .value("Price", s.p)).foregroundStyle(color)
                        }
                    }
                    .chartYScale(domain: lo...hi)
                    .chartXAxis(.hidden).chartYAxis(.hidden)
                    .chartOverlay { proxy in
                        GeometryReader { _ in
                            Rectangle().fill(.clear).contentShape(Rectangle())
                                .gesture(DragGesture(minimumDistance: 0)
                                    .onChanged { v in
                                        if let d: Date = proxy.value(atX: v.location.x) {
                                            selected = data.min { abs($0.t.timeIntervalSince(d)) < abs($1.t.timeIntervalSince(d)) }
                                        }
                                    }
                                    .onEnded { _ in selected = nil })
                        }
                    }
                }
            }
            .frame(height: 180)
            HStack {
                ForEach(ChartRange.allCases) { r in
                    Button(r.rawValue) { range = r }
                        .buttonStyle(.borderless)
                        .font(.caption.weight(r == range ? .bold : .regular))
                        .foregroundStyle(r == range ? color : .secondary)
                    if r != .y5 { Spacer() }
                }
            }
            if range == .d1 {
                Text("1D draws live from today's open while the app is open. Dashed line = yesterday's close.")
                    .font(.caption2).foregroundStyle(.secondary)
            }
        }
        .task(id: "\(ticker)|\(range.rawValue)|\(live?.price ?? 0)") {
            do { pts = try await Charts.history(ticker, range, key: model.massiveKey); error = nil }
            catch { self.error = error.localizedDescription; if pts == nil { pts = [] } }
        }
    }
}
