import ActivityKit

/// What the Lock Screen and Dynamic Island can say about a session. Deliberately coarse: this
/// is visible to anyone who sees the phone, so it carries no names, no device and no levels.
enum SessionPhase: String, Codable, Hashable {
    case together
    case paused
    case waiting
}

/// Compiled into both the app and the widget extension.
struct SessionActivityAttributes: ActivityAttributes {
    struct ContentState: Codable, Hashable {
        var phase: SessionPhase
    }
}
