import SwiftUI

/// A personal role chooser; pairing and permission are handled explicitly after the choice.
struct WelcomeView: View {
    let onChoose: (Role) -> Void
    @State private var showingSettings = false

    var body: some View {
        ScrollView {
            VStack(spacing: 0) {
                HStack {
                    Text("heel")
                        .font(Nunito.font(size: 25, weight: 800))
                        .foregroundStyle(Color("AppAccent"))
                    Spacer()
                }
                .frame(minHeight: 48)
                .contentShape(Rectangle())
                .onTapGesture { showingSettings = true }
                .accessibilityLabel("Settings")

                Text("Pick")
                    .font(Nunito.font(size: 38, weight: 800))
                    .foregroundStyle(Color("AppForeground"))
                    .frame(maxWidth: .infinity)
                    .padding(.top, 44)
                    .accessibilityAddTraits(.isHeader)

                HStack(spacing: 12) {
                    RoleCard(image: "ArtemisOwner", halo: "OwnerHalo", title: "Owner",
                             hint: "Lead the way", rotation: -5, action: { onChoose(.owner) })
                    RoleCard(image: "ArtemisCurious", halo: "PetHalo", title: "Pet",
                             hint: "Come closer", rotation: 6, action: { onChoose(.pet) })
                }
                .padding(.top, 64)
            }
            .padding(24)
            .frame(maxWidth: .infinity)
        }
        .background(Color("AppBackground"))
        .alert("Settings", isPresented: $showingSettings) {
            Button("Reset role choice") { RolePreferences.reset() }
            Button("Close", role: .cancel) {}
        } message: {
            Text("Pick Owner or Pet once. Heel remembers your choice. You can reset it here after pairing.")
        }
    }
}

private struct RoleCard: View {
    let image: String
    let halo: String
    let title: String
    let hint: String
    let rotation: Double
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: 0) {
                ZStack(alignment: .bottom) {
                    Ellipse()
                        .fill(Color(halo))
                        .frame(height: 108)
                        .padding(.horizontal, 9)
                        .padding(.bottom, 6)
                    Image(image)
                        .resizable()
                        .scaledToFit()
                        .rotationEffect(.degrees(rotation))
                        .accessibilityHidden(true)
                }
                .frame(height: 142)

                Text(title)
                    .font(Nunito.font(size: 25, weight: 800))
                    .foregroundStyle(Color("AppForeground"))
                    .padding(.top, 14)

                Text(hint)
                    .font(Nunito.font(size: 13, weight: 500))
                    .foregroundStyle(Color("AppMuted"))
                    .padding(.top, 5)
            }
            .padding(.horizontal, 2)
            .padding(.top, 8)
            .padding(.bottom, 16)
            .frame(maxWidth: .infinity)
            .background(RoundedRectangle(cornerRadius: 24).fill(.white))
        }
        .buttonStyle(PressableCardStyle())
        .accessibilityLabel("\(title). \(hint)")
    }
}

/// Stands in for Android's ripple: a subtle press scale/fade instead of a material ripple.
private struct PressableCardStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.97 : 1)
            .opacity(configuration.isPressed ? 0.85 : 1)
            .animation(.easeOut(duration: 0.12), value: configuration.isPressed)
    }
}

#Preview {
    WelcomeView(onChoose: { _ in })
}
