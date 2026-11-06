import SwiftUI

/// One list section per followed person: businesses, latest videos, news.
struct FollowingSections: View {
    @State private var list = Following.load()
    @State private var adding = false
    @State private var newName = ""
    @State private var newHandle = ""

    var body: some View {
        ForEach(list) { f in
            FollowedSection(person: f) {
                list.removeAll { $0.id == f.id }
                Following.save(list)
            }
        }
        Section {
            if adding {
                TextField("Name", text: $newName)
                TextField("YouTube handle (optional), e.g. @grahamstephan", text: $newHandle)
                    .textInputAutocapitalization(.never).autocorrectionDisabled()
                Button("Follow") {
                    let name = newName.trimmingCharacters(in: .whitespaces)
                    list.append(Followed(name: name, youtube: newHandle.replacingOccurrences(of: "@", with: "")
                        .trimmingCharacters(in: .whitespaces), newsQuery: "\"\(name)\"", businesses: [], links: []))
                    Following.save(list)
                    newName = ""; newHandle = ""; adding = false
                }
                .disabled(newName.trimmingCharacters(in: .whitespaces).isEmpty)
                Button("Cancel", role: .cancel) { adding = false }
            } else {
                Button { adding = true } label: { Label("Follow someone else", systemImage: "plus.circle") }
            }
        }
    }
}

struct FollowedSection: View {
    let person: Followed
    let onRemove: () -> Void
    @State private var videos: [Video]?
    @State private var news: [NewsItem]?
    @State private var mentions: [Mention]?
    @EnvironmentObject var model: AppModel

    private var reported: [Move] {
        model.moves.filter { cleanName($0.who).localizedCaseInsensitiveContains(person.name) }.sorted { $0.time > $1.time }
    }

    var body: some View {
        Section {
            if !person.businesses.isEmpty {
                VStack(alignment: .leading, spacing: 4) {
                    Text("Businesses").font(.subheadline.weight(.semibold))
                    ForEach(person.businesses, id: \.self) { Text("• \($0)").font(.subheadline) }
                }
            }
            if !reported.isEmpty {
                Text("Reported trades").font(.subheadline.weight(.semibold))
                ForEach(Array(reported.prefix(8).enumerated()), id: \.offset) { _, m in
                    Button {
                        model.openStock = StockTarget(ticker: m.ticker, company: m.company)
                    } label: {
                        TradeLine(line: "\(m.side == "buy" ? "Bought" : "Sold") \(m.ticker) · \(m.valueLabel) · \(Format.ago(m.time))")
                    }
                    .buttonStyle(.borderless)
                }
            }
            Text("Stocks they've talked about").font(.subheadline.weight(.semibold))
            if let ms = mentions {
                if ms.isEmpty {
                    Text("No specific stocks named in their recent videos or news.").font(.footnote).foregroundStyle(.secondary)
                } else {
                    ForEach(ms) { m in
                        Button {
                            model.openStock = StockTarget(ticker: m.ticker, company: m.company)
                        } label: {
                            HStack {
                                Text(m.ticker).font(.headline).frame(width: 64, alignment: .leading)
                                VStack(alignment: .leading, spacing: 1) {
                                    Text(m.company).font(.caption).lineLimit(1)
                                    Text("\(m.count) mention\(m.count == 1 ? "" : "s") · latest \(Format.ago(m.latest))")
                                        .font(.caption2).foregroundStyle(.secondary)
                                }
                                Spacer()
                                Text(m.stance.capitalized).font(.subheadline.weight(.semibold))
                                    .foregroundStyle(m.stance == "buying" ? .green : m.stance == "selling" ? .red : .secondary)
                            }
                            .foregroundStyle(.primary)
                        }
                        .buttonStyle(.borderless)
                    }
                    Text("From their video titles and descriptions and news about them. These are things they've said, not confirmed trades.")
                        .font(.caption2).foregroundStyle(.secondary)
                }
            } else {
                ProgressView()
            }
            Text("Latest videos").font(.subheadline.weight(.semibold))
            if let v = videos {
                if v.isEmpty {
                    Text(person.youtube.isEmpty ? "No YouTube channel added." : "Couldn't load videos right now.")
                        .font(.footnote).foregroundStyle(.secondary)
                }
                ForEach(v.prefix(5)) { video in
                    if let u = URL(string: video.url) {
                        Link(destination: u) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(video.title).font(.subheadline.weight(.medium)).foregroundStyle(.primary)
                                Text(Format.ago(video.time)).font(.caption2).foregroundStyle(.secondary)
                            }
                        }
                    }
                }
            } else {
                ProgressView()
            }
            Text("In the news").font(.subheadline.weight(.semibold))
            if let n = news {
                if n.isEmpty { Text("No recent news.").font(.footnote).foregroundStyle(.secondary) }
                ForEach(n.prefix(4)) { item in
                    if let u = URL(string: item.url) {
                        Link(destination: u) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(item.title).font(.subheadline).foregroundStyle(.primary)
                                Text("\(item.source) · \(Format.ago(item.time))").font(.caption2).foregroundStyle(.secondary)
                            }
                        }
                    }
                }
            } else {
                ProgressView()
            }
            ForEach(person.links, id: \.self) { l in
                if l.count == 2, let u = URL(string: l[1]) { Link(l[0], destination: u) }
            }
        } header: {
            HStack {
                Text(person.name).font(.title3.weight(.bold)).foregroundStyle(.primary).textCase(nil)
                Spacer()
                Button("Unfollow", action: onRemove).font(.caption).textCase(nil)
            }
        }
        .task(id: person.id) {
            let v = await Following.videos(person)
            let n = (try? await News.search(person.newsQuery)) ?? []
            videos = v
            news = n
            mentions = await Following.mentions(videos: v, news: n, ua: model.userAgent)
        }
    }
}
