import SwiftUI
import UIKit
import CoreText

/// Nunito ships as a single variable font; Android reaches a specific weight with
/// setFontVariationSettings("'wght' N"). SwiftUI's Font type has no axis API, so this
/// goes through UIFontDescriptor's variation attribute. The result is run back through
/// UIFontMetrics against the closest system text style so it still scales with Dynamic
/// Type. `category` must come from the call site's own `@Environment(\.sizeCategory)`:
/// UIFontMetrics otherwise reads the system's current category at render time rather
/// than SwiftUI's environment, so a view that doesn't re-render for another reason
/// would miss a live size change instead of picking it up immediately.
enum Nunito {
    private static let wghtAxis: UInt32 = 0x77676874 // 'wght'
    private static let variationAttribute = UIFontDescriptor.AttributeName(rawValue: kCTFontVariationAttribute as String)

    static func font(size: CGFloat, weight: CGFloat, relativeTo textStyle: Font.TextStyle = .body, category: ContentSizeCategory) -> Font {
        let base = UIFont(name: "Nunito", size: size) ?? UIFont.systemFont(ofSize: size)
        let descriptor = base.fontDescriptor.addingAttributes([
            variationAttribute: [wghtAxis: weight]
        ])
        let weighted = UIFont(descriptor: descriptor, size: size)
        let traits = UITraitCollection(preferredContentSizeCategory: UIContentSizeCategory(category))
        let scaled = UIFontMetrics(forTextStyle: UIFont.TextStyle(textStyle)).scaledFont(for: weighted, compatibleWith: traits)
        return Font(scaled)
    }
}

private extension UIFont.TextStyle {
    init(_ style: Font.TextStyle) {
        switch style {
        case .largeTitle: self = .largeTitle
        case .title: self = .title1
        case .title2: self = .title2
        case .title3: self = .title3
        case .headline: self = .headline
        case .subheadline: self = .subheadline
        case .callout: self = .callout
        case .footnote: self = .footnote
        case .caption: self = .caption1
        case .caption2: self = .caption2
        default: self = .body
        }
    }
}

private extension UIContentSizeCategory {
    init(_ category: ContentSizeCategory) {
        switch category {
        case .extraSmall: self = .extraSmall
        case .small: self = .small
        case .medium: self = .medium
        case .large: self = .large
        case .extraLarge: self = .extraLarge
        case .extraExtraLarge: self = .extraExtraLarge
        case .extraExtraExtraLarge: self = .extraExtraExtraLarge
        case .accessibilityMedium: self = .accessibilityMedium
        case .accessibilityLarge: self = .accessibilityLarge
        case .accessibilityExtraLarge: self = .accessibilityExtraLarge
        case .accessibilityExtraExtraLarge: self = .accessibilityExtraExtraLarge
        case .accessibilityExtraExtraExtraLarge: self = .accessibilityExtraExtraExtraLarge
        @unknown default: self = .large
        }
    }
}
