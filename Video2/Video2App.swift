import SwiftUI
import AVFoundation

@main
struct Video2App: App {
    @StateObject private var library = LibraryStore()
    @StateObject private var downloads = DownloadManager()
    @StateObject private var browser = BrowserModel()
    @StateObject private var lang = LanguageStore()
    @StateObject private var translations = TranslationManager()
    @StateObject private var converter = FormatConverter()
    @StateObject private var appLock = AppLock()

    init() {
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
                }
        }
    }
}
