import SwiftUI

struct SettingsView: View {
    @EnvironmentObject var model: AppModel
    let onReady: () -> Void

    @State private var email = ""
    @State private var key = ""
    @State private var newTicker = ""
    @State private var savedMessage: String?

    private let minValues: [(Double, String)] = [(0, "Any"), (10_000, "$10K"), (100_000, "$100K"), (1_000_000, "$1M")]

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Jane Doe jane@email.com", text: $email)
                        .textContentType(.emailAddress)
                        .keyboardType(.emailAddress)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                    Button(model.isSetUp ? "Save" : "Save and start") {
                        guard email.contains("@") else { savedMessage = "Include an email address"; return }
                        let wasSetUp = model.isSetUp
                        model.userAgent = email.trimmingCharacters(in: .whitespaces)
                        model.scheduleBackgroundRefresh()
                        if wasSetUp { savedMessage = "Saved" } else { onReady() }
                    }
                    .disabled(email.trimmingCharacters(in: .whitespaces).isEmpty)
                } header: {
                    Text("Your name and email")
                } footer: {
                    Text(savedMessage ?? "The SEC requires apps to identify themselves. This is only sent to sec.gov.")
                }

                Section {
                    ForEach(Category.allCases) { c in
                        Toggle(isOn: Binding(get: { model.isWatching(c) }, set: { model.setWatching(c, $0) })) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(c.label)
                                Text(c.subtitle).font(.caption).foregroundStyle(.secondary)
                            }
                        }
                    }
                } header: {
                    Text("Sections")
                } footer: {
                    Text("Each group gets its own section in the feed.")
                }

                Section {
                    Toggle(isOn: $model.buysOnly) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Purchases only")
                            Text("Skip sales, stock awards and option exercises").font(.caption).foregroundStyle(.secondary)
                        }
                    }
                    Picker("Minimum trade size", selection: $model.minValue) {
                        ForEach(minValues.indices, id: \.self) { i in
                            Text(minValues[i].1).tag(minValues[i].0)
                        }
                    }
                } header: {
                    Text("Alert filters")
                } footer: {
                    Text("Filters only change what you get alerted about. Common picks always uses everything.")
                }

                Section {
                    HStack {
                        TextField("Add ticker, e.g. NVDA", text: $newTicker)
                            .textInputAutocapitalization(.characters)
                            .autocorrectionDisabled()
                            .onSubmit(addTicker)
                        Button(action: addTicker) { Image(systemName: "plus.circle.fill") }
                            .disabled(newTicker.isEmpty)
                    }
                    ForEach(model.tickers, id: \.self) { Text($0).font(.body.monospaced()) }
                        .onDelete { model.tickers.remove(atOffsets: $0) }
                } header: {
                    Text("Watchlist")
                } footer: {
                    Text(model.tickers.isEmpty
                         ? "Watching every company. Add tickers to narrow it down."
                         : "Only these companies will alert you. Swipe left to remove one.")
                }

                Section {
                    SecureField("Bargo API key (optional)", text: $key)
                    Button("Save key") {
                        model.bargoKey = key.trimmingCharacters(in: .whitespaces)
                        model.resetCongressTimer()
                    }
                    Link("Get a free key", destination: URL(string: Congress.sourceURL)!)
                } header: {
                    Text("Congress data")
                } footer: {
                    Text("Without a key, Senate and House are checked about every 5 hours to stay inside the free limit. A free Bargo key lets the app check hourly.")
                }

                Section {
                    Toggle("Check in the background", isOn: $model.backgroundOn)
                        .onChange(of: model.backgroundOn) { _, _ in model.scheduleBackgroundRefresh() }
                } footer: {
                    Text("iOS decides when background checks run, usually every hour or few. Opening the app or pulling down always checks right away.")
                }

                Section {
                    Button("Clear feed history", role: .destructive) { model.clearAlerts() }
                }

                Section {
                    EmptyView()
                } footer: {
                    Text("Sources: SEC EDGAR (insiders and funds) and Bargo, built from official House Clerk and Senate eFD filings (Congress). Form 4s arrive within 2 business days of a trade, congressional trades within 45 days, and 13Fs up to 45 days after each quarter. Senior federal officials and state legislators aren't included because their disclosures have no live public feed.")
                }
            }
            .navigationTitle("Settings")
            .onAppear {
                email = model.userAgent
                key = model.bargoKey
            }
        }
    }

    private func addTicker() {
        let t = newTicker.uppercased().filter { $0.isLetter || $0.isNumber || $0 == "." }
        if !t.isEmpty && !model.tickers.contains(t) { model.tickers = (model.tickers + [t]).sorted() }
        newTicker = ""
    }
}
