import UIKit

/// Keeps the display from locking. A seam so tests don't touch UIApplication.
@MainActor
protocol ScreenWake: AnyObject {
    func setKeepAwake(_ on: Bool)
}

@MainActor
final class UIKitScreenWake: ScreenWake {
    func setKeepAwake(_ on: Bool) {
        UIApplication.shared.isIdleTimerDisabled = on
    }
}
