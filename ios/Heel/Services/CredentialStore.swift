import Foundation
import Security

/// Only the per-install relay token is persisted. Unlike Android, which encrypts a
/// SharedPreferences value with an AndroidKeyStore-backed cipher, the Keychain item
/// itself is already encrypted at rest and non-exportable, so no extra cipher layer
/// is needed here.
final class CredentialStore {
    enum CredentialStoreError: Error, LocalizedError {
        case saveFailed(OSStatus)
        case readFailed(OSStatus)

        var errorDescription: String? {
            switch self {
            case .saveFailed, .readFailed: return "Could not save this phone's pairing identity."
            }
        }
    }

    private let defaults: UserDefaults
    private let service = "dev.prototype.lovense.relay"
    private let account = "token"

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    var endpoint: String {
        defaults.string(forKey: "endpoint") ?? ""
    }

    func setEndpoint(_ endpoint: String) {
        defaults.set(endpoint, forKey: "endpoint")
        try? deleteToken()
    }

    var hasToken: Bool {
        (try? token()) != nil
    }

    func token() throws -> String? {
        var query = baseQuery
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne

        var result: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = result as? Data,
              let token = String(data: data, encoding: .utf8)
        else { throw CredentialStoreError.readFailed(status) }
        return token
    }

    func setToken(_ token: String) throws {
        let data = Data(token.utf8)
        try deleteToken()
        var attributes = baseQuery
        attributes[kSecValueData as String] = data
        attributes[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        let status = SecItemAdd(attributes as CFDictionary, nil)
        guard status == errSecSuccess else { throw CredentialStoreError.saveFailed(status) }
    }

    func deleteToken() throws {
        let status = SecItemDelete(baseQuery as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw CredentialStoreError.saveFailed(status)
        }
    }

    private var baseQuery: [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
    }
}
