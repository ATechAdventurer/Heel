import SwiftUI

/// Each screen draws its own "heel" wordmark header, so the system navigation bar stays
/// hidden throughout; NavigationStack is still what gives us the real push transition
/// and the edge-swipe-back gesture, which neither depends on the bar being visible.
enum AppRoute: Hashable {
    case pairing(Role)
    case paired(Role)
}

@main
struct HeelApp: App {
    @State private var path: [AppRoute]

    init() {
        if let role = RolePreferences.get() {
            _path = State(initialValue: [.pairing(role)])
        } else {
            _path = State(initialValue: [])
        }
    }

    var body: some Scene {
        WindowGroup {
            NavigationStack(path: $path) {
                WelcomeView(onChoose: { chosen in
                    RolePreferences.choose(chosen)
                    path.append(.pairing(chosen))
                })
                .toolbar(.hidden, for: .navigationBar)
                .navigationDestination(for: AppRoute.self) { route in
                    destination(for: route)
                        .toolbar(.hidden, for: .navigationBar)
                }
            }
        }
    }

    @ViewBuilder
    private func destination(for route: AppRoute) -> some View {
        switch route {
        case .pairing(let role):
            PairingView(role: role, onPaired: { paired in
                RolePreferences.choose(paired)
                if !path.isEmpty { path[path.count - 1] = .paired(paired) }
                else { path.append(.paired(paired)) }
            }, onReturnToPick: {
                RolePreferences.reset()
                path.removeAll()
            })
        case .paired(let role):
            if role == .owner {
                ControlView(onReturnToPick: {
                    RolePreferences.reset()
                    path.removeAll()
                })
            } else {
                PetControlView(onReturnToPick: {
                    RolePreferences.reset()
                    path.removeAll()
                })
            }
        }
    }
}
