import SwiftUI
import UIKit
import UserNotifications

@main
struct StockTrackerApp: App {
    @StateObject private var model = AppModel()
    @Environment(\.scenePhase) private var scenePhase
    private let notificationDelegate = NotificationDelegate()

    init() {
        UNUserNotificationCenter.current().delegate = notificationDelegate
    }

    var body: some Scene {
        WindowGroup {
            LockGate { ContentView() }
                .environmentObject(model)
                .onAppear { model.requestNotificationPermission() }
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .background { model.scheduleBackgroundRefresh() }
            // Check right away whenever the app is opened.
            if phase == .active { Task { await model.check(); await model.refreshNotificationHealth() } }
        }
        .backgroundTask(.appRefresh(AppModel.refreshTaskID)) {
            await model.scheduleBackgroundRefresh()
            await model.check(background: true)
        }
    }
}

/// Shows notifications while the app is open, and opens the filing when one is tapped.
final class NotificationDelegate: NSObject, UNUserNotificationCenterDelegate {
    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification,
                                withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([.banner, .sound, .list])
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse,
                                withCompletionHandler completionHandler: @escaping () -> Void) {
        if let link = response.notification.request.content.userInfo["link"] as? String,
           let url = URL(string: link) {
            DispatchQueue.main.async { UIApplication.shared.open(url) }
        }
        completionHandler()
    }
}
