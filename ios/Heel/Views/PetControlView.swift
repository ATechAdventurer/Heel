import SwiftUI

/// Pet's screen: find the paired device over Bluetooth and hand it to Owner. Applying Owner's
/// live frames to the device isn't wired up yet, so this only covers discovery and selection.
struct PetControlView: View {
    @StateObject private var model = PetControlViewModel()
    let onReturnToPick: () -> Void
    @Environment(\.sizeCategory) private var sizeCategory

    var body: some View {
        VStack(spacing: 16) {
            header

            Image("ArtemisCurious")
                .resizable()
                .scaledToFit()
                .frame(height: 96)

            statusText
                .font(Nunito.font(size: 15, weight: 500, relativeTo: .subheadline, category: sizeCategory))
                .foregroundStyle(Color("AppMuted"))
                .multilineTextAlignment(.center)
                .padding(.horizontal, 32)

            deviceList

            scanButton
                .disabled(!model.ready)
                .padding(.horizontal, 24)

            Spacer()

            Button(action: onReturnToPick) {
                Text("Back to Pick")
                    .font(Nunito.font(size: 15, weight: 700, relativeTo: .subheadline, category: sizeCategory))
                    .foregroundStyle(Color("AppAccent"))
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 12)
                    .background(RoundedRectangle(cornerRadius: 18).fill(Color("PairingSurface")))
            }
            .padding(.horizontal, 24)
            .padding(.bottom, 24)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color("AppBackground"))
        .onAppear { model.onAppear() }
        .onDisappear { model.onDisappear() }
    }

    private var header: some View {
        HStack {
            Text("heel")
                .font(Nunito.font(size: 25, weight: 800, relativeTo: .title2, category: sizeCategory))
                .foregroundStyle(Color("AppAccent"))
            Spacer()
        }
        .frame(minHeight: 48)
        .padding(.horizontal, 24)
        .padding(.top, 20)
    }

    private var statusText: Text {
        if let errorMessage = model.errorMessage {
            return Text(errorMessage)
        }
        if !model.ready {
            return Text("Waiting to pair…")
        }
        if model.devices.isEmpty {
            return Text(model.scanning ? "Scanning for your device…" : "No device found yet. Tap Scan.")
        }
        return Text(model.selected == nil ? "Choose a device below." : "Ready for your Owner.")
    }

    @ViewBuilder
    private var deviceList: some View {
        if !model.devices.isEmpty {
            VStack(spacing: 8) {
                ForEach(model.devices, id: \.id) { device in
                    Button {
                        model.select(model.selected?.id == device.id ? nil : device)
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
                            if model.selected?.id == device.id {
                                Image(systemName: "checkmark.circle.fill")
                                    .foregroundStyle(Color("AppAccent"))
                            }
                        }
                        .padding(12)
                        .background(RoundedRectangle(cornerRadius: 14).fill(Color("CardSurface")))
                    }
                    .accessibilityLabel("\(device.name), \(device.features.count) vibration controls\(model.selected?.id == device.id ? ", selected" : "")")
                }
            }
            .padding(.horizontal, 24)
        }
    }

    private var scanButton: some View {
        Button {
            model.toggleScan()
        } label: {
            Text(model.scanning ? "Stop scan" : "Scan")
                .font(Nunito.font(size: 15, weight: 700, relativeTo: .subheadline, category: sizeCategory))
                .foregroundStyle(.white)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 12)
                .background(RoundedRectangle(cornerRadius: 18).fill(Color("AppAccent")))
        }
    }
}

#Preview {
    PetControlView(onReturnToPick: {})
}
