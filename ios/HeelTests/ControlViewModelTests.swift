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

    private func ownerState(_ epoch: String, ready: Bool = false, active: Bool = false,
                            features: Int = 1) -> JSONDictionary {
        let list: [JSONDictionary] = (0..<features).map { ["index": $0, "label": "Motor \($0 + 1)", "steps": 20] }
        return ["type": "state", "phase": "paired", "role": "owner", "epoch": epoch, "online": true,
                "ready": ready, "active": active, "resumeAllowed": active,
                "device": ["id": 3, "name": "Hush", "features": list]]
    }

    /// Pet is ready, Owner taps Start, and the relay confirms the session is active.
    private func startSession(features: Int = 1) {
        model.relayConnectionChanged(connected: true, reason: "Connected")
        model.relayReceived(ownerState("e1", ready: true, features: features))
        model.action()
        model.relayReceived(ownerState("e1", ready: true, active: true, features: features))
    }

    private func frameValues() -> [[Double]] {
        relay.frames.map { ($0["values"] as? [Double]) ?? [] }
    }

    func testActivatingKeepsTheStateTheRelayReplays() {
        relay.state = ownerState("e1", ready: true)
        model.activate(onReturnToPick: {}, onUnpaired: {})
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

    func testAnUnpairWeDidNotAskForGoesBackToPairingInTheSameRole() {
        model.relayConnectionChanged(connected: true, reason: "Connected")
        model.relayReceived(ownerState("e1"))
        var pick = 0, unpaired = 0
        model.onReturnToPick = { pick += 1 }
        model.onUnpaired = { unpaired += 1 }
        model.relayReceived(["type": "state", "phase": "unpaired"])
        XCTAssertEqual(unpaired, 1)
        XCTAssertEqual(pick, 0)
    }

    func testResettingTheRoleReturnsToPickOnceTheRelayConfirms() {
        model.relayConnectionChanged(connected: true, reason: "Connected")
        model.relayReceived(ownerState("e1"))
        var pick = 0, unpaired = 0
        model.onReturnToPick = { pick += 1 }
        model.onUnpaired = { unpaired += 1 }
        model.unpairAndReset()
        XCTAssertEqual(pick, 0, "Nothing moves until the relay confirms")
        model.relayReceived(["type": "state", "phase": "unpaired"])
        XCTAssertEqual(pick, 1)
        XCTAssertEqual(unpaired, 0)
    }

    func testAFailedResetDoesNotSendTheNextUnpairToPick() {
        model.relayConnectionChanged(connected: true, reason: "Connected")
        model.relayReceived(ownerState("e1"))
        var pick = 0, unpaired = 0
        model.onReturnToPick = { pick += 1 }
        model.onUnpaired = { unpaired += 1 }
        relay.connected = false
        model.unpairAndReset()
        XCTAssertNotNil(model.resetError)
        relay.connected = true
        model.relayReceived(["type": "state", "phase": "unpaired"])
        XCTAssertEqual(unpaired, 1)
        XCTAssertEqual(pick, 0)
    }

    // MARK: - Slider mode (VoiceOver / Switch Control)

    func testSliderStepIsOneDeviceStepButNeverFinerThanFivePercent() {
        func step(_ steps: Int) -> Double {
            ControlViewModel.sliderStep(for: TouchModel.Feature(scalarIndex: 0, label: "", stepCount: steps))
        }
        XCTAssertEqual(step(3), 1.0 / 3, accuracy: 1e-9)
        XCTAssertEqual(step(20), 0.05, accuracy: 1e-9)
        XCTAssertEqual(step(100), 0.05, accuracy: 1e-9)
        XCTAssertEqual(step(0), 0.05, accuracy: 1e-9)
    }

    func testAdjustingASliderThatIsNotRunningSendsNothing() {
        model.setSliderMode(true)
        startSession()
        model.setSliderLevel(0, 0.52)
        XCTAssertTrue(relay.frames.isEmpty)
        XCTAssertEqual(model.sliderLevels, [0.5], "The draft level is normalized to the device's steps")
    }

    func testStartSendsTheDraftAndStopSendsZero() {
        model.setSliderMode(true)
        startSession()
        model.setSliderLevel(0, 0.52)
        model.startSlider(0)
        model.setSliderLevel(0, 0.8)
        model.stopSlider(0)
        XCTAssertEqual(frameValues(), [[0.5], [0.8], [0.0]])
        XCTAssertEqual(relay.frames.map { $0.optLong("seq") }, [1, 2, 3])
        XCTAssertEqual(model.sliderRunning, [false])
    }

    func testOnlyTheRunningFeatureSendsItsLevel() {
        model.setSliderMode(true)
        startSession(features: 2)
        model.setSliderLevel(0, 0.4)
        model.setSliderLevel(1, 0.6)
        model.startSlider(1)
        XCTAssertEqual(frameValues().last, [0.0, 0.6])
        model.startSlider(0)
        XCTAssertEqual(frameValues().last, [0.4, 0.6])
        model.stopSlider(1)
        XCTAssertEqual(frameValues().last, [0.4, 0.0])
    }

    func testSlidersDoNothingWithoutAnActiveSession() {
        model.relayConnectionChanged(connected: true, reason: "Connected")
        model.relayReceived(ownerState("e1", ready: true))
        model.setSliderMode(true)
        model.startSlider(0)
        model.setSliderLevel(0, 0.5)
        XCTAssertTrue(relay.frames.isEmpty)
        XCTAssertEqual(model.sliderRunning, [false])
    }

    func testSliderControlsAreIgnoredOutsideSliderMode() {
        startSession()
        model.startSlider(0)
        XCTAssertTrue(relay.frames.isEmpty)
    }

    func testOutOfRangeFeatureIndexIsIgnored() {
        model.setSliderMode(true)
        startSession()
        model.startSlider(5)
        model.setSliderLevel(-1, 0.5)
        XCTAssertTrue(relay.frames.isEmpty)
    }

    func testSwitchingModeDuringASessionPausesIt() {
        model.setSliderMode(true)
        startSession()
        model.startSlider(0)
        XCTAssertFalse(relay.frames.isEmpty)
        model.setSliderMode(false)
        XCTAssertFalse(model.sessionActive)
        XCTAssertEqual(relay.calls.last, "pause")
        XCTAssertEqual(model.sliderRunning, [false])
        XCTAssertEqual(model.sliderLevels, [0])
    }

    func testEnablingSliderModeDuringATouchSessionPausesIt() {
        startSession()
        model.setSliderMode(true)
        XCTAssertFalse(model.sessionActive)
        XCTAssertEqual(relay.calls.last, "pause")
    }

    func testSwitchingModeWhileIdleDoesNotPause() {
        model.relayConnectionChanged(connected: true, reason: "Connected")
        model.relayReceived(ownerState("e1", ready: true))
        model.setSliderMode(true)
        XCTAssertTrue(model.sliderMode)
        XCTAssertFalse(relay.calls.contains("pause"))
    }

    func testStopAllClearsRunningSliders() {
        model.setSliderMode(true)
        startSession()
        model.setSliderLevel(0, 0.8)
        model.startSlider(0)
        model.stopAll()
        XCTAssertEqual(model.sliderRunning, [false])
        XCTAssertEqual(model.sliderLevels, [0])
    }

    func testAHeldLevelIsNotRestoredIntoSliderMode() {
        func resumedWithHold() -> JSONDictionary {
            var state = ownerState("e1", ready: true, active: true)
            state["resumed"] = true
            state["restore"] = ["hold": true, "device": state["device"]!, "values": [0.5]]
            return state
        }
        startSession()
        model.relayReceived(resumedWithHold())
        XCTAssertEqual(frameValues(), [[0.5]], "Touch-pad mode restores the held level")

        relay.frames = []
        model = ControlViewModel(relay: relay, haptics: OwnerHaptics(output: NullHapticsOutput()),
                                 spellbook: SpellBook(defaults: defaults), schedule: { _, _ in })
        model.setSliderMode(true)
        startSession()
        model.relayReceived(resumedWithHold())
        XCTAssertTrue(relay.frames.isEmpty)
    }

    // MARK: - Screen lifecycle

    func testHidingTheScreenPausesARunningSession() {
        startSession()
        model.screenDidDisappear()
        XCTAssertFalse(model.sessionActive)
        XCTAssertEqual(relay.calls.last, "pause", "A pause the session can resume from, not a stop")
    }

    func testHidingTheScreenWhileIdleSendsNothing() {
        model.relayConnectionChanged(connected: true, reason: "Connected")
        model.relayReceived(ownerState("e1", ready: true))
        model.screenDidDisappear()
        XCTAssertTrue(relay.calls.isEmpty)
    }

    func testDeactivatingReleasesTheRelayAndForgetsWhereToReturn() {
        model.activate(onReturnToPick: {}, onUnpaired: {})
        model.deactivate()
        XCTAssertNil(relay.controller)
        XCTAssertNil(model.onReturnToPick)
    }

    func testActivatingWiresBothDestinations() {
        var unpaired = false
        relay.state = ownerState("e1", ready: true)
        model.activate(onReturnToPick: {}, onUnpaired: { unpaired = true })
        model.relayReceived(["type": "state", "phase": "unpaired"])
        XCTAssertTrue(unpaired)
    }

    // MARK: - Pause, resume and keeping the screen awake

    /// The relay starts a new epoch when it pauses a session.
    private func pausedState() -> JSONDictionary {
        var state = ownerState("e2")
        state["paused"] = true
        state["resumeAllowed"] = true
        return state
    }

    func testAPausedSessionSaysSoAndOffersResume() {
        startSession()
        model.screenDidDisappear()
        model.relayReceived(pausedState())
        XCTAssertEqual(model.statusText, "Paused · resume when you like")
        XCTAssertEqual(model.sessionActionTitle, "Resume ♡")
    }

    func testResumeSendsResumeForTheCurrentEpoch() {
        startSession()
        model.screenDidDisappear()
        model.relayReceived(pausedState())
        model.action()
        XCTAssertEqual(relay.sent.last?.optString("type"), "resume")
        XCTAssertEqual(relay.sent.last?.optString("epoch"), "e2")
    }

    func testBackgroundingPausesARunningSession() {
        startSession()
        model.appDidEnterBackground()
        XCTAssertFalse(model.sessionActive)
        XCTAssertEqual(relay.calls.last, "pause")
    }

    func testBackgroundingWhileIdleSendsNothing() {
        model.relayConnectionChanged(connected: true, reason: "Connected")
        model.relayReceived(ownerState("e1"))
        model.appDidEnterBackground()
        XCTAssertTrue(relay.calls.isEmpty)
    }

    func testScreenIsHeldAwakeForALiveSessionOrAReadyPetButNotOtherwise() {
        model.relayConnectionChanged(connected: true, reason: "Connected")
        model.relayReceived(ownerState("e1"))
        XCTAssertFalse(model.keepsScreenAwake, "Nothing is happening")
        model.relayReceived(ownerState("e1", ready: true))
        XCTAssertTrue(model.keepsScreenAwake, "Pet is ready and waiting for Start")
        model.action()
        model.relayReceived(ownerState("e1", ready: true, active: true))
        XCTAssertTrue(model.keepsScreenAwake)
    }

    func testAPausedSessionDoesNotPinTheScreenOn() {
        startSession()
        model.screenDidDisappear()
        model.relayReceived(pausedState())
        XCTAssertTrue(model.sessionActionEnabled, "It can still be resumed")
        XCTAssertFalse(model.keepsScreenAwake)
    }

    func testScreenIsNotHeldAwakeOffline() {
        startSession()
        model.relayConnectionChanged(connected: false, reason: "Offline")
        XCTAssertFalse(model.keepsScreenAwake)
    }
}
