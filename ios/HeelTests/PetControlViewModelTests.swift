import XCTest
@testable import Heel

@MainActor
private final class FakeRelay: PetRelay {
    var connected = true
    var sent: [JSONDictionary] = []
    var calls: [String] = []

    func attach(_ value: RelayListener) {}
    func detach(_ value: RelayListener) {}
    func send(_ message: JSONDictionary) -> Bool {
        sent.append(message)
        return connected
    }
    func pause() { calls.append("pause") }
    func stop() { calls.append("stop") }
    func safeWord() { calls.append("safeword") }
    func clearSafeWordForReady() { calls.append("clearSafeWord") }
    func blockRetries(_ blocked: Bool) { calls.append("blockRetries(\(blocked))") }
    func connect(enrollment: String?) { calls.append("connect") }
    func disconnect(reason: String) { calls.append("disconnect") }

    func sent(_ type: String) -> [JSONDictionary] { sent.filter { $0.optString("type") == type } }
}

private final class FakeEngine: NativeEngine {
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
private final class ManualScheduler {
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
final class PetControlViewModelTests: XCTestCase {
    private var relay: FakeRelay!
    private var engine: FakeEngine!
    private var scheduler: ManualScheduler!
    private var defaults: UserDefaults!
    private var model: PetControlViewModel!

    override func setUp() {
        super.setUp()
        relay = FakeRelay()
        engine = FakeEngine()
        scheduler = ManualScheduler()
        defaults = UserDefaults(suiteName: "PetControlViewModelTests.\(UUID().uuidString)")
        model = makeModel()
    }

    private func makeModel() -> PetControlViewModel {
        PetControlViewModel(relay: relay, engine: engine, defaults: defaults, schedule: scheduler.schedule)
    }

    private func state(_ epoch: String, ready: Bool = false, active: Bool = false,
                       resumeAllowed: Bool = false, safeWord: Bool = false) -> JSONDictionary {
        ["type": "state", "phase": "paired", "role": "pet", "epoch": epoch, "online": true,
         "ready": ready, "active": active, "resumeAllowed": resumeAllowed, "safeWord": safeWord,
         "profile": ["owner": "Sam"]]
    }

    private let hush: JSONDictionary = ["id": 3, "name": "Lovense Hush", "messages": ["ScalarCmd": [
        ["ActuatorType": "Vibrate", "FeatureDescriptor": "", "StepCount": 20],
    ]]]

    private func emit(_ events: JSONDictionary...) {
        engine.events.append(contentsOf: events)
        scheduler.tick { $0 < 1 }
    }

    /// Pair → find and pick the device → Ready → Owner starts, following the relay's epoch moves.
    private func startSession() {
        model.relayConnectionChanged(connected: true, reason: "Connected")
        model.relayReceived(state("e1"))
        emit(["type": "ready", "active": true], ["type": "devices", "devices": [hush]])
        model.select(model.devices.first)
        emit(["type": "stopped", "confirmed": true, "active": true, "devices": [3]])
        model.relayReceived(state("e2")) // The relay pauses and re-epochs when the device changes.
        model.action()
        model.relayReceived(state("e3", ready: true))
        model.relayReceived(state("e3", active: true, resumeAllowed: true))
    }

    private func frame(_ seq: Int64, _ values: [Double], epoch: String = "e3") -> JSONDictionary {
        ["type": "frame", "epoch": epoch, "seq": seq, "values": values, "hold": false]
    }

    func testPairingResumesTheEngineBeforeUse() {
        model.relayConnectionChanged(connected: true, reason: "Connected")
        model.relayReceived(state("e1"))
        XCTAssertEqual(engine.submitted("resume").count, 1)
        XCTAssertFalse(model.ready, "Only the engine's own ready event marks it usable")
        emit(["type": "ready", "active": true])
        XCTAssertTrue(model.ready)
    }

    func testReadySendsTheCurrentEpochAndAdvertisesTheDevice() {
        startSession()
        XCTAssertEqual(relay.sent("ready").first?.optString("epoch"), "e2")
        let device = relay.sent("device").last?.optDictionary("device")
        XCTAssertEqual(device?.optInt("id"), 3)
        XCTAssertTrue(model.sessionActive)
        XCTAssertEqual(model.statusText, "Locked for Sam ♡")
    }

    func testFramesBeforeOwnerStartsNeverReachTheDevice() {
        model.relayConnectionChanged(connected: true, reason: "Connected")
        model.relayReceived(state("e1"))
        emit(["type": "ready", "active": true], ["type": "devices", "devices": [hush]])
        model.select(model.devices.first)
        model.relayReceived(frame(1, [0.5], epoch: "e1"))
        XCTAssertTrue(engine.submitted("vibration_frame").isEmpty)
    }

    func testAppliedFrameIsAcknowledgedWithNormalizedLevels() throws {
        startSession()
        model.relayReceived(frame(1, [0.52]))
        let command = try XCTUnwrap(engine.submitted("vibration_frame").first)
        XCTAssertEqual(command.optInt("device"), 3)
        let output = try XCTUnwrap(command.optArray("values")?.first as? JSONDictionary)
        XCTAssertEqual(output.optInt("index"), 0)
        XCTAssertEqual(output.optDouble("value"), 0.5, accuracy: 1e-9)

        emit(["type": "vibration_applied", "device": 3, "request": command.optLong("request")])
        let ack = try XCTUnwrap(relay.sent("applied").first)
        XCTAssertEqual(ack.optString("epoch"), "e3")
        XCTAssertEqual(ack.optLong("seq"), 1)
        XCTAssertEqual(ack.optArray("values") as? [Double], [0.5])
        XCTAssertEqual(model.levels, [0.5])
    }

    func testRepeatedOrOlderSequenceIsDropped() {
        startSession()
        model.relayReceived(frame(2, [0.5]))
        model.relayReceived(frame(2, [0.6]))
        model.relayReceived(frame(1, [0.7]))
        XCTAssertEqual(engine.submitted("vibration_frame").count, 1)
    }

    func testOutOfRangeLevelStopsTheSession() {
        startSession()
        model.relayReceived(frame(1, [1.5]))
        XCTAssertTrue(engine.submitted("vibration_frame").isEmpty)
        XCTAssertFalse(model.sessionActive)
        XCTAssertEqual(relay.calls.last, "stop")
    }

    func testFeatureCountMismatchStopsTheSession() {
        startSession()
        model.relayReceived(frame(1, [0.5, 0.5]))
        XCTAssertTrue(engine.submitted("vibration_frame").isEmpty)
        XCTAssertFalse(model.sessionActive)
    }

    func testSafeWordStopsLatchesAndSurvivesRelaunch() {
        startSession()
        model.safeWord()
        XCTAssertEqual(relay.calls.last, "safeword")
        XCTAssertFalse(model.sessionActive)
        XCTAssertEqual(engine.submitted.last?.optString("type"), "stop")
        model.relayReceived(frame(1, [0.5]))
        XCTAssertTrue(engine.submitted("vibration_frame").isEmpty)
        XCTAssertTrue(makeModel().safeWordLatched)
    }

    func testRelayRefusesAnActiveSessionWhileSafeWordIsLatched() {
        SafeWordPreferences.set(true, defaults: defaults)
        model = makeModel()
        model.relayConnectionChanged(connected: true, reason: "Connected")
        model.relayReceived(state("e1", active: true))
        XCTAssertFalse(model.sessionActive)
        XCTAssertEqual(relay.calls.last, "safeword")
    }

    func testAcknowledgedReadyClearsTheSafeWordLatch() {
        SafeWordPreferences.set(true, defaults: defaults)
        model = makeModel()
        startSession()
        XCTAssertTrue(relay.calls.contains("clearSafeWord"))
        XCTAssertFalse(model.safeWordLatched)
        XCTAssertFalse(SafeWordPreferences.get(defaults))
        XCTAssertTrue(model.sessionActive)
    }

    func testUnacknowledgedReadyTimesOut() {
        model.relayConnectionChanged(connected: true, reason: "Connected")
        model.relayReceived(state("e1"))
        emit(["type": "ready", "active": true], ["type": "devices", "devices": [hush]])
        model.select(model.devices.first)
        emit(["type": "stopped", "confirmed": true, "active": true, "devices": [3]])
        model.relayReceived(state("e2"))
        model.action()
        scheduler.tick { $0 == 5 }
        XCTAssertEqual(relay.calls.last, "stop")
        XCTAssertFalse(model.actionEnabled)
    }

    func testStaleLeaseWhileActiveDropsTheConnection() {
        startSession()
        model.relayReceived(["type": "lease", "epoch": "e3", "active": true, "peerFresh": false])
        XCTAssertFalse(model.sessionActive)
        XCTAssertEqual(relay.calls.last, "disconnect")
        XCTAssertEqual(engine.submitted.last?.optString("type"), "stop")
    }

    func testLosingTheSelectedDeviceStopsTheSession() {
        startSession()
        emit(["type": "devices", "devices": []])
        XCTAssertNil(model.selected)
        XCTAssertFalse(model.sessionActive)
        XCTAssertTrue(relay.calls.contains("stop"))
        XCTAssertTrue(relay.sent("device").last?["device"] is NSNull)
    }

    func testDeviceCannotBeSwappedMidSession() {
        startSession()
        model.select(nil)
        XCTAssertNotNil(model.selected)
        XCTAssertTrue(model.sessionActive)
    }

    func testRelayDropStopsOutputButKeepsTheDevice() {
        startSession()
        model.relayConnectionChanged(connected: false, reason: "Connection lost")
        XCTAssertFalse(model.sessionActive)
        XCTAssertNotNil(model.selected)
        XCTAssertEqual(engine.submitted.last?.optString("type"), "stop")
    }

    func testScanResumesASuspendedEngineFirst() {
        model.relayConnectionChanged(connected: true, reason: "Connected")
        model.relayReceived(state("e1"))
        emit(["type": "ready", "active": false])
        engine.submitted = []
        model.toggleScan()
        XCTAssertEqual(engine.submitted.map { $0.optString("type") }, ["resume", "scan"])
    }
}
