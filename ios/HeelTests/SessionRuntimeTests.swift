import XCTest
@testable import Heel

@MainActor
final class SessionRuntimeTests: XCTestCase {
    private var relay: FakeRelay!
    private var engine: FakeEngine!
    private var scheduler: ManualScheduler!
    private var screen: FakeScreen!
    private var runtime: SessionRuntime!

    override func setUp() {
        super.setUp()
        let relay = FakeRelay()
        let engine = FakeEngine()
        let scheduler = ManualScheduler()
        let screen = FakeScreen()
        let defaults = UserDefaults(suiteName: "SessionRuntimeTests.\(UUID().uuidString)")!
        self.relay = relay
        self.engine = engine
        self.scheduler = scheduler
        self.screen = screen
        runtime = SessionRuntime(
            owner: { ControlViewModel(relay: relay, haptics: OwnerHaptics(output: NullHapticsOutput()),
                                      spellbook: SpellBook(defaults: defaults), schedule: { _, _ in }) },
            pet: { PetControlViewModel(relay: relay, engine: engine, defaults: defaults, schedule: scheduler.schedule) },
            screen: screen)
    }

    private func state(role: String, epoch: String = "e1", ready: Bool = false, active: Bool = false) -> JSONDictionary {
        ["type": "state", "phase": "paired", "role": role, "epoch": epoch, "online": true,
         "ready": ready, "active": active, "resumeAllowed": active,
         "device": ["id": 3, "name": "Hush", "features": [["index": 0, "label": "Tip", "steps": 20]]]]
    }

    /// Owner with a live session: Pet is ready, Owner starts, the relay confirms.
    private func startOwnerSession(onReturnToPick: @escaping () -> Void = {}) {
        relay.state = state(role: "owner", ready: true)
        runtime.activate(.owner, onReturnToPick: onReturnToPick, onUnpaired: {})
        runtime.owner.action()
        runtime.owner.relayReceived(state(role: "owner", ready: true, active: true))
    }

    func testActivatingARoleMakesItsModelTheRelayController() {
        runtime.activate(.owner, onReturnToPick: {}, onUnpaired: {})
        XCTAssertTrue(relay.controller === runtime.owner)
        XCTAssertEqual(runtime.role, .owner)
    }

    func testChangingRoleStopsTheOldSessionAndHandsOverTheRelay() {
        startOwnerSession()
        XCTAssertTrue(runtime.owner.sessionActive)
        runtime.activate(.pet, onReturnToPick: {}, onUnpaired: {})
        XCTAssertFalse(runtime.owner.sessionActive)
        XCTAssertEqual(relay.calls.first, "stop")
        XCTAssertTrue(relay.controller === runtime.pet)
        XCTAssertEqual(runtime.role, .pet)
    }

    func testDeactivatingStopsALiveSessionAndReleasesTheRelay() {
        startOwnerSession()
        runtime.deactivate()
        XCTAssertFalse(runtime.owner.sessionActive)
        XCTAssertTrue(relay.calls.contains("stop"))
        XCTAssertNil(relay.controller)
        XCTAssertNil(runtime.role)
    }

    func testDeactivatingWithNothingActiveDoesNothing() {
        runtime.deactivate()
        XCTAssertTrue(relay.calls.isEmpty)
        XCTAssertNil(relay.controller)
    }

    func testActivatingTheActiveRoleAgainKeepsItsSessionRunning() {
        var firstPick = 0, firstUnpaired = 0, secondUnpaired = 0
        startOwnerSession(onReturnToPick: { firstPick += 1 })
        runtime.activate(.owner, onReturnToPick: { firstPick += 1 }, onUnpaired: { secondUnpaired += 1 })
        XCTAssertTrue(runtime.owner.sessionActive, "A re-rendered screen must not interrupt the session")
        XCTAssertFalse(relay.calls.contains("stop"))
        // The refreshed closures are the ones used when the relay later says unpaired.
        runtime.owner.relayReceived(["type": "state", "phase": "unpaired"])
        XCTAssertEqual(firstUnpaired, 0)
        XCTAssertEqual(secondUnpaired, 1)
        XCTAssertEqual(firstPick, 0, "A plain unpair keeps the role, so it doesn't go to Pick")
    }

    func testTheModelsAreCreatedOnceAndOnlyWhenNeeded() {
        let relay = self.relay!
        var made = 0
        let runtime = SessionRuntime(owner: { made += 1; return ControlViewModel(relay: relay) },
                                     pet: { XCTFail("Pet was never used"); return PetControlViewModel() })
        XCTAssertEqual(made, 0)
        runtime.activate(.owner, onReturnToPick: {}, onUnpaired: {})
        runtime.deactivate()
        runtime.activate(.owner, onReturnToPick: {}, onUnpaired: {})
        XCTAssertEqual(made, 1)
    }

    func testPetEngineStopsPollingOnceDeactivated() {
        relay.state = state(role: "pet")
        runtime.activate(.pet, onReturnToPick: {}, onUnpaired: {})
        engine.events = [["type": "ready", "active": true]]
        scheduler.tick { $0 < 1 }
        XCTAssertTrue(runtime.pet.ready, "Polling runs while Pet is active")

        runtime.deactivate()
        XCTAssertGreaterThanOrEqual(engine.submitted("suspend").count, 1, "The engine is suspended")
        engine.events = [["type": "devices", "devices": []]]
        scheduler.tick { $0 < 1 }
        XCTAssertFalse(engine.events.isEmpty, "Nothing drains the engine once Pet is deactivated")
    }

    func testPetKeepsRunningWhileNoScreenIsShowing() {
        relay.state = state(role: "pet")
        runtime.activate(.pet, onReturnToPick: {}, onUnpaired: {})
        // No screen exists in this test at all; the model still reacts to the relay.
        runtime.pet.relayReceived(state(role: "pet", ready: false))
        XCTAssertTrue(runtime.pet.initialized)
        XCTAssertTrue(relay.controller === runtime.pet)
    }

    // MARK: - Screen wake

    func testScreenStaysAwakeWhileASessionIsLive() {
        startOwnerSession()
        runtime.refreshScreenWake()
        XCTAssertTrue(screen.awake)
    }

    func testScreenIsOnlyToldWhenTheAnswerChanges() {
        startOwnerSession()
        runtime.refreshScreenWake()
        runtime.refreshScreenWake()
        runtime.refreshScreenWake()
        XCTAssertEqual(screen.changes, [true])
    }

    func testScreenIsNotHeldAwakeWhileIdle() {
        relay.state = state(role: "owner")
        runtime.activate(.owner, onReturnToPick: {}, onUnpaired: {})
        runtime.refreshScreenWake()
        XCTAssertTrue(screen.changes.isEmpty)
        XCTAssertFalse(screen.awake)
    }

    func testScreenStaysAwakeWhileThePeerIsReadyToStart() {
        relay.state = state(role: "owner", ready: true)
        runtime.activate(.owner, onReturnToPick: {}, onUnpaired: {})
        XCTAssertTrue(screen.awake, "Owner is one tap from starting, so the phone must not lock")
    }

    func testDeactivatingReleasesTheScreen() {
        startOwnerSession()
        runtime.refreshScreenWake()
        runtime.deactivate()
        XCTAssertFalse(screen.awake)
    }

    func testScreenFollowsTheModelWithoutBeingAsked() {
        relay.state = state(role: "owner")
        runtime.activate(.owner, onReturnToPick: {}, onUnpaired: {})
        XCTAssertFalse(screen.awake)

        let woke = expectation(description: "screen held awake")
        screen.onChange = { if $0 { woke.fulfill() } }
        runtime.owner.relayReceived(state(role: "owner", ready: true))
        wait(for: [woke], timeout: 2)
    }

    // MARK: - Background

    func testBackgroundPausesALiveSessionInsteadOfLettingItTimeOut() {
        startOwnerSession()
        runtime.appDidEnterBackground()
        XCTAssertFalse(runtime.owner.sessionActive)
        XCTAssertEqual(relay.calls.last, "pause", "A pause the session can resume from, not a stop")
    }

    func testBackgroundReleasesTheScreen() {
        startOwnerSession()
        runtime.refreshScreenWake()
        runtime.appDidEnterBackground()
        runtime.owner.relayReceived(["type": "state", "phase": "paired", "role": "owner", "epoch": "e1",
                                     "online": true, "ready": false, "active": false, "paused": true,
                                     "resumeAllowed": false])
        runtime.refreshScreenWake()
        XCTAssertFalse(screen.awake)
    }

    func testBackgroundWithNothingActiveDoesNothing() {
        runtime.appDidEnterBackground()
        XCTAssertTrue(relay.calls.isEmpty)
    }

    func testBackgroundWhileIdleDoesNotPause() {
        relay.state = state(role: "owner", ready: true)
        runtime.activate(.owner, onReturnToPick: {}, onUnpaired: {})
        runtime.appDidEnterBackground()
        XCTAssertFalse(relay.calls.contains("pause"))
    }

    func testBackgroundPausesPetsSessionAndStopsTheDevice() {
        relay.state = state(role: "pet")
        runtime.activate(.pet, onReturnToPick: {}, onUnpaired: {})
        engine.events = [["type": "ready", "active": true], ["type": "devices", "devices": [
            ["id": 3, "name": "Hush", "messages": ["ScalarCmd": [["ActuatorType": "Vibrate", "FeatureDescriptor": "", "StepCount": 20]]]],
        ]]]
        scheduler.tick { $0 < 1 }
        runtime.pet.select(runtime.pet.devices.first)
        engine.events = [["type": "stopped", "confirmed": true, "active": true, "devices": [3]]]
        scheduler.tick { $0 < 1 }
        // The relay re-epochs when the device changes, and again once Ready is accepted.
        runtime.pet.relayReceived(state(role: "pet", epoch: "e2"))
        runtime.pet.action()
        runtime.pet.relayReceived(state(role: "pet", epoch: "e3", ready: true))
        runtime.pet.relayReceived(state(role: "pet", epoch: "e3", active: true))
        XCTAssertTrue(runtime.pet.sessionActive)

        runtime.appDidEnterBackground()
        XCTAssertFalse(runtime.pet.sessionActive)
        XCTAssertEqual(relay.calls.last, "pause")
        XCTAssertEqual(engine.submitted.last?.optString("type"), "stop")
    }
}
