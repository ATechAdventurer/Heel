import XCTest
@testable import Heel

/// The App Store rejects uploads that use a required-reason API without declaring it. This
/// guards that the manifest ships in the app and still covers what the app is known to use.
final class PrivacyManifestTests: XCTestCase {
    private func manifest() throws -> [String: Any] {
        let bundle = Bundle(for: RelayClient.self)
        let url = try XCTUnwrap(bundle.url(forResource: "PrivacyInfo", withExtension: "xcprivacy"),
                                "PrivacyInfo.xcprivacy must be in the app bundle")
        let data = try Data(contentsOf: url)
        return try XCTUnwrap(PropertyListSerialization.propertyList(from: data, format: nil) as? [String: Any])
    }

    private func reasons(_ category: String) throws -> [String] {
        let entries = try XCTUnwrap(manifest()["NSPrivacyAccessedAPITypes"] as? [[String: Any]])
        let entry = entries.first { $0["NSPrivacyAccessedAPIType"] as? String == category }
        return entry?["NSPrivacyAccessedAPITypeReasons"] as? [String] ?? []
    }

    func testTheAppDoesNotTrack() throws {
        let manifest = try manifest()
        XCTAssertEqual(manifest["NSPrivacyTracking"] as? Bool, false)
        XCTAssertEqual((manifest["NSPrivacyTrackingDomains"] as? [Any])?.count, 0)
    }

    func testUserDefaultsIsDeclaredForTheAppsOwnSettings() throws {
        XCTAssertEqual(try reasons("NSPrivacyAccessedAPICategoryUserDefaults"), ["CA92.1"])
    }

    func testSystemBootTimeIsDeclaredForElapsedTimeMeasurement() throws {
        XCTAssertEqual(try reasons("NSPrivacyAccessedAPICategorySystemBootTime"), ["35F9.1"])
    }

    func testFileTimestampIsDeclaredForTheRustCoresFileAccess() throws {
        XCTAssertEqual(try reasons("NSPrivacyAccessedAPICategoryFileTimestamp"), ["C617.1"])
    }

    func testNothingUndeclaredAndNothingUnneeded() throws {
        let entries = try XCTUnwrap(manifest()["NSPrivacyAccessedAPITypes"] as? [[String: Any]])
        let declared = Set(entries.compactMap { $0["NSPrivacyAccessedAPIType"] as? String })
        XCTAssertEqual(declared, [
            "NSPrivacyAccessedAPICategoryUserDefaults",
            "NSPrivacyAccessedAPICategorySystemBootTime",
            "NSPrivacyAccessedAPICategoryFileTimestamp",
        ], "Add a category when the app starts using another required-reason API, and drop one it no longer uses")
    }
}
