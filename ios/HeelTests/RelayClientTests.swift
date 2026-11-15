import XCTest
@testable import Heel

@MainActor
private final class RecordingListener: RelayListener {
    var events: [String] = []
    func relayReceived(_ message: JSONDictionary) { events.append("message:" + message.optString("type")) }
    func relayConnectionChanged(connected: Bool, reason: String) { events.append("connected:\(connected)") }
}

@MainActor
final class RelayClientTests: XCTestCase {
    private func client() -> RelayClient {
        let defaults = UserDefaults(suiteName: "RelayClientTests.\(UUID().uuidString)")!
        return RelayClient(store: CredentialStore(defaults: defaults))
    }

    func testAMessageThatBeatsTheOpenCallbackIsReplayedAfterTheConnectionIsReported() {
        let relay = client()
        let listener = RecordingListener()
        relay.setController(listener)
        listener.events = []
        let now = ProcessInfo.processInfo.systemUptime
        relay.handleMessage(text: #"{"type":"state","phase":"paired"}"#, receivedAt: now, generation: relay.generation)
        XCTAssertTrue(listener.events.isEmpty, "Nothing is delivered before the connection is known")
        relay.socketOpened(generation: relay.generation)
        XCTAssertEqual(listener.events, ["connected:true", "message:state"])
        relay.shutdown()
    }

    func testAMessageFromAnOldConnectionIsStillDropped() {
        let relay = client()
        let listener = RecordingListener()
        relay.setController(listener)
        listener.events = []
        relay.handleMessage(text: #"{"type":"state"}"#, receivedAt: 0, generation: relay.generation - 1)
        relay.socketOpened(generation: relay.generation)
        XCTAssertEqual(listener.events, ["connected:true"])
        relay.shutdown()
    }

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
