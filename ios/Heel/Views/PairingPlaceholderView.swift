import SwiftUI

/// Stands in for the real pairing flow (relay connection, invitations, approval) until
/// that's ported. Honest about what it is rather than pretending to be finished.
struct PairingPlaceholderView: View {
    let role: Role
    let onResetRole: () -> Void

    var body: some View {
        VStack(spacing: 16) {
            HStack {
                Text("heel")
                    .font(Nunito.font(size: 25, weight: 800))
                    .foregroundStyle(Color("AppAccent"))
                Spacer()
            }
            .frame(minHeight: 48)

            Spacer()

            Image(role == .owner ? "ArtemisOwner" : "ArtemisCurious")
                .resizable()
                .scaledToFit()
                .frame(height: 132)

            Text(role == .owner ? "Owner" : "Pet")
                .font(Nunito.font(size: 28, weight: 800))
                .foregroundStyle(Color("AppForeground"))

            Text("Pairing isn't built yet — this is just confirming the role picker works.")
                .font(Nunito.font(size: 14, weight: 500))
                .foregroundStyle(Color("AppMuted"))
                .multilineTextAlignment(.center)
                .padding(.horizontal, 32)

            Spacer()

            Button("Change role", action: onResetRole)
                .font(Nunito.font(size: 15, weight: 700))
                .foregroundStyle(Color("AppAccent"))
                .padding(.bottom, 24)
        }
        .padding(.horizontal, 24)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color("AppBackground"))
    }
}

#Preview {
    PairingPlaceholderView(role: .owner, onResetRole: {})
}
