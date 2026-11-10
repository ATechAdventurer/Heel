import SwiftUI

/// Owner's live control screen: touch pad, session start/stop, and per-feature readouts.
/// Pet role uses PetControlView instead.
struct ControlView: View {
    @StateObject private var model = ControlViewModel()
    let onReturnToPick: () -> Void
    @State private var showingSettings = false
    @State private var showingResetConfirm = false
    @State private var showingSpellbook = false
    @Environment(\.sizeCategory) private var sizeCategory
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.accessibilityVoiceOverEnabled) private var voiceOver
    @Environment(\.accessibilitySwitchControlEnabled) private var switchControl

    /// Neither can drag a paw across the touch pad, so each feature gets a slider instead.
    private var needsSliders: Bool { voiceOver || switchControl }

    var body: some View {
        VStack(spacing: 0) {
            header

            sessionRow
                .padding(.horizontal, 24)
                .padding(.top, 8)

            deviceCaption
                .padding(.horizontal, 24)
                .padding(.top, 6)

            creativeCaption
                .padding(.horizontal, 24)
                .padding(.top, 4)

            readouts
                .padding(.horizontal, 24)
                .padding(.top, 12)

            if model.sliderMode {
                sliderPanel
                    .padding(.horizontal, 24)
                    .padding(.top, 12)
            }

            // Stays mounted in slider mode: the pad owns the TouchModel the view model mirrors into.
            TouchPad(
                features: model.features,
                dark: colorScheme == .dark,
                version: model.touchVersion,
                onTargetsChanged: { model.touchTargetsChanged($0) },
                onStateChanged: { model.touchStateChanged() },
                onCancelRequested: { model.touchCancelled() },
                bind: { model.bindTouchModel($0) }
            )
            .frame(maxHeight: model.sliderMode ? 0 : .infinity)
            .opacity(model.sliderMode ? 0 : 1)
            .allowsHitTesting(!model.sliderMode)
            .accessibilityHidden(model.sliderMode)
            .padding(.horizontal, 24)
            .padding(.top, model.sliderMode ? 0 : 12)
            .accessibilityLabel(model.hold ? "Touch pad. Drag a paw to adjust. Lift to keep." : "Touch pad. Drag a paw up or down. Lift to stop.")

            if !model.sliderMode {
                holdToggle
                    .padding(.horizontal, 24)
                    .padding(.top, 12)
            }

            hapticsToggle
                .padding(.horizontal, 24)
                .padding(.top, 4)

            stopButton
                .padding(.horizontal, 24)
                .padding(.top, 8)
                .padding(.bottom, 16)
        }
        .background(Color("AppBackground"))
        .onAppear {
            model.onReturnToPick = onReturnToPick
            model.onAppear()
            model.setSliderMode(needsSliders)
        }
        .onChange(of: needsSliders) { model.setSliderMode($0) }
        .onDisappear { model.onDisappear() }
        .confirmationDialog("Settings", isPresented: $showingSettings, titleVisibility: .visible) {
            Button("Reset role choice") { showingResetConfirm = true }
            Button("Close", role: .cancel) {}
        }
        .alert("Unpair and choose again?", isPresented: $showingResetConfirm) {
            Button("Keep role", role: .cancel) {}
            Button("Unpair & reset", role: .destructive) { model.unpairAndReset() }
        } message: {
            Text("This stops the session and returns to Pick. This phone's setup identity is kept, so you won't need another setup code.")
        }
        .alert("Couldn’t unpair", isPresented: Binding(get: { model.resetError != nil }, set: { if !$0 { model.resetError = nil } })) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(model.resetError ?? "")
        }
        .alert("Spellbook", isPresented: Binding(get: { model.spellError != nil }, set: { if !$0 { model.spellError = nil } })) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(model.spellError ?? "")
        }
        .sheet(isPresented: $showingSpellbook) {
            SpellbookView(model: model)
        }
        .sheet(isPresented: Binding(get: { model.pendingSpellDraft }, set: { if !$0 { model.discardDraft() } })) {
            SaveSpellSheet(model: model)
        }
    }

    private func tapSpellbook() {
        if model.isPlayingSpell { model.stopPlayback() }
        else if model.isRecording { model.finishRecording() }
        else { showingSpellbook = true }
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
            Button(action: tapSpellbook) {
                Text(model.isPlayingSpell ? "Stop spell" : model.isRecording ? "Finish recording" : "Spellbook")
                    .font(Nunito.font(size: 15, weight: 700, relativeTo: .subheadline, category: sizeCategory))
                    .foregroundStyle(Color("AppAccent"))
            }
            .frame(minHeight: 44)
        }
        .frame(minHeight: 48)
        .padding(.horizontal, 24)
        .padding(.top, 20)
    }

    @ViewBuilder
    private var creativeCaption: some View {
        if let name = model.playingSpellName {
            Text("Playing \u{201c}\(name)\u{201d}\u{2026} touch the pad to take over.")
                .font(Nunito.font(size: 12, weight: 500, relativeTo: .footnote, category: sizeCategory))
                .foregroundStyle(Color("AppMuted"))
                .frame(maxWidth: .infinity, alignment: .leading)
        } else if model.isRecording {
            Text("Recording your gesture\u{2026}")
                .font(Nunito.font(size: 12, weight: 500, relativeTo: .footnote, category: sizeCategory))
                .foregroundStyle(Color("AppMuted"))
                .frame(maxWidth: .infinity, alignment: .leading)
        }
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

    private var sliderPanel: some View {
        ScrollView {
            VStack(spacing: 16) {
                if model.features.isEmpty {
                    Text("No vibration controls yet.")
                        .font(Nunito.font(size: 14, weight: 500, relativeTo: .footnote, category: sizeCategory))
                        .foregroundStyle(Color("AppMuted"))
                }
                ForEach(Array(model.features.enumerated()), id: \.offset) { index, feature in
                    sliderRow(index, feature)
                }
                Text("Choose a level, then Start. Adjustments are live while running.")
                    .font(Nunito.font(size: 12, weight: 500, relativeTo: .footnote, category: sizeCategory))
                    .foregroundStyle(Color("AppMuted"))
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .frame(maxHeight: .infinity)
    }

    private func sliderRow(_ index: Int, _ feature: TouchModel.Feature) -> some View {
        let level = model.sliderLevels.indices.contains(index) ? model.sliderLevels[index] : 0
        let running = model.sliderRunning.indices.contains(index) && model.sliderRunning[index]
        let controllable = model.canControl
        return VStack(alignment: .leading, spacing: 8) {
            Text("\(index + 1) · \(feature.label)")
                .font(Nunito.font(size: 15, weight: 700, relativeTo: .subheadline, category: sizeCategory))
                .foregroundStyle(index == 0 ? Color("AppAccent") : Color("AppForeground"))
            Text("Level: \(ControlView.formatPercent(level)) · \(running ? "Running" : "Rest")")
                .font(Nunito.font(size: 13, weight: 500, relativeTo: .footnote, category: sizeCategory))
                .foregroundStyle(Color("AppMuted"))
            Slider(value: Binding(get: { level }, set: { model.setSliderLevel(index, $0) }),
                   in: 0...1, step: ControlViewModel.sliderStep(for: feature))
                .tint(Color("AppAccent"))
                .frame(minHeight: 44)
                .disabled(!controllable)
                .accessibilityLabel("\(feature.label) level")
                .accessibilityValue("\(Int((level * 100).rounded())) percent, \(running ? "running" : "stopped")")
            HStack(spacing: 12) {
                sliderButton("Start \(index + 1)", label: "Start \(feature.label)", filled: true,
                             enabled: controllable && !running) { model.startSlider(index) }
                sliderButton("Stop \(index + 1)", label: "Stop \(feature.label)", filled: false,
                             enabled: controllable && running) { model.stopSlider(index) }
            }
        }
        .padding(12)
        .background(RoundedRectangle(cornerRadius: 14).fill(Color("CardSurface")))
    }

    private func sliderButton(_ title: String, label: String, filled: Bool, enabled: Bool,
                              action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .font(Nunito.font(size: 15, weight: 700, relativeTo: .subheadline, category: sizeCategory))
                .foregroundStyle(filled ? .white : Color("AppAccent"))
                .frame(maxWidth: .infinity, minHeight: 44)
                .background(RoundedRectangle(cornerRadius: 18)
                    .fill(filled ? Color("AppAccent") : Color("PairingSurface")))
                .opacity(enabled ? 1 : 0.4)
        }
        .disabled(!enabled)
        .accessibilityLabel(label)
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

    private var hapticsToggle: some View {
        Toggle(isOn: Binding(get: { model.hapticsEnabled }, set: { model.setHapticsEnabled($0) })) {
            Text("Feel it on this phone")
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
