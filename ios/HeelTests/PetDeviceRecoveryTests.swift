import XCTest
@testable import Heel

/// Finding a dropped toy again. The Bluetooth stack itself isn't involved: these drive the
/// engine's events directly, so they prove the state machine, not that a real toy reconnects.
@MainActor
final class PetDeviceRecoveryTests: XCTestCase {
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
        defaults = UserDefaults(suiteName: "PetDeviceRecoveryTests.\(UUID().uuidString)")
        model = PetControlViewModel(relay: relay, engine: engine, defaults: defaults, schedule: scheduler.schedule)
    }

    // MARK: - Helpers

    private func toy(identity: String = "hush-1", id: Int = 3, features: Int = 1) -> JSONDictionary {
        let scalar: [JSONDictionary] = (0..<features).map { _ in
            ["ActuatorType": "Vibrate", "FeatureDescriptor": "", "StepCount": 20]
        }
        return ["id": id, "name": "Lovense Hush", "identity": identity, "messages": ["ScalarCmd": scalar]]
    }

    private func state(_ epoch: String, ready: Bool = false, active: Bool = false, resumeAllowed: Bool = false,
                       deviceOnline: Bool? = nil) -> JSONDictionary {
        var value: JSONDictionary = ["type": "state", "phase": "paired", "role": "pet", "epoch": epoch, "online": true,
                                     "ready": ready, "active": active, "resumeAllowed": resumeAllowed,
                                     "safeWord": false, "profile": ["owner": "Sam"]]
        if let deviceOnline { value["deviceOnline"] = deviceOnline }
        return value
    }

    /// One poll of the engine: events are drained together, as the real poll does.
    private func emit(_ events: JSONDictionary...) {
        engine.events.append(contentsOf: events)
        scheduler.tick { $0 == 0.05 }
    }

    private func tick(_ delay: TimeInterval) { scheduler.tick { $0 == delay } }

    private func stopped(devices: [Int] = [3]) -> JSONDictionary {
        ["type": "stopped", "confirmed": true, "active": true, "devices": devices]
    }

    private func scans(_ enabled: Bool) -> Int {
        engine.submitted("scan").filter { $0.optBool("enabled") == enabled }.count
    }

    /// Pet readied, Owner started: a live session on a connected toy.
    private func startSession() {
        model.relayConnectionChanged(connected: true, reason: "Connected")
        model.relayReceived(state("e1"))
        emit(["type": "ready", "active": true], ["type": "devices", "devices": [toy()]])
        model.select(model.devices.first)
        emit(stopped())
        model.relayReceived(state("e2"))
        model.action()
        model.relayReceived(state("e3", ready: true))
        model.relayReceived(state("e3", active: true, resumeAllowed: true))
        XCTAssertTrue(model.sessionActive, "Precondition: a live session")
    }

    /// The toy drops mid-session, and the engine settles after the stop.
    private func loseTheToy() {
        emit(["type": "devices", "devices": []])
        emit(stopped(devices: []))
    }

    private func device(_ message: JSONDictionary) -> Any? { message["device"] }

    // MARK: - Losing the toy

    func testLosingTheToyMidSessionPausesAndLooksForItInsteadOfEndingTheSession() {
        startSession()
        emit(["type": "devices", "devices": []])
        XCTAssertTrue(model.recoveringDevice)
        XCTAssertFalse(model.sessionActive)
        XCTAssertNotNil(model.selected, "The toy stays selected while it is being found")
        XCTAssertFalse(relay.calls.contains("stop"), "The session is paused, not ended")
        let link = relay.sent("device_link").last
        XCTAssertEqual(link?.optInt("id"), 3)
        XCTAssertEqual(link?.optBool("connected"), false)
        XCTAssertEqual(model.statusText, "Finding your toy again… ♡")
    }

    func testStopsOutputTheMomentTheToyDrops() {
        startSession()
        let before = engine.submitted("stop").count
        emit(["type": "devices", "devices": []])
        XCTAssertGreaterThan(engine.submitted("stop").count, before)
    }

    func testKeepsAdvertisingTheToyWhileLookingSoTheRelayHoldsOnToIt() {
        startSession()
        loseTheToy()
        let last = relay.sent("device").last
        XCTAssertFalse((last.flatMap(device)) is NSNull, "Advertising nothing would make the relay drop the toy")
    }

    func testTheLostToyStaysListed() {
        startSession()
        loseTheToy()
        XCTAssertTrue(model.devices.isEmpty)
        XCTAssertEqual(model.displayDevices.map(\.id), [3])
    }

    func testAToyNothingWasUsingIsJustForgotten() {
        model.relayConnectionChanged(connected: true, reason: "Connected")
        model.relayReceived(state("e1"))
        emit(["type": "ready", "active": true], ["type": "devices", "devices": [toy()]])
        model.select(model.devices.first)
        emit(["type": "devices", "devices": []])
        XCTAssertFalse(model.recoveringDevice)
        XCTAssertNil(model.selected)
        XCTAssertTrue(relay.sent("device_link").isEmpty)
    }

    func testAToyWithNoIdentityCannotBeFoundAgain() {
        model.relayConnectionChanged(connected: true, reason: "Connected")
        model.relayReceived(state("e1"))
        var anonymous = toy()
        anonymous["identity"] = nil
        emit(["type": "ready", "active": true], ["type": "devices", "devices": [anonymous]])
        model.select(model.devices.first)
        emit(stopped())
        model.relayReceived(state("e2"))
        model.action()
        model.relayReceived(state("e3", ready: true))
        model.relayReceived(state("e3", active: true, resumeAllowed: true))
        emit(["type": "devices", "devices": []])
        XCTAssertFalse(model.recoveringDevice)
        XCTAssertNil(model.selected)
        XCTAssertTrue(relay.calls.contains("stop"), "Nothing to look for, so the session ends")
    }

    func testTheDeviceListIsHandledBeforeAnErrorFromTheSameBatch() {
        startSession()
        // The failing write's error arrives alongside the list that shows why.
        emit(["type": "error", "message": "Live vibration command failed"],
             ["type": "devices", "devices": []])
        XCTAssertTrue(model.recoveringDevice, "Handling the error first would end a recoverable session")
        XCTAssertFalse(relay.calls.contains("stop"))
    }

    // MARK: - Looking

    func testScansInWindowsAndComesBackForMoreAfterEachEnds() {
        startSession()
        loseTheToy()
        tick(1)
        XCTAssertEqual(scans(true), 1)
        XCTAssertGreaterThanOrEqual(engine.submitted("resume").count, 1, "A failed write suspends the engine")
        tick(15)
        XCTAssertEqual(scans(false), 1, "The window closes")
        tick(2)
        XCTAssertEqual(scans(true), 2, "And another opens")
    }

    func testDoesNotScanBeforeTheStopSettles() {
        startSession()
        emit(["type": "devices", "devices": []])
        tick(0.25)
        XCTAssertEqual(scans(true), 0)
    }

    func testAnErrorWhileLookingRetriesInsteadOfGivingUp() {
        startSession()
        loseTheToy()
        emit(["type": "error", "message": "Scan failed"])
        XCTAssertTrue(model.recoveringDevice)
        tick(1)
        XCTAssertGreaterThanOrEqual(scans(true), 1)
    }

    func testScanAndDeviceChoiceAreFrozenWhileLooking() {
        startSession()
        loseTheToy()
        XCTAssertFalse(model.canChangeDevice)
        let before = engine.submitted("scan").count
        model.toggleScan()
        model.select(nil)
        XCTAssertEqual(engine.submitted("scan").count, before)
        XCTAssertNotNil(model.selected)
    }

    // MARK: - The toy returns

    func testTheSameToyReturningIsConfirmedAtZeroBeforeItCanBeDrivenAgain() {
        startSession()
        loseTheToy()
        model.relayReceived(state("e4", resumeAllowed: true, deviceOnline: false))
        tick(0) // A relay state re-arms the next scan window immediately.
        let stopsBefore = engine.submitted("stop").count
        emit(["type": "devices", "devices": [toy()]])
        XCTAssertGreaterThan(engine.submitted("stop").count, stopsBefore, "Output is zeroed on the reconnected toy")
        XCTAssertTrue(model.recoveringDevice, "Not recovered until the zero is confirmed")
        XCTAssertFalse(relay.sent("device_link").contains { $0.optBool("connected") })

        emit(stopped())
        XCTAssertFalse(model.recoveringDevice)
        XCTAssertEqual(relay.sent("device_link").last?.optBool("connected"), true)
        XCTAssertEqual(scans(false), 1, "Scanning stops once it is found")
    }

    func testOutputDoesNotResumeByItselfWhenTheToyReturns() {
        startSession()
        loseTheToy()
        model.relayReceived(state("e4", resumeAllowed: true, deviceOnline: false))
        tick(0) // A relay state re-arms the next scan window immediately.
        emit(["type": "devices", "devices": [toy()]])
        emit(stopped())
        model.relayReceived(state("e4", resumeAllowed: true, deviceOnline: true))
        XCTAssertTrue(relay.sent("resume").isEmpty, "Pet has to choose to resume")
        XCTAssertFalse(model.sessionActive)
    }

    func testPetCanResumeOnceTheToyIsBack() {
        startSession()
        loseTheToy()
        model.relayReceived(state("e4", resumeAllowed: true, deviceOnline: false))
        tick(0) // A relay state re-arms the next scan window immediately.
        emit(["type": "devices", "devices": [toy()]])
        emit(stopped())
        model.relayReceived(state("e4", resumeAllowed: true, deviceOnline: true))
        XCTAssertEqual(model.actionTitle, "Resume ♡")
        XCTAssertTrue(model.actionEnabled)
        model.action()
        let resume = relay.sent("resume").last
        XCTAssertEqual(resume?.optString("epoch"), "e4")
        XCTAssertNotEqual(resume?.optBool("automatic"), true, "A tap, not an automatic resume")
    }

    func testADifferentToyNeverCompletesRecovery() {
        startSession()
        loseTheToy()
        tick(1)
        let stopsBefore = engine.submitted("stop").count
        // Same name and slot, but a different physical toy.
        emit(["type": "devices", "devices": [toy(identity: "hush-2")]])
        emit(["type": "devices", "devices": [toy(identity: "other", id: 5)]])
        XCTAssertTrue(model.recoveringDevice)
        XCTAssertEqual(engine.submitted("stop").count, stopsBefore, "Nothing was treated as a candidate")
        XCTAssertTrue(relay.sent("device_link").allSatisfy { !$0.optBool("connected") })
    }

    func testTheSameToyWithDifferentControlsEndsTheSession() {
        startSession()
        loseTheToy()
        tick(1)
        emit(["type": "devices", "devices": [toy(features: 2)]])
        XCTAssertFalse(model.recoveringDevice)
        XCTAssertNil(model.selected)
        XCTAssertTrue(relay.calls.contains("stop"), "A session built for the old controls can't continue")
    }

    // MARK: - Safe Word and giving up

    func testSafeWordWhileLookingStopsScanningAndNothingReconnects() {
        startSession()
        loseTheToy()
        tick(1)
        XCTAssertEqual(scans(true), 1)
        model.safeWord()
        XCTAssertEqual(scans(false), 1, "The open scan is closed")
        XCTAssertEqual(relay.calls.last, "safeword")

        let stopsBefore = engine.submitted("stop").count
        emit(["type": "devices", "devices": [toy()]])
        tick(2); tick(1); tick(15)
        XCTAssertTrue(model.recoveringDevice, "It does not count as found")
        XCTAssertEqual(scans(true), 1, "No new scan starts")
        XCTAssertEqual(engine.submitted("stop").count, stopsBefore)
    }

    func testAfterSafeWordChoosingReadyAllowsTheReconnect() {
        startSession()
        loseTheToy()
        model.safeWord()
        emit(stopped(devices: [])) // The engine confirms Safe Word's stop.
        XCTAssertEqual(model.actionTitle, "Reconnect & ready ♡")
        XCTAssertTrue(model.actionEnabled)

        model.action()
        XCTAssertFalse(model.actionEnabled, "Already asked")
        tick(0)
        XCTAssertEqual(scans(true), 1, "Looking resumes only now")
        emit(["type": "devices", "devices": [toy()]])
        emit(stopped())
        XCTAssertFalse(model.recoveringDevice)
    }

    func testStopLookingForgetsTheToyAndEndsTheSession() {
        startSession()
        loseTheToy()
        model.stopLookingForDevice()
        XCTAssertFalse(model.recoveringDevice)
        XCTAssertNil(model.selected)
        XCTAssertTrue(relay.calls.contains("stop"))
        XCTAssertTrue((relay.sent("device").last.flatMap(device)) is NSNull)
        tick(2); tick(1)
        XCTAssertEqual(scans(true), 0, "Nothing keeps scanning for it")
    }

    func testUnpairingWhileLookingClearsItAll() {
        startSession()
        loseTheToy()
        model.relayReceived(["type": "state", "phase": "unpaired"])
        XCTAssertFalse(model.recoveringDevice)
        XCTAssertNil(model.selected)
        XCTAssertTrue(model.displayDevices.isEmpty)
    }

    // MARK: - The device model

    func testIdentityIsKeptLocallyAndNeverSentToTheRelay() throws {
        let device = try XCTUnwrap(PetDevice(nativeDevice: toy(identity: "hush-1")))
        XCTAssertEqual(device.identity, "hush-1")
        XCTAssertNil(device.relayPayload["identity"])
        XCTAssertFalse(device.relayPayload.compactJSONString.contains("hush-1"))
    }
}
