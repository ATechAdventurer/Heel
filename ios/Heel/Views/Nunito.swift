import SwiftUI
import UIKit
import CoreText

/// Nunito ships as a single variable font; Android reaches a specific weight with
/// setFontVariationSettings("'wght' N"). SwiftUI's Font type has no axis API, so this
/// goes through UIFontDescriptor's variation attribute and wraps the result for SwiftUI.
enum Nunito {
    private static let wghtAxis: UInt32 = 0x77676874 // 'wght'
    private static let variationAttribute = UIFontDescriptor.AttributeName(rawValue: kCTFontVariationAttribute as String)

    static func font(size: CGFloat, weight: CGFloat) -> Font {
        let base = UIFont(name: "Nunito", size: size) ?? UIFont.systemFont(ofSize: size)
        let descriptor = base.fontDescriptor.addingAttributes([
            variationAttribute: [wghtAxis: weight]
        ])
        return Font(UIFont(descriptor: descriptor, size: size))
    }
}
