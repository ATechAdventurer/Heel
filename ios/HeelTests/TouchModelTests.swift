import XCTest
@testable import Heel

final class TouchModelTests: XCTestCase {
    func testNormalizeClampsAndAppliesRestLimit() {
        XCTAssertEqual(TouchModel.normalize(-1, steps: 0), 0)
        XCTAssertEqual(TouchModel.normalize(.nan, steps: 0), 0)
        XCTAssertEqual(TouchModel.normalize(0.05, steps: 0), 0, "At the rest limit counts as rest")
        XCTAssertEqual(TouchModel.normalize(2, steps: 0), 1)
    }

    func testNormalizeQuantizesToSteps() {
        // 0.3 at 10 steps rounds to the nearest 0.1.
        XCTAssertEqual(TouchModel.normalize(0.34, steps: 10), 0.3, accuracy: 1e-9)
        XCTAssertEqual(TouchModel.normalize(0.36, steps: 10), 0.4, accuracy: 1e-9)
    }

    func testTrackAndRequestRoundTrip() {
        for request in stride(from: 0.0, through: 1.0, by: 0.1) {
            let track = TouchModel.requestToTrack(request)
            let back = TouchModel.trackToRequest(track)
            XCTAssertEqual(back, request, accuracy: 1e-9)
        }
    }

    func testConfigureRejectsMoreThanTwoFeatures() {
        let model = TouchModel()
        let features = (0..<3).map { TouchModel.Feature(scalarIndex: $0, label: nil, stepCount: 0) }
        XCTAssertThrowsError(try model.configure(features)) { error in
            XCTAssertEqual(error as? TouchModel.ModelError, .invalidFeatureCount)
        }
    }

    func testConfigureRejectsDuplicateScalarIndex() {
        let model = TouchModel()
        let features = [
            TouchModel.Feature(scalarIndex: 0, label: "A", stepCount: 0),
            TouchModel.Feature(scalarIndex: 0, label: "B", stepCount: 0),
        ]
        XCTAssertThrowsError(try model.configure(features)) { error in
            XCTAssertEqual(error as? TouchModel.ModelError, .duplicateScalarIndex)
        }
    }

    func testFeatureLabelFallsBackWhenBlank() {
        XCTAssertEqual(TouchModel.Feature(scalarIndex: 0, label: " ", stepCount: 0).label, "Vibration")
        XCTAssertEqual(TouchModel.Feature(scalarIndex: 0, label: nil, stepCount: 0).label, "Vibration")
    }

    private func singlePointModel(steps: Int = 0) throws -> TouchModel {
        let model = TouchModel()
        try model.configure([TouchModel.Feature(scalarIndex: 0, label: "Vibration", stepCount: steps)])
        return model
    }

    func testBeginRejectsSecondPointerOnSamePoint() throws {
        let model = try singlePointModel()
        XCTAssertTrue(model.begin(pointerId: 1, point: 0, position: 0, pickupTolerance: 0.1))
        XCTAssertFalse(model.begin(pointerId: 2, point: 0, position: 0, pickupTolerance: 0.1))
    }

    func testDragUpThenReleaseDropsToZeroWithoutHold() throws {
        let model = try singlePointModel()
        XCTAssertTrue(model.begin(pointerId: 1, point: 0, position: 0, pickupTolerance: 0.1))
        _ = model.move(pointerId: 1, position: 0.9)
        XCTAssertGreaterThan(model.target(0), 0)
        XCTAssertTrue(model.isPointActive(0))
        _ = model.end(pointerId: 1)
        XCTAssertFalse(model.isPointActive(0))
        XCTAssertEqual(model.target(0), 0)
    }

    func testHoldKeepsLevelAfterRelease() throws {
        let model = try singlePointModel()
        model.setHold(true)
        XCTAssertTrue(model.begin(pointerId: 1, point: 0, position: 0, pickupTolerance: 0.1))
        _ = model.move(pointerId: 1, position: 0.9)
        let held = model.target(0)
        XCTAssertGreaterThan(held, 0)
        _ = model.end(pointerId: 1)
        XCTAssertEqual(model.target(0), held, "Hold keeps the last target after lifting")
        XCTAssertFalse(model.isPointActive(0))
    }

    func testClearingHoldZeroesInactivePoints() throws {
        let model = try singlePointModel()
        model.setHold(true)
        XCTAssertTrue(model.begin(pointerId: 1, point: 0, position: 0, pickupTolerance: 0.1))
        _ = model.move(pointerId: 1, position: 0.9)
        _ = model.end(pointerId: 1)
        XCTAssertGreaterThan(model.target(0), 0)
        model.setHold(false)
        XCTAssertEqual(model.target(0), 0)
    }

    func testSetTargetsRejectsWhileTouchInProgress() throws {
        let model = try singlePointModel()
        XCTAssertTrue(model.begin(pointerId: 1, point: 0, position: 0, pickupTolerance: 0.1))
        XCTAssertThrowsError(try model.setTargets([0.5])) { error in
            XCTAssertEqual(error as? TouchModel.ModelError, .touchInProgress)
        }
    }

    func testSetTargetsRejectsOutOfRangeValues() throws {
        let model = try singlePointModel()
        XCTAssertThrowsError(try model.setTargets([1.5])) { error in
            XCTAssertEqual(error as? TouchModel.ModelError, .targetOutOfRange)
        }
    }

    func testSetTargetsQuantizesToFeatureSteps() throws {
        let model = try singlePointModel(steps: 10)
        try model.setTargets([0.34])
        XCTAssertEqual(model.target(0), 0.3, accuracy: 1e-9)
        XCTAssertEqual(model.visualTarget(0), 0.3, accuracy: 1e-9)
    }

    func testSetVisualTargetsKeepsVisualContinuousButOutputQuantized() throws {
        let model = try singlePointModel(steps: 10)
        try model.setVisualTargets([0.34])
        XCTAssertEqual(model.target(0), 0.3, accuracy: 1e-9, "Actual output snaps to the device's steps")
        XCTAssertEqual(model.visualTarget(0), 0.34, accuracy: 1e-9, "Visual stays continuous for smooth playback")
    }

    func testClearInvalidatesOldPointerIds() throws {
        let model = try singlePointModel()
        XCTAssertTrue(model.begin(pointerId: 1, point: 0, position: 0, pickupTolerance: 0.1))
        _ = model.move(pointerId: 1, position: 0.9)
        model.clear()
        XCTAssertFalse(model.move(pointerId: 1, position: 0.9), "Old pointer id no longer owns anything")
        XCTAssertEqual(model.target(0), 0)
    }
}
