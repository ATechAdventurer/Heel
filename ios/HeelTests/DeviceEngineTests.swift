import XCTest
@testable import Heel

/// Exercises the real native engine (no hardware involved): the Rust side already has its own
/// hardware-free unit tests, so this only checks that the Swift wrapper calls through correctly.
final class DeviceEngineTests: XCTestCase {
    func testInitializeSucceedsAndIsIdempotent() {
        let engine = DeviceEngine()
        XCTAssertEqual(engine.initialize(), "")
        XCTAssertEqual(engine.initialize(), "")
    }

    func testSubmitRejectsMalformedCommands() {
        let engine = DeviceEngine()
        _ = engine.initialize()
        XCTAssertFalse(engine.submit("not json").isEmpty)
        XCTAssertFalse(engine.submit(#"{"type":"not_a_real_command"}"#).isEmpty)
    }

    func testSubmitAcceptsAKnownCommand() {
        let engine = DeviceEngine()
        _ = engine.initialize()
        XCTAssertEqual(engine.submit(#"{"type":"refresh"}"#), "")
    }

    func testDrainReturnsAJSONArray() {
        let engine = DeviceEngine()
        _ = engine.initialize()
        let drained = try? JSONSerialization.jsonObject(with: Data(engine.drain().utf8))
        XCTAssertNotNil(drained as? [Any])
    }
}
