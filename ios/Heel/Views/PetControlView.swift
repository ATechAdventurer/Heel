import SwiftUI

/// Pet's screen: find the device over Bluetooth, choose Ready, then follow Owner's levels.
/// Pause and the Safe Word slider are always within reach once paired.
struct PetControlView: View {
    /// Owned by SessionRuntime, not this view, so the session outlives the screen.
    @ObservedObject var model: PetControlViewModel
    let onReturnToPick: () -> Void
    @State private var showingDetails = false
    @State private var showingUnpairConfirm = false
    @Environment(\.sizeCategory) private var sizeCategory

    var body: some View {
        VStack(spacing: 0) {
            header

            ScrollView {
                VStack(spacing: 16) {
                    Image(sticker)
                        .resizable()
                        .scaledToFit()
                        .frame(height: 96)
                        .accessibilityHidden(true)

                    Text(model.statusText)
                        .font(Nunito.font(size: 15, weight: 500, relativeTo: .subheadline, category: sizeCategory))
                        .foregroundStyle(Color("AppMuted"))
                        .multilineTextAlignment(.center)
                        .accessibilityAddTraits(.updatesFrequently)
                        .padding(.horizontal, 8)

                    if model.keepsScreenAwake {
                        Text("Keep Heel open. The session pauses if you leave the app.")
                            .font(Nunito.font(size: 12, weight: 500, relativeTo: .footnote, category: sizeCategory))
                            .foregroundStyle(Color("AppMuted"))
                            .multilineTextAlignment(.center)
                    }

                    if model.sessionActive {
                        readouts
                        pauseButton
                    } else {
                        sessionButton
                    }

                    if model.recoveringDevice {
                        stopLookingButton
                    }

                    deviceList
                    scanButton
                }
                .padding(.horizontal, 24)
                .padding(.top, 8)
            }

            if model.pairedPet || model.safeWordLatched {
                SafeWordSlider(latched: model.safeWordLatched) { model.safeWord() }
                    .padding(.horizontal, 24)
                    .padding(.top, 8)
            }

            Button(action: onReturnToPick) {
                Text("Back to Pick")
                    .font(Nunito.font(size: 15, weight: 700, relativeTo: .subheadline, category: sizeCategory))
                    .foregroundStyle(Color("AppAccent"))
                    .frame(maxWidth: .infinity, minHeight: 44)
                    .background(RoundedRectangle(cornerRadius: 18).fill(Color("PairingSurface")))
            }
            .padding(.horizontal, 24)
            .padding(.top, 8)
            .padding(.bottom, 24)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color("AppBackground"))
        .confirmationDialog("Pair & device", isPresented: $showingDetails, titleVisibility: .visible) {
            Button("Unpair", role: .destructive) { showingUnpairConfirm = true }
            Button("Close", role: .cancel) {}
        } message: {
            Text("\(model.pairSummary)\n\(model.deviceSummary)")
        }
        .alert("Unpair these phones?", isPresented: $showingUnpairConfirm) {
            Button("Keep pair", role: .cancel) {}
            Button("Unpair", role: .destructive) { model.unpairAndReset() }
        } message: {
            Text("Remote control will stop. You can pair again with a new invitation.")
        }
        .alert("Couldn’t unpair", isPresented: Binding(get: { model.resetError != nil }, set: { if !$0 { model.resetError = nil } })) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(model.resetError ?? "")
        }
    }

    private var sticker: String {
        model.safeWordLatched ? "ArtemisRest" : model.sessionActive ? "ArtemisWink" : "ArtemisCurious"
    }

    private var header: some View {
        HStack {
            Button {
                model.openDetails()
                showingDetails = true
            } label: {
                Text("heel")
                    .font(Nunito.font(size: 25, weight: 800, relativeTo: .title2, category: sizeCategory))
                    .foregroundStyle(Color("AppAccent"))
            }
            .frame(minHeight: 44)
            .accessibilityLabel("Pair and device details")
            Spacer()
        }
        .frame(minHeight: 48)
        .padding(.horizontal, 24)
        .padding(.top, 20)
    }

    private func primaryButton(_ title: String, enabled: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .font(Nunito.font(size: 15, weight: 700, relativeTo: .subheadline, category: sizeCategory))
                .foregroundStyle(enabled ? .white : Color("AppAccent"))
                .frame(maxWidth: .infinity, minHeight: 44)
                .background(RoundedRectangle(cornerRadius: 18).fill(enabled ? Color("AppAccent") : Color("PairingSurface")))
        }
        .disabled(!enabled)
    }

    private var sessionButton: some View {
        primaryButton(model.actionTitle, enabled: model.actionEnabled) { model.action() }
            .accessibilityHint(model.actionEnabled ? "" : "Choose a device first, with both phones online")
    }

    /// A toy that isn't coming back shouldn't leave Pet stuck searching with no way out.
    private var stopLookingButton: some View {
        Button {
            model.stopLookingForDevice()
        } label: {
            Text("Stop looking for this toy")
                .font(Nunito.font(size: 15, weight: 700, relativeTo: .subheadline, category: sizeCategory))
                .foregroundStyle(Color("AppAccent"))
                .frame(maxWidth: .infinity, minHeight: 44)
                .background(RoundedRectangle(cornerRadius: 18).fill(Color("PairingSurface")))
        }
        .accessibilityHint("Gives up on it so you can choose a different device.")
    }

    private var pauseButton: some View {
        Button {
            model.pause(temporary: true)
        } label: {
            Text("Pause")
                .font(Nunito.font(size: 15, weight: 700, relativeTo: .subheadline, category: sizeCategory))
                .foregroundStyle(.white)
                .frame(maxWidth: .infinity, minHeight: 44)
                .background(RoundedRectangle(cornerRadius: 18).fill(Color.red.opacity(0.85)))
        }
        .accessibilityHint("Stops the toy now. You can resume afterwards.")
    }

    private var readouts: some View {
        HStack(spacing: 12) {
            ForEach(Array((model.selected?.features ?? []).enumerated()), id: \.offset) { index, feature in
                let level = index < model.levels.count ? model.levels[index] : 0
                VStack(alignment: .leading, spacing: 2) {
                    Text("\(feature.label) · \(model.hold ? "Held" : "Owner’s level")")
                        .font(Nunito.font(size: 13, weight: 500, relativeTo: .footnote, category: sizeCategory))
                        .foregroundStyle(Color("AppMuted"))
                        .lineLimit(1)
                    Text(ControlView.formatPercent(level))
                        .font(Nunito.font(size: 22, weight: 800, relativeTo: .title3, category: sizeCategory))
                        .foregroundStyle(Color("AppForeground"))
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .accessibilityElement(children: .combine)
            }
        }
        .padding(12)
        .background(RoundedRectangle(cornerRadius: 14).fill(Color("CardSurface")))
    }

    @ViewBuilder
    private var deviceList: some View {
        if !model.displayDevices.isEmpty {
            VStack(spacing: 8) {
                ForEach(model.displayDevices, id: \.id) { device in
                    let isSelected = model.selected?.id == device.id
                    Button {
                        model.select(isSelected ? nil : device)
                    } label: {
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(device.name)
                                    .font(Nunito.font(size: 15, weight: 700, relativeTo: .subheadline, category: sizeCategory))
                                    .foregroundStyle(Color("AppForeground"))
                                Text("\(device.features.count) vibration control(s)")
                                    .font(Nunito.font(size: 12, weight: 500, relativeTo: .footnote, category: sizeCategory))
                                    .foregroundStyle(Color("AppMuted"))
                            }
                            Spacer()
                            if isSelected {
                                Image(systemName: "checkmark.circle.fill")
                                    .foregroundStyle(Color("AppAccent"))
                            }
                        }
                        .padding(12)
                        .frame(minHeight: 44)
                        .background(RoundedRectangle(cornerRadius: 14).fill(Color("CardSurface")))
                    }
                    .disabled(!model.canChangeDevice)
                    .opacity(model.canChangeDevice || isSelected ? 1 : 0.5)
                    .accessibilityLabel("\(device.name), \(device.features.count) vibration controls")
                    .accessibilityAddTraits(isSelected ? [.isSelected] : [])
                    .accessibilityHint(model.canChangeDevice ? "" : "Pause first to change device")
                }
            }
        }
    }

    private var scanButton: some View {
        let enabled = model.initialized && (model.canChangeDevice || model.scanning)
        return Button {
            model.toggleScan()
        } label: {
            Text(model.scanning ? "Stop scan" : "Scan")
                .font(Nunito.font(size: 15, weight: 700, relativeTo: .subheadline, category: sizeCategory))
                .foregroundStyle(Color("AppAccent"))
                .frame(maxWidth: .infinity, minHeight: 44)
                .background(RoundedRectangle(cornerRadius: 18).strokeBorder(Color("AppAccent").opacity(enabled ? 1 : 0.35), lineWidth: 1.5))
                .opacity(enabled ? 1 : 0.5)
        }
        .disabled(!enabled)
        .accessibilityHint(enabled ? "" : model.initialized ? "Pause first to scan again" : "Waiting to pair first")
    }
}

/// A deliberate start-to-end slide. A tap anywhere, including the far end, never activates it;
/// VoiceOver gets a named action instead of a default activation. Mirrors Android's SafeWordView.
struct SafeWordSlider: View {
    let latched: Bool
    let onActivate: () -> Void
    @State private var progress: CGFloat = 0
    @Environment(\.sizeCategory) private var sizeCategory

    private static let track = Color(red: 92 / 255, green: 28 / 255, blue: 49 / 255)
    private static let knob = Color(red: 249 / 255, green: 214 / 255, blue: 226 / 255)
    private let radius: CGFloat = 24
    private let inset: CGFloat = 32

    var body: some View {
        GeometryReader { geometry in
            let travel = max(1, geometry.size.width - inset * 2)
            ZStack(alignment: .leading) {
                Capsule().fill(Self.track)
                    .padding(.vertical, 4)
                Text(label)
                    .font(Nunito.font(size: 15, weight: 700, relativeTo: .subheadline, category: sizeCategory))
                    .foregroundStyle(.white)
                    .frame(maxWidth: .infinity)
                    .offset(x: latched ? 0 : 12)
                if !latched {
                    Circle().fill(Self.knob)
                        .frame(width: radius * 2, height: radius * 2)
                        .overlay(Image(systemName: "chevron.right").font(.system(size: 16, weight: .bold)).foregroundStyle(Self.track))
                        .offset(x: inset - radius + progress * travel)
                        .gesture(DragGesture(minimumDistance: 0)
                            .onChanged { value in
                                // Straying well off the track cancels rather than completes.
                                progress = abs(value.translation.height) <= 48
                                    ? min(1, max(0, value.translation.width / travel)) : 0
                            }
                            .onEnded { _ in
                                let activate = progress >= 0.9
                                progress = 0
                                if activate { onActivate() }
                            })
                }
            }
        }
        .frame(height: 64)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Safe Word")
        .accessibilityValue(latched ? "Activated. Session stopped." : "Slide from left to right to stop and pause the session.")
        .accessibilityAction(named: "Activate Safe Word and stop the session") {
            if !latched { onActivate() }
        }
        .onChange(of: latched) { _ in progress = 0 }
    }

    private var label: String {
        latched ? "Safe Word · stopped" : progress > 0.85 ? "Release to stop" : "Slide for Safe Word"
    }
}

#Preview {
    PetControlView(model: PetControlViewModel(), onReturnToPick: {})
}
