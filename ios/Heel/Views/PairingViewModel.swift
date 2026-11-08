import Foundation
import UIKit

enum PairingPhase {
    case unpaired
    case inviting
    case preview(profile: JSONDictionary)
    case approval(message: JSONDictionary)
}

/// Personal invitation flow. No account, contact permission, or automatic message sending.
@MainActor
final class PairingViewModel: NSObject, ObservableObject {
    let role: Role
    private let relay: RelayClient
    var onPaired: ((Role) -> Void)?
    var onReturnToPick: (() -> Void)?

    @Published private(set) var phase: PairingPhase = .unpaired
    @Published var connectionReason = "Connecting…"
    @Published var invite = ""
    @Published var typedCode = ""
    @Published var ownerName = ""
    @Published var petName = ""
    @Published var species = "puppy"
    @Published var enrollmentCode = ""

    private var resettingRole = false
    private var resumed = false
    private var launched = false
    private var phaseName = "unpaired"

    init(role: Role, relay: RelayClient = .shared) {
        self.role = role
        self.relay = relay
    }

    func onAppear() {
        resumed = true
        launched = false
        relay.attach(self)
        if relay.hasToken { relay.connect(enrollment: nil) }
    }

    func onDisappear() {
        resumed = false
        relay.detach(self)
    }

    var endpoint: String { relay.endpoint }
    var hasToken: Bool { relay.hasToken }

    func connectThisPhone() {
        relay.connect(enrollment: enrollmentCode)
    }

    func reconnect() {
        relay.connect(enrollment: nil)
    }

    func makeInvitation() {
        guard !ownerName.trimmingCharacters(in: .whitespaces).isEmpty,
              !petName.trimmingCharacters(in: .whitespaces).isEmpty,
              !species.trimmingCharacters(in: .whitespaces).isEmpty
        else {
            connectionReason = "Fill in the names and their kind first."
            return
        }
        if !relay.send(RelayClient.json("invite", ["owner": ownerName, "pet": petName, "species": species])) {
            connectionReason = "Connect this phone first."
        }
    }

    func findMyOwner() {
        if !relay.send(RelayClient.json("preview", ["code": typedCode])) {
            connectionReason = "Connect this phone first."
        }
    }

    func copyInviteCode() {
        UIPasteboard.general.string = invite
        connectionReason = "Copied · send it privately"
    }

    func cancelInvitation() {
        _ = relay.send(RelayClient.json("unpair"))
    }

    func acceptPreview() {
        _ = relay.send(RelayClient.json("accept", ["code": typedCode]))
    }

    func goBackFromPreview() {
        phase = .unpaired
    }

    func approvePairing() {
        _ = relay.send(RelayClient.json("approve"))
    }

    func cancelApproval() {
        _ = relay.send(RelayClient.json("unpair"))
    }

    func resetRole() {
        if !relay.hasToken || (relay.connected && relay.state.optString("phase") == "unpaired") {
            relay.pause()
            onReturnToPick?()
            return
        }
        guard relay.connected else {
            connectionReason = "Reconnect first so Heel can unpair before changing roles."
            return
        }
        resettingRole = true
        relay.pause()
        if !relay.send(RelayClient.json("unpair")) {
            resettingRole = false
            connectionReason = "Reconnect to reset the role."
        }
    }

    func saveRelayAddress(_ value: String) {
        do {
            try relay.setEndpoint(value.trimmingCharacters(in: .whitespaces))
            invite = ""
            enrollmentCode = ""
        } catch {
            connectionReason = error.localizedDescription
        }
    }
}

extension PairingViewModel: RelayListener {
    nonisolated func relayConnectionChanged(connected: Bool, reason: String) {
        Task { @MainActor in self.connectionReason = reason }
    }

    nonisolated func relayReceived(_ message: JSONDictionary) {
        Task { @MainActor in self.handle(message) }
    }

    private func handle(_ message: JSONDictionary) {
        let type = message.optString("type")
        if type == "error" {
            connectionReason = message.optString("message", "Could not complete that action.")
            return
        }
        if type == "invite" {
            invite = message.optString("code")
            phaseName = "inviting"
            phase = .inviting
            return
        }
        if type == "preview" {
            guard let profile = message.optDictionary("profile") else { return }
            phaseName = "preview"
            phase = .preview(profile: profile)
            return
        }
        guard type == "state" else { return }
        let next = message.optString("phase")
        if resettingRole, next == "unpaired" {
            resettingRole = false
            onReturnToPick?()
            return
        }
        if next == "paired" {
            if resumed, !launched {
                launched = true
                let role = Role(rawValue: message.optString("role")) ?? self.role
                onPaired?(role)
            }
        } else if next == "approval" {
            phaseName = "approval"
            phase = .approval(message: message)
        } else if next == "inviting" {
            if phaseName != "inviting" {
                invite = message.optString("code")
                phaseName = "inviting"
                phase = .inviting
            }
        } else if phaseName != "unpaired" || (!enrollmentCode.isEmpty && relay.hasToken) {
            enrollmentCode = ""
            phaseName = "unpaired"
            phase = .unpaired
        }
    }
}
