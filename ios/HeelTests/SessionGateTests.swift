import XCTest
@testable import Heel

final class SessionGateTests: XCTestCase {
    func testPetReachesReadyThenActiveOnOwnersStart() {
        let pet = SessionGate(pet: true)
        XCTAssertTrue(pet.state(incoming: "e1", paired: true, online: true, ready: false, active: false))
        XCTAssertTrue(pet.mayRequest())
        pet.requestReady()
        XCTAssertFalse(pet.mayRequest(), "A pending request blocks another until the server answers")

        // Server reports the pet ready for this epoch.
        XCTAssertTrue(pet.state(incoming: "e1", paired: true, online: true, ready: true, active: false))
        XCTAssertTrue(pet.readyForEpoch("e1"))

        // Owner starts; the pet's gate authorizes the transition because it matches readyEpoch.
        XCTAssertTrue(pet.state(incoming: "e1", paired: true, online: true, ready: false, active: true))
        XCTAssertTrue(pet.active())
    }

    func testOwnerReachesActiveOnlyAfterItsOwnRequestStart() {
        let owner = SessionGate(pet: false)
        XCTAssertTrue(owner.state(incoming: "e1", paired: true, online: true, ready: false, active: false))
        XCTAssertTrue(owner.state(incoming: "e1", paired: true, online: true, ready: true, active: false))
        owner.requestStart()

        XCTAssertTrue(owner.state(incoming: "e1", paired: true, online: true, ready: false, active: true))
        XCTAssertTrue(owner.active())
    }

    func testOwnerCannotBeActivatedWithoutRequestingStart() {
        let owner = SessionGate(pet: false)
        XCTAssertTrue(owner.state(incoming: "e1", paired: true, online: true, ready: true, active: false))
        // No requestStart() was called locally, so an "active" report is not authorized.
        XCTAssertFalse(owner.state(incoming: "e1", paired: true, online: true, ready: false, active: true))
        XCTAssertFalse(owner.active())
    }

    func testPetCannotBeActivatedWithoutAMatchingReadyEpoch() {
        let pet = SessionGate(pet: true)
        XCTAssertTrue(pet.state(incoming: "e1", paired: true, online: true, ready: false, active: false))
        // The pet never became ready, so an "active" report is not authorized.
        XCTAssertFalse(pet.state(incoming: "e1", paired: true, online: true, ready: false, active: true))
        XCTAssertFalse(pet.active())
    }

    func testLocalStopRejectsAReplayedActiveMessageForTheSameEpoch() {
        let owner = SessionGate(pet: false)
        XCTAssertTrue(owner.state(incoming: "e1", paired: true, online: true, ready: true, active: false))
        owner.requestStart()
        XCTAssertTrue(owner.state(incoming: "e1", paired: true, online: true, ready: false, active: true))
        XCTAssertTrue(owner.active())

        owner.stop()
        XCTAssertFalse(owner.active())
        XCTAssertFalse(owner.canRecover(), "A deliberate Stop forfeits recovery")

        // A stale "still active" message for the epoch Stop just blocked must be rejected,
        // not silently restore control.
        XCTAssertFalse(owner.state(incoming: "e1", paired: true, online: true, ready: false, active: true))
        XCTAssertFalse(owner.active())
    }

    func testFreshEpochClearsTheWaitingForPauseBlock() {
        let owner = SessionGate(pet: false)
        XCTAssertTrue(owner.state(incoming: "e1", paired: true, online: true, ready: true, active: false))
        owner.requestStart()
        XCTAssertTrue(owner.state(incoming: "e1", paired: true, online: true, ready: false, active: true))
        owner.pause()

        // A genuinely new epoch (not the one Pause just blocked) is accepted normally.
        XCTAssertTrue(owner.state(incoming: "e2", paired: true, online: true, ready: false, active: false))
    }

    /// Drives a pet gate through the full ready/start handshake up to an active epoch.
    @discardableResult
    private func makePetActive(_ pet: SessionGate, epoch: String) -> Bool {
        pet.state(incoming: epoch, paired: true, online: true, ready: false, active: false)
        pet.requestReady()
        pet.state(incoming: epoch, paired: true, online: true, ready: true, active: false)
        return pet.state(incoming: epoch, paired: true, online: true, ready: false, active: true)
    }

    func testDisconnectClearsEpochButKeepsRecoverability() {
        let pet = SessionGate(pet: true)
        XCTAssertTrue(makePetActive(pet, epoch: "e1"))
        XCTAssertTrue(pet.active())

        pet.disconnected()
        XCTAssertFalse(pet.active())
        XCTAssertEqual(pet.epoch, "")
        XCTAssertTrue(pet.canRecover(), "An ordinary disconnect, unlike Stop, does not forfeit recovery")
    }

    func testResumeRecoversOnlyWithAFreshEpochDifferentFromTheBlockedOne() {
        let pet = SessionGate(pet: true)
        XCTAssertTrue(makePetActive(pet, epoch: "e1"))
        pet.disconnected()
        XCTAssertTrue(pet.canRecover())

        // The relay always assigns a new epoch on resume; replaying the old blocked one must not recover.
        XCTAssertFalse(pet.state(incoming: "e1", paired: true, online: true, ready: false, active: true, resumed: true))
        XCTAssertFalse(pet.active())

        XCTAssertTrue(pet.state(incoming: "e2", paired: true, online: true, ready: false, active: true, resumed: true))
        XCTAssertTrue(pet.active())
        XCTAssertEqual(pet.epoch, "e2")
    }

    func testAcceptFrameIsPetOnlyAndRequiresAStrictlyIncreasingSequence() {
        let pet = SessionGate(pet: true)
        XCTAssertTrue(makePetActive(pet, epoch: "e1"))

        XCTAssertTrue(pet.acceptFrame(incoming: "e1", nextSequence: 1))
        XCTAssertFalse(pet.acceptFrame(incoming: "e1", nextSequence: 1), "Repeats of the same sequence are rejected")
        XCTAssertTrue(pet.acceptFrame(incoming: "e1", nextSequence: 2))
        XCTAssertFalse(pet.acceptFrame(incoming: "e1", nextSequence: 0), "Sequence numbers below 1 are invalid")
        XCTAssertFalse(pet.acceptFrame(incoming: "e2", nextSequence: 3), "A frame for a different epoch is rejected")
    }

    func testAcceptFrameAlwaysRejectsForTheOwnerRole() {
        let owner = SessionGate(pet: false)
        XCTAssertTrue(owner.state(incoming: "e1", paired: true, online: true, ready: true, active: false))
        owner.requestStart()
        XCTAssertTrue(owner.state(incoming: "e1", paired: true, online: true, ready: false, active: true))
        XCTAssertFalse(owner.acceptFrame(incoming: "e1", nextSequence: 1), "Only the pet role applies incoming frames")
    }
}
