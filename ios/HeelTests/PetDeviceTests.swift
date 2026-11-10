import XCTest
@testable import Heel

final class PetDeviceTests: XCTestCase {
    private func nativeDevice(scalar: [JSONDictionary]) -> JSONDictionary {
        ["id": 3, "name": "Lovense Hush", "messages": ["ScalarCmd": scalar], "battery": true]
    }

    func testOnlyVibrateActuatorsBecomeFeaturesInOrder() {
        let device = PetDevice(nativeDevice: nativeDevice(scalar: [
            ["ActuatorType": "Vibrate", "FeatureDescriptor": "", "StepCount": 20],
            ["ActuatorType": "Rotate", "FeatureDescriptor": "Rotation", "StepCount": 20],
            ["ActuatorType": "Vibrate", "FeatureDescriptor": "Tip", "StepCount": 100],
        ]))
        XCTAssertEqual(device?.features.map(\.scalarIndex), [0, 2])
        XCTAssertEqual(device?.features.map(\.label), ["Vibration 1", "Tip"])
        XCTAssertEqual(device?.features.map(\.stepCount), [20, 100])
    }

    func testBlankOrPlaceholderDescriptorFallsBackToAGeneratedLabel() {
        let device = PetDevice(nativeDevice: nativeDevice(scalar: [
            ["ActuatorType": "Vibrate", "FeatureDescriptor": "N/A", "StepCount": 20],
        ]))
        XCTAssertEqual(device?.features.first?.label, "Vibration 1")
    }

    func testRejectsDevicesWithNoOrTooManyVibrationFeatures() {
        XCTAssertNil(PetDevice(nativeDevice: nativeDevice(scalar: [])))
        let tooMany = (0..<3).map { _ in ["ActuatorType": "Vibrate", "FeatureDescriptor": "", "StepCount": 20] }
        XCTAssertNil(PetDevice(nativeDevice: nativeDevice(scalar: tooMany)))
    }

    func testRelayPayloadMatchesTheShapeOwnerExpects() {
        let device = PetDevice(nativeDevice: nativeDevice(scalar: [
            ["ActuatorType": "Vibrate", "FeatureDescriptor": "Tip", "StepCount": 20],
        ]))
        let payload = device?.relayPayload
        XCTAssertEqual(payload?["id"] as? Int, 3)
        XCTAssertEqual(payload?["name"] as? String, "Lovense Hush")
        let features = payload?["features"] as? [JSONDictionary]
        XCTAssertEqual(features?.first?["index"] as? Int, 0)
        XCTAssertEqual(features?.first?["label"] as? String, "Tip")
        XCTAssertEqual(features?.first?["steps"] as? Int, 20)
    }
}
