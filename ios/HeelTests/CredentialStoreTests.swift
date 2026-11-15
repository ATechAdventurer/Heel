import XCTest
@testable import Heel

final class CredentialStoreTests: XCTestCase {
    private func freshStore() -> CredentialStore {
        let suiteName = "CredentialStoreTests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        addTeardownBlock { defaults.removePersistentDomain(forName: suiteName) }
        return CredentialStore(defaults: defaults)
    }

    override func tearDown() {
        // Keychain items use a fixed service/account, so clean up after every test
        // regardless of which store instance wrote them.
        try? CredentialStore().deleteToken()
        super.tearDown()
    }

    func testHasTokenIsFalseInitially() {
        XCTAssertFalse(freshStore().hasToken)
    }

    func testSetAndReadTokenRoundTrips() throws {
        let store = freshStore()
        try store.setToken("secret-token")
        XCTAssertTrue(store.hasToken)
        XCTAssertEqual(try store.token(), "secret-token")
    }

    func testSettingTokenAgainReplacesThePrevious() throws {
        let store = freshStore()
        try store.setToken("first")
        try store.setToken("second")
        XCTAssertEqual(try store.token(), "second")
    }

    func testSetEndpointClearsAnyExistingToken() throws {
        let store = freshStore()
        try store.setToken("secret-token")
        store.setEndpoint("https://relay.example.com")
        XCTAssertFalse(store.hasToken)
        XCTAssertEqual(store.endpoint, "https://relay.example.com")
    }

    private func storedAccessibility() -> String? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: "dev.atechadventure.heel.relay",
            kSecAttrAccount as String: "token",
            kSecReturnAttributes as String: true,
        ]
        var result: AnyObject?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess,
              let attributes = result as? [String: Any] else { return nil }
        return attributes[kSecAttrAccessible as String] as? String
    }

    func testTokenIsReadableAfterFirstUnlockSoALockedPhoneCanReconnect() throws {
        try freshStore().setToken("secret-token")
        XCTAssertEqual(storedAccessibility(), kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly as String)
    }

    func testATokenSavedWhileUnlockedOnlyIsMovedOverWhenRead() throws {
        let store = freshStore()
        try store.setToken("secret-token")
        let base: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: "dev.atechadventure.heel.relay",
            kSecAttrAccount as String: "token",
        ]
        XCTAssertEqual(SecItemUpdate(base as CFDictionary,
            [kSecAttrAccessible as String: kSecAttrAccessibleWhenUnlockedThisDeviceOnly] as CFDictionary), errSecSuccess)
        XCTAssertEqual(storedAccessibility(), kSecAttrAccessibleWhenUnlockedThisDeviceOnly as String)
        XCTAssertEqual(try store.token(), "secret-token")
        XCTAssertEqual(storedAccessibility(), kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly as String)
    }
}
