import XCTest

/// Opens the app on a simulator and taps through every tab and every view inside each tab.
/// Fails (and blocks the release) if the app crashes or a tab doesn't appear.
final class SmokeTests: XCTestCase {
    let app = XCUIApplication()

    override func setUp() {
        continueAfterFailure = true
        // Pre-fill the SEC contact so the first-run settings sheet doesn't cover the tabs.
        app.launchArguments = ["-ua", "StockTracker smoke test stocktracker-ci@users.noreply.github.com"]
    }

    private func alive(_ step: String, file: StaticString = #filePath, line: UInt = #line) -> Bool {
        let ok = app.wait(for: .runningForeground, timeout: 5)
        if !ok { XCTFail("App crashed or closed after: \(step)", file: file, line: line) }
        let shot = XCTAttachment(screenshot: app.screenshot())
        shot.name = step; shot.lifetime = .keepAlways
        add(shot)
        return ok
    }

    func testEveryTabAndView() {
        app.launch()
        guard alive("launch") else { return }
        let tabs = ["Home", "Markets", "Predictions", "Feed", "Portfolio"]
        for t in tabs {
            let b = app.tabBars.buttons[t]
            guard b.waitForExistence(timeout: 30) else {
                XCTFail("Tab \(t) didn't appear")
                print("SMOKE TREE BEGIN\n" + app.debugDescription + "\nSMOKE TREE END")
                break
            }
            b.tap()
            sleep(4)
            guard alive(t) else { app.launch(); continue }

            // Every pill-shaped view switcher on this tab (e.g. Markets: Top 30, Movers, Common picks...).
            let pills = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'pill-'"))
            let ids = (0..<pills.count).map { pills.element(boundBy: $0).identifier }
            for id in Array(NSOrderedSet(array: ids)) as! [String] {
                let p = app.buttons[id]
                guard p.exists else { continue }
                var tries = 0
                while !p.isHittable && tries < 4 {
                    app.scrollViews.containing(.button, identifier: id).firstMatch.swipeLeft()
                    tries += 1
                }
                guard p.isHittable else { continue }
                p.tap()
                sleep(4)
                let name = "\(t) → \(id.dropFirst(5))"
                guard alive(name) else { app.launch(); break }
                // Scroll the view to load more of it, then back up.
                app.swipeUp(); sleep(1); app.swipeDown()
                _ = alive(name + " (scrolled)")
            }
        }
    }
}
