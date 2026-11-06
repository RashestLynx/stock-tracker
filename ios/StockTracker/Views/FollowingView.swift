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

    var body: some View {
        Section {
            if !person.businesses.isEmpty {
                VStack(alignment: .leading, spacing: 4) {
                    Text("Businesses").font(.subheadline.weight(.semibold))
                    ForEach(person.businesses, id: \.self) { Text("• \($0)").font(.subheadline) }
                }
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
            videos = await Following.videos(person)
            news = (try? await News.search(person.newsQuery)) ?? []
        }
    }
}
