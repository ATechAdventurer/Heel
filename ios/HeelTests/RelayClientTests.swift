import XCTest
@testable import Heel

final class RelayClientTests: XCTestCase {
    func testNormalizedEndpointAcceptsAPlainHTTPSHost() throws {
        XCTAssertEqual(try RelayClient.normalizedEndpoint("https://relay.example.com"), "https://relay.example.com")
    }

    func testNormalizedEndpointStripsATrailingSlash() throws {
        XCTAssertEqual(try RelayClient.normalizedEndpoint("https://relay.example.com/"), "https://relay.example.com")
    }

    func testNormalizedEndpointRejectsNonHTTPS() {
        XCTAssertThrowsError(try RelayClient.normalizedEndpoint("http://relay.example.com"))
    }

    func testNormalizedEndpointRejectsAPath() {
        XCTAssertThrowsError(try RelayClient.normalizedEndpoint("https://relay.example.com/v1"))
    }

    func testNormalizedEndpointRejectsAQuery() {
        XCTAssertThrowsError(try RelayClient.normalizedEndpoint("https://relay.example.com?x=1"))
    }

    func testNormalizedEndpointRejectsEmbeddedCredentials() {
        XCTAssertThrowsError(try RelayClient.normalizedEndpoint("https://user:pass@relay.example.com"))
    }

    func testNormalizedEndpointRejectsGarbage() {
        XCTAssertThrowsError(try RelayClient.normalizedEndpoint("not a url"))
    }

    func testJSONHelperSetsTypeAndMergesFields() {
        let message = RelayClient.json("invite", ["owner": "A", "pet": "B"])
        XCTAssertEqual(message.optString("type"), "invite")
        XCTAssertEqual(message.optString("owner"), "A")
        XCTAssertEqual(message.optString("pet"), "B")
    }

    func testJSONHelperWithNoFields() {
        let message = RelayClient.json("heartbeat")
        XCTAssertEqual(message.optString("type"), "heartbeat")
        XCTAssertEqual(message.count, 1)
    }
}
