import SwiftUI

struct RootView: View {
    @EnvironmentObject var lang: LanguageStore
    @EnvironmentObject var appLock: AppLock
    @State private var tab = 0
    @State private var password = ""
    @State private var error = false

    var body: some View {
        Group {
            if appLock.isLocked {
                VStack(spacing: 18) {
                    Image(systemName: "lock.fill").font(.system(size: 42)).foregroundStyle(V2Theme.accent)
                    Text("التطبيق مقفل").font(.title2.bold())
                    SecureField("كلمة السر", text: $password).textFieldStyle(.roundedBorder).frame(maxWidth: 280)
                    Button("دخول") { error = !appLock.unlock(password); if !error { password = "" } }
                        .buttonStyle(.borderedProminent)
                    if error { Text("كلمة السر غير صحيحة").foregroundStyle(.red).font(.caption) }
                }.padding()
            } else {
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
            FormatConversionView()
                .tabItem { Label("تحويل الصيغ", systemImage: "arrow.triangle.2.circlepath") }
                .tag(5)
            DownloadsView()
                .tabItem { Label(lang.t("tab.downloads"), systemImage: "arrow.down.circle.fill") }
                .tag(3)
            SettingsView()
                .tabItem { Label(lang.t("tab.settings"), systemImage: "gearshape.fill") }
                .tag(4)
        }
        .tint(V2Theme.accent)
        .background(V2Theme.bg.ignoresSafeArea())
        .id(lang.code)
                }
        }
    }
}
