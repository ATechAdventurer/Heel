import Foundation
@testable import Heel

/// Stands in for RelayClient in both roles' view-model tests. Like the real client, attach
/// replays the current state to the new listener.
@MainActor
final class FakeRelay: OwnerRelay, PetRelay {
    var connected = true
    var state: JSONDictionary?
    var sent: [JSONDictionary] = []
    var frames: [JSONDictionary] = []
    var calls: [String] = []

    func attach(_ value: RelayListener) {
        value.relayConnectionChanged(connected: connected, reason: "Connected")
        if connected, let state { value.relayReceived(state) }
    }
    func detach(_ value: RelayListener) {}
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
