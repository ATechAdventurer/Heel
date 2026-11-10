import XCTest
@testable import Heel

/// Exercises the real native engine (no hardware involved): the Rust side already has its own
/// hardware-free unit tests, so this only checks that the Swift wrapper calls through correctly.
final class DeviceEngineTests: XCTestCase {
    /// The engine is process-wide and suspends itself when nothing drains it for 2s, which a slow
    /// CI runner can hit between tests. Resume it so each test starts from a live engine.
    private func liveEngine() -> DeviceEngine {
        let engine = DeviceEngine()
        XCTAssertEqual(engine.initialize(), "")
        XCTAssertEqual(engine.submit(#"{"type":"resume"}"#), "")
        return engine
    }

    func testInitializeSucceedsAndIsIdempotent() {
        let engine = DeviceEngine()
        XCTAssertEqual(engine.initialize(), "")
        XCTAssertEqual(engine.initialize(), "")
    }

    func testSubmitRejectsMalformedCommands() {
        let engine = liveEngine()
        XCTAssertFalse(engine.submit("not json").isEmpty)
        XCTAssertFalse(engine.submit(#"{"type":"not_a_real_command"}"#).isEmpty)
    }

    func testSubmitAcceptsAKnownCommand() {
        let engine = liveEngine()
        XCTAssertEqual(engine.submit(#"{"type":"refresh"}"#), "")
    }

    func testDrainReturnsAJSONArray() {
        let engine = liveEngine()
        let drained = try? JSONSerialization.jsonObject(with: Data(engine.drain().utf8))
        XCTAssertNotNil(drained as? [Any])
    }
}
