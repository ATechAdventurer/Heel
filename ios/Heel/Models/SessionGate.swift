import Foundation

/// Local authority. A server message can never restore permission revoked by a local Stop.
final class SessionGate {
    private let pet: Bool
    private var epoch_ = "", readyEpoch = "", activeEpoch = "", requestedEpoch = "", blockedEpoch = ""
    private var pendingReady = false, pendingStart = false, waitingForPause = false
    private var recoverable = false
    private var sequence: Int64 = 0

    init(pet: Bool) { self.pet = pet }

    var epoch: String { epoch_ }
    func active() -> Bool { !activeEpoch.isEmpty }
    func mayRequest() -> Bool { !waitingForPause && !pendingReady && !pendingStart && !epoch_.isEmpty }
    func pending() -> Bool { pendingReady || pendingStart }
    func readyForEpoch(_ value: String) -> Bool { pet && !readyEpoch.isEmpty && readyEpoch == value }

    func requestReady() {
        guard pet, mayRequest() else { return }
        pendingReady = true
        requestedEpoch = epoch_
    }

    func requestStart() {
        guard !pet, mayRequest() else { return }
        pendingStart = true
        requestedEpoch = epoch_
    }

    func stop() {
        recoverable = false
        pause()
    }

    func pause() {
        if !epoch_.isEmpty { blockedEpoch = epoch_ }
        readyEpoch = ""
        activeEpoch = ""
        requestedEpoch = ""
        pendingReady = false
        pendingStart = false
        waitingForPause = !epoch_.isEmpty
        sequence = 0
    }

    func disconnected() {
        pause()
        epoch_ = ""
        waitingForPause = false
    }

    func canRecover() -> Bool { recoverable }

    @discardableResult
    func state(incoming: String, paired: Bool, online: Bool, ready: Bool, active: Bool, resumed: Bool) -> Bool {
        if waitingForPause && active { return false }
        if active && paired && online && !activeEpoch.isEmpty && incoming == activeEpoch { return true }
        if resumed && recoverable && active && paired && online && incoming != blockedEpoch {
            epoch_ = incoming
            activeEpoch = incoming
            readyEpoch = ""
            requestedEpoch = ""
            waitingForPause = false
            pendingReady = false
            pendingStart = false
            sequence = 0
            return true
        }
        return state(incoming: incoming, paired: paired, online: online, ready: ready, active: active)
    }

    /// Returns false when an active state attempted to bypass local readiness.
    @discardableResult
    func state(incoming: String, paired: Bool, online: Bool, ready: Bool, active: Bool) -> Bool {
        if incoming.isEmpty || !paired || !online {
            disconnected()
            epoch_ = incoming
            return !active
        }
        epoch_ = incoming
        if waitingForPause {
            if !active && !ready && incoming != blockedEpoch {
                waitingForPause = false
            } else {
                return !active
            }
        }
        if !active {
            activeEpoch = ""
            sequence = 0
            if pet && ready && pendingReady && incoming != blockedEpoch {
                readyEpoch = incoming
                pendingReady = false
            } else if !ready {
                readyEpoch = ""
                if incoming != requestedEpoch {
                    pendingReady = false
                    pendingStart = false
                }
            }
            return true
        }
        if incoming == activeEpoch { return true }
        let authorized = pet
            ? (incoming == readyEpoch && !readyEpoch.isEmpty)
            : (pendingStart && incoming == requestedEpoch)
        guard authorized else {
            activeEpoch = ""
            readyEpoch = ""
            pendingReady = false
            pendingStart = false
            return false
        }
        activeEpoch = incoming
        recoverable = true
        pendingStart = false
        sequence = 0
        return true
    }

    func acceptFrame(incoming: String, nextSequence: Int64) -> Bool {
        guard pet, active(), activeEpoch == incoming, nextSequence > sequence, nextSequence >= 1 else { return false }
        sequence = nextSequence
        return true
    }
}
