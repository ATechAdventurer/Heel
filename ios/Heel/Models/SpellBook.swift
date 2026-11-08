import Foundation

/// Local recordings contain normalized gestures, never device addresses or session authority.
final class SpellBook {
    static let maxDuration: Int64 = 120_000
    static let maxFrames = 6000
    static let maxSpells = 32

    enum SpellBookError: Error, LocalizedError {
        case chooseDeviceFirst
        case spellbookFull
        case saveFailed
        case invalidSpell
        case tooManyGestures
        case invalidChannels
        case invalidLevel
        case invalidGesture
        case invalidDuration
        case mustStartAtRest
        case mustFinishAtRest

        var errorDescription: String? {
            switch self {
            case .chooseDeviceFirst: return "Choose a device first"
            case .spellbookFull: return "Your Spellbook is full. Delete a spell to make room."
            case .saveFailed: return "Could not save your Spellbook. Please try again."
            case .invalidSpell: return "Invalid spell"
            case .tooManyGestures: return "Too many gestures"
            case .invalidChannels: return "Invalid channels"
            case .invalidLevel: return "Invalid level"
            case .invalidGesture: return "Invalid gesture"
            case .invalidDuration: return "Invalid duration"
            case .mustStartAtRest: return "Spell must start at rest"
            case .mustFinishAtRest: return "Spell must finish at rest"
            }
        }
    }

    struct Frame: Codable, Equatable {
        let time: Int64
        let values: [Double]
        let touching: Int

        enum CodingKeys: String, CodingKey {
            case time = "t", values = "v", touching = "touch"
        }
    }

    struct Spell: Codable {
        let id: String
        let name: String
        let sticker: Int
        let channels: Int
        let duration: Int64
        let frames: [Frame]

        init(id: String, name: String, sticker: Int, channels: Int, duration: Int64, frames: [Frame]) throws {
            self.id = id
            self.name = name
            self.sticker = sticker
            self.channels = channels
            self.duration = duration
            self.frames = frames
            try Self.validate(id: id, name: name, sticker: sticker, channels: channels, duration: duration, frames: frames)
        }

        init(from decoder: Decoder) throws {
            let container = try decoder.container(keyedBy: CodingKeys.self)
            try self.init(
                id: try container.decode(String.self, forKey: .id),
                name: try container.decode(String.self, forKey: .name),
                sticker: try container.decode(Int.self, forKey: .sticker),
                channels: try container.decode(Int.self, forKey: .channels),
                duration: try container.decode(Int64.self, forKey: .duration),
                frames: try container.decode([Frame].self, forKey: .frames)
            )
        }

        /// Interpolates between the two frames bracketing `time`; still-touching channels ramp smoothly,
        /// a channel released on either side snaps to the newer frame's value.
        func at(_ time: Int64) -> [Double] {
            guard time >= 0, time < duration else { return Array(repeating: 0, count: channels) }
            var lo = 0, hi = frames.count - 1
            while lo < hi {
                let mid = (lo + hi + 1) / 2
                if frames[mid].time <= time { lo = mid } else { hi = mid - 1 }
            }
            let a = frames[lo]
            var result = a.values
            if lo + 1 < frames.count {
                let b = frames[lo + 1]
                let ratio = Double(time - a.time) / Double(b.time - a.time)
                for i in 0..<channels where (a.touching & b.touching & (1 << i)) != 0 {
                    result[i] = TouchModel.normalize(a.values[i] + (b.values[i] - a.values[i]) * ratio, steps: 0)
                }
            }
            return result
        }

        private static func validate(id: String, name: String, sticker: Int, channels: Int, duration: Int64, frames: [Frame]) throws {
            guard !id.isEmpty, id.count <= 80,
                  !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, name.count <= 40,
                  (0...2).contains(sticker), (1...2).contains(channels),
                  duration >= 1, duration <= maxDuration,
                  frames.count >= 2, frames.count <= maxFrames
            else { throw SpellBookError.invalidSpell }
            var previous: Int64 = -1
            for frame in frames {
                try checkValues(frame.values, count: channels)
                guard frame.time > previous, frame.time <= duration,
                      frame.touching >= 0, frame.touching < (1 << channels)
                else { throw SpellBookError.invalidGesture }
                previous = frame.time
            }
            guard frames[0].time == 0, previous == duration else { throw SpellBookError.invalidDuration }
            guard frames[0].values.allSatisfy({ $0 == 0 }) else { throw SpellBookError.mustStartAtRest }
            guard frames[frames.count - 1].values.allSatisfy({ $0 == 0 }) else { throw SpellBookError.mustFinishAtRest }
        }
    }

    final class Recording {
        let channels: Int
        let started: Int64
        private(set) var frames: [Frame]

        init(channels: Int, now: Int64) throws {
            guard (1...2).contains(channels) else { throw SpellBookError.chooseDeviceFirst }
            self.channels = channels
            self.started = now
            self.frames = [Frame(time: 0, values: Array(repeating: 0, count: channels), touching: 0)]
        }

        @discardableResult
        func add(now: Int64, values: [Double], touching: Int) throws -> Bool {
            let time = max(1, now - started)
            if time >= SpellBook.maxDuration || frames.count >= SpellBook.maxFrames - 1 { return false }
            try checkValues(values, count: channels)
            let previous = frames[frames.count - 1]
            let next = Frame(time: time, values: values, touching: touching)
            // Keep both ends of a stationary span. Repeated samples advance its last anchor,
            // so later motion never invents a ramp through a long stationary finger dwell.
            let stationary = previous.values == values && previous.touching == touching
            let before = frames.count > 1 ? frames[frames.count - 2] : nil
            if previous.time == time || (stationary && before != nil && before!.values == values && before!.touching == touching) {
                frames[frames.count - 1] = next
            } else {
                frames.append(next)
            }
            return true
        }

        func finish(now: Int64, name: String, sticker: Int) throws -> Spell {
            let end = min(SpellBook.maxDuration, max(frames[frames.count - 1].time + 1, now - started))
            var complete = frames
            complete.append(Frame(time: end, values: Array(repeating: 0, count: channels), touching: 0))
            return try Spell(id: UUID().uuidString, name: name.trimmingCharacters(in: .whitespacesAndNewlines),
                              sticker: sticker, channels: channels, duration: end, frames: complete)
        }

        func hasMovement() -> Bool {
            frames.contains { $0.values.contains { $0 > TouchModel.restLimit } }
        }
    }

    private let defaults: UserDefaults
    private let storageKey = "spells"

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    func all() -> [Spell] {
        guard let data = defaults.data(forKey: storageKey), data.count <= 16_000_000,
              let raw = try? JSONDecoder().decode([FailableSpell].self, from: data)
        else { return [] }
        return raw.compactMap(\.spell).prefix(Self.maxSpells).map { $0 }
    }

    func save(_ spell: Spell) throws {
        var list = all()
        guard list.count < Self.maxSpells else { throw SpellBookError.spellbookFull }
        list.insert(spell, at: 0)
        try write(list)
    }

    func delete(id: String) throws {
        var list = all()
        list.removeAll { $0.id == id }
        try write(list)
    }

    private func write(_ spells: [Spell]) throws {
        guard let data = try? JSONEncoder().encode(spells) else { throw SpellBookError.saveFailed }
        defaults.set(data, forKey: storageKey)
    }
}

/// Decodes one array element permissively: an invalid spell becomes nil rather than failing the whole array,
/// matching the per-item try/catch Android's SpellBook.all() uses.
private struct FailableSpell: Decodable {
    let spell: SpellBook.Spell?
    init(from decoder: Decoder) throws {
        spell = try? SpellBook.Spell(from: decoder)
    }
}

private func checkValues(_ values: [Double], count: Int) throws {
    guard values.count == count else { throw SpellBook.SpellBookError.invalidChannels }
    guard values.allSatisfy({ $0.isFinite && $0 >= 0 && $0 <= 1 }) else { throw SpellBook.SpellBookError.invalidLevel }
}
