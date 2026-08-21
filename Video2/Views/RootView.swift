import SwiftUI

struct RootView: View {
    @State private var tab = 0

    var body: some View {
        TabView(selection: $tab) {
            BrowserView()
                .tabItem { Label("متصفح", systemImage: "safari.fill") }
                .tag(0)
            LibraryView()
                .tabItem { Label("المكتبة", systemImage: "play.square.stack.fill") }
                .tag(1)
            TranslateView()
                .tabItem { Label("الترجمة", systemImage: "captions.bubble.fill") }
                .tag(2)
            DownloadsView()
                .tabItem { Label("التحميلات", systemImage: "arrow.down.circle.fill") }
                .tag(3)
            SettingsView()
                .tabItem { Label("إعدادات", systemImage: "gearshape.fill") }
                .tag(4)
        }
        .tint(V2Theme.accent)
        .background(V2Theme.bg.ignoresSafeArea())
    }
}
