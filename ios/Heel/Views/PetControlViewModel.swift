import Foundation

/// Pet-side session authority: finds a Lovense device over Bluetooth through the native engine,
/// advertises it to the relay, and applies Owner's live frames to it only while the local
/// SessionGate says Pet chose Ready for this epoch. Mirrors the pet path of Android's
/// ControlRuntime. Android's BleRecovery-driven reacquisition of a dropped device is left out:
/// here a lost device stops the session, and Pet picks it again and chooses Ready.
@MainActor
final class PetControlViewModel: NSObject, ObservableObject, RelayListener {
    private let relay: PetRelay
    private let engine: NativeEngine
    private let defaults: UserDefaults
    private let schedule: (TimeInterval, @escaping () -> Void) -> Void
    private let gate = SessionGate(pet: true)

    var onReturnToPick: (() -> Void)?

    @Published private(set) var relayOnline = false
    @Published private(set) var relayState: JSONDictionary = [:]
    @Published private(set) var errorMessage: String?
    @Published private(set) var devices: [PetDevice] = []
    @Published private(set) var selected: PetDevice?
    @Published private(set) var scanning = false
    @Published private(set) var initialized = false
    @Published private(set) var ready = false
    /// Owner's levels as last confirmed by the device, normalized per feature.
    @Published private(set) var levels: [Double] = []
    @Published private(set) var hold = false
    @Published private(set) var safeWordLatched: Bool

    private var stopping = false
    private var advertised = "null"
    private var pollGeneration = 0
    private var pendingReadyEpoch = ""
    private var readyGeneration = 0
    private var recoveryRequest = ""
    private var frames: [Int64: AppliedFrame] = [:]
    private var frameOrder: [Int64] = []
    private var acceptedSequence: Int64 = 0
    /// Process-wide like the engine itself, so a request id is never reused across screens.
    private static var requests: Int64 = 0

    private struct AppliedFrame {
        let epoch: String
        let seq: Int64
        let values: [Double]
        let hold: Bool
    }

    private struct InvalidFrame: Error {}

    init(relay: PetRelay = RelayClient.shared, engine: NativeEngine = DeviceEngine(),
         defaults: UserDefaults = .standard,
         schedule: @escaping (TimeInterval, @escaping () -> Void) -> Void = { delay, work in
             DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: work)
         }) {
        self.relay = relay
        self.engine = engine
        self.defaults = defaults
        self.schedule = schedule
        safeWordLatched = SafeWordPreferences.get(defaults)
    }

    var pairedPet: Bool {
        relayOnline && relayState.optString("phase") == "paired" && relayState.optString("role") == "pet"
    }

    /// The selected device can take Owner's frames right now.
    var available: Bool {
        pairedPet && initialized && ready && !stopping && selected != nil
    }

    func onAppear() {
        relay.blockRetries(safeWordLatched)
        // attach replays the current state; Pet needs it to advertise its device at all.
        relay.attach(self)
    }

    /// Leaving this screen must not leave a toy running behind it.
    func onDisappear() {
        if gate.active() || gate.pending() { pause(temporary: false) }
        relay.detach(self)
    }

    // MARK: - RelayListener

    func relayConnectionChanged(connected: Bool, reason: String) {
        relayOnline = connected
        if !connected {
            cancelPendingReady()
            gate.disconnected()
            advertised = "null"
            recoveryRequest = ""
            stopNative()
        }
        objectWillChange.send()
    }

    func relayReceived(_ message: JSONDictionary) {
        do {
            switch message.optString("type") {
            case "state": handleState(message)
            case "lease":
                if gate.active(), !message.optBool("active") || !message.optBool("peerFresh")
                    || message.optString("epoch") != gate.epoch {
                    gate.disconnected()
                    stopNative()
                    relay.disconnect(reason: "Connection interrupted · reconnecting…")
                }
                tryRecover()
            case "frame": try apply(message)
            case "error":
                recoveryRequest = ""
                failPendingReady()
            default: break
            }
        } catch {
            pause(temporary: false)
        }
        objectWillChange.send()
    }

    private func handleState(_ message: JSONDictionary) {
        relayState = message
        let paired = message.optString("phase") == "paired"
        guard paired, message.optString("role") == "pet" else {
            cancelPendingReady()
            gate.stop()
            reset()
            if !paired {
                relay.blockRetries(false)
                onReturnToPick?()
            }
            return
        }
        startEngineIfNeeded()

        let incomingEpoch = message.optString("epoch")
        let awaitingReady = !pendingReadyEpoch.isEmpty
        let readyAck = awaitingReady && incomingEpoch != pendingReadyEpoch
            && message.optBool("online") && message.optBool("ready") && !message.optBool("active")
            && !message.optBool("safeWord") && message.optBool("deviceOnline", true) && available
        let priorSafeState = awaitingReady && incomingEpoch == pendingReadyEpoch
            && !message.optBool("ready") && !message.optBool("active")
        if awaitingReady && !readyAck && !priorSafeState { cancelPendingReady() }

        if message.optBool("safeWord") && !priorSafeState {
            let wasRunning = gate.active()
            // Repeated status for an already-stopped epoch must not create a new
            // wait-for-stop barrier that the same epoch cannot satisfy.
            if wasRunning || gate.pending() || gate.canRecover() || incomingEpoch != gate.epoch { gate.stop() }
            if wasRunning { stopNative() }
            latchSafeWord(true)
            relay.blockRetries(true)
        }

        let wasActive = gate.active()
        let oldEpoch = gate.epoch
        let accepted = gate.state(incoming: incomingEpoch, paired: true,
                                  online: message.optBool("online"), ready: message.optBool("ready"),
                                  active: message.optBool("active"), resumed: message.optBool("resumed"))
        if (safeWordLatched && message.optBool("active")) || !accepted {
            gate.stop()
            stopNative()
            if safeWordLatched { relay.safeWord() } else { relay.stop() }
            return
        }
        if wasActive && !gate.active() { stopNative() }
        if readyAck && gate.readyForEpoch(incomingEpoch) {
            cancelPendingReady()
            latchSafeWord(false)
        }
        if oldEpoch != gate.epoch { clearApplied() }
        if message.optBool("resumeAllowed") && !gate.canRecover() {
            relay.stop()
            return
        }
        advertise()
        tryRecover()
    }

    // MARK: - Session controls

    var ownerName: String {
        let name = relayState.optDictionary("profile")?.optString("owner") ?? ""
        return name.isEmpty ? "your owner" : name
    }

    var sessionActive: Bool { gate.active() }

    private var resumable: Bool {
        relayState.optBool("resumeAllowed") && !relayState.optBool("safeWord") && gate.canRecover()
    }

    var statusText: String {
        if safeWordLatched { return "Safe Word · stopped until you choose Ready" }
        guard relayOnline else { return "Reconnecting…" }
        if let errorMessage { return errorMessage }
        guard pairedPet else { return "Waiting to pair…" }
        guard selected != nil else {
            if devices.isEmpty { return scanning ? "Scanning for your device…" : "No device found yet. Tap Scan." }
            return "Choose a device below."
        }
        guard relayState.optBool("online") else { return "Waiting for \(ownerName)’s phone…" }
        if gate.active() { return "Locked for \(ownerName) ♡" }
        if relayState.optBool("paused") { return "Paused · resume when you like" }
        if relayState.optBool("ready") { return "Ready · waiting for \(ownerName)" }
        return "Ready when you are"
    }

    var actionTitle: String {
        !relayOnline ? "Reconnect" : gate.pending() ? "One moment…" : resumable ? "Resume ♡" : "I’m ready"
    }

    var actionEnabled: Bool {
        guard relayOnline else { return true }
        return relayState.optBool("online") && !gate.active() && gate.mayRequest()
            && (resumable || (available && !relayState.optBool("ready")))
    }

    /// Device choice is frozen while Owner may be driving it or is about to.
    var canChangeDevice: Bool {
        pairedPet && initialized && !stopping && !gate.active() && !gate.pending()
    }

    func action() {
        errorMessage = nil
        defer { objectWillChange.send() }
        guard relayOnline else {
            relay.blockRetries(false)
            relay.connect(enrollment: nil)
            return
        }
        guard !gate.active(), gate.mayRequest() else { return }
        if resumable {
            relay.send(RelayClient.json("resume", ["epoch": gate.epoch]))
            return
        }
        guard available else { return }
        // Keep the persisted Safe Word latch until the relay acknowledges this request.
        relay.clearSafeWordForReady()
        gate.requestReady()
        pendingReadyEpoch = gate.epoch
        readyGeneration += 1
        let generation = readyGeneration
        schedule(5) { [weak self] in
            guard let self, generation == self.readyGeneration else { return }
            self.failPendingReady()
        }
        if !relay.send(RelayClient.json("ready", ["epoch": pendingReadyEpoch])) { failPendingReady() }
    }

    /// Pet's own Pause: stops output now; a temporary pause keeps the session resumable.
    func pause(temporary: Bool) {
        cancelPendingReady()
        if temporary { gate.pause() } else { gate.stop() }
        stopNative()
        if temporary { relay.pause() } else { relay.stop() }
        objectWillChange.send()
    }

    func safeWord() {
        cancelPendingReady()
        latchSafeWord(true)
        gate.stop()
        stopNative()
        relay.safeWord()
        objectWillChange.send()
    }

    // MARK: - Scanning and selection

    func toggleScan() {
        guard initialized, canChangeDevice || scanning else { return }
        let enabled = !scanning
        // A suspended engine (after Stop failed, a fatal error, or a lost heartbeat) refuses scans.
        if enabled && !ready { _ = submit(RelayClient.json("resume")) }
        let error = submit(RelayClient.json("scan", ["enabled": enabled]))
        if !error.isEmpty { errorMessage = error; return }
        errorMessage = nil
        if enabled {
            let generation = pollGeneration
            schedule(15) { [weak self] in self?.stopScanIfStillPolling(generation) }
        }
    }

    func select(_ device: PetDevice?) {
        guard canChangeDevice else { return }
        if device != selected {
            cancelPendingReady()
            gate.stop()
            stopNative()
            selected = device
            clearApplied()
        }
        advertise()
    }

    // MARK: - Frames

    private func apply(_ message: JSONDictionary) throws {
        guard !safeWordLatched,
              gate.acceptFrame(incoming: message.optString("epoch"), nextSequence: message.optLong("seq"))
        else { return }
        guard available, let selected else { pause(temporary: true); return }
        guard let input = message.optArray("values"), input.count == selected.features.count else { throw InvalidFrame() }
        var normalized: [Double] = []
        var outputs: [JSONDictionary] = []
        for (index, raw) in input.enumerated() {
            guard let value = (raw as? NSNumber)?.doubleValue, value.isFinite, value >= 0, value <= 1 else {
                throw InvalidFrame()
            }
            let feature = selected.features[index]
            let level = TouchModel.normalize(value, steps: feature.stepCount)
            normalized.append(level)
            outputs.append(["index": feature.scalarIndex, "value": level])
        }
        Self.requests += 1
        let request = Self.requests
        let error = submit(RelayClient.json("vibration_frame", [
            "device": selected.id, "request": request, "values": outputs,
        ]))
        guard error.isEmpty else { pause(temporary: false); return }
        frames[request] = AppliedFrame(epoch: message.optString("epoch"), seq: message.optLong("seq"),
                                       values: normalized, hold: message.optBool("hold"))
        frameOrder.append(request)
        while frameOrder.count > 64 { frames[frameOrder.removeFirst()] = nil }
    }

    private func clearApplied() {
        frames = [:]
        frameOrder = []
        acceptedSequence = 0
        levels = Array(repeating: 0, count: selected?.features.count ?? 0)
        hold = false
    }

    // MARK: - Engine lifecycle

    /// Every command goes through here so a frame can never reach the device past the gate.
    private func submit(_ command: JSONDictionary) -> String {
        let type = command.optString("type")
        guard initialized else { return "Device engine is not ready." }
        let stopping = type == "stop" || type == "suspend" || (type == "scan" && !command.optBool("enabled"))
        if !pairedPet && !stopping { return "Pet connection is required." }
        if type == "vibration_frame" && (safeWordLatched || !gate.active()) { return "Control is stopped." }
        return engine.submit(command.compactJSONString)
    }

    private func stopNative() {
        clearApplied()
        guard initialized else { return }
        stopping = true
        if !submit(RelayClient.json("stop")).isEmpty {
            ready = false
            stopping = false
        }
    }

    private func startEngineIfNeeded() {
        guard !initialized else { return }
        let error = engine.initialize()
        guard error.isEmpty else { errorMessage = error; return }
        initialized = true
        ready = false
        stopping = false
        errorMessage = nil
        // The engine outlives this screen; a previous Pair may have suspended it. Its "ready"
        // event, not this call, is what marks the device usable.
        _ = submit(RelayClient.json("resume"))
        pollGeneration += 1
        poll(generation: pollGeneration)
    }

    private func reset() {
        stopNative()
        if initialized { _ = engine.submit(RelayClient.json("suspend").compactJSONString) }
        initialized = false
        ready = false
        stopping = false
        devices = []
        selected = nil
        scanning = false
        advertised = "null"
        recoveryRequest = ""
        pollGeneration += 1
        clearApplied()
    }

    private func stopScanIfStillPolling(_ generation: Int) {
        guard generation == pollGeneration, initialized else { return }
        _ = submit(RelayClient.json("scan", ["enabled": false]))
    }

    private func poll(generation: Int) {
        guard generation == pollGeneration else { return }
        let drained = engine.drain()
        if let events = try? JSONSerialization.jsonObject(with: Data(drained.utf8)) as? [JSONDictionary] {
            for event in events { handle(event) }
        }
        schedule(0.05) { [weak self] in self?.poll(generation: generation) }
    }

    private func handle(_ event: JSONDictionary) {
        switch event.optString("type") {
        case "ready":
            ready = event.optBool("active")
            if !ready && gate.active() { pause(temporary: false) }
        case "stopped":
            if !stopping && gate.active() {
                gate.pause()
                relay.pause()
            }
            stopping = false
            let confirmed = event.optBool("confirmed")
            ready = event.optBool("active") && confirmed && selectedStopConfirmed(event)
            if !confirmed { errorMessage = "Stop unconfirmed. Check the device." }
        case "devices":
            let incoming = (event.optArray("devices") ?? [])
                .compactMap { ($0 as? JSONDictionary).flatMap(PetDevice.init) }
            devices = incoming
            // A changed or missing device can't be trusted with the session it was readied for.
            if let selected, !incoming.contains(selected) {
                if gate.active() || gate.pending() || gate.canRecover() {
                    cancelPendingReady()
                    gate.stop()
                    stopNative()
                    relay.stop()
                }
                self.selected = nil
                clearApplied()
            }
        case "scanning":
            scanning = event.optBool("enabled")
        case "vibration_applied":
            let request = event.optLong("request")
            guard let frame = frames.removeValue(forKey: request) else { break }
            frameOrder.removeAll { $0 == request }
            guard !safeWordLatched, !stopping, gate.active(), frame.epoch == gate.epoch,
                  frame.seq > acceptedSequence
            else { break }
            levels = frame.values
            hold = frame.hold
            acceptedSequence = frame.seq
            relay.send(RelayClient.json("applied", [
                "epoch": gate.epoch, "seq": acceptedSequence, "values": frame.values,
            ]))
        case "error":
            errorMessage = event.optString("message")
            // Mid-session, output is no longer confirmed; an idle scan error leaves the device usable.
            if gate.active() || gate.pending() {
                ready = false
                pause(temporary: false)
            }
        case "fatal":
            errorMessage = event.optString("message")
            ready = false
            pause(temporary: false)
        default:
            break
        }
        advertise()
        tryRecover()
    }

    private func selectedStopConfirmed(_ event: JSONDictionary) -> Bool {
        guard let selected else { return true }
        return (event.optArray("devices") ?? []).contains { ($0 as? NSNumber)?.intValue == selected.id }
    }

    // MARK: - Relay

    private func advertise() {
        guard pairedPet else { return }
        let payload: Any = initialized && ready ? selected?.relayPayload ?? NSNull() : NSNull()
        let signature = (payload as? JSONDictionary)?.compactJSONString ?? "null"
        guard signature != advertised else { return }
        advertised = signature
        relay.send(RelayClient.json("device", ["device": payload]))
    }

    /// After a dropped connection, ask once per epoch to pick the session back up.
    private func tryRecover() {
        guard available, relayState.optBool("deviceOnline", true), !safeWordLatched, gate.canRecover(),
              !gate.active(), relayState.optBool("online"), relayState.optBool("resumeAllowed"),
              !relayState.optBool("paused"), !relayState.optBool("safeWord"), gate.epoch != recoveryRequest
        else { return }
        recoveryRequest = gate.epoch
        relay.send(RelayClient.json("resume", ["epoch": gate.epoch, "automatic": true]))
    }

    private func cancelPendingReady() {
        pendingReadyEpoch = ""
        readyGeneration += 1
    }

    private func failPendingReady() {
        guard !pendingReadyEpoch.isEmpty else { return }
        cancelPendingReady()
        gate.stop()
        stopNative()
        if safeWordLatched { relay.safeWord() } else { relay.stop() }
        objectWillChange.send()
    }

    private func latchSafeWord(_ value: Bool) {
        safeWordLatched = value
        SafeWordPreferences.set(value, defaults: defaults)
    }
}

/// Pet's Safe Word survives a relaunch: output stays stopped until Pet chooses Ready again.
enum SafeWordPreferences {
    private static let key = "heel.pet.safeWord"

    static func get(_ defaults: UserDefaults = .standard) -> Bool {
        defaults.bool(forKey: key)
    }

    static func set(_ value: Bool, defaults: UserDefaults = .standard) {
        defaults.set(value, forKey: key)
    }
}
