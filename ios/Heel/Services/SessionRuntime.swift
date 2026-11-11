import Combine
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

    private let screen: ScreenWake
    private var observation: AnyCancellable?
    private var keepingAwake = false

    init(owner: @escaping @MainActor () -> ControlViewModel = { ControlViewModel() },
         pet: @escaping @MainActor () -> PetControlViewModel = { PetControlViewModel() },
         screen: ScreenWake? = nil) {
        makeOwner = owner
        makePet = pet
        self.screen = screen ?? UIKitScreenWake()
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
        // Watch the active model so the screen stays awake exactly while a session needs it.
        // objectWillChange fires before the change lands, so look at it on the next turn.
        switch role {
        case .owner:
            observation = owner.objectWillChange.receive(on: DispatchQueue.main)
                .sink { [weak self] _ in self?.refreshScreenWake() }
            owner.activate(onReturnToPick: onReturnToPick)
        case .pet:
            observation = pet.objectWillChange.receive(on: DispatchQueue.main)
                .sink { [weak self] _ in self?.refreshScreenWake() }
            pet.activate(onReturnToPick: onReturnToPick)
        }
        refreshScreenWake()
    }

    /// Ends whichever session is live. Safe to call when none is.
    func deactivate() {
        guard let active = role else { return }
        role = nil
        observation = nil
        switch active {
        case .owner: owner.deactivate()
        case .pet: pet.deactivate()
        }
        refreshScreenWake()
    }

    /// Called when the app moves to the background. A live session pauses instead of being
    /// left to time out when iOS suspends the app.
    func appDidEnterBackground() {
        switch role {
        case .owner: owner.appDidEnterBackground()
        case .pet: pet.appDidEnterBackground()
        case nil: break
        }
        refreshScreenWake()
    }

    /// Only touches the display setting when the answer actually changes.
    func refreshScreenWake() {
        let wanted: Bool
        switch role {
        case .owner: wanted = owner.keepsScreenAwake
        case .pet: wanted = pet.keepsScreenAwake
        case nil: wanted = false
        }
        guard wanted != keepingAwake else { return }
        keepingAwake = wanted
        screen.setKeepAwake(wanted)
    }
}
