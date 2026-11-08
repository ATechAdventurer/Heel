import Foundation

enum Role: String {
    case owner, pet
}

/// Role is a navigation preference, never authorization to control another phone.
enum RolePreferences {
    private static let key = "heel.navigation.role"

    static func get(_ defaults: UserDefaults = .standard) -> Role? {
        guard let raw = defaults.string(forKey: key) else { return nil }
        return Role(rawValue: raw)
    }

    static func choose(_ role: Role, defaults: UserDefaults = .standard) {
        defaults.set(role.rawValue, forKey: key)
    }

    static func reset(_ defaults: UserDefaults = .standard) {
        defaults.removeObject(forKey: key)
    }
}
