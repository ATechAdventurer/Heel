import Foundation

/// One entry from the native engine's "devices" event, reduced to what Pet needs: only the
/// vibration (Scalar/Vibrate) controls. Mirrors Android's MainActivity.DeviceInfo(JSONObject).
struct PetDevice: Equatable {
    let id: Int
    let name: String
    let features: [TouchModel.Feature]

    init?(nativeDevice: JSONDictionary) {
        guard let name = nativeDevice["name"] as? String,
              let messages = nativeDevice.optDictionary("messages")
        else { return nil }
        let id = nativeDevice.optInt("id", -1)
        guard id >= 0 else { return nil }
        let scalar = (messages["ScalarCmd"] as? [Any])?.compactMap { $0 as? JSONDictionary } ?? []
        var features: [TouchModel.Feature] = []
        for (index, feature) in scalar.enumerated() {
            guard feature.optString("ActuatorType") == "Vibrate" else { continue }
            let descriptor = feature.optString("FeatureDescriptor").trimmingCharacters(in: .whitespacesAndNewlines)
            let label = descriptor.isEmpty || descriptor == "N/A" ? "Vibration \(features.count + 1)" : descriptor
            // The array position is the original scalar command index, even for mixed actuator types.
            features.append(TouchModel.Feature(scalarIndex: index, label: label, stepCount: max(0, feature.optInt("StepCount"))))
        }
        guard (1...2).contains(features.count) else { return nil }
        self.id = id
        self.name = name
        self.features = features
    }

    /// The {id, name, features:[{index,label,steps}]} shape Owner's ControlViewModel expects.
    var relayPayload: JSONDictionary {
        ["id": id, "name": name, "features": features.map {
            ["index": $0.scalarIndex, "label": $0.label, "steps": $0.stepCount]
        }]
    }
}
