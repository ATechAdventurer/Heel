import SwiftUI

struct PairingView: View {
    @StateObject private var model: PairingViewModel
    @State private var showingSettings = false
    @State private var showingResetConfirm = false
    @State private var showingRelaySettings = false
    @State private var relayAddressDraft = ""
    @Environment(\.sizeCategory) private var sizeCategory

    init(role: Role, onPaired: @escaping (Role) -> Void, onReturnToPick: @escaping () -> Void) {
        let viewModel = PairingViewModel(role: role)
        viewModel.onPaired = onPaired
        viewModel.onReturnToPick = onReturnToPick
        _model = StateObject(wrappedValue: viewModel)
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 0) {
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

                Image(model.role == .owner ? "ArtemisOwner" : "ArtemisCurious")
                    .resizable()
                    .scaledToFit()
                    .frame(height: 132)
                    .accessibilityHidden(true)

                Text(model.connectionReason)
                    .font(Nunito.font(size: 13, weight: 500, relativeTo: .footnote, category: sizeCategory))
                    .foregroundStyle(Color("AppMuted"))
                    .multilineTextAlignment(.center)

                content
                    .padding(.top, 20)
            }
            .padding(.horizontal, 24)
            .padding(.top, 20)
            .padding(.bottom, 24)
        }
        .background(Color("AppBackground"))
        .onAppear { model.onAppear() }
        .onDisappear { model.onDisappear() }
        .confirmationDialog("Settings", isPresented: $showingSettings, titleVisibility: .visible) {
            Button("Reset role choice") { showingResetConfirm = true }
            Button("Advanced connection settings") {
                relayAddressDraft = model.endpoint
                showingRelaySettings = true
            }
            Button("Close", role: .cancel) {}
        }
        .alert("Unpair and choose again?", isPresented: $showingResetConfirm) {
            Button("Keep role", role: .cancel) {}
            Button("Unpair & reset", role: .destructive) { model.resetRole() }
        } message: {
            Text("This stops the pair and returns to Pick. This phone's setup identity is kept, so you won't need another setup code.")
        }
        .alert("Relay address", isPresented: $showingRelaySettings) {
            TextField("HTTPS relay address", text: $relayAddressDraft)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
            Button("Keep", role: .cancel) {}
            Button("Save / reset") { model.saveRelayAddress(relayAddressDraft) }
        } message: {
            Text("Changing or resetting the address removes this phone's saved identity. You'll need a new setup code.")
        }
    }

    @ViewBuilder
    private var content: some View {
        switch model.phase {
        case .unpaired: unpairedContent
        case .inviting: invitingContent
        case .preview(let profile): previewContent(profile: profile)
        case .approval(let message): approvalContent(message: message)
        }
    }

    private var unpairedContent: some View {
        VStack(alignment: .leading, spacing: 8) {
            heading(model.role == .owner ? "Make it yours" : "Someone waiting?")

            if model.endpoint.isEmpty {
                body("Enter the relay address from your host to connect your phones.")
                actionButton("Set relay address") {
                    relayAddressDraft = model.endpoint
                    showingRelaySettings = true
                }
            } else {
                if !model.hasToken {
                    body("First, give this phone its private setup code.")
                    SecureField("Phone setup code", text: $model.enrollmentCode)
                        .heelFieldStyle(category: sizeCategory)
                    actionButton("Connect this phone") { model.connectThisPhone() }
                } else {
                    actionButton("Reconnect") { model.reconnect() }
                }

                if model.role == .owner {
                    TextField("Your name", text: $model.ownerName).heelFieldStyle(category: sizeCategory)
                    TextField("Their name", text: $model.petName).heelFieldStyle(category: sizeCategory)
                    TextField("Their kind · puppy, bunny…", text: $model.species).heelFieldStyle(category: sizeCategory)
                    actionButton("Make an invitation") { model.makeInvitation() }
                } else {
                    TextField("Invitation code", text: $model.typedCode)
                        .textInputAutocapitalization(.characters)
                        .heelFieldStyle(category: sizeCategory)
                    actionButton("Find my owner") { model.findMyOwner() }
                }
            }
        }
    }

    private var invitingContent: some View {
        VStack(alignment: .leading, spacing: 8) {
            heading("A little invitation")
            body("Send this code to your \(model.species). It expires in 10 minutes.")
            Text(model.invite.isEmpty ? "Cancel, then make a new invitation" : model.invite)
                .font(Nunito.font(size: 27, weight: 800, relativeTo: .title, category: sizeCategory))
                .foregroundStyle(Color("AppForeground"))
                .frame(maxWidth: .infinity)
                .multilineTextAlignment(.center)
                .textSelection(.enabled)
                .padding(.vertical, 18)
            if !model.invite.isEmpty {
                actionButton("Copy code") { model.copyInviteCode() }
            }
            body("You'll approve the other phone before it can join.")
            actionButton("Cancel invitation") { model.cancelInvitation() }
        }
    }

    private func previewContent(profile: JSONDictionary) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            heading("There you are")
            Text("\(profile.optString("owner")) would like to call you \(profile.optString("pet")), their \(profile.optString("species")).")
                .font(Nunito.font(size: 19, weight: 800, relativeTo: .title3, category: sizeCategory))
                .foregroundStyle(Color("AppForeground"))
            body("Your phone connects to your device. Your owner can control it after you tap Ready. Pause is a temporary break; Safe Word stays stopped until you choose Ready again. Stop and unpair always stay yours.")
            actionButton("That's me") { model.acceptPreview() }
            actionButton("Go back") { model.goBackFromPreview() }
        }
    }

    private func approvalContent(message: JSONDictionary) -> some View {
        let owning = message.optString("role") == "owner"
        let profile = message.optDictionary("profile")
        return VStack(alignment: .leading, spacing: 8) {
            heading(owning ? "Your pet is here" : "One little check")
            Text(profile == nil ? "Compare this number on both phones." : "\(profile!.optString("owner")) + \(profile!.optString("pet"))")
                .font(Nunito.font(size: 18, weight: 800, relativeTo: .title3, category: sizeCategory))
                .foregroundStyle(Color("AppForeground"))
            Text(message.optString("confirmation"))
                .font(Nunito.font(size: 36, weight: 800, relativeTo: .largeTitle, category: sizeCategory))
                .foregroundStyle(Color("AppForeground"))
                .frame(maxWidth: .infinity)
                .multilineTextAlignment(.center)
                .padding(.vertical, 20)
            body("Compare this number with the other phone before approving.")
            if owning {
                actionButton("The numbers match · pair") { model.approvePairing() }
            } else {
                body("Waiting for your owner's approval…")
            }
            actionButton("Cancel") { model.cancelApproval() }
        }
    }

    private func heading(_ text: String) -> some View {
        Text(text)
            .font(Nunito.font(size: 28, weight: 800, relativeTo: .title, category: sizeCategory))
            .foregroundStyle(Color("AppForeground"))
    }

    private func body(_ text: String) -> some View {
        Text(text)
            .font(Nunito.font(size: 15, weight: 500, relativeTo: .subheadline, category: sizeCategory))
            .foregroundStyle(Color("AppForeground"))
    }

    private func actionButton(_ label: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(label)
                .font(Nunito.font(size: 15, weight: 700, relativeTo: .subheadline, category: sizeCategory))
                .foregroundStyle(Color("AppAccent"))
                .frame(maxWidth: .infinity)
                .padding(.vertical, 12)
                .background(RoundedRectangle(cornerRadius: 18).fill(Color("PairingSurface")))
        }
        .frame(minHeight: 44)
        .padding(.top, 4)
    }
}

private extension View {
    /// Shared text-field chrome: a system-material surface (so it adapts to Dark Mode
    /// the way the custom hex palette around it can't on its own) and the brand font.
    func heelFieldStyle(category: ContentSizeCategory) -> some View {
        self
            .textFieldStyle(.plain)
            .font(Nunito.font(size: 16, weight: 500, relativeTo: .body, category: category))
            .foregroundStyle(Color("AppForeground"))
            .padding(12)
            .frame(minHeight: 44)
            .background(RoundedRectangle(cornerRadius: 12).fill(Color(.secondarySystemBackground)))
    }
}
