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
}
