import SwiftUI

struct RootView: View {
    @EnvironmentObject var lang: LanguageStore
    @State private var tab = 0

    var body: some View {
        TabView(selection: $tab) {
            BrowserView()
                .tabItem { Label(lang.t("tab.browser"), systemImage: "safari.fill") }
                .tag(0)
            LibraryView()
                .tabItem { Label(lang.t("tab.library"), systemImage: "play.square.stack.fill") }
                .tag(1)
            TranslateView()
                .tabItem { Label(lang.t("tab.translate"), systemImage: "captions.bubble.fill") }
                .tag(2)
            DownloadsView()
                .tabItem { Label(lang.t("tab.downloads"), systemImage: "arrow.down.circle.fill") }
                .tag(3)
            SettingsView()
                .tabItem { Label(lang.t("tab.settings"), systemImage: "gearshape.fill") }
                .tag(4)
        }
        .tint(V2Theme.accent)
        .background(V2Theme.bg.ignoresSafeArea())
        .environment(\.layoutDirection, lang.isRTL ? .rightToLeft : .leftToRight)
        .id(lang.code)
    }
}
