import SwiftUI

@main
struct HeelApp: App {
    @State private var currentRole: Role? = RolePreferences.get()

    var body: some Scene {
        WindowGroup {
            content
        }
    }

    @ViewBuilder
    private var content: some View {
        if let activeRole = currentRole {
            PairingPlaceholderView(role: activeRole, onResetRole: {
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
