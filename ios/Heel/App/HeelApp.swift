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
    private let runtime = SessionRuntime.shared
    @Environment(\.scenePhase) private var scenePhase

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
            // .inactive (Control Center, a notification peek) is not leaving; .background is.
            .onChange(of: scenePhase) { phase in
                if phase == .background { runtime.appDidEnterBackground() }
            }
            // Leaving the paired screen by any route (Back to Pick, an edge swipe, unpairing)
            // ends the live session; staying inside it never does.
            .onChange(of: path) { path in
                if !path.contains(where: { if case .paired = $0 { return true } else { return false } }) {
                    runtime.deactivate()
                }
            }
        }
    }

    private func returnToPick() {
        RolePreferences.reset()
        path.removeAll()
    }

    @ViewBuilder
    private func destination(for route: AppRoute) -> some View {
        switch route {
        case .pairing(let role):
            PairingView(role: role, onPaired: { paired in
                RolePreferences.choose(paired)
                if !path.isEmpty { path[path.count - 1] = .paired(paired) }
                else { path.append(.paired(paired)) }
            }, onReturnToPick: { returnToPick() })
        case .paired(let role):
            Group {
                if role == .owner {
                    ControlView(model: runtime.owner)
                } else {
                    PetControlView(model: runtime.pet)
                }
            }
            .onAppear {
                runtime.activate(role, onReturnToPick: { returnToPick() },
                                 // The pair is gone but this phone keeps its role: pair again.
                                 onUnpaired: { path = [.pairing(role)] })
            }
        }
    }
}
