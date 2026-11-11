import Foundation
@testable import Heel

/// Stands in for RelayClient in both roles' view-model tests. Like the real client, setController
/// replays the current state to the new listener.
@MainActor
final class FakeRelay: OwnerRelay, PetRelay {
    var connected = true
    private(set) var controller: RelayListener?
    var state: JSONDictionary?
    var sent: [JSONDictionary] = []
    var frames: [JSONDictionary] = []
    var calls: [String] = []

    /// Like the real client, registering replays the current connection and state.
    func setController(_ value: RelayListener?) {
        controller = value
        value?.relayConnectionChanged(connected: connected, reason: "Connected")
        if connected, let state { value?.relayReceived(state) }
    }
    func send(_ message: JSONDictionary) -> Bool {
        sent.append(message)
        return connected
    }
    func frame(_ value: JSONDictionary, priorityZero: Bool) { frames.append(value) }
    func pause() { calls.append("pause") }
    func stop() { calls.append("stop") }
    func safeWord() { calls.append("safeword") }
    func clearSafeWordForReady() { calls.append("clearSafeWord") }
    func blockRetries(_ blocked: Bool) { calls.append("blockRetries(\(blocked))") }
    func connect(enrollment: String?) { calls.append("connect") }
    func disconnect(reason: String) { calls.append("disconnect") }

    func sent(_ type: String) -> [JSONDictionary] { sent.filter { $0.optString("type") == type } }
}

final class NullHapticsOutput: OwnerHapticsOutput {
    func tick(strength: Double) {}
    func cancel() {}
}

final class FakeEngine: NativeEngine {
    var submitted: [JSONDictionary] = []
    var events: [JSONDictionary] = []

    func initialize() -> String { "" }
    func submit(_ command: String) -> String {
        submitted.append((try? JSONSerialization.jsonObject(with: Data(command.utf8))) as? JSONDictionary ?? [:])
        return ""
    }
    func drain() -> String {
        defer { events = [] }
        let data = try? JSONSerialization.data(withJSONObject: events)
        return data.flatMap { String(data: $0, encoding: .utf8) } ?? "[]"
    }

    func submitted(_ type: String) -> [JSONDictionary] { submitted.filter { $0.optString("type") == type } }
}

/// Runs scheduled work by hand. The engine poll reschedules itself, so each tick runs only
/// what was queued before it.
final class ManualScheduler {
    private var queue: [(TimeInterval, () -> Void)] = []

    var schedule: (TimeInterval, @escaping () -> Void) -> Void {
        { [unowned self] delay, work in self.queue.append((delay, work)) }
    }

    func tick(where matches: (TimeInterval) -> Bool = { _ in true }) {
        let due = queue.filter { matches($0.0) }
        queue = queue.filter { !matches($0.0) }
        due.forEach { $0.1() }
    }
}


@MainActor
final class FakeScreen: ScreenWake {
    private(set) var changes: [Bool] = []
    var onChange: ((Bool) -> Void)?
    var awake: Bool { changes.last ?? false }

    func setKeepAwake(_ on: Bool) {
        changes.append(on)
        onChange?(on)
    }
}


@MainActor
final class FakeActivity: SessionActivity {
    private(set) var shown: [SessionPhase?] = []
    var phase: SessionPhase? { shown.last ?? nil }

    func show(_ phase: SessionPhase?) { shown.append(phase) }
}
