import SwiftUI

/// Saved gestures, played back onto the pad. Mirrors Android's Spellbook dialog as a native
/// List/sheet, folding its separate record/play/delete rows into standard List affordances.
struct SpellbookView: View {
    @ObservedObject var model: ControlViewModel
    @Environment(\.dismiss) private var dismiss
    @Environment(\.sizeCategory) private var sizeCategory
    @State private var pendingDeleteId: String?

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Button {
                        dismiss()
                        model.startRecording()
                    } label: {
                        Text("Record a Ghost Hand")
                            .font(Nunito.font(size: 16, weight: 700, relativeTo: .body, category: sizeCategory))
                    }
                    .disabled(!model.canPerformSpell)
                } footer: {
                    Text(model.canPerformSpell
                        ? "Record up to 2 minutes. Every spell starts and ends at rest."
                        : "Start a session to record or play. Your saved spells stay here.")
                        .font(Nunito.font(size: 12, weight: 500, relativeTo: .footnote, category: sizeCategory))
                }

                if !model.spells.isEmpty {
                    Section("Your gestures, kept as little spells.") {
                        ForEach(model.spells, id: \.id) { spell in
                            spellRow(spell)
                        }
                    }
                }
            }
            .navigationTitle("Spellbook")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close") { dismiss() }
                }
            }
        }
    }

    private func spellRow(_ spell: SpellBook.Spell) -> some View {
        HStack(spacing: 12) {
            Image(Self.stickerAsset(spell.sticker))
                .resizable()
                .scaledToFit()
                .frame(width: 44, height: 44)
                .accessibilityHidden(true)

            VStack(alignment: .leading, spacing: 2) {
                Text(spell.name)
                    .font(Nunito.font(size: 16, weight: 700, relativeTo: .body, category: sizeCategory))
                    .foregroundStyle(Color("AppForeground"))
                Text("\(Self.durationLabel(spell.duration)) · \(spell.channels == 1 ? "1 paw" : "2 paws")")
                    .font(Nunito.font(size: 12, weight: 500, relativeTo: .footnote, category: sizeCategory))
                    .foregroundStyle(Color("AppMuted"))
                if !model.features.isEmpty && spell.channels != model.features.count {
                    Text("Choose a device with \(spell.channels) control\(spell.channels == 1 ? "" : "s") to play this spell.")
                        .font(Nunito.font(size: 11, weight: 500, relativeTo: .caption, category: sizeCategory))
                        .foregroundStyle(Color("AppMuted"))
                }
            }

            Spacer()

            Button("Play") {
                dismiss()
                model.startPlayback(spell)
            }
            .disabled(!model.canPerformSpell || spell.channels != model.features.count)
            .accessibilityLabel("Play \(spell.name)")
        }
        .swipeActions(edge: .trailing) {
            Button("Delete", role: .destructive) { pendingDeleteId = spell.id }
        }
        .confirmationDialog("Delete \u{201c}\(spell.name)\u{201d}?",
                             isPresented: Binding(get: { pendingDeleteId == spell.id }, set: { if !$0 { pendingDeleteId = nil } }),
                             titleVisibility: .visible) {
            Button("Delete", role: .destructive) {
                model.deleteSpell(spell.id)
                pendingDeleteId = nil
            }
            Button("Keep", role: .cancel) { pendingDeleteId = nil }
        }
    }

    private static func stickerAsset(_ sticker: Int) -> String {
        switch sticker {
        case 1: return "ArtemisCurious"
        case 2: return "ArtemisRest"
        default: return "ArtemisWink"
        }
    }

    private static func durationLabel(_ millis: Int64) -> String {
        String(format: "%d:%02d", millis / 60000, (millis / 1000) % 60)
    }
}

/// Name and a little face for a just-finished recording, shown once the pad returns to rest.
struct SaveSpellSheet: View {
    @ObservedObject var model: ControlViewModel
    @Environment(\.dismiss) private var dismiss
    @Environment(\.sizeCategory) private var sizeCategory
    @State private var name = ""
    @State private var sticker = 0

    private static let stickers: [(value: Int, label: String)] = [(0, "Wink"), (1, "Curious"), (2, "Rest")]

    var body: some View {
        NavigationStack {
            Form {
                Section("A name and a little face for your spell.") {
                    TextField("Spell name", text: $name)
                        .font(Nunito.font(size: 16, weight: 500, relativeTo: .body, category: sizeCategory))
                    Picker("Face", selection: $sticker) {
                        ForEach(Self.stickers, id: \.value) { item in
                            Text(item.label).tag(item.value)
                        }
                    }
                    .pickerStyle(.segmented)
                }
            }
            .navigationTitle("Save your spell")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Discard") {
                        model.discardDraft()
                        dismiss()
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        model.saveDraft(name: name.trimmingCharacters(in: .whitespacesAndNewlines), sticker: sticker)
                        dismiss()
                    }
                    .disabled(name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
        }
    }
}
