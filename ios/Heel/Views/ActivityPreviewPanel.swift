#if DEBUG
import SwiftUI

/// Debug builds only: starts the Lock Screen / Dynamic Island activity in each state so its
/// layouts can be checked without a live session. Lock the phone or leave the app to see it.
struct ActivityPreviewPanel: View {
    @State private var activity = ActivityKitSessionActivity()

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Live Activity preview (debug)").font(.footnote).foregroundStyle(.secondary)
            HStack {
                button("Together") { activity.preview(.together) }
                button("Paused") { activity.preview(.paused) }
                button("Waiting") { activity.preview(.waiting) }
            }
            HStack {
                button("Stale in 5s") { activity.preview(.together, staleAfter: 5) }
                button("End") { activity.preview(nil) }
            }
        }
        .padding(.top, 32)
    }

    private func button(_ title: String, _ action: @escaping () -> Void) -> some View {
        Button(title, action: action).buttonStyle(.bordered)
    }
}
#endif
