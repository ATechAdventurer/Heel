import XCTest
@testable import Heel

private final class SpyOutput: OwnerHapticsOutput {
    var strengths: [Double] = []
    var cancels = 0
    func tick(strength: Double) { strengths.append(strength) }
    func cancel() { cancels += 1 }
}

/// Captures scheduled work instead of running it, so tests can fire pulses on demand
/// rather than waiting on the real clock.
private final class FakeScheduler {
    private(set) var scheduled: [(delay: TimeInterval, work: () -> Void)] = []

    func capture(_ delay: TimeInterval, _ work: @escaping () -> Void) {
        scheduled.append((delay, work))
    }

    func fireNext() {
        guard !scheduled.isEmpty else { return }
        scheduled.removeFirst().work()
    }
}

@MainActor
final class OwnerHapticsTests: XCTestCase {
    func testUpdateTicksImmediatelyAndSchedulesTheNextPulse() {
        let output = SpyOutput()
        let scheduler = FakeScheduler()
        let haptics = OwnerHaptics(output: output, schedule: scheduler.capture)

        haptics.update(0.5)

        XCTAssertEqual(output.strengths, [0.75])
        XCTAssertEqual(scheduler.scheduled.count, 1)
        XCTAssertEqual(scheduler.scheduled[0].delay, 0.125, accuracy: 0.0001)
    }

    func testHoldingALevelKeepsPulsingWithoutAnotherUpdate() {
        let output = SpyOutput()
        let scheduler = FakeScheduler()
        let haptics = OwnerHaptics(output: output, schedule: scheduler.capture)

        haptics.update(0.5)
        scheduler.fireNext()
        scheduler.fireNext()

        XCTAssertEqual(output.strengths, [0.75, 0.75, 0.75])
        for strength in output.strengths { XCTAssertTrue(strength >= 0.5 && strength <= 1) }
    }

    func testUpdateWhileRunningDoesNotRestartTheLoop() {
        let output = SpyOutput()
        let scheduler = FakeScheduler()
        let haptics = OwnerHaptics(output: output, schedule: scheduler.capture)

        haptics.update(0.3)
        haptics.update(0.6)

        XCTAssertEqual(output.strengths.count, 1)
        XCTAssertEqual(scheduler.scheduled.count, 1)
    }

    func testRestLevelNeverStartsTheLoop() {
        let output = SpyOutput()
        let haptics = OwnerHaptics(output: output)

        haptics.update(TouchModel.restLimit)

        XCTAssertTrue(output.strengths.isEmpty)
        XCTAssertEqual(output.cancels, 0)
    }

    func testInvalidOrZeroNeverBuzzes() {
        let output = SpyOutput()
        let haptics = OwnerHaptics(output: output)

        haptics.update(0)
        haptics.update(.nan)
        haptics.update(-1)

        XCTAssertTrue(output.strengths.isEmpty)
    }

    func testDroppingBelowRestCancelsTheRunningLoop() {
        let output = SpyOutput()
        let scheduler = FakeScheduler()
        let haptics = OwnerHaptics(output: output, schedule: scheduler.capture)

        haptics.update(0.5)
        haptics.update(0.02)

        XCTAssertEqual(output.cancels, 1)
    }

    func testStopCancelsAndIgnoresAnAlreadyScheduledPulse() {
        let output = SpyOutput()
        let scheduler = FakeScheduler()
        let haptics = OwnerHaptics(output: output, schedule: scheduler.capture)

        haptics.update(0.5)
        haptics.stop()
        scheduler.fireNext()

        XCTAssertEqual(output.strengths, [0.75])
        XCTAssertEqual(output.cancels, 1)
    }

    func testIntervalShortensAndStrengthensAsLevelRises() {
        XCTAssertEqual(OwnerHaptics.interval(for: 0), 0.15, accuracy: 0.0001)
        XCTAssertEqual(OwnerHaptics.interval(for: 1), 0.10, accuracy: 0.0001)
    }
}
