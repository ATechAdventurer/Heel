import ActivityKit
import Foundation

/// A seam so the runtime can be tested without ActivityKit.
@MainActor
protocol SessionActivity: AnyObject {
    /// Shows `phase` on the Lock Screen and Dynamic Island; nil removes it.
    func show(_ phase: SessionPhase?)
}

/// A Live Activity shows the session's state at a glance and opens Heel when tapped. It does
/// not keep the session running: iOS still suspends the app in the background, and the app
/// pauses the session on the way out (see `SessionRuntime.appDidEnterBackground`).
///
/// Updates are local only. A suspended app can't change the activity, so the live phase carries
/// a stale date that the app keeps pushing forward; if the app dies, the Lock Screen falls back
/// to "Open Heel" instead of claiming a session that isn't running.
@MainActor
final class ActivityKitSessionActivity: SessionActivity {
    private static let staleAfter: TimeInterval = 10 * 60
    private static let refreshEvery: TimeInterval = 4 * 60

    private var activity: Activity<SessionActivityAttributes>?
    private var shown: SessionPhase?
    private var refresh: Timer?

    init() {
        // An activity left behind by a previous launch describes a session that no longer exists.
        Task {
            for old in Activity<SessionActivityAttributes>.activities {
                await old.end(nil, dismissalPolicy: .immediate)
            }
        }
    }

    func show(_ phase: SessionPhase?) {
        guard phase != shown else { return }
        shown = phase
        guard let phase else {
            refresh?.invalidate()
            refresh = nil
            end()
            return
        }
        push(phase)
        if refresh == nil {
            refresh = Timer.scheduledTimer(withTimeInterval: Self.refreshEvery, repeats: true) { [weak self] _ in
                MainActor.assumeIsolated { if let phase = self?.shown { self?.push(phase) } }
            }
        }
    }

    private func push(_ phase: SessionPhase) {
        let content = ActivityContent(state: SessionActivityAttributes.ContentState(phase: phase),
                                      staleDate: Date().addingTimeInterval(Self.staleAfter))
        if let activity {
            Task { await activity.update(content) }
        } else if ActivityAuthorizationInfo().areActivitiesEnabled {
            // Requested while the app is in front: ActivityKit refuses to start one from the background.
            activity = try? Activity.request(attributes: SessionActivityAttributes(), content: content, pushType: nil)
        }
    }

    private func end() {
        guard let activity else { return }
        self.activity = nil
        Task { await activity.end(nil, dismissalPolicy: .immediate) }
    }
}
