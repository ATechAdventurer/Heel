import XCTest
@testable import Heel

@MainActor
final class SessionRuntimeTests: XCTestCase {
    private var relay: FakeRelay!
    private var engine: FakeEngine!
    private var scheduler: ManualScheduler!
    private var runtime: SessionRuntime!

    override func setUp() {
        super.setUp()
        let relay = FakeRelay()
        let engine = FakeEngine()
        let scheduler = ManualScheduler()
        let defaults = UserDefaults(suiteName: "SessionRuntimeTests.\(UUID().uuidString)")!
        self.relay = relay
        self.engine = engine
        self.scheduler = scheduler
        runtime = SessionRuntime(
            owner: { ControlViewModel(relay: relay, haptics: OwnerHaptics(output: NullHapticsOutput()),
                                      spellbook: SpellBook(defaults: defaults), schedule: { _, _ in }) },
            pet: { PetControlViewModel(relay: relay, engine: engine, defaults: defaults, schedule: scheduler.schedule) })
    }

    private func state(role: String, ready: Bool = false, active: Bool = false) -> JSONDictionary {
        ["type": "state", "phase": "paired", "role": role, "epoch": "e1", "online": true,
         "ready": ready, "active": active, "resumeAllowed": active,
         "device": ["id": 3, "name": "Hush", "features": [["index": 0, "label": "Tip", "steps": 20]]]]
    }

    /// Owner with a live session: Pet is ready, Owner starts, the relay confirms.
    private func startOwnerSession(onReturnToPick: @escaping () -> Void = {}) {
        relay.state = state(role: "owner", ready: true)
        runtime.activate(.owner, onReturnToPick: onReturnToPick)
        runtime.owner.action()
        runtime.owner.relayReceived(state(role: "owner", ready: true, active: true))
    }

    func testActivatingARoleMakesItsModelTheRelayController() {
        runtime.activate(.owner) {}
        XCTAssertTrue(relay.controller === runtime.owner)
        XCTAssertEqual(runtime.role, .owner)
    }

    func testChangingRoleStopsTheOldSessionAndHandsOverTheRelay() {
        startOwnerSession()
        XCTAssertTrue(runtime.owner.sessionActive)
        runtime.activate(.pet) {}
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
        var first = 0, second = 0
        startOwnerSession(onReturnToPick: { first += 1 })
        runtime.activate(.owner) { second += 1 }
        XCTAssertTrue(runtime.owner.sessionActive, "A re-rendered screen must not interrupt the session")
        XCTAssertFalse(relay.calls.contains("stop"))
        // The refreshed closure is the one used when the relay later says unpaired.
        runtime.owner.relayReceived(["type": "state", "phase": "unpaired"])
        XCTAssertEqual(first, 0)
        XCTAssertEqual(second, 1)
    }

    func testTheModelsAreCreatedOnceAndOnlyWhenNeeded() {
        let relay = self.relay!
        var made = 0
        let runtime = SessionRuntime(owner: { made += 1; return ControlViewModel(relay: relay) },
                                     pet: { XCTFail("Pet was never used"); return PetControlViewModel() })
        XCTAssertEqual(made, 0)
        runtime.activate(.owner) {}
        runtime.deactivate()
        runtime.activate(.owner) {}
        XCTAssertEqual(made, 1)
    }

    func testPetEngineStopsPollingOnceDeactivated() {
        relay.state = state(role: "pet")
        runtime.activate(.pet) {}
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
        runtime.activate(.pet) {}
        // No screen exists in this test at all; the model still reacts to the relay.
        runtime.pet.relayReceived(state(role: "pet", ready: false))
        XCTAssertTrue(runtime.pet.initialized)
        XCTAssertTrue(relay.controller === runtime.pet)
    }
}
