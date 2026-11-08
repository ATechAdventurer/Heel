import Foundation

/// Platform-independent state for one or two vibration control points.
final class TouchModel {
    static let restLimit = 0.05
    static let restTrack = 0.0665

    enum ModelError: Error, Equatable {
        case invalidFeatureCount
        case duplicateScalarIndex
        case targetCountMismatch
        case touchInProgress
        case targetOutOfRange
    }

    /// Positive counts quantize output; zero means continuous normalized values.
    struct Feature: Equatable {
        let scalarIndex: Int
        let label: String
        let stepCount: Int

        init(scalarIndex: Int, label: String?, stepCount: Int) {
            precondition(scalarIndex >= 0, "Negative scalar index")
            precondition(stepCount >= 0, "Negative step count")
            self.scalarIndex = scalarIndex
            let trimmed = label?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            self.label = trimmed.isEmpty ? "Vibration" : trimmed
            self.stepCount = stepCount
        }
    }

    /// Spatial track coordinates and requested output are deliberately distinct.
    static func trackToRequest(_ position: Double) -> Double {
        let p = min(1, max(0, position))
        return p <= restTrack
            ? restLimit * p / restTrack
            : restLimit + (1 - restLimit) * (p - restTrack) / (1 - restTrack)
    }

    static func requestToTrack(_ value: Double) -> Double {
        let r = min(1, max(0, value))
        return r <= restLimit
            ? restTrack * r / restLimit
            : restTrack + (1 - restTrack) * (r - restLimit) / (1 - restLimit)
    }

    static func normalize(_ value: Double, steps: Int) -> Double {
        guard value.isFinite else { return 0 }
        let r = min(1, max(0, value))
        if r <= restLimit { return 0 }
        let q = steps > 0 ? (r * Double(steps)).rounded() / Double(steps) : r
        return q <= restLimit ? 0 : q
    }

    private(set) var features: [Feature] = []
    private var levels: [Double] = []
    private var visualLevels: [Double] = []
    private var pointers: [Int] = []
    private var downPosition: [Double] = []
    private var downLevel: [Double] = []
    private var restLatched: [Bool] = []
    private(set) var isHolding = false

    func configure(_ replacement: [Feature]) throws {
        guard replacement.count <= 2 else { throw ModelError.invalidFeatureCount }
        for i in replacement.indices {
            for j in 0..<i where replacement[j].scalarIndex == replacement[i].scalarIndex {
                throw ModelError.duplicateScalarIndex
            }
        }
        features = replacement
        levels = Array(repeating: 0, count: replacement.count)
        visualLevels = Array(repeating: 0, count: replacement.count)
        pointers = Array(repeating: -1, count: replacement.count)
        downPosition = Array(repeating: 0, count: replacement.count)
        downLevel = Array(repeating: 0, count: replacement.count)
        restLatched = Array(repeating: false, count: replacement.count)
        isHolding = false
    }

    var size: Int { features.count }
    func feature(_ point: Int) -> Feature { features[point] }
    func targets() -> [Double] { levels }
    func visualTargets() -> [Double] { visualLevels }
    func visualTarget(_ point: Int) -> Double { visualLevels[point] }
    func target(_ point: Int) -> Double { levels[point] }
    func isPointActive(_ point: Int) -> Bool { pointers[point] >= 0 }
    func activeTouchCount() -> Int { pointers.filter { $0 >= 0 }.count }
    func hasOutput() -> Bool { levels.contains { $0 > 0 } }

    /// Position is bottom=0/top=1 and may lie beyond the track. Down never changes output.
    @discardableResult
    func begin(pointerId: Int, point: Int, position: Double, pickupTolerance: Double) -> Bool {
        guard pointerId >= 0, point >= 0, point < size, position.isFinite,
              pickupTolerance.isFinite, pickupTolerance >= 0 else { return false }
        guard findPointer(pointerId) < 0, pointers[point] < 0 else { return false }
        let restPickup = levels[point] == 0 && position >= -pickupTolerance && position <= Self.restTrack
        if !restPickup && abs(position - Self.requestToTrack(visualLevels[point])) > pickupTolerance { return false }
        pointers[point] = pointerId
        downPosition[point] = position
        downLevel[point] = Self.requestToTrack(visualLevels[point])
        restLatched[point] = levels[point] == 0 && position <= Self.restTrack
        return true
    }

    @discardableResult
    func move(pointerId: Int, position: Double) -> Bool {
        guard let point = findPointerIndex(pointerId), position.isFinite else { return false }
        let next: Double
        if position <= Self.restTrack {
            next = 0
            restLatched[point] = true
            downPosition[point] = Self.restTrack
            downLevel[point] = Self.restTrack
        } else {
            if restLatched[point] {
                downPosition[point] = Self.restTrack
                downLevel[point] = Self.restTrack
                restLatched[point] = false
            }
            next = Self.normalize(Self.trackToRequest(downLevel[point] + position - downPosition[point]), steps: 0)
        }
        if visualLevels[point] == next { return false }
        visualLevels[point] = next
        levels[point] = quantize(point, next)
        return true
    }

    /// Releases only this pointer; another point's active/held target stays untouched.
    @discardableResult
    func end(pointerId: Int) -> Bool {
        guard let point = findPointerIndex(pointerId) else { return false }
        pointers[point] = -1
        restLatched[point] = false
        downPosition[point] = 0
        downLevel[point] = 0
        if !isHolding {
            levels[point] = 0
            visualLevels[point] = 0
        }
        return true
    }

    func setHold(_ enabled: Bool) {
        isHolding = enabled
        if !isHolding {
            for i in 0..<size where !isPointActive(i) {
                levels[i] = 0
                visualLevels[i] = 0
            }
        }
    }

    /// Invalidates all old pointer IDs. Only a new begin() can take ownership again.
    func clear() {
        for i in levels.indices {
            levels[i] = 0
            visualLevels[i] = 0
            pointers[i] = -1
            downPosition[i] = 0
            downLevel[i] = 0
            restLatched[i] = false
        }
        isHolding = false
    }

    func releaseTouches() {
        for i in pointers.indices {
            pointers[i] = -1
            restLatched[i] = false
        }
        if !isHolding {
            for i in levels.indices {
                levels[i] = 0
                visualLevels[i] = 0
            }
        }
    }

    /// Synchronizes an alternate input path without inheriting any touch ownership.
    func setTargets(_ replacement: [Double]) throws {
        guard replacement.count == size else { throw ModelError.targetCountMismatch }
        guard activeTouchCount() == 0 else { throw ModelError.touchInProgress }
        guard replacement.allSatisfy({ $0.isFinite && $0 >= 0 && $0 <= 1 }) else { throw ModelError.targetOutOfRange }
        for i in 0..<size {
            let value = quantize(i, replacement[i])
            levels[i] = value
            visualLevels[i] = value
        }
    }

    /// Playback visuals remain continuous; actual output always uses the device's stages.
    func setVisualTargets(_ replacement: [Double]) throws {
        try setTargets(replacement)
        for i in 0..<size {
            visualLevels[i] = Self.normalize(replacement[i], steps: 0)
        }
    }

    private func findPointer(_ pointerId: Int) -> Int {
        findPointerIndex(pointerId) ?? -1
    }

    private func findPointerIndex(_ pointerId: Int) -> Int? {
        guard pointerId >= 0 else { return nil }
        return pointers.firstIndex(of: pointerId)
    }

    private func quantize(_ point: Int, _ value: Double) -> Double {
        Self.normalize(value, steps: features[point].stepCount)
    }
}
