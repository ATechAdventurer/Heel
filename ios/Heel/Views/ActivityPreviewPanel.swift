#if DEBUG
import SwiftUI

/// Debug builds only: starts the Lock Screen / Dynamic Island activity in each state so its
/// layouts can be checked without a live session. Lock the phone or leave the app to see it.
struct ActivityPreviewPanel: View {
    @State private var status = ""
    private let activity = ActivityKitSessionActivity.shared

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Live Activity preview (debug)").font(.footnote).foregroundStyle(.secondary)
            HStack {
                button("Together") { status = activity.preview(.together) }
                button("Paused") { status = activity.preview(.paused) }
                button("Waiting") { status = activity.preview(.waiting) }
            }
            HStack {
                button("Stale in 5s") { status = activity.preview(.together, staleAfter: 5) }
                button("End") { status = activity.preview(nil) }
            }
            if !status.isEmpty {
                Text(status).font(.footnote).foregroundStyle(.secondary)
            }
        }
        .padding(.top, 32)
    }

    private func button(_ title: String, _ action: @escaping () -> Void) -> some View {
        Button(title, action: action).buttonStyle(.bordered)
    }
}
#endif
