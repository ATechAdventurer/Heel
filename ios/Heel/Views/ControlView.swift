import SwiftUI

/// Owner's live control screen: touch pad, session start/stop, and per-feature readouts.
/// Pet role still uses ControlPlaceholderView until the native BLE bridge is wired up.
struct ControlView: View {
    @StateObject private var model = ControlViewModel()
    let onReturnToPick: () -> Void
    @State private var showingSettings = false
    @State private var showingResetConfirm = false
    @Environment(\.sizeCategory) private var sizeCategory
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        VStack(spacing: 0) {
            header

            sessionRow
                .padding(.horizontal, 24)
                .padding(.top, 8)

            deviceCaption
                .padding(.horizontal, 24)
                .padding(.top, 6)

            readouts
                .padding(.horizontal, 24)
                .padding(.top, 12)

            TouchPad(
                features: model.features,
                dark: colorScheme == .dark,
                version: model.touchVersion,
                onTargetsChanged: { model.touchTargetsChanged($0) },
                onStateChanged: { model.touchStateChanged() },
                onCancelRequested: { model.touchCancelled() },
                bind: { model.bindTouchModel($0) }
            )
            .frame(maxHeight: .infinity)
            .padding(.horizontal, 24)
            .padding(.top, 12)
            .accessibilityLabel(model.hold ? "Touch pad. Drag a paw to adjust. Lift to keep." : "Touch pad. Drag a paw up or down. Lift to stop.")

            holdToggle
                .padding(.horizontal, 24)
                .padding(.top, 12)

            stopButton
                .padding(.horizontal, 24)
                .padding(.top, 8)
                .padding(.bottom, 16)
        }
        .background(Color("AppBackground"))
        .onAppear { model.onAppear() }
        .onDisappear { model.onDisappear() }
        .confirmationDialog("Settings", isPresented: $showingSettings, titleVisibility: .visible) {
            Button("Reset role choice") { showingResetConfirm = true }
            Button("Close", role: .cancel) {}
        }
        .alert("Unpair and choose again?", isPresented: $showingResetConfirm) {
            Button("Keep role", role: .cancel) {}
            Button("Unpair & reset", role: .destructive) {
                model.stopAll()
                onReturnToPick()
            }
        } message: {
            Text("This stops the session and returns to Pick. This phone's setup identity is kept, so you won't need another setup code.")
        }
    }

    private var header: some View {
        HStack {
            Button {
                showingSettings = true
            } label: {
                Text("heel")
                    .font(Nunito.font(size: 25, weight: 800, relativeTo: .title2, category: sizeCategory))
                    .foregroundStyle(Color("AppAccent"))
            }
            .frame(minHeight: 44)
            .accessibilityLabel("Settings")
            Spacer()
        }
        .frame(minHeight: 48)
        .padding(.horizontal, 24)
        .padding(.top, 20)
    }

    private var sessionRow: some View {
        HStack(alignment: .center) {
            Text(model.statusText)
                .font(Nunito.font(size: 15, weight: 500, relativeTo: .subheadline, category: sizeCategory))
                .foregroundStyle(Color("AppMuted"))
                .accessibilityAddTraits(.updatesFrequently)
            Spacer()
            if !model.sessionActive {
                Button(model.sessionActionTitle) { model.action() }
                    .font(Nunito.font(size: 15, weight: 700, relativeTo: .subheadline, category: sizeCategory))
                    .foregroundStyle(Color("AppAccent"))
                    .disabled(!model.sessionActionEnabled)
                    .frame(minHeight: 44)
            }
        }
    }

    private var deviceCaption: some View {
        Text(model.deviceName.map { "\($0) · \(model.features.count) vibration control(s)" } ?? "No device connected yet.")
            .font(Nunito.font(size: 12, weight: 500, relativeTo: .footnote, category: sizeCategory))
            .foregroundStyle(Color("AppMuted"))
            .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var readouts: some View {
        HStack(spacing: 12) {
            ForEach(Array(model.features.enumerated()), id: \.offset) { index, feature in
                VStack(alignment: .leading, spacing: 2) {
                    Text("\(index + 1) · \(feature.label)")
                        .font(Nunito.font(size: 13, weight: 500, relativeTo: .footnote, category: sizeCategory))
                        .foregroundStyle(index == 0 ? Color("AppAccent") : Color("AppForeground"))
                        .lineLimit(1)
                    Text(ControlView.formatPercent(index < model.displayValues.count ? model.displayValues[index] : 0))
                        .font(Nunito.font(size: 22, weight: 800, relativeTo: .title3, category: sizeCategory))
                        .foregroundStyle(Color("AppForeground"))
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .opacity(model.features.isEmpty ? 0 : 1)
        .frame(height: model.features.isEmpty ? 0 : nil)
    }

    private var holdToggle: some View {
        Toggle(isOn: Binding(get: { model.hold }, set: { model.setHold($0) })) {
            Text("Hold on release")
                .font(Nunito.font(size: 14, weight: 500, relativeTo: .footnote, category: sizeCategory))
                .foregroundStyle(Color("AppForeground"))
        }
        .tint(Color("AppAccent"))
        .frame(minHeight: 44)
    }

    private var stopButton: some View {
        Button {
            model.stopAll()
        } label: {
            Text("Stop all")
                .font(Nunito.font(size: 15, weight: 700, relativeTo: .subheadline, category: sizeCategory))
                .foregroundStyle(.white)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 12)
                .background(RoundedRectangle(cornerRadius: 18).fill(Color.red.opacity(0.85)))
        }
        .frame(minHeight: 44)
    }

    static func formatPercent(_ value: Double) -> String {
        let rounded = (value * 100).rounded()
        return value > 0.05 && rounded == 5 ? ">5%" : "\(Int(rounded))%"
    }
}

#Preview {
    ControlView(onReturnToPick: {})
}
