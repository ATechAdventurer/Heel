import XCTest
@testable import Heel

final class SpellBookTests: XCTestCase {
    private func validSpell(duration: Int64 = 100, channels: Int = 1, middle: Double = 0.8) throws -> SpellBook.Spell {
        try SpellBook.Spell(
            id: "spell-1", name: "Test", sticker: 0, channels: channels, duration: duration,
            frames: [
                SpellBook.Frame(time: 0, values: Array(repeating: 0, count: channels), touching: 0),
                SpellBook.Frame(time: duration / 2, values: Array(repeating: middle, count: channels), touching: 1),
                SpellBook.Frame(time: duration, values: Array(repeating: 0, count: channels), touching: 0),
            ]
        )
    }

    func testValidSpellConstructsSuccessfully() throws {
        let spell = try validSpell()
        XCTAssertEqual(spell.frames.count, 3)
    }

    func testSpellMustStartAtRest() {
        XCTAssertThrowsError(try SpellBook.Spell(
            id: "s", name: "n", sticker: 0, channels: 1, duration: 10,
            frames: [SpellBook.Frame(time: 0, values: [0.5], touching: 0), SpellBook.Frame(time: 10, values: [0], touching: 0)]
        )) { error in
            XCTAssertEqual(error as? SpellBook.SpellBookError, .mustStartAtRest)
        }
    }

    func testSpellMustFinishAtRest() {
        XCTAssertThrowsError(try SpellBook.Spell(
            id: "s", name: "n", sticker: 0, channels: 1, duration: 10,
            frames: [SpellBook.Frame(time: 0, values: [0], touching: 0), SpellBook.Frame(time: 10, values: [0.5], touching: 0)]
        )) { error in
            XCTAssertEqual(error as? SpellBook.SpellBookError, .mustFinishAtRest)
        }
    }

    func testSpellRejectsOutOfOrderFrameTimes() {
        XCTAssertThrowsError(try SpellBook.Spell(
            id: "s", name: "n", sticker: 0, channels: 1, duration: 10,
            frames: [
                SpellBook.Frame(time: 0, values: [0], touching: 0),
                SpellBook.Frame(time: 5, values: [0.5], touching: 1),
                SpellBook.Frame(time: 5, values: [0], touching: 0),
            ]
        )) { error in
            XCTAssertEqual(error as? SpellBook.SpellBookError, .invalidGesture)
        }
    }

    func testSpellRejectsWrongChannelCount() {
        XCTAssertThrowsError(try SpellBook.Spell(
            id: "s", name: "n", sticker: 0, channels: 2, duration: 10,
            frames: [SpellBook.Frame(time: 0, values: [0], touching: 0), SpellBook.Frame(time: 10, values: [0, 0], touching: 0)]
        )) { error in
            XCTAssertEqual(error as? SpellBook.SpellBookError, .invalidChannels)
        }
    }

    func testAtInterpolatesLinearlyBetweenTouchingFrames() throws {
        // The channel is touching on both sides of each segment, so it ramps smoothly;
        // touching=0 would instead snap to the earlier frame's value.
        let spell = try SpellBook.Spell(
            id: "s", name: "n", sticker: 0, channels: 1, duration: 100,
            frames: [
                SpellBook.Frame(time: 0, values: [0], touching: 1),
                SpellBook.Frame(time: 50, values: [1.0], touching: 1),
                SpellBook.Frame(time: 100, values: [0], touching: 1),
            ]
        )
        XCTAssertEqual(spell.at(25)[0], 0.5, accuracy: 1e-9)
    }

    func testAtSnapsWhenEitherSideIsNotTouching() throws {
        let spell = try SpellBook.Spell(
            id: "s", name: "n", sticker: 0, channels: 1, duration: 100,
            frames: [
                SpellBook.Frame(time: 0, values: [0], touching: 0),
                SpellBook.Frame(time: 50, values: [0.8], touching: 0), // held, not touching
                SpellBook.Frame(time: 100, values: [0], touching: 0),
            ]
        )
        // Not touching on either side of this span: snaps to the newer (left) frame's value, no ramp.
        XCTAssertEqual(spell.at(25)[0], 0, accuracy: 1e-9)
    }

    func testAtOutsideDurationReturnsRest() throws {
        let spell = try validSpell(duration: 100)
        XCTAssertEqual(spell.at(-1), [0])
        XCTAssertEqual(spell.at(100), [0])
    }

    func testRecordingRejectsInvalidChannelCount() {
        XCTAssertThrowsError(try SpellBook.Recording(channels: 3, now: 0)) { error in
            XCTAssertEqual(error as? SpellBook.SpellBookError, .chooseDeviceFirst)
        }
    }

    func testRecordingCollapsesRepeatedSamplesAtTheSameTime() throws {
        let recording = try SpellBook.Recording(channels: 1, now: 0)
        XCTAssertTrue(try recording.add(now: 10, values: [0.5], touching: 1))
        XCTAssertTrue(try recording.add(now: 10, values: [0.6], touching: 1), "Same timestamp replaces, not appends")
        XCTAssertEqual(recording.frames.count, 2)
        XCTAssertEqual(recording.frames.last?.values, [0.6])
    }

    func testRecordingKeepsBothEndsOfAStationarySpan() throws {
        let recording = try SpellBook.Recording(channels: 1, now: 0)
        XCTAssertTrue(try recording.add(now: 10, values: [0.5], touching: 1))
        XCTAssertTrue(try recording.add(now: 20, values: [0.5], touching: 1))
        // A third identical sample advances the last anchor instead of growing the frame list.
        XCTAssertTrue(try recording.add(now: 30, values: [0.5], touching: 1))
        XCTAssertEqual(recording.frames.count, 3, "Start-of-rest(0) + two anchors around the stationary span")
    }

    func testFinishProducesAValidSpellEndingAtRest() throws {
        let recording = try SpellBook.Recording(channels: 1, now: 0)
        XCTAssertTrue(try recording.add(now: 10, values: [0.8], touching: 1))
        let spell = try recording.finish(now: 20, name: "  My Spell  ", sticker: 1)
        XCTAssertEqual(spell.name, "My Spell", "Name is trimmed")
        XCTAssertEqual(spell.frames.last?.values, [0])
        XCTAssertTrue(recording.hasMovement())
    }

    func testFinishWithNoMovementReportsNoMovement() throws {
        let recording = try SpellBook.Recording(channels: 1, now: 0)
        XCTAssertFalse(recording.hasMovement())
    }

    func testSaveAndDeleteRoundTripThroughUserDefaults() throws {
        let suiteName = "SpellBookTests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        defer { defaults.removePersistentDomain(forName: suiteName) }
        let book = SpellBook(defaults: defaults)

        XCTAssertEqual(book.all().count, 0)
        let spell = try validSpell()
        try book.save(spell)
        XCTAssertEqual(book.all().first?.id, spell.id)

        try book.delete(id: spell.id)
        XCTAssertEqual(book.all().count, 0)
    }

    func testSaveRejectsWhenSpellbookIsFull() throws {
        let suiteName = "SpellBookTests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        defer { defaults.removePersistentDomain(forName: suiteName) }
        let book = SpellBook(defaults: defaults)
        for i in 0..<SpellBook.maxSpells {
            try book.save(try SpellBook.Spell(
                id: "s-\(i)", name: "n", sticker: 0, channels: 1, duration: 10,
                frames: [SpellBook.Frame(time: 0, values: [0], touching: 0), SpellBook.Frame(time: 10, values: [0], touching: 0)]
            ))
        }
        XCTAssertThrowsError(try book.save(try validSpell())) { error in
            XCTAssertEqual(error as? SpellBook.SpellBookError, .spellbookFull)
        }
    }
}
