import SwiftUI

/// Stands in for the real control screen (touch pad, safe word, spellbook) until that's
/// ported. Honest about what it is rather than pretending to be finished.
struct ControlPlaceholderView: View {
    let role: Role
    let onReturnToPick: () -> Void
    @Environment(\.sizeCategory) private var sizeCategory

    var body: some View {
        VStack(spacing: 16) {
            HStack {
                Text("heel")
                    .font(Nunito.font(size: 25, weight: 800, relativeTo: .title2, category: sizeCategory))
                    .foregroundStyle(Color("AppAccent"))
                Spacer()
            }
            .frame(minHeight: 48)

            Spacer()

            Image(role == .owner ? "ArtemisOwner" : "ArtemisCurious")
                .resizable()
                .scaledToFit()
                .frame(height: 132)

            Text("Paired!")
                .font(Nunito.font(size: 28, weight: 800, relativeTo: .title, category: sizeCategory))
                .foregroundStyle(Color("AppForeground"))

            Text("The control screen (touch pad, safe word, spellbook) isn't built yet — this just confirms pairing finished.")
                .font(Nunito.font(size: 14, weight: 500, relativeTo: .footnote, category: sizeCategory))
                .foregroundStyle(Color("AppMuted"))
                .multilineTextAlignment(.center)
                .padding(.horizontal, 32)

            Spacer()

            Button(action: onReturnToPick) {
                Text("Back to Pick")
                    .font(Nunito.font(size: 15, weight: 700, relativeTo: .subheadline, category: sizeCategory))
                    .foregroundStyle(Color("AppAccent"))
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 12)
                    .background(RoundedRectangle(cornerRadius: 18).fill(Color("PairingSurface")))
            }
            .padding(.bottom, 24)
        }
        .padding(.horizontal, 24)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color("AppBackground"))
    }
}

#Preview {
    ControlPlaceholderView(role: .owner, onReturnToPick: {})
}
