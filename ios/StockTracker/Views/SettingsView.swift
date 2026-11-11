import SwiftUI

struct SettingsView: View {
    @EnvironmentObject var model: AppModel
    let onReady: () -> Void

    @State private var email = ""
    @State private var key = ""
    @State private var massiveKey = ""
    @State private var finnhubKey = Live.key
    @State private var broker = Broker.name
    @State private var customLink = Broker.customTemplate
    @State private var newTicker = ""
    @State private var savedMessage: String?
    @State private var showSources = false

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
                    Picker("Brokerage", selection: $broker) {
                        ForEach(Broker.choices.map(\.name) + [Broker.custom], id: \.self) { Text($0).tag($0) }
                    }
                    .onChange(of: broker) { _, v in Broker.name = v }
                    if broker == Broker.custom {
                        TextField("https://yourbroker.com/quote/{T}", text: $customLink)
                            .textInputAutocapitalization(.never).autocorrectionDisabled().keyboardType(.URL)
                            .onChange(of: customLink) { _, v in Broker.customTemplate = v }
                    }
                } header: {
                    Text("Your brokerage")
                } footer: {
                    Text(broker == Broker.custom
                         ? "Open any stock on your brokerage's website, copy the address, and replace the ticker with {T}."
                         : "Stock summaries get a button that opens the ticker in this app. If the app is installed it opens there, otherwise in Safari.")
                }

                Section {
                    SecureField("Finnhub API key", text: $finnhubKey)
                    Button("Save key") { Live.key = finnhubKey }
                    Link("Get a free key", destination: URL(string: Live.signup)!)
                } header: {
                    Text("Live prices")
                } footer: {
                    Text("Live stock prices for your watchlist and stock summaries use a free key from Finnhub.")
                }

                Section {
                    SecureField("Massive API key", text: $massiveKey)
                    Button("Save key") { model.massiveKey = massiveKey.trimmingCharacters(in: .whitespaces) }
                    Link("Get a free key", destination: URL(string: Movers.signupURL)!)
                } header: {
                    Text("Market data")
                } footer: {
                    Text("The Movers tab uses a free key from Massive (formerly Polygon.io) for weekly stock prices.")
                }

                Section {
                    if AppLock.available {
                        Toggle(isOn: $model.appLock) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text("App lock")
                                Text("Ask for Face ID, Touch ID or your passcode to open the app, and again after a minute away. Also hides the app in the app switcher.")
                                    .font(.caption).foregroundStyle(.secondary)
                            }
                        }
                    } else {
                        Text("Set a passcode on your iPhone to lock this app.").font(.footnote)
                    }
                } header: {
                    Text("Privacy and security")
                } footer: {
                    Text("Your keys, holdings and settings stay on this iPhone. Keys and holdings are kept in the Keychain and are never sent anywhere except the service they belong to.")
                }

                Section {
                    Picker("Appearance", selection: $model.appearance) {
                        Text("System").tag("system")
                        Text("Light").tag("light")
                        Text("Dark").tag("dark")
                    }
                    .pickerStyle(.segmented)
                    Toggle(isOn: $model.marketBackground) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Market color background")
                            Text("Green when the S&P 500 is up today, red when it's down. Checked every 5 minutes.")
                                .font(.caption).foregroundStyle(.secondary)
                        }
                    }
                } header: {
                    Text("Appearance")
                }

                Section {
                    Toggle(isOn: $model.newsAlerts) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Watchlist news")
                            Text("Get notified about headlines and SEC filings for your watchlist tickers.")
                                .font(.caption).foregroundStyle(.secondary)
                        }
                    }
                } header: {
                    Text("News alerts")
                }

                Section {
                    Toggle("Check in the background", isOn: $model.backgroundOn)
                        .onChange(of: model.backgroundOn) { _, _ in model.scheduleBackgroundRefresh() }
                    if !model.notificationsAllowed {
                        Text("Notifications are turned off for Stock Tracker, so new filings can't alert you.")
                            .font(.caption).foregroundStyle(.red)
                    }
                    if !model.backgroundRefreshAllowed {
                        Text("Background App Refresh is off (or Low Power Mode is on), so the app can only check while it's open. Turn it on in Settings → General → Background App Refresh.")
                            .font(.caption).foregroundStyle(.red)
                    }
                    if !model.notificationsAllowed || !model.backgroundRefreshAllowed {
                        Button("Open iPhone Settings") {
                            if let u = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(u) }
                        }
                    }
                } footer: {
                    Text("iOS decides when background checks run, usually every hour or few. Opening the app or pulling down always checks right away.")
                }

                Section {
                    Toggle(isOn: $model.appNotify) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Notifications from this app")
                            Text("Turn off to stop all filing notifications from the app.").font(.caption).foregroundStyle(.secondary)
                        }
                    }
                } header: {
                    Text("Live alerts")
                } footer: {
                    Text("While the app is open, it checks the SEC for new insider trades every second (once a minute outside SEC filing hours, weekdays 6 a.m. to 10 p.m. Eastern). When it's closed, iOS only allows the occasional background check.")
                }

                Section {
                    Button("See all sources") { showSources = true }
                } footer: {
                    Text("Every data feed the app uses, and the article or filing behind each person's holdings.")
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
            .sheet(isPresented: $showSources) { SourcesView().environmentObject(model) }
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Done") { model.showSettings = false } } }
            .onAppear {
                email = model.userAgent
                key = model.bargoKey
                massiveKey = model.massiveKey
            }
        }
    }

    private func addTicker() {
        let t = newTicker.uppercased().filter { $0.isLetter || $0.isNumber || $0 == "." }
        if !t.isEmpty && !model.tickers.contains(t) { model.tickers = (model.tickers + [t]).sorted() }
        newTicker = ""
    }
}
