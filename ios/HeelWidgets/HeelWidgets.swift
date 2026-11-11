import ActivityKit
import SwiftUI
import WidgetKit

@main
struct HeelWidgets: WidgetBundle {
    var body: some Widget {
        SessionLiveActivity()
    }
}

struct SessionLiveActivity: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: SessionActivityAttributes.self) { context in
            HStack(spacing: 12) {
                Image(systemName: "heart.fill").foregroundStyle(.pink)
                VStack(alignment: .leading, spacing: 2) {
                    Text("Heel").font(.headline)
                    Text(message(context.state.phase, stale: context.isStale)).font(.subheadline).foregroundStyle(.secondary)
                }
                Spacer()
            }
            .padding()
            .activityBackgroundTint(Color.black.opacity(0.35))
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.center) {
                    Text(message(context.state.phase, stale: context.isStale)).font(.subheadline)
                }
            } compactLeading: {
                Image(systemName: "heart.fill").foregroundStyle(.pink)
            } compactTrailing: {
                Text(short(context.state.phase, stale: context.isStale)).font(.caption)
            } minimal: {
                Image(systemName: "heart.fill").foregroundStyle(.pink)
            }
            .keylineTint(.pink)
        }
    }

    private func message(_ phase: SessionPhase, stale: Bool) -> String {
        if stale { return "Open Heel to check in" }
        switch phase {
        case .together: return "Together ♡"
        case .paused: return "Paused · open Heel to resume"
        case .waiting: return "Waiting"
        }
    }

    private func short(_ phase: SessionPhase, stale: Bool) -> String {
        if stale { return "Open" }
        switch phase {
        case .together: return "♡"
        case .paused: return "Paused"
        case .waiting: return "Wait"
        }
    }
}
