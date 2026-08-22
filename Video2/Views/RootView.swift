import SwiftUI

struct RootView: View {
    @EnvironmentObject var lang: LanguageStore
    @EnvironmentObject var appLock: AppLock
    @State private var tab = 0

    var body: some View {
        ZStack {
            // تبويب واحد فقط (بلا تداخل) — إصلاح في العدد السابق الذي سبّب قفزة
            // التبويبات لمتصفح Safari عند الضغط على "More".
            // 5 تبويبات فقط — نظام iOS يُظهر تبويب "More" تلقائياً إذا تجاوزت 5،
            // وهو ما سبّب أن يفتح المتصفّح عند الضغط عليه.
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

            // شاشة القفل تغطي كل شيء فوق التبويبات
            if appLock.isLocked {
                LockScreenView()
                    .transition(.opacity)
                    .zIndex(2)
            }
        }
        .background(V2Theme.bg.ignoresSafeArea())
        .id(lang.code)
    }
}

// MARK: - شاشة إدخال كلمة السر

struct LockScreenView: View {
    @EnvironmentObject var appLock: AppLock
    @State private var password = ""
    @State private var error = false

    var body: some View {
        VStack(spacing: 18) {
            Image(systemName: "lock.fill")
                .font(.system(size: 42))
                .foregroundStyle(V2Theme.accent)
            Text("التطبيق مقفل")
                .font(.title2.bold())
                .foregroundStyle(.white)
            SecureField("كلمة السر", text: $password)
                .textFieldStyle(.roundedBorder)
                .frame(maxWidth: 280)
            Button("دخول") {
                error = !appLock.unlock(password)
                if !error { password = "" }
            }
            .buttonStyle(.borderedProminent)
            .disabled(password.isEmpty)
            if error {
                Text("كلمة السر غير صحيحة")
                    .foregroundStyle(.red)
                    .font(.caption)
            }
        }
        .padding()
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(V2Theme.bg.ignoresSafeArea())
    }
}
