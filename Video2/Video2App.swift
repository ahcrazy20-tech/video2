import SwiftUI
import AVFoundation
import UIKit

@main
struct Video2App: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var library = LibraryStore()
    @StateObject private var downloads = DownloadManager()
    @StateObject private var browser = BrowserModel()
    @StateObject private var lang = LanguageStore()
    @StateObject private var translations = TranslationManager()
    @StateObject private var converter = FormatConverter()
    @StateObject private var appLock = AppLock()

    init() {
        // Force the app's base language at launch so iOS lays the UI out RTL
        // natively. This is the correct fix for the "flipped Pickers/Forms in
        // RTL" SwiftUI bug — forcing `.environment(\.layoutDirection: .rightToLeft)`
        // at the root double-flips NavigationStack/Form content (mirrored options).
        let saved = UserDefaults.standard.string(forKey: LanguageStore.key)
        let code = (saved == "en" || saved == "ar") ? saved! : "ar"
        UserDefaults.standard.set([code], forKey: "AppleLanguages")
        UIView.appearance().semanticContentAttribute = (code == "en")
            ? .forceLeftToRight
            : .forceRightToLeft

        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback)
        try? AVAudioSession.sharedInstance().setActive(true)
        AdBlock.compileIfNeeded()
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(library)
                .environmentObject(downloads)
                .environmentObject(browser)
                .environmentObject(lang)
                .environmentObject(translations)
                .environmentObject(converter)
                .environmentObject(appLock)
                .preferredColorScheme(.dark)
                .onAppear {
                    downloads.attach(library: library)
                    translations.attach(library: library)
                    converter.attach(library: library)
                    library.load()
                    translations.load()
                    converter.load()
                    downloads.load()
                }
                .onChange(of: scenePhase) { phase in
                    if phase != .active {
                        downloads.saveIndex()
                        library.saveIndex()
                    }
                }
        }
    }
}
