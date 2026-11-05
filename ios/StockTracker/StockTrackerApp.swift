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
            ContentView()
                .environmentObject(model)
                .onAppear { model.requestNotificationPermission() }
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .background { model.scheduleBackgroundRefresh() }
        }
        .backgroundTask(.appRefresh(AppModel.refreshTaskID)) {
            await model.scheduleBackgroundRefresh()
            await model.check()
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
