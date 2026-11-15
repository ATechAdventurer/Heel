import XCTest
@testable import Heel

@MainActor
final class PairingViewModelTests: XCTestCase {
    private func model() -> PairingViewModel {
        let defaults = UserDefaults(suiteName: "PairingViewModelTests.\(UUID().uuidString)")!
        return PairingViewModel(role: .owner, relay: RelayClient(store: CredentialStore(defaults: defaults)))
    }

    private func isInviting(_ phase: PairingPhase) -> Bool {
        if case .inviting = phase { return true } else { return false }
    }

    private func isApproval(_ phase: PairingPhase) -> Bool {
        if case .approval = phase { return true } else { return false }
    }

    private func isUnpaired(_ phase: PairingPhase) -> Bool {
        if case .unpaired = phase { return true } else { return false }
    }

    func testRelayMessagesTakeEffectBeforeTheCallReturns() {
        let model = model()
        model.relayReceived(["type": "invite", "code": "ABCDE-FGHIJ"])
        XCTAssertTrue(isInviting(model.phase))
        XCTAssertEqual(model.invite, "ABCDE-FGHIJ")
    }

    func testConnectionReasonUpdatesImmediately() {
        let model = model()
        model.relayConnectionChanged(connected: false, reason: "Connection lost")
        XCTAssertEqual(model.connectionReason, "Connection lost")
    }

    func testMessagesAreHandledInTheOrderTheRelaySentThem() {
        let first = model()
        first.relayReceived(["type": "state", "phase": "approval"])
        first.relayReceived(["type": "state", "phase": "unpaired"])
        XCTAssertTrue(isUnpaired(first.phase))

        let second = model()
        second.relayReceived(["type": "state", "phase": "unpaired"])
        second.relayReceived(["type": "state", "phase": "approval"])
        XCTAssertTrue(isApproval(second.phase))
    }
}
