import LocalAuthentication
import SwiftUI

enum AppLock {
    /// True when the iPhone has a passcode (and so Face ID or Touch ID) set, which is what the app lock uses.
    static var available: Bool { LAContext().canEvaluatePolicy(.deviceOwnerAuthentication, error: nil) }
}

/// Locks the app behind Face ID, Touch ID or the iPhone passcode. It locks when the app opens and again after a minute
/// in the background, and covers the screen in the app switcher so balances don't show.
struct LockGate<Content: View>: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.scenePhase) private var scenePhase
    @ViewBuilder let content: () -> Content
    @State private var unlocked = false
    @State private var leftAt: Date?
    @State private var message = ""
    @State private var asking = false

    private var lockOn: Bool { model.appLock && AppLock.available }

    var body: some View {
        ZStack {
            if unlocked || !lockOn {
                content()
            } else {
                lockScreen
            }
            // Hides what's on screen in the app switcher.
            if lockOn && scenePhase != .active {
                Color(.systemBackground).ignoresSafeArea()
                    .overlay(Image(systemName: "lock.fill").font(.largeTitle).foregroundStyle(.secondary))
            }
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .background { leftAt = Date() }
            if phase == .active {
                if let t = leftAt, Date().timeIntervalSince(t) > 60 { unlocked = false }
                leftAt = nil
                if lockOn && !unlocked { unlock() }
            }
        }
        .onAppear { if lockOn && !unlocked { unlock() } }
    }

    private var lockScreen: some View {
        VStack(spacing: 14) {
            Image(systemName: "lock.fill").font(.system(size: 44))
            Text("Stock Tracker is locked").font(.title2.weight(.bold))
            if !message.isEmpty { Text(message).font(.footnote).foregroundStyle(.secondary).multilineTextAlignment(.center) }
            Button("Unlock") { unlock() }.buttonStyle(.borderedProminent)
        }
        .padding(32)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color(.systemBackground))
    }

    private func unlock() {
        guard !asking else { return }
        asking = true
        message = ""
        let ctx = LAContext()
        ctx.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: "Unlock Stock Tracker") { ok, err in
            DispatchQueue.main.async {
                asking = false
                if ok { unlocked = true } else { message = err?.localizedDescription ?? "Not unlocked." }
            }
        }
    }
}
