import SwiftUI

enum V2Theme {
    static let bg = Color(red: 0.06, green: 0.07, blue: 0.10)
    static let card = Color(red: 0.11, green: 0.13, blue: 0.18)
    static let accent = Color(red: 0.95, green: 0.36, blue: 0.34)
    static let gold = Color(red: 0.95, green: 0.76, blue: 0.35)
    static let mint = Color(red: 0.40, green: 0.85, blue: 0.72)
}

struct GlassCard<Content: View>: View {
    var content: () -> Content
    var body: some View {
        content()
            .padding(14)
            .background(V2Theme.card.opacity(0.92), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
    }
}
