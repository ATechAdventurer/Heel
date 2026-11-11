import Foundation

/// Pet-side session authority: finds a Lovense device over Bluetooth through the native engine,
/// advertises it to the relay, and applies Owner's live frames to it only while the local
/// SessionGate says Pet chose Ready for this epoch. Mirrors the pet path of Android's
/// ControlRuntime, including finding a dropped device again (see "Device recovery" below).
@MainActor
final class PetControlViewModel: NSObject, ObservableObject, RelayListener {
    private let relay: PetRelay
    private let engine: NativeEngine
    private let defaults: UserDefaults
    private let schedule: (TimeInterval, @escaping () -> Void) -> Void
    private let gate = SessionGate(pet: true)

    /// Where to go once the relay confirms the pair is gone: Pick after a role reset, otherwise
    /// back to pairing in the same role, as on Android.
    var onReturnToPick: (() -> Void)?
    var onUnpaired: (() -> Void)?

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
    /// The selected toy dropped mid-session and is being found again.
    @Published private(set) var recoveringDevice = false
    @Published var resetError: String?

    private var stopping = false
    private var resettingRole = false
    private var advertised = "null"
    private var pollGeneration = 0
    private var pendingReadyEpoch = ""
    private var readyGeneration = 0
    private var recoveryRequest = ""
    private var recoveryTarget: PetDevice?
    private var recoveredCandidate = false
    private var recoveryScanning = false
    private var recoveryGeneration = 0
    private var readyAfterRecovery = false
    /// After a device returns, Pet taps Resume rather than output restarting by itself.
    private var needsManualResume = false
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
        pairedPet && initialized && ready && !stopping && selected != nil && !recoveringDevice
    }

    /// Becomes the relay's app-wide listener, so the engine, session and device keep running
    /// whichever screen is showing. SessionRuntime calls this when Pet's role is paired.
    func activate(onReturnToPick: @escaping () -> Void, onUnpaired: @escaping () -> Void) {
        self.onReturnToPick = onReturnToPick
        self.onUnpaired = onUnpaired
        resettingRole = false
        relay.blockRetries(safeWordLatched)
        // setController replays the current state; Pet needs it to start the engine and
        // advertise its device at all.
        relay.setController(self)
    }

    /// Leaving Pet's role stops output and shuts the engine down, including its poll loop.
    func deactivate() {
        if gate.active() || gate.pending() { pause(temporary: false) }
        reset()
        relay.setController(nil)
        onReturnToPick = nil
        onUnpaired = nil
        resettingRole = false
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
                if resettingRole {
                    resettingRole = false
                    onReturnToPick?()
                } else {
                    onUnpaired?()
                }
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
        reportDeviceLink()
        if recoveringDevice { scheduleRecovery(0) }
        tryRecover()
    }

    // MARK: - Session controls

    var ownerName: String {
        let name = relayState.optDictionary("profile")?.optString("owner") ?? ""
        return name.isEmpty ? "your owner" : name
    }

    var sessionActive: Bool { gate.active() }

    /// "Sam + Mia · fox" for the Pair & device sheet, mirroring Android's showPairDetails.
    var pairSummary: String {
        guard let profile = relayState.optDictionary("profile") else { return "Your pair" }
        let species = profile.optString("species")
        return "\(profile.optString("owner")) + \(profile.optString("pet"))" + (species.isEmpty ? "" : " · \(species)")
    }

    var deviceSummary: String { selected?.name ?? "No toy connected" }

    /// Opening Settings pauses output, as on Android, so nothing runs behind it.
    func openSettings() {
        if gate.active() || gate.pending() { pause(temporary: true) }
    }

    /// Ends the pair on the relay and returns to pairing in the same role. Navigation happens
    /// when the relay confirms "unpaired" (see handleState).
    func unpair() {
        requestUnpair(resetting: false, offline: "Reconnect first so Heel can unpair these phones.",
                      failed: "Reconnect to unpair.")
    }

    /// Ends the pair on the relay too, then returns to Pick, so Pick is never reached with a
    /// pair still standing. Navigation happens when the relay confirms "unpaired".
    func unpairAndReset() {
        requestUnpair(resetting: true, offline: "Reconnect first so Heel can unpair before changing roles.",
                      failed: "Reconnect to reset the role.")
    }

    private func requestUnpair(resetting: Bool, offline: String, failed: String) {
        guard relayOnline else {
            resetError = offline
            return
        }
        pause(temporary: false)
        resettingRole = resetting
        if !relay.send(RelayClient.json("unpair")) {
            resettingRole = false
            resetError = failed
        }
    }

    private var resumable: Bool {
        relayState.optBool("resumeAllowed") && !relayState.optBool("safeWord") && gate.canRecover()
    }

    var statusText: String {
        if safeWordLatched { return "Safe Word · stopped until you choose Ready" }
        guard relayOnline else { return "Reconnecting…" }
        if let errorMessage { return errorMessage }
        guard pairedPet else { return "Waiting to pair…" }
        if recoveringDevice { return "Finding your toy again… ♡" }
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
        if recoveringDevice { return safeWordLatched ? "Reconnect & ready ♡" : "Reconnecting…" }
        return !relayOnline ? "Reconnect" : gate.pending() ? "One moment…" : resumable ? "Resume ♡" : "I’m ready"
    }

    var actionEnabled: Bool {
        // After Safe Word the toy stays unreachable until Pet chooses to reconnect and be ready.
        if recoveringDevice { return safeWordLatched && !readyAfterRecovery }
        guard relayOnline else { return true }
        return relayState.optBool("online") && !gate.active() && gate.mayRequest()
            && (resumable || (available && !relayState.optBool("ready")))
    }

    /// Device choice is frozen while Owner may be driving it or is about to.
    var canChangeDevice: Bool {
        pairedPet && initialized && !stopping && !gate.active() && !gate.pending() && !recoveringDevice
    }

    /// What the device list shows: while a lost toy is being found, it stays listed.
    var displayDevices: [PetDevice] {
        guard recoveringDevice, let target = recoveryTarget, !devices.contains(where: { $0.id == target.id }) else {
            return devices
        }
        return devices + [target]
    }

    /// A session is live, starting, or waiting on Owner, so the phone must not lock under it.
    /// Unlike Android's wake lock this ignores a merely resumable session: that clause exists to
    /// reconnect in the background, which iOS can't do, and here it would pin the screen on forever
    /// after a pause.
    var keepsScreenAwake: Bool {
        relayOnline && (gate.active() || gate.pending() || relayState.optBool("ready"))
    }

    /// iOS suspends the app, and with it the relay socket, within seconds of backgrounding.
    /// Pausing now stops the toy for certain and tells Owner's phone why; Resume stays one tap away.
    func appDidEnterBackground() {
        if gate.active() { pause(temporary: true) }
    }

    func action() {
        errorMessage = nil
        defer { objectWillChange.send() }
        // After Safe Word, choosing Ready is what allows the toy to be reconnected at all.
        if safeWordLatched && recoveringDevice {
            readyAfterRecovery = true
            relay.blockRetries(false)
            if !relayOnline { relay.connect(enrollment: nil) }
            scheduleRecovery(0)
            return
        }
        guard relayOnline else {
            relay.blockRetries(false)
            relay.connect(enrollment: nil)
            return
        }
        guard !recoveringDevice else { return }
        // Pet tapping this is the confirmation that a reconnected toy may be driven again.
        needsManualResume = false
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
        // Nothing may reconnect until Pet chooses Ready again.
        readyAfterRecovery = false
        stopRecoveryScan()
        gate.stop()
        stopNative()
        relay.safeWord()
        objectWillChange.send()
    }

    // MARK: - Scanning and selection

    func toggleScan() {
        guard initialized, !recoveringDevice, canChangeDevice || scanning else { return }
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
        cancelDeviceRecovery()
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
            // A lost toy's device list has to be seen before the error its failing write produced in
            // the same batch, or that error ends a session that could have been recovered.
            let lists = events.filter { $0.optString("type") == "devices" }
            for event in lists + events.filter({ $0.optString("type") != "devices" }) { handle(event) }
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
            if recoveringDevice {
                if recoveredCandidate && confirmed && ready { finishDeviceRecovery() }
                else { recoveredCandidate = false; scheduleRecovery(1) }
            }
        case "devices":
            let incoming = (event.optArray("devices") ?? [])
                .compactMap { ($0 as? JSONDictionary).flatMap(PetDevice.init) }
            devices = incoming
            reconcileSelected(with: incoming)
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
            if recoveringDevice {
                // A failed scan or connect while looking: try again shortly.
                recoveredCandidate = false
                scheduleRecovery(1)
            } else if gate.active() || gate.pending() {
                // Mid-session, output is no longer confirmed; an idle scan error leaves the device usable.
                ready = false
                pause(temporary: false)
            }
        case "fatal":
            errorMessage = event.optString("message")
            cancelDeviceRecovery()
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

    // MARK: - Device recovery

    /// Whether a session could be using the selected toy right now.
    private var hasSessionContext: Bool {
        gate.active() || gate.pending() || gate.canRecover() || relayState.optBool("ready")
    }

    /// What a fresh device list means for the selected toy.
    private func reconcileSelected(with incoming: [PetDevice]) {
        guard let selected else { return }
        if recoveringDevice { considerReturned(incoming); return }
        if incoming.contains(selected) { return }
        let sameToy = !selected.identity.isEmpty && incoming.contains { $0.identity == selected.identity }
        // Gone, with a session that could have been using it: look for the same toy again.
        // Changed controls, no identity to look for, or nothing depending on it: forget it.
        if !sameToy && !selected.identity.isEmpty && hasSessionContext {
            beginDeviceRecovery()
        } else {
            forgetSelectedDevice()
        }
    }

    /// The selected toy can't be trusted with a session it wasn't readied for.
    private func forgetSelectedDevice() {
        if gate.active() || gate.pending() || gate.canRecover() {
            cancelPendingReady()
            gate.stop()
            stopNative()
            relay.stop()
        }
        selected = nil
        clearApplied()
    }

    /// The toy dropped. Stop output, tell Owner, and look for it again. Android additionally
    /// gates reconnection to this one toy at the Bluetooth layer; iOS can't, so a different toy
    /// that connects while scanning is simply never driven, and only an exact match counts.
    private func beginDeviceRecovery() {
        guard !recoveringDevice, let target = selected, !target.identity.isEmpty else { return }
        cancelPendingReady()
        recoveryTarget = target
        recoveringDevice = true
        recoveredCandidate = false
        recoveryRequest = ""
        needsManualResume = true
        gate.disconnected()
        stopNative()
        stopRecoveryScan()
        reportDeviceLink()
        scheduleRecovery(0.25)
    }

    /// While looking: the same toy coming back is a candidate; its controls changing ends things.
    private func considerReturned(_ incoming: [PetDevice]) {
        guard let target = recoveryTarget else { return }
        if incoming.contains(where: { $0.identity == target.identity && $0.id == target.id && $0 != target }) {
            cancelDeviceRecovery()
            forgetSelectedDevice()
            return
        }
        guard !recoveredCandidate, !safeWordLatched || readyAfterRecovery,
              let match = incoming.first(where: { $0 == target })
        else { return }
        recoveredCandidate = true
        selected = match
        stopRecoveryScan()
        // Confirm zero on the reconnected hardware before it can be driven again.
        stopNative()
    }

    private func finishDeviceRecovery() {
        recoveringDevice = false
        recoveredCandidate = false
        recoveryTarget = nil
        stopRecoveryScan()
        reportDeviceLink()
        advertise()
        if readyAfterRecovery {
            readyAfterRecovery = false
            action()
        }
    }

    /// Pet gives up on a toy that isn't coming back: forget it, end what it was part of, and
    /// let a different one be chosen.
    func stopLookingForDevice() {
        guard recoveringDevice else { return }
        cancelDeviceRecovery()
        forgetSelectedDevice()
        advertise()
        objectWillChange.send()
    }

    private func cancelDeviceRecovery() {
        stopRecoveryScan()
        recoveringDevice = false
        recoveredCandidate = false
        recoveryTarget = nil
        readyAfterRecovery = false
        needsManualResume = false
    }

    /// Scans in short windows with pauses between, only while nothing blocks reconnection.
    private func scheduleRecovery(_ delay: TimeInterval) {
        guard recoveringDevice, !recoveredCandidate, !recoveryScanning,
              !safeWordLatched || readyAfterRecovery else { return }
        recoveryGeneration += 1
        let generation = recoveryGeneration
        schedule(delay) { [weak self] in self?.retryDevice(generation) }
    }

    private func retryDevice(_ generation: Int) {
        guard generation == recoveryGeneration, recoveringDevice, !recoveredCandidate,
              !safeWordLatched || readyAfterRecovery else { return }
        guard pairedPet, initialized, !stopping else { scheduleRecovery(1); return }
        // A failed write suspends the engine; it has to be woken before it will scan.
        if !ready { _ = submit(RelayClient.json("resume")) }
        let error = submit(RelayClient.json("scan", ["enabled": true]))
        guard error.isEmpty else { scheduleRecovery(2); return }
        recoveryScanning = true
        schedule(15) { [weak self] in self?.endRecoveryScan(generation) }
    }

    private func endRecoveryScan(_ generation: Int) {
        guard generation == recoveryGeneration, recoveringDevice else { return }
        stopRecoveryScan()
        scheduleRecovery(2)
    }

    /// Also invalidates anything already scheduled, so a stale retry can't scan after this.
    private func stopRecoveryScan() {
        recoveryGeneration += 1
        if recoveryScanning && initialized { _ = submit(RelayClient.json("scan", ["enabled": false])) }
        recoveryScanning = false
    }

    /// Owner learns through the relay whether the toy is reachable, separately from the session.
    private func reportDeviceLink() {
        guard pairedPet, let selected, relayState.optBool("deviceOnline", true) == recoveringDevice else { return }
        relay.send(RelayClient.json("device_link", ["id": selected.id, "connected": !recoveringDevice]))
    }

    // MARK: - Relay

    private func advertise() {
        guard pairedPet else { return }
        // Keep advertising a toy that is being found again, so the relay holds on to it and
        // Owner sees "finding your toy" rather than a device that vanished.
        let payload: Any = initialized && (ready || recoveringDevice) ? selected?.relayPayload ?? NSNull() : NSNull()
        let signature = (payload as? JSONDictionary)?.compactJSONString ?? "null"
        guard signature != advertised else { return }
        advertised = signature
        relay.send(RelayClient.json("device", ["device": payload]))
    }

    /// After a dropped connection, ask once per epoch to pick the session back up.
    private func tryRecover() {
        guard available, !needsManualResume, relayState.optBool("deviceOnline", true), !safeWordLatched, gate.canRecover(),
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
