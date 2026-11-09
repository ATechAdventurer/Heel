import Foundation

/// Thin optString/optBoolean-style helpers on [String: Any], mirroring org.json.JSONObject's
/// permissive accessors so the relay message handling reads the same as the Android code.
typealias JSONDictionary = [String: Any]

extension Dictionary where Key == String, Value == Any {
    func optString(_ key: String, _ fallback: String = "") -> String {
        self[key] as? String ?? fallback
    }
    func optBool(_ key: String, _ fallback: Bool = false) -> Bool {
        self[key] as? Bool ?? fallback
    }
    func optInt(_ key: String, _ fallback: Int = 0) -> Int {
        (self[key] as? NSNumber)?.intValue ?? fallback
    }
    func optLong(_ key: String, _ fallback: Int64 = 0) -> Int64 {
        (self[key] as? NSNumber)?.int64Value ?? fallback
    }
    func optDouble(_ key: String, _ fallback: Double = 0) -> Double {
        (self[key] as? NSNumber)?.doubleValue ?? fallback
    }
    func optDictionary(_ key: String) -> JSONDictionary? {
        self[key] as? JSONDictionary
    }
    func optArray(_ key: String) -> [Any]? {
        self[key] as? [Any]
    }

    /// The wire format the native engine's commands expect (see native/src/lib.rs's Command enum).
    var compactJSONString: String {
        guard JSONSerialization.isValidJSONObject(self),
              let data = try? JSONSerialization.data(withJSONObject: self)
        else { return "{}" }
        return String(data: data, encoding: .utf8) ?? "{}"
    }
}
