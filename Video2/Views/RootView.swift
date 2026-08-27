import SwiftUI

/// موجّه التبويبات: يسمح لأي شاشة (مثل البحث السحري) بفتح نتيجة في المتصفح
/// دون لمس أي منطق قائم في المتصفح أو التحميل.
final class TabRouter: ObservableObject {
    @Published var selection: Int = 0

    @MainActor func openInBrowser(_ urlString: String, browser: BrowserModel) {
        // نفس سلوك المتصفح المعتاد: إعادة استخدام التبويب الفارغ أو فتح تبويب جديد
        if browser.current.urlString.isEmpty {
            browser.current.load(urlString)
        } else {
            browser.newTab()
            browser.current.load(urlString)
        }
        selection = 0
    }
}

struct RootView: View {
    @EnvironmentObject var lang: LanguageStore
    @EnvironmentObject var appLock: AppLock
    @StateObject private var router = TabRouter()

    var body: some View {
        ZStack {
            tabs
                .environmentObject(router)
                .tint(V2Theme.accent)

            // شاشة القفل تغطي كل شيء فوق التبويبات
            if appLock.isLocked {
                LockScreenView()
                    .transition(.opacity)
                    .zIndex(2)
            }
        }
        // تبويب سادس (البحث السحري) "بطريقة آمنة": بدل TabView الذي يولّد تبويب
        // "More" تلقائياً عند تجاوز 5 تبويبات (وكان يسبب قفزة إلى المتصفح)،
        // نستخدم شريط تبويبات مخصصاً يعرض الستة تبويبات مباشرة — لا More، لا قفزات.
        // ترتيب التبويبات الخمسة الأصلية لم يتغير إطلاقاً.
        .safeAreaInset(edge: .bottom, spacing: 0) {
            V2TabBar(selection: $router.selection)
        }
        .background(V2Theme.bg.ignoresSafeArea())
        // الاتجاه RTL مضبوط على مستوى النظام عند الإقلاع (AppleLanguages +
        // semanticContentAttribute في Video2App)، فيعمل بشكل طبيعي بدون قلب
        // الـ Pickers/Forms.
        .id(lang.code)
    }

    @ViewBuilder private var tabs: some View {
        ZStack {
            BrowserView().tabVisible(router.selection == 0)
            LibraryView().tabVisible(router.selection == 1)
            TranslateView().tabVisible(router.selection == 2)
            DownloadsView().tabVisible(router.selection == 3)
            SettingsView().tabVisible(router.selection == 4)
            MagicSearchView().tabVisible(router.selection == 5)
        }
    }
}

/// إبقاء كل الشاشات حيّة (نفس سلوك TabView) مع إظهار المحددة فقط.
private extension View {
    @ViewBuilder func tabVisible(_ visible: Bool) -> some View {
        self
            .opacity(visible ? 1 : 0)
            .allowsHitTesting(visible)
            .accessibilityHidden(!visible)
    }
}

// MARK: - شريط التبويبات المخصص (6 تبويبات، بلا "More" النظامي)

struct V2TabBar: View {
    @Binding var selection: Int
    @EnvironmentObject var lang: LanguageStore

    private struct TabItem {
        let tag: Int
        let key: String
        let icon: String
    }

    private let items: [TabItem] = [
        TabItem(tag: 0, key: "tab.browser", icon: "safari.fill"),
        TabItem(tag: 1, key: "tab.library", icon: "play.square.stack.fill"),
        TabItem(tag: 2, key: "tab.translate", icon: "captions.bubble.fill"),
        TabItem(tag: 3, key: "tab.downloads", icon: "arrow.down.circle.fill"),
        TabItem(tag: 4, key: "tab.settings", icon: "gearshape.fill"),
        TabItem(tag: 5, key: "tab.magic", icon: "wand.and.stars"),
    ]

    var body: some View {
        HStack(spacing: 0) {
            ForEach(items, id: \.tag) { item in
                Button {
                    withAnimation(.easeInOut(duration: 0.12)) {
                        selection = item.tag
                    }
                } label: {
                    VStack(spacing: 3) {
                        Image(systemName: item.icon)
                            .font(.system(size: 19, weight: .medium))
                        Text(lang.t(item.key))
                            .font(.system(size: 9, weight: .semibold))
                            .lineLimit(1)
                            .minimumScaleFactor(0.6)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 6)
                    .padding(.bottom, 3)
                    .foregroundStyle(selection == item.tag ? V2Theme.accent : .secondary)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(lang.t(item.key))
                .accessibilityAddTraits(selection == item.tag ? .isSelected : [])
            }
        }
        .padding(.horizontal, 2)
        .background(
            Rectangle()
                .fill(V2Theme.card.opacity(0.98))
                .overlay(
                    Rectangle()
                        .fill(Color.white.opacity(0.09))
                        .frame(height: 0.5),
                    alignment: .top
                )
                .ignoresSafeArea(edges: .bottom)
        )
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
                .environment(\.layoutDirection, .leftToRight)
                .frame(maxWidth: 280)
            Button("دخول") {
                error = !appLock.unlock(password: password)
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
