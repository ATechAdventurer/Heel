import Foundation

/// The one app-wide owner of the live session, mirroring Android's ControlRuntime. Screens
/// observe its models; they don't own them, so navigating, presenting sheets or re-rendering
/// a view can't drop a session or leave a device running.
///
/// Exactly one role is active at a time. Activating a role makes its model the relay's
/// controller; changing or leaving the role stops that session first. Anything else that needs
/// to reach the session later (a watch app, Shortcuts, a widget) goes through here too.
@MainActor
final class SessionRuntime {
    static let shared = SessionRuntime()

    private let makeOwner: @MainActor () -> ControlViewModel
    private let makePet: @MainActor () -> PetControlViewModel

    /// Built on first use so a Pet-only phone never creates Owner's haptics engine, and vice versa.
    private(set) lazy var owner: ControlViewModel = makeOwner()
    private(set) lazy var pet: PetControlViewModel = makePet()

    private(set) var role: Role?

    init(owner: @escaping @MainActor () -> ControlViewModel = { ControlViewModel() },
         pet: @escaping @MainActor () -> PetControlViewModel = { PetControlViewModel() }) {
        makeOwner = owner
        makePet = pet
    }

    /// Makes `role` the live session. Asking for the role that's already active only refreshes
    /// where it returns to, so a re-rendered screen can't restart or interrupt it.
    func activate(_ role: Role, onReturnToPick: @escaping () -> Void) {
        if self.role == role {
            switch role {
            case .owner: owner.onReturnToPick = onReturnToPick
            case .pet: pet.onReturnToPick = onReturnToPick
            }
            return
        }
        deactivate()
        self.role = role
        switch role {
        case .owner: owner.activate(onReturnToPick: onReturnToPick)
        case .pet: pet.activate(onReturnToPick: onReturnToPick)
        }
    }

    /// Ends whichever session is live. Safe to call when none is.
    func deactivate() {
        guard let active = role else { return }
        role = nil
        switch active {
        case .owner: owner.deactivate()
        case .pet: pet.deactivate()
        }
    }
}
