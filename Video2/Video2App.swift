import SwiftUI
import AVFoundation

@main
struct Video2App: App {
    @StateObject private var library = LibraryStore()
    @StateObject private var downloads = DownloadManager()
    @StateObject private var browser = BrowserModel()

    init() {
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback)
        try? AVAudioSession.sharedInstance().setActive(true)
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(library)
                .environmentObject(downloads)
                .environmentObject(browser)
                .environment(\.layoutDirection, .rightToLeft)
                .preferredColorScheme(.dark)
                .onAppear {
                    downloads.attach(library: library)
                    library.load()
                }
        }
    }
}
