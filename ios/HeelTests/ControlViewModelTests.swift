import XCTest
@testable import Heel

@MainActor
final class ControlViewModelTests: XCTestCase {
    private var relay: FakeRelay!
    private var defaults: UserDefaults!
    private var model: ControlViewModel!

    override func setUp() {
        super.setUp()
        relay = FakeRelay()
        defaults = UserDefaults(suiteName: "ControlViewModelTests.\(UUID().uuidString)")
        model = ControlViewModel(relay: relay, haptics: OwnerHaptics(output: NullHapticsOutput()),
                                 spellbook: SpellBook(defaults: defaults), schedule: { _, _ in })
    }

    private func ownerState(_ epoch: String, ready: Bool = false, active: Bool = false) -> JSONDictionary {
        ["type": "state", "phase": "paired", "role": "owner", "epoch": epoch, "online": true,
         "ready": ready, "active": active, "resumeAllowed": active,
         "device": ["id": 3, "name": "Hush", "features": [["index": 0, "label": "Tip", "steps": 20]]]]
    }

    func testReopeningTheScreenKeepsTheStateTheRelayReplays() {
        relay.state = ownerState("e1", ready: true)
        model.onAppear()
        XCTAssertEqual(model.relayState.optString("phase"), "paired")
        XCTAssertEqual(model.deviceName, "Hush")
        XCTAssertTrue(model.sessionActionEnabled, "Owner can start straight away when Pet is ready")
    }

    func testUnpairStopsThenAsksTheRelayToUnpair() {
        model.relayConnectionChanged(connected: true, reason: "Connected")
        model.relayReceived(ownerState("e1"))
        model.unpairAndReset()
        XCTAssertEqual(relay.calls.last, "stop")
        XCTAssertEqual(relay.sent.last?.optString("type"), "unpair")
        XCTAssertNil(model.resetError)
    }

    func testUnpairWhileOfflineExplainsInsteadOfLeaving() {
        model.relayConnectionChanged(connected: false, reason: "Offline")
        model.unpairAndReset()
        XCTAssertNotNil(model.resetError)
        XCTAssertTrue(relay.sent("unpair").isEmpty)
    }

    func testRelayConfirmingUnpairedReturnsToPick() {
        model.relayConnectionChanged(connected: true, reason: "Connected")
        model.relayReceived(ownerState("e1"))
        var returned = false
        model.onReturnToPick = { returned = true }
        model.relayReceived(["type": "state", "phase": "unpaired"])
        XCTAssertTrue(returned)
    }
}
