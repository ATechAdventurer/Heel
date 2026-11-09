import SwiftUI
import UIKit

/// Direct-control pad: drag a paw up or down, lift to stop. Mirrors Android's TouchPadView,
/// but drawing happens in UIKit so real multitouch (two paws moving independently) works —
/// SwiftUI's gesture system has no equivalent for that.
final class TouchPadUIView: UIView {
    var onTargetsChanged: (([Double]) -> Void)?
    var onStateChanged: (() -> Void)?
    var onCancelRequested: (() -> Void)?

    private(set) var model = TouchModel()
    var dark = false { didSet { setNeedsDisplay() } }

    private var pointerIds: [ObjectIdentifier: Int] = [:]
    private var nextPointerId = 0
    private var rejectedPoint: Int?
    private var rejectionWorkItem: DispatchWorkItem?

    override init(frame: CGRect) {
        super.init(frame: frame)
        isMultipleTouchEnabled = true
        backgroundColor = .clear
        isAccessibilityElement = false
        NotificationCenter.default.addObserver(self, selector: #selector(releaseForBackground),
                                                name: UIApplication.willResignActiveNotification, object: nil)
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    deinit { NotificationCenter.default.removeObserver(self) }

    func configure(_ features: [TouchModel.Feature]) {
        try? model.configure(features)
        clearFeedback()
        setNeedsDisplay()
    }

    @objc func releaseForBackground() {
        guard model.activeTouchCount() > 0 || model.hasOutput() else { return }
        let before = model.targets()
        model.releaseTouches()
        notifyTargetsIfChanged(before)
        setNeedsDisplay()
        onStateChanged?()
    }

    private func trackTop() -> CGFloat { 28 }
    private func trackBottom() -> CGFloat { max(trackTop() + 1, bounds.height - 28) }
    private func position(_ y: CGFloat) -> Double { Double((trackBottom() - y) / (trackBottom() - trackTop())) }
    private func levelY(_ point: Int) -> CGFloat {
        trackBottom() - CGFloat(TouchModel.requestToTrack(model.visualTarget(point))) * (trackBottom() - trackTop())
    }

    private func pointIndex(for x: CGFloat) -> Int {
        guard bounds.width > 0 else { return 0 }
        let size = model.size
        return min(size - 1, max(0, Int(x * CGFloat(size) / bounds.width)))
    }

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard model.size > 0 else { return }
        let before = model.targets()
        for touch in touches {
            let location = touch.location(in: self)
            let point = pointIndex(for: location.x)
            let tolerance = Double(28 / (trackBottom() - trackTop()))
            let id = nextPointerId; nextPointerId += 1
            let accepted = model.begin(pointerId: id, point: point, position: position(location.y), pickupTolerance: tolerance)
            if accepted {
                pointerIds[ObjectIdentifier(touch)] = id
                clearFeedback()
            } else if !model.isPointActive(point) {
                showPickupFeedback(point)
            }
        }
        notifyTargetsIfChanged(before)
        setNeedsDisplay()
        onStateChanged?()
    }

    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
        let before = model.targets()
        for touch in touches {
            guard let id = pointerIds[ObjectIdentifier(touch)] else { continue }
            model.move(pointerId: id, position: position(touch.location(in: self).y))
        }
        notifyTargetsIfChanged(before)
        setNeedsDisplay()
        onStateChanged?()
    }

    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
        let before = model.targets()
        for touch in touches {
            guard let id = pointerIds.removeValue(forKey: ObjectIdentifier(touch)) else { continue }
            model.move(pointerId: id, position: position(touch.location(in: self).y))
            model.end(pointerId: id)
        }
        clearFeedback()
        notifyTargetsIfChanged(before)
        setNeedsDisplay()
        onStateChanged?()
    }

    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) {
        for touch in touches { pointerIds.removeValue(forKey: ObjectIdentifier(touch)) }
        model.clear()
        pointerIds.removeAll()
        clearFeedback()
        setNeedsDisplay()
        onStateChanged?()
        onCancelRequested?()
    }

    private func notifyTargetsIfChanged(_ before: [Double]) {
        let current = model.targets()
        if current != before { onTargetsChanged?(current) }
    }

    private func showPickupFeedback(_ point: Int) {
        rejectedPoint = point
        rejectionWorkItem?.cancel()
        let work = DispatchWorkItem { [weak self] in self?.rejectedPoint = nil; self?.setNeedsDisplay() }
        rejectionWorkItem = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.7, execute: work)
    }
    private func clearFeedback() { rejectionWorkItem?.cancel(); rejectedPoint = nil }

    override func draw(_ rect: CGRect) {
        guard let ctx = UIGraphicsGetCurrentContext() else { return }
        let background = UIColor(dark ? Color(hex: "#2A2526") : Color(hex: "#E7E0DA"))
        let border = UIColor(dark ? Color(hex: "#54464A") : Color(hex: "#C4B8B2"))
        let restShelf = UIColor(dark ? Color(hex: "#382E31") : Color(hex: "#DED0CA"))
        let wine = UIColor(dark ? Color(hex: "#A65870") : Color(hex: "#7E2E43"))
        let muted = UIColor(dark ? Color(hex: "#C0B1AA") : Color(hex: "#6F6463"))
        let foreground = UIColor(dark ? Color(hex: "#F3ECE5") : Color(hex: "#2E2828"))
        let pointColors = [UIColor(dark ? Color(hex: "#7FD9DF") : Color(hex: "#116A77")),
                            UIColor(dark ? Color(hex: "#C7A7ED") : Color(hex: "#6C4AA1"))]

        let bodyRect = rect.insetBy(dx: 1, dy: 1)
        let bodyPath = UIBezierPath(roundedRect: bodyRect, cornerRadius: 12)
        background.setFill(); bodyPath.fill()
        border.setStroke(); bodyPath.lineWidth = 1; bodyPath.stroke()

        guard model.size > 0 else {
            foreground.setFill()
            let text = "No vibration controls" as NSString
            let attrs: [NSAttributedString.Key: Any] = [.foregroundColor: foreground, .font: UIFont.systemFont(ofSize: 14)]
            let size = text.size(withAttributes: attrs)
            text.draw(at: CGPoint(x: (rect.width - size.width) / 2, y: (rect.height - size.height) / 2), withAttributes: attrs)
            return
        }

        let restTop = trackBottom() - CGFloat(TouchModel.restTrack) * (trackBottom() - trackTop())
        let shelfRect = CGRect(x: 2, y: restTop, width: rect.width - 4, height: rect.height - 2 - restTop)
        restShelf.setFill()
        UIBezierPath(roundedRect: shelfRect, cornerRadius: 10).fill()

        wine.setStroke()
        ctx.setLineWidth(1)
        ctx.move(to: CGPoint(x: 12, y: restTop)); ctx.addLine(to: CGPoint(x: rect.width - 12, y: restTop)); ctx.strokePath()

        border.setStroke()
        for i in 0...4 {
            let y = trackBottom() - CGFloat(TouchModel.requestToTrack(Double(i) / 4.0)) * (trackBottom() - trackTop())
            ctx.move(to: CGPoint(x: 12, y: y)); ctx.addLine(to: CGPoint(x: rect.width - 12, y: y)); ctx.strokePath()
        }

        if model.size == 2 {
            ctx.saveGState()
            ctx.setLineDash(phase: 0, lengths: [3, 4])
            border.setStroke()
            ctx.move(to: CGPoint(x: rect.width / 2, y: 12)); ctx.addLine(to: CGPoint(x: rect.width / 2, y: rect.height - 12)); ctx.strokePath()
            ctx.restoreGState()
        }

        let labelAttrs: [NSAttributedString.Key: Any] = [.foregroundColor: muted, .font: UIFont.systemFont(ofSize: 11)]
        ("100%" as NSString).draw(at: CGPoint(x: rect.width - 12 - 36, y: 6), withAttributes: labelAttrs)
        ("Rest · 0%" as NSString).draw(at: CGPoint(x: rect.width - 12 - 58, y: rect.height - 20), withAttributes: labelAttrs)

        let zoneWidth = rect.width / CGFloat(model.size)
        for i in 0..<model.size {
            let accent = pointColors[i % pointColors.count]
            let x = zoneWidth * (CGFloat(i) + 0.5)
            let y = levelY(i)
            let active = model.isPointActive(i)
            let held = model.isHolding && model.target(i) > 0 && !active

            accent.setStroke()
            ctx.setLineWidth(2)
            ctx.move(to: CGPoint(x: zoneWidth * CGFloat(i) + 11, y: y)); ctx.addLine(to: CGPoint(x: zoneWidth * CGFloat(i + 1) - 11, y: y)); ctx.strokePath()

            if active || rejectedPoint == i {
                accent.withAlphaComponent(0.18).setFill()
                ctx.fillEllipse(in: CGRect(x: x - 25, y: y - 25, width: 50, height: 50))
            }

            ctx.saveGState()
            if held { ctx.setLineDash(phase: 0, lengths: [4, 3]) }
            (held ? accent.withAlphaComponent(0.5) : accent).setStroke()
            ctx.setLineWidth(2)
            ctx.strokeEllipse(in: CGRect(x: x - 25, y: y - 25, width: 50, height: 50))
            ctx.restoreGState()

            accent.setFill()
            ctx.fillEllipse(in: CGRect(x: x - 16, y: y - 16, width: 32, height: 32))

            let numeral = "\(i + 1)" as NSString
            let isLight = accent.isPerceptuallyLight
            let numeralAttrs: [NSAttributedString.Key: Any] = [
                .foregroundColor: isLight ? UIColor(Color(hex: "#1D191A")) : UIColor(Color(hex: "#F4EFE9")),
                .font: UIFont.boldSystemFont(ofSize: 13),
            ]
            let size = numeral.size(withAttributes: numeralAttrs)
            numeral.draw(at: CGPoint(x: x - size.width / 2, y: y - size.height / 2), withAttributes: numeralAttrs)
        }
    }
}

struct TouchPad: UIViewRepresentable {
    let features: [TouchModel.Feature]
    let dark: Bool
    /// Bumped by the owning view model whenever the shared TouchModel mutates from outside
    /// this view (e.g. mirroring an accepted frame), so draw() stays in sync.
    let version: Int
    let onTargetsChanged: ([Double]) -> Void
    let onStateChanged: () -> Void
    let onCancelRequested: () -> Void
    let bind: (TouchModel) -> Void

    func makeUIView(context: Context) -> TouchPadUIView {
        let view = TouchPadUIView()
        view.onTargetsChanged = onTargetsChanged
        view.onStateChanged = onStateChanged
        view.onCancelRequested = onCancelRequested
        view.configure(features)
        bind(view.model)
        return view
    }

    func updateUIView(_ uiView: TouchPadUIView, context: Context) {
        uiView.dark = dark
        if uiView.model.features != features {
            uiView.configure(features)
            bind(uiView.model)
        }
        uiView.setNeedsDisplay()
    }
}

private extension Color {
    init(hex: String) {
        var value: UInt64 = 0
        Scanner(string: hex.trimmingCharacters(in: CharacterSet(charactersIn: "#"))).scanHexInt64(&value)
        self.init(.sRGB,
                   red: Double((value >> 16) & 0xFF) / 255,
                   green: Double((value >> 8) & 0xFF) / 255,
                   blue: Double(value & 0xFF) / 255,
                   opacity: 1)
    }
}

private extension UIColor {
    var isPerceptuallyLight: Bool {
        var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        getRed(&r, green: &g, blue: &b, alpha: &a)
        return (r * 299 + g * 587 + b * 114) > 0.5
    }
}
