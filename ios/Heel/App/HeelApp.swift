import SwiftUI

@main
struct HeelApp: App {
    @State private var currentRole: Role? = RolePreferences.get()
    @State private var pairedRole: Role?

    var body: some Scene {
        WindowGroup {
            content
        }
    }

    @ViewBuilder
    private var content: some View {
        if let activeRole = pairedRole {
            ControlPlaceholderView(role: activeRole, onReturnToPick: {
                pairedRole = nil
                currentRole = nil
            })
        } else if let activeRole = currentRole {
            PairingView(role: activeRole, onPaired: { paired in
                RolePreferences.choose(paired)
                pairedRole = paired
            }, onReturnToPick: {
                RolePreferences.reset()
                currentRole = nil
            })
        } else {
            WelcomeView(onChoose: { chosen in
                RolePreferences.choose(chosen)
                currentRole = chosen
            })
        }
    }
}
