import Foundation

/// Pet-side device discovery: scans for a Lovense device over Bluetooth through the native
/// engine, lists what's found, and advertises the selected one to the relay so Owner's screen
/// can show its controls. Applying live frames from Owner to the device is not wired up yet;
/// mirrors the discovery half of Android's ControlRuntime, with device-recovery left out.
@MainActor
final class PetControlViewModel: NSObject, ObservableObject, RelayListener {
    private let relay: RelayClient
    private let engine: NativeEngine
    private let schedule: (TimeInterval, @escaping () -> Void) -> Void

    @Published private(set) var relayOnline = false
    @Published private(set) var relayState: JSONDictionary = [:]
    @Published private(set) var errorMessage: String?
    @Published private(set) var devices: [PetDevice] = []
    @Published private(set) var selected: PetDevice?
    @Published private(set) var scanning = false
    @Published private(set) var ready = false

    private var initialized = false
    private var advertised = "null"
    private var pollGeneration = 0

    init(relay: RelayClient = .shared, engine: NativeEngine = DeviceEngine(),
         schedule: @escaping (TimeInterval, @escaping () -> Void) -> Void = { delay, work in
             DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: work)
         }) {
        self.relay = relay
        self.engine = engine
        self.schedule = schedule
    }

    var pairedPet: Bool {
        relayOnline && relayState.optString("phase") == "paired" && relayState.optString("role") == "pet"
    }

    func onAppear() {
        relay.attach(self)
        if relay.connected { relayState = [:] }
    }

    func onDisappear() {
        relay.detach(self)
    }

    // MARK: - RelayListener

    func relayConnectionChanged(connected: Bool, reason: String) {
        relayOnline = connected
        if !connected { reset() }
    }

    func relayReceived(_ message: JSONDictionary) {
        guard message.optString("type") == "state" else { return }
        relayState = message
        guard pairedPet else { reset(); return }
        startEngineIfNeeded()
    }

    // MARK: - Scanning and selection

    func toggleScan() {
        guard initialized else { return }
        let enabled = !scanning
        _ = engine.submit(RelayClient.json("scan", ["enabled": enabled]).compactJSONString)
        if enabled {
            let generation = pollGeneration
            schedule(15) { [weak self] in self?.stopScanIfStillPolling(generation) }
        }
    }

    func select(_ device: PetDevice?) {
        selected = device
        advertise()
    }

    // MARK: - Engine lifecycle

    private func startEngineIfNeeded() {
        guard !initialized else { return }
        let error = engine.initialize()
        guard error.isEmpty else { errorMessage = error; return }
        initialized = true
        ready = true
        errorMessage = nil
        pollGeneration += 1
        poll(generation: pollGeneration)
    }

    private func reset() {
        if initialized { _ = engine.submit(RelayClient.json("suspend").compactJSONString) }
        initialized = false
        ready = false
        devices = []
        selected = nil
        scanning = false
        advertised = "null"
        pollGeneration += 1
    }

    private func stopScanIfStillPolling(_ generation: Int) {
        guard generation == pollGeneration, initialized else { return }
        _ = engine.submit(RelayClient.json("scan", ["enabled": false]).compactJSONString)
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
        case "devices":
            let incoming = (event.optArray("devices") ?? [])
                .compactMap { ($0 as? JSONDictionary).flatMap(PetDevice.init) }
            devices = incoming
            if let selected, let updated = incoming.first(where: { $0.id == selected.id }) {
                self.selected = updated
            } else if selected != nil {
                selected = nil
            }
            advertise()
        case "scanning":
            scanning = event.optBool("enabled")
        case "error", "fatal":
            errorMessage = event.optString("message")
        default:
            break
        }
    }

    private func advertise() {
        guard pairedPet else { return }
        let payload: Any = selected?.relayPayload ?? NSNull()
        let signature = (payload as? JSONDictionary)?.compactJSONString ?? "null"
        guard signature != advertised else { return }
        advertised = signature
        relay.send(RelayClient.json("device", ["device": payload]))
    }
}
