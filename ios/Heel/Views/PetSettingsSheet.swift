import SwiftUI

/// Pet's Settings, opened from the logo like Android's: the pair and device at a glance, plus
/// Unpair (pair again in the same role) and Reset role choice (back to Pick). Each asks first.
/// Relay address lives on the Pairing screen, as on Android, so changing it means unpairing first.
struct PetSettingsSheet: View {
    @ObservedObject var model: PetControlViewModel
    @Environment(\.dismiss) private var dismiss
    @Environment(\.sizeCategory) private var sizeCategory
    @State private var confirming: Confirmation?

    enum Confirmation: Identifiable {
        case unpair, resetRole
        var id: Self { self }

        var title: String {
            switch self {
            case .unpair: return "Unpair these phones?"
            case .resetRole: return "Unpair and choose again?"
            }
        }
        var message: String {
            switch self {
            case .unpair: return "Remote control will stop. You can pair again with a new invitation."
            case .resetRole: return "This stops the pair and returns to Pick. This phone’s setup identity is kept, so you won’t need another setup code."
            }
        }
        var keep: String { self == .unpair ? "Keep pair" : "Keep role" }
        var confirm: String { self == .unpair ? "Unpair" : "Unpair & reset" }
    }

    var body: some View {
        VStack(spacing: 16) {
            HStack {
                Text("Settings")
                    .font(Nunito.font(size: 22, weight: 800, relativeTo: .title3, category: sizeCategory))
                    .foregroundStyle(Color("AppForeground"))
                    .accessibilityAddTraits(.isHeader)
                Spacer()
                Button("Close") { dismiss() }
                    .font(Nunito.font(size: 15, weight: 700, relativeTo: .subheadline, category: sizeCategory))
                    .foregroundStyle(Color("AppAccent"))
                    .frame(minHeight: 44)
            }

            VStack(alignment: .leading, spacing: 4) {
                Text("Pair & device")
                    .font(Nunito.font(size: 13, weight: 500, relativeTo: .footnote, category: sizeCategory))
                    .foregroundStyle(Color("AppMuted"))
                Text(model.pairSummary)
                    .font(Nunito.font(size: 17, weight: 700, relativeTo: .body, category: sizeCategory))
                    .foregroundStyle(Color("AppForeground"))
                Text(model.deviceSummary)
                    .font(Nunito.font(size: 15, weight: 500, relativeTo: .subheadline, category: sizeCategory))
                    .foregroundStyle(Color("AppForeground"))
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(12)
            .background(RoundedRectangle(cornerRadius: 14).fill(Color("CardSurface")))
            .accessibilityElement(children: .combine)

            if let error = model.resetError {
                Text(error)
                    .font(Nunito.font(size: 13, weight: 500, relativeTo: .footnote, category: sizeCategory))
                    .foregroundStyle(Color.red)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .accessibilityAddTraits(.updatesFrequently)
            }

            actionButton("Unpair", hint: "Ends this pair. You can pair again as Pet.") { ask(.unpair) }
            actionButton("Reset role choice", hint: "Ends this pair and returns to choosing Owner or Pet.") { ask(.resetRole) }

            Spacer()
        }
        .padding(24)
        .background(Color("AppBackground").ignoresSafeArea())
        .presentationDetents([.medium, .large])
        .alert(item: $confirming) { item in
            Alert(title: Text(item.title), message: Text(item.message),
                  primaryButton: .cancel(Text(item.keep)),
                  secondaryButton: .destructive(Text(item.confirm)) {
                      switch item {
                      case .unpair: model.unpair()
                      case .resetRole: model.unpairAndReset()
                      }
                  })
        }
    }

    private func ask(_ confirmation: Confirmation) {
        model.resetError = nil
        confirming = confirmation
    }

    private func actionButton(_ title: String, hint: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .font(Nunito.font(size: 15, weight: 700, relativeTo: .subheadline, category: sizeCategory))
                .foregroundStyle(Color("AppAccent"))
                .frame(maxWidth: .infinity, minHeight: 44)
                .background(RoundedRectangle(cornerRadius: 18).fill(Color("PairingSurface")))
        }
        .accessibilityHint(hint)
    }
}

#Preview {
    PetSettingsSheet(model: PetControlViewModel())
}
