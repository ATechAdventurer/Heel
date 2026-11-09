import Foundation
import CoreHaptics

/// Backend for a single haptic pulse. PhoneHapticsOutput below is the production
/// CoreHaptics implementation; tests substitute a spy.
protocol OwnerHapticsOutput: AnyObject {
    func tick(strength: Double)
    func cancel()
}

/// Foreground phone feedback that pulses in sync with the live control level, repeating
/// faster and stronger as the level rises. Mirrors Android's OwnerHaptics loop, so the
/// owner feels roughly what the pet's device is doing without watching the screen.
@MainActor
final class OwnerHaptics {
    private let output: OwnerHapticsOutput
    private let schedule: (TimeInterval, @escaping () -> Void) -> Void
    private var level: Double = 0
    private(set) var running = false
    private var generation = 0

    init(output: OwnerHapticsOutput,
         schedule: @escaping (TimeInterval, @escaping () -> Void) -> Void = { delay, work in
             DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: work)
         }) {
        self.output = output
        self.schedule = schedule
    }

    static func interval(for level: Double) -> TimeInterval {
        max(0, 150 - 50 * level) / 1000
    }

    func update(_ next: Double) {
        level = next.isFinite ? min(1, max(0, next)) : 0
        guard level > TouchModel.restLimit else { stop(); return }
        guard !running else { return }
        running = true
        pulse()
    }

    func stop() {
        level = 0
        generation += 1
        if running { running = false; output.cancel() }
    }

    private func pulse() {
        guard running, level > 0 else { return }
        output.tick(strength: 0.5 + 0.5 * level)
        let current = generation
        schedule(Self.interval(for: level)) { [weak self] in
            guard let self, self.generation == current else { return }
            self.pulse()
        }
    }
}

/// A short transient tap scaled by intensity. Leaves the engine running between pulses
/// rather than stopping/restarting it, since CoreHaptics engines are meant to stay warm.
final class PhoneHapticsOutput: OwnerHapticsOutput {
    private var engine: CHHapticEngine?
    private let supported = CHHapticEngine.capabilitiesForHardware().supportsHaptics

    init() {
        guard supported else { return }
        engine = try? CHHapticEngine()
        engine?.resetHandler = { [weak self] in try? self?.engine?.start() }
        engine?.stoppedHandler = { _ in }
        try? engine?.start()
    }

    func tick(strength: Double) {
        guard supported, let engine else { return }
        let intensity = CHHapticEventParameter(parameterID: .hapticIntensity, value: Float(strength))
        let sharpness = CHHapticEventParameter(parameterID: .hapticSharpness, value: 0.6)
        let event = CHHapticEvent(eventType: .hapticTransient, parameters: [intensity, sharpness], relativeTime: 0)
        do {
            let player = try engine.makePlayer(with: try CHHapticPattern(events: [event], parameters: []))
            try engine.start()
            try player.start(atTime: 0)
        } catch {
            // Hardware/engine hiccup; the next pulse in the loop simply retries.
        }
    }

    func cancel() {}
}

/// Persists the owner's choice to feel live haptic feedback on the Control screen.
enum HapticsPreferences {
    private static let key = "heel.control.hapticsEnabled"

    static func get(_ defaults: UserDefaults = .standard) -> Bool {
        defaults.object(forKey: key) == nil ? true : defaults.bool(forKey: key)
    }

    static func set(_ value: Bool, defaults: UserDefaults = .standard) {
        defaults.set(value, forKey: key)
    }
}
