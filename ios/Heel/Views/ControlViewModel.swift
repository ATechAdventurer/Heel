import Foundation

/// Owner-side control session. Sends frames over the relay; no BLE or native bridge involved,
/// since only Pet drives a physical device. Mirrors the owner path of Android's MainActivity +
/// ControlRuntime, with Pet-only device discovery/recovery left out.
@MainActor
final class ControlViewModel: NSObject, ObservableObject, RelayListener {
    private let relay: RelayClient
    private let gate = SessionGate(pet: false)
    private weak var touchModel: TouchModel?

    var onReturnToPick: (() -> Void)?

    @Published private(set) var relayOnline = false
    @Published private(set) var relayState: JSONDictionary = [:]
    @Published private(set) var deviceName: String?
    @Published private(set) var features: [TouchModel.Feature] = []
    @Published private(set) var hold = false
    @Published private(set) var touchVersion = 0
    @Published private(set) var displayValues: [Double] = []

    private var outgoingSequence: Int64 = 0
    private var lastSubmitted: [Double]?

    init(relay: RelayClient = .shared) {
        self.relay = relay
    }

    func onAppear() {
        relay.attach(self)
        if relay.connected { relayState = [:] }
    }

    func onDisappear() {
        relay.detach(self)
    }

    func bindTouchModel(_ model: TouchModel) {
        touchModel = model
    }

    // MARK: - RelayListener

    func relayConnectionChanged(connected: Bool, reason: String) {
        relayOnline = connected
        if !connected { resetLocal() }
    }

    func relayReceived(_ message: JSONDictionary) {
        switch message.optString("type") {
        case "state": handleState(message)
        case "applied":
            if gate.active(), message.optString("epoch") == gate.epoch {
                mirror(message)
            }
        default:
            break
        }
    }

    private func handleState(_ message: JSONDictionary) {
        relayState = message
        let paired = message.optString("phase") == "paired"
        guard paired, message.optString("role") == "owner" else {
            gate.stop()
            resetLocal()
            if !paired { onReturnToPick?() }
            return
        }

        let incomingEpoch = message.optString("epoch")
        if message.optBool("safeWord") {
            let wasRunning = gate.active()
            if wasRunning || gate.pending() || gate.canRecover() || incomingEpoch != gate.epoch {
                gate.stop()
            }
            if wasRunning { resetLocal() }
        }

        let wasActive = gate.active()
        let oldEpoch = gate.epoch
        let accepted = gate.state(incoming: incomingEpoch, paired: true,
                                   online: message.optBool("online"), ready: message.optBool("ready"),
                                   active: message.optBool("active"), resumed: message.optBool("resumed"))
        guard accepted else {
            gate.stop(); resetLocal(); relay.stop(); return
        }
        if wasActive && !gate.active() { resetLocal() }
        if oldEpoch != gate.epoch { outgoingSequence = 0; lastSubmitted = nil }
        if message.optBool("active") { mirror(message) }
        bindDevice(message.optDictionary("device"))
    }

    private func bindDevice(_ device: JSONDictionary?) {
        guard let device else {
            if deviceName != nil { deviceName = nil; features = [] }
            return
        }
        let name = device.optString("name")
        let rawFeatures = (device.optArray("features") ?? []).compactMap { $0 as? JSONDictionary }
        let parsed = rawFeatures.map {
            TouchModel.Feature(scalarIndex: $0.optInt("index"), label: $0.optString("label"), stepCount: $0.optInt("steps"))
        }
        guard name != deviceName || parsed != features else { return }
        deviceName = name
        features = parsed
    }

    private func mirror(_ message: JSONDictionary) {
        guard let touchModel else { return }
        let values = (message.optArray("values") ?? []).compactMap { ($0 as? NSNumber)?.doubleValue }
        guard values.count == features.count else { return }
        try? touchModel.setVisualTargets(values)
        hold = message.optBool("hold")
        touchModel.setHold(hold)
        touchVersion += 1
        displayValues = touchModel.visualTargets()
    }

    // MARK: - Session controls

    var canControl: Bool {
        relayOnline && relayState.optString("phase") == "paired" && relayState.optString("role") == "owner"
            && gate.active() && !features.isEmpty
    }

    var sessionActive: Bool { gate.active() }
    var sessionPending: Bool { gate.pending() }

    var statusText: String {
        guard relayOnline else { return "Reconnecting…" }
        guard relayState.optBool("online") else { return "Waiting for your pet…" }
        if gate.active() { return "Together ♡" }
        if relayState.optBool("ready") { return "Your pet is ready" }
        return "Ready when you are"
    }

    var sessionActionTitle: String {
        !relayOnline ? "Reconnect" : gate.active() ? "Together ♡" : gate.pending() ? "One moment…" : "Start"
    }

    var sessionActionEnabled: Bool {
        guard relayOnline else { return true }
        let online = relayState.optBool("online")
        let resumable = relayState.optBool("resumeAllowed") && !relayState.optBool("safeWord") && gate.canRecover()
        return online && !gate.active() && gate.mayRequest() && (resumable || relayState.optBool("ready"))
    }

    func action() {
        guard relayOnline else { relay.connect(enrollment: nil); return }
        guard !gate.active(), gate.mayRequest() else { return }
        if relayState.optBool("resumeAllowed") && !relayState.optBool("safeWord") && gate.canRecover() {
            relay.send(RelayClient.json("resume", ["epoch": gate.epoch]))
            return
        }
        if relayState.optBool("ready") {
            gate.requestStart()
            relay.send(RelayClient.json("start", ["epoch": gate.epoch]))
        }
    }

    func stopAll() {
        gate.stop()
        resetLocal()
        relay.stop()
    }

    func setHold(_ enabled: Bool) {
        hold = enabled
        touchModel?.setHold(enabled)
        touchVersion += 1
        if canControl, let targets = touchModel?.targets() {
            submitFrame(targets, force: true)
        }
    }

    func touchTargetsChanged(_ targets: [Double]) {
        guard canControl else { return }
        submitFrame(targets)
    }

    func touchStateChanged() {
        touchVersion += 1
        displayValues = touchModel?.visualTargets() ?? []
    }

    func touchCancelled() {
        stopAll()
    }

    @discardableResult
    private func submitFrame(_ targets: [Double], force: Bool = false) -> Bool {
        guard canControl, targets.count == features.count else { return false }
        if !force, targets == lastSubmitted { return true }
        var zero = false
        var values: [Double] = []
        for (index, value) in targets.enumerated() {
            let normalized = TouchModel.normalize(value, steps: features[index].stepCount)
            if normalized == 0, let last = lastSubmitted, index < last.count, last[index] > 0 { zero = true }
            values.append(normalized)
        }
        outgoingSequence += 1
        relay.frame(RelayClient.json("frame", [
            "epoch": gate.epoch, "seq": outgoingSequence, "values": values, "hold": hold,
        ]), priorityZero: zero)
        lastSubmitted = targets
        return true
    }

    private func resetLocal() {
        touchModel?.clear()
        lastSubmitted = nil
        hold = false
        touchVersion += 1
        displayValues = Array(repeating: 0, count: features.count)
    }
}
