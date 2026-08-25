import SwiftUI
import AVKit
import AVFoundation
import MediaPlayer
import UIKit

final class OfflinePlayerModel: ObservableObject {
    let video: SavedVideo
    let player: AVPlayer
    private let layer: AVPlayerLayer
    private var timeObs: Any?
    private var endObs: NSObjectProtocol?

    // Reference to language store for translated toast messages
    weak var lang: LanguageStore?

    @Published var isPlaying = false
    @Published var current: Double = 0
    @Published var duration: Double = 0
    @Published var rate: Float = 1
    @Published var looping = false
    @Published var fill = true
    @Published var locked = false
    @Published var showChrome = true
    @Published var toast: String?
    @Published var sleepMinutes: Int = 0
    @Published var subtitleText: String = ""
    @Published var autoSpeak = false

    // الدبلجة — تُشغَّل بدل الصوت الأصلي مع مزامنة مستمرة
    @Published var hasDub = false
    @Published var dubOn = false
    private var dubPlayer: AVPlayer?
    private var dubEndObs: NSObjectProtocol?
    private var dubFinished = false

    // كتم الصوت
    @Published var mutedUser = false

    // الترجمة
    var origCues: [SubCue] = []
    var trCues: [SubCue] = []
    private var subModes: [SubtitleDisplayMode] = [.off]

    var subMode: SubtitleDisplayMode {
        SubtitleDisplayMode(rawValue: UserDefaults.standard.string(forKey: "sub.mode") ?? "") ?? .translated
    }
    var subFontSize: Int {
        let v = UserDefaults.standard.integer(forKey: "sub.fontSize")
        return v == 0 ? 18 : v
    }
    var hasSubtitles: Bool { !origCues.isEmpty || !trCues.isEmpty }
    var availableModes: [SubtitleDisplayMode] { subModes }

    private var hideWork: DispatchWorkItem?
    private var sleepWork: DispatchWorkItem?
    private var statusObs: NSKeyValueObservation?
    private var lastSpoken = ""
    private var lastNowPlayingAt = Date.distantPast
    let pip: AVPictureInPictureController?

    init(video: SavedVideo, lang: LanguageStore? = nil) {
        self.video = video
        self.lang = lang
        let p = AVPlayer()
        p.actionAtItemEnd = .pause
        player = p
        let pl = AVPlayerLayer(player: p)
        pl.videoGravity = .resizeAspect
        layer = pl
        fill = false
        duration = video.duration ?? 0
        let savedRate = UserDefaults.standard.float(forKey: "player.rate")
        rate = savedRate > 0 ? savedRate : 1
        if AVPictureInPictureController.isPictureInPictureSupported() {
            pip = AVPictureInPictureController(playerLayer: pl)
        } else {
            pip = nil
        }
    }

    var playerLayer: AVPlayerLayer { layer }

    func t(_ key: String) -> String {
        lang?.t(key) ?? key
    }

    func start() {
        UIApplication.shared.isIdleTimerDisabled = true
        setupRemoteCommands()
        loadSubtitles()
        do {
            let url = try LocalFileServer.shared.playbackURL(for: video)
            let item = AVPlayerItem(url: url)
            player.replaceCurrentItem(with: item)
            statusObs = item.observe(\.status, options: [.new]) { [weak self] item, _ in
                DispatchQueue.main.async {
                    guard let self else { return }
                    if item.status == .failed {
                        self.flash(item.error?.localizedDescription ?? self.t("pl.fail"))
                        self.isPlaying = false
                    } else if item.status == .readyToPlay {
                        if self.video.lastPosition > 1 {
                            self.player.seek(to: CMTime(seconds: self.video.lastPosition, preferredTimescale: 600))
                        }
                        self.play()
                    }
                }
            }
            endObs = NotificationCenter.default.addObserver(forName: .AVPlayerItemDidPlayToEndTime, object: item, queue: .main) { [weak self] _ in
                guard let self else { return }
                if self.looping {
                    self.seek(to: 0)
                    self.player.play()
                } else {
                    self.isPlaying = false
                    self.showChrome = true
                }
            }
        } catch {
            flash(t("pl.prep"))
        }
        setupDub()
        timeObs = player.addPeriodicTimeObserver(forInterval: CMTime(seconds: 0.25, preferredTimescale: 600), queue: .main) { [weak self] t in
            guard let self else { return }
            self.current = t.seconds
            self.updateSubtitle(at: t.seconds)
            self.syncDub()
            if let d = self.player.currentItem?.duration.seconds, d.isFinite, d > 0 {
                self.duration = d
            } else if let known = self.video.duration, known > 0 {
                self.duration = known
            }
            self.publishNowPlaying(force: false)
        }
        play()
        scheduleHide()
    }

    func stop() {
        UIApplication.shared.isIdleTimerDisabled = false
        if let timeObs { player.removeTimeObserver(timeObs) }
        if let endObs { NotificationCenter.default.removeObserver(endObs) }
        if let dubEndObs { NotificationCenter.default.removeObserver(dubEndObs) }
        player.pause()
        dubPlayer?.pause()
        dubPlayer = nil
        sleepWork?.cancel()
        MainActor.assumeIsolated { SpeechNarrator.shared.stop() }
        teardownRemoteCommands()
    }

    func play() {
        player.rate = rate
        player.play()
        isPlaying = true
        scheduleHide()
        publishNowPlaying(force: true)
        if dubOn, !dubFinished { dubPlayer?.play() }
    }

    func pause() {
        player.pause()
        isPlaying = false
        showChrome = true
        MainActor.assumeIsolated { SpeechNarrator.shared.stop() }
        publishNowPlaying(force: true)
        if dubOn { dubPlayer?.pause() }
    }

    func toggle() {
        isPlaying ? pause() : play()
    }

    func seek(to seconds: Double) {
        let t = min(max(0, seconds), max(duration, 0.1))
        player.seek(to: CMTime(seconds: t, preferredTimescale: 600), toleranceBefore: .zero, toleranceAfter: .zero)
        current = t
        // الدبلجة: نقفز معها فوراً (الفرق الأكبر يتصحّح عبر syncDub)
        if dubOn, let dp = dubPlayer {
            dp.seek(to: CMTime(seconds: t, preferredTimescale: 600), toleranceBefore: .zero, toleranceAfter: .zero)
        }
    }

    func skip(_ delta: Double) {
        seek(to: current + delta)
        flash("\(delta > 0 ? "+" : "")\(Int(delta)) s")
    }

    func setRate(_ r: Float) {
        rate = r
        UserDefaults.standard.set(r, forKey: "player.rate")
        if isPlaying { player.rate = r }
        flash(String(format: "\(t("pl.speed")) ×%.2g", r))
    }

    func toggleFill() {
        fill.toggle()
        layer.videoGravity = fill ? .resizeAspectFill : .resizeAspect
        flash(fill ? t("pl.fill") : t("pl.fit"))
    }

    // MARK: الدبلجة

    /// يجهّز مشغّل الدبلجة إذا كان الفيديو معه ملف دبلجة محفوظ
    private func setupDub() {
        guard let rel = video.dubbedAudioPath,
              FileManager.default.fileExists(atPath: LibraryStore.documents.appendingPathComponent(rel).path) else { return }
        let url = LibraryStore.documents.appendingPathComponent(rel)
        let dp = AVPlayer(url: url)
        dp.actionAtItemEnd = .pause
        dubPlayer = dp
        hasDub = true
        dubEndObs = NotificationCenter.default.addObserver(forName: .AVPlayerItemDidPlayToEndTime,
                                                            object: dp.currentItem, queue: .main) { [weak self] _ in
            // الدبلجة انتهت (أقصر من الفيديو) — الصوت الأصلي يرجع تلقائياً
            guard let self else { return }
            self.dubFinished = true
            self.dubOn = false
            self.applyMute()
        }
        // تفعيل تلقائي: الهدف من الدبلجة هو مشاهدة الفيديو بها
        dubOn = true
        if video.lastPosition > 1 {
            dp.seek(to: CMTime(seconds: video.lastPosition, preferredTimescale: 600))
        }
        dp.play()
        applyMute()
        flash(t("pl.dub.on"))
    }

    /// يحافظ على مزامنة الدبلجة مع الفيديو (من المراقب الدوري 0.25ث)
    private func syncDub() {
        guard dubOn, !dubFinished,
              let dp = dubPlayer,
              let item = dp.currentItem, item.status == .readyToPlay else { return }
        let d = item.currentTime().seconds
        let m = current
        if d.isFinite, m.isFinite, abs(d - m) > 0.8 {
            dp.seek(to: CMTime(seconds: m, preferredTimescale: 600), toleranceBefore: .zero, toleranceAfter: .zero)
        }
        if isPlaying, dp.rate == 0 {
            dp.play()
        } else if !isPlaying, dp.rate != 0 {
            dp.pause()
        }
    }

    func toggleDub() {
        guard let dp = dubPlayer else { return }
        dubOn.toggle()
        if dubOn {
            dubFinished = false
            let t = CMTime(seconds: current, preferredTimescale: 600)
            dp.seek(to: t, toleranceBefore: .zero, toleranceAfter: .zero)
            if isPlaying { dp.play() }
        } else {
            dp.pause()
        }
        applyMute()
        flash(dubOn ? t("pl.dub.on") : t("pl.dub.off"))
    }

    /// كتم الصوت الأصلي أثناء تشغيل الدبلجة
    private func applyMute() {
        player.isMuted = mutedUser || (dubOn && !dubFinished)
    }

    // MARK: كتم الصوت

    func toggleMute() {
        mutedUser.toggle()
        applyMute()
        flash(mutedUser ? t("pl.mute") : t("pl.unmute"))
    }

    // MARK: لقطة

    func snapshot() {
        let url: URL
        if video.kind == .hls || video.localURL.pathExtension.lowercased() == "m3u8" {
            guard let u = try? LocalFileServer.shared.playbackURL(for: video) else {
                flash(t("pl.snapshot.fail"))
                return
            }
            url = u
        } else {
            url = video.localURL
        }
        let asset = AVURLAsset(url: url)
        let gen = AVAssetImageGenerator(asset: asset)
        gen.appliesPreferredTrackTransform = true
        gen.maximumSize = CGSize(width: 1920, height: 1080)
        let cmTime = CMTime(seconds: current, preferredTimescale: 600)
        Task {
            do {
                let (cg, _) = try await gen.image(at: cmTime)
                let img = UIImage(cgImage: cg)
                UIImageWriteToSavedPhotosAlbum(img, nil, nil, nil)
                flash(t("pl.snapshot.saved"))
            } catch {
                flash(t("pl.snapshot.fail"))
            }
        }
    }

    // MARK: دوران الشاشة

    var isLandscapeNow: Bool {
        UIScreen.main.bounds.width > UIScreen.main.bounds.height
    }

    @MainActor
    func rotate() {
        // التبديل عبر requestGeometryUpdate (iOS 16+) — لا يترك حالة الجهاز
        // متعارضة مع الاتجاه الفعلي كما كان يفعل setValue(forKey:"orientation").
        OrientationLock.shared.toggleLandscape(currentlyLandscape: isLandscapeNow)
        flash(isLandscapeNow ? t("pl.portrait") : t("pl.landscape"))
    }

    func startSleep(_ minutes: Int) {
        sleepWork?.cancel()
        sleepMinutes = minutes
        guard minutes > 0 else { flash(t("pl.cancel.timer")); return }
        flash(String(format: t("pl.sleep.in"), minutes))
        let w = DispatchWorkItem { [weak self] in
            self?.pause()
            self?.sleepMinutes = 0
            self?.flash(self?.t("pl.sleep.done") ?? "Stopped")
        }
        sleepWork = w
        DispatchQueue.main.asyncAfter(deadline: .now() + Double(minutes * 60), execute: w)
    }

    func flash(_ t: String) {
        toast = t
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.2) {
            if self.toast == t { self.toast = nil }
        }
    }

    func loadSubtitles() {
        let urls = TranslationManager.subtitleURLs(for: video)
        if let o = urls.orig {
            origCues = SubtitleCodec.parseSRTFile(at: o)
        }
        if let t = urls.target {
            trCues = SubtitleCodec.parseSRTFile(at: t)
        } else if let b = urls.bilingual {
            let cues = SubtitleCodec.parseSRTFile(at: b)
            trCues = cues.compactMap { c in
                let lines = c.text.components(separatedBy: "\n")
                guard let first = lines.first, !first.isEmpty else { return nil }
                var copy = c
                copy.text = first
                return copy
            }
        }
        var modes: [SubtitleDisplayMode] = [.off]
        if !origCues.isEmpty { modes.append(.original) }
        if !trCues.isEmpty { modes.append(.translated) }
        if !origCues.isEmpty && !trCues.isEmpty { modes.append(.bilingual) }
        subModes = modes
        updateSubtitle(at: video.lastPosition > 1 ? video.lastPosition : 0)
    }

    func setSubMode(_ raw: String) {
        UserDefaults.standard.set(raw, forKey: "sub.mode")
        updateSubtitle(at: current)
    }

    func setSubFontSize(_ size: Int) {
        UserDefaults.standard.set(size, forKey: "sub.fontSize")
    }

    func updateSubtitle(at t: Double) {
        guard t.isFinite else { return }
        guard subMode != .off, hasSubtitles else {
            if !subtitleText.isEmpty { subtitleText = "" }
            return
        }
        let text = SubtitleOverlayRenderer.text(original: origCues, translated: trCues,
                                                mode: subMode, time: t)
        if text != subtitleText { subtitleText = text ?? "" }
        if autoSpeak {
            let spoken = (text ?? "").replacingOccurrences(of: "\n", with: " ")
            if !spoken.isEmpty, spoken != lastSpoken {
                lastSpoken = spoken
                let langCode = video.subtitleTargetLang ?? "ar"
                MainActor.assumeIsolated { SpeechNarrator.shared.speak(spoken, language: langCode) }
            }
        }
    }

    func toggleAutoSpeak() {
        autoSpeak.toggle()
        if !autoSpeak {
            MainActor.assumeIsolated { SpeechNarrator.shared.stop() }
            flash(t("pl.tts.off"))
        } else {
            lastSpoken = ""
            updateSubtitle(at: current)
            flash(t("pl.tts.on"))
        }
    }

    func searchHits(query: String) -> [SubtitleHit] {
        let q = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard q.count >= 1 else { return [] }
        var hits: [SubtitleHit] = []
        func add(_ cues: [SubCue], kind: String) {
            for c in cues where c.text.localizedCaseInsensitiveContains(q) {
                hits.append(SubtitleHit(id: "\(kind)-\(c.id)-\(c.start)", start: c.start, text: c.text, kind: kind))
            }
        }
        add(trCues, kind: "tr")
        add(origCues, kind: "orig")
        hits.sort { $0.start < $1.start }
        return hits
    }

    private func setupRemoteCommands() {
        UIApplication.shared.beginReceivingRemoteControlEvents()
        let c = MPRemoteCommandCenter.shared()
        c.playCommand.isEnabled = true
        c.pauseCommand.isEnabled = true
        c.togglePlayPauseCommand.isEnabled = true
        c.skipForwardCommand.isEnabled = true
        c.skipBackwardCommand.isEnabled = true
        c.skipForwardCommand.preferredIntervals = [10]
        c.skipBackwardCommand.preferredIntervals = [10]
        c.changePlaybackPositionCommand.isEnabled = true

        c.playCommand.removeTarget(nil)
        c.pauseCommand.removeTarget(nil)
        c.togglePlayPauseCommand.removeTarget(nil)
        c.skipForwardCommand.removeTarget(nil)
        c.skipBackwardCommand.removeTarget(nil)
        c.changePlaybackPositionCommand.removeTarget(nil)

        c.playCommand.addTarget { [weak self] _ in
            DispatchQueue.main.async { self?.play() }
            return .success
        }
        c.pauseCommand.addTarget { [weak self] _ in
            DispatchQueue.main.async { self?.pause() }
            return .success
        }
        c.togglePlayPauseCommand.addTarget { [weak self] _ in
            DispatchQueue.main.async { self?.toggle() }
            return .success
        }
        c.skipForwardCommand.addTarget { [weak self] _ in
            DispatchQueue.main.async { self?.skip(10) }
            return .success
        }
        c.skipBackwardCommand.addTarget { [weak self] _ in
            DispatchQueue.main.async { self?.skip(-10) }
            return .success
        }
        c.changePlaybackPositionCommand.addTarget { [weak self] event in
            guard let ev = event as? MPChangePlaybackPositionCommandEvent else { return .commandFailed }
            DispatchQueue.main.async { self?.seek(to: ev.positionTime) }
            return .success
        }
        publishNowPlaying(force: true)
    }

    private func teardownRemoteCommands() {
        let c = MPRemoteCommandCenter.shared()
        c.playCommand.removeTarget(nil)
        c.pauseCommand.removeTarget(nil)
        c.togglePlayPauseCommand.removeTarget(nil)
        c.skipForwardCommand.removeTarget(nil)
        c.skipBackwardCommand.removeTarget(nil)
        c.changePlaybackPositionCommand.removeTarget(nil)
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
    }

    func publishNowPlaying(force: Bool) {
        let now = Date()
        if !force, now.timeIntervalSince(lastNowPlayingAt) < 1 { return }
        lastNowPlayingAt = now
        var info: [String: Any] = [
            MPMediaItemPropertyTitle: video.title,
            MPMediaItemPropertyAlbumTitle: "فيديو ٢",
            MPNowPlayingInfoPropertyElapsedPlaybackTime: current,
            MPMediaItemPropertyPlaybackDuration: max(duration, 0),
            MPNowPlayingInfoPropertyPlaybackRate: isPlaying ? Double(rate) : 0
        ]
        if let rel = video.thumbnailRelativePath {
            let path = LibraryStore.documents.appendingPathComponent(rel).path
            if let img = UIImage(contentsOfFile: path) {
                info[MPMediaItemPropertyArtwork] = MPMediaItemArtwork(boundsSize: img.size) { _ in img }
            }
        }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
    }

    func bumpChrome() {
        showChrome = true
        scheduleHide()
    }

    func scheduleHide() {
        hideWork?.cancel()
        guard isPlaying, !locked else { return }
        let w = DispatchWorkItem { [weak self] in self?.showChrome = false }
        hideWork = w
        DispatchQueue.main.asyncAfter(deadline: .now() + 3.5, execute: w)
    }

    func fmt(_ s: Double) -> String {
        guard s.isFinite else { return "0:00" }
        let n = Int(max(0, s))
        let h = n / 3600
        let m = (n % 3600) / 60
        let sec = n % 60
        if h > 0 { return String(format: "%d:%02d:%02d", h, m, sec) }
        return String(format: "%d:%02d", m, sec)
    }
}

struct PlayerLayerView: UIViewRepresentable {
    let layer: AVPlayerLayer
    func makeUIView(context: Context) -> PlayerUIView {
        let v = PlayerUIView()
        v.playerLayer = layer
        v.layer.addSublayer(layer)
        return v
    }
    func updateUIView(_ uiView: PlayerUIView, context: Context) {
        uiView.playerLayer = layer
        layer.frame = uiView.bounds
    }
    final class PlayerUIView: UIView {
        var playerLayer: AVPlayerLayer?
        override func layoutSubviews() {
            super.layoutSubviews()
            playerLayer?.frame = bounds
        }
    }
}

struct SubtitleHit: Identifiable {
    var id: String
    var start: Double
    var text: String
    var kind: String
}

struct OfflinePlayerView: View {
    let video: SavedVideo
    @EnvironmentObject var library: LibraryStore
    @EnvironmentObject var lang: LanguageStore
    @Environment(\.dismiss) var dismiss
    @StateObject private var vm: OfflinePlayerModel
    @State private var dragStart: Double?
    @State private var showSpeed = false
    @State private var showAirPlay = false
    @State private var showSearch = false

    init(video: SavedVideo) {
        self.video = video
        _vm = StateObject(wrappedValue: OfflinePlayerModel(video: video))
    }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            PlayerLayerView(layer: vm.playerLayer)
                .ignoresSafeArea()
                .gesture(tapGestures)
                .gesture(verticalScrub)
            if vm.subMode != .off, !vm.subtitleText.isEmpty {
                SubtitleOverlay(text: vm.subtitleText, fontSize: vm.subFontSize)
                    .padding(.bottom, (vm.showChrome || vm.locked) ? 128 : 28)
                    .allowsHitTesting(false)
            }
            if vm.showChrome || vm.locked {
                chrome
            }
            if let t = vm.toast {
                Text(t)
                    .font(.headline)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 10)
                    .background(.ultraThinMaterial, in: Capsule())
            }
        }
        .statusBarHidden(true)
        .persistentSystemOverlays(.hidden)
        .onAppear {
            // Connect the language store to the model after init
            vm.lang = lang
            vm.start()
            // يدعم التطبيق الوضعين العمودي والأفقي بشكل طبيعي، بدون قفل المشغّل على الأفقي.
        }
        .onDisappear {
            library.updatePosition(id: video.id, position: vm.current, duration: vm.duration)
            vm.stop()
            // لا نغيّر قناع الاتجاه عند الخروج؛ كل الشاشات تدعم الدوران الطبيعي.
        }
        .confirmationDialog(lang.t("pl.speed"), isPresented: $showSpeed, titleVisibility: .visible) {
            ForEach([0.5, 0.75, 1.0, 1.25, 1.5, 2.0], id: \.self) { r in
                Button(r == 1.0 ? lang.t("pl.normal") : "×\(r)") { vm.setRate(Float(r)) }
            }
            Button(lang.t("nav.cancel"), role: .cancel) {}
        }
        .sheet(isPresented: $showAirPlay) {
            VStack(spacing: 12) {
                AirPlayPicker()
                    .frame(height: 44)
                Button(lang.t("nav.close")) { showAirPlay = false }
                    .font(.caption.bold())
            }
            .padding(20)
            .presentationDetents([.height(120)])
        }
    }

    /// قائمة "المزيد" — مميزات ثانوية في مكان واحد عشان الشريط العلوي يفضل مرتب
    private var moreMenu: some View {
        Menu {
            Menu(lang.t("pl.sleep")) {
                Button("15 min") { vm.startSleep(15) }
                Button("30 min") { vm.startSleep(30) }
                Button("45 min") { vm.startSleep(45) }
                Button("60 min") { vm.startSleep(60) }
                Button(lang.t("pl.sleep.off")) { vm.startSleep(0) }
            }
            Button { vm.snapshot() } label: { Label(lang.t("pl.snapshot"), systemImage: "camera") }
            Button { showAirPlay = true } label: { Label(lang.t("pl.airplay"), systemImage: "dot.radiowaves.left.and.right") }
            Button { vm.rotate() } label: {
                Label(vm.isLandscapeNow ? lang.t("pl.portrait") : lang.t("pl.landscape"), systemImage: "rotate.right")
            }
            if !(video.kind == .hls || video.localURL.pathExtension.lowercased() == "m3u8") {
                ShareLink(item: video.localURL) {
                    Label(lang.t("pl.share"), systemImage: "square.and.arrow.up")
                }
            }
        } label: {
            Image(systemName: "ellipsis.circle").padding(8)
        }
    }

    private var subModeBinding: Binding<String> {
        Binding(get: {
            UserDefaults.standard.string(forKey: "sub.mode") ?? SubtitleDisplayMode.translated.rawValue
        }, set: { raw in
            vm.setSubMode(raw)
        })
    }

    private var subFontSizeBinding: Binding<Int> {
        Binding(get: {
            let v = UserDefaults.standard.integer(forKey: "sub.fontSize")
            return v == 0 ? 18 : v
        }, set: { size in
            vm.setSubFontSize(size)
        })
    }

    private var tapGestures: some Gesture {
        SpatialTapGesture(count: 2).onEnded { v in
            let w = UIScreen.main.bounds.width
            if v.location.x < w * 0.33 { vm.skip(-10) }
            else if v.location.x > w * 0.66 { vm.skip(10) }
            else { vm.toggle() }
        }
        .exclusively(before: TapGesture().onEnded {
            if vm.locked { return }
            vm.showChrome.toggle()
            if vm.showChrome { vm.scheduleHide() }
        })
    }

    private var verticalScrub: some Gesture {
        DragGesture(minimumDistance: 24)
            .onChanged { g in
                if vm.locked { return }
                let h = UIScreen.main.bounds.height
                let delta = -g.translation.height / h
                if g.startLocation.x < UIScreen.main.bounds.width * 0.4 {
                    UIScreen.main.brightness = min(1, max(0, UIScreen.main.brightness + delta * 0.04))
                } else if g.startLocation.x > UIScreen.main.bounds.width * 0.6 {
                    let vol = AVAudioSession.sharedInstance().outputVolume
                    MPVolumeView.setVolume(min(1, max(0, vol + Float(delta * 0.04))))
                }
            }
    }

    private var chrome: some View {
        VStack(spacing: 0) {
            HStack {
                Button { dismiss() } label: {
                    Image(systemName: "chevron.forward")
                        .font(.title2.bold())
                        .padding(12)
                }
                VStack(alignment: .leading, spacing: 2) {
                    Text(video.title).font(.headline).lineLimit(1)
                    Text(video.kind.titleAR).font(.caption2).foregroundStyle(.secondary)
                }
                Spacer()
                if vm.locked {
                    Button { vm.locked = false; vm.bumpChrome() } label: {
                        Image(systemName: "lock.fill").padding(12)
                    }
                } else {
                    Button { vm.toggleFill() } label: { Image(systemName: vm.fill ? "arrow.up.left.and.arrow.down.right" : "arrow.down.right.and.arrow.up.left").padding(8) }
                    if vm.hasSubtitles {
                        Button { showSearch = true } label: { Image(systemName: "magnifyingglass").padding(8) }
                        Button { vm.toggleAutoSpeak() } label: {
                            Image(systemName: vm.autoSpeak ? "speaker.wave.2.circle.fill" : "speaker.wave.2.circle")
                                .padding(8)
                        }
                    }
                    if vm.hasDub {
                        Button { vm.toggleDub() } label: {
                            Image(systemName: vm.dubOn ? "waveform.circle.fill" : "waveform.circle")
                                .padding(8)
                        }
                        .foregroundStyle(vm.dubOn ? V2Theme.gold : Color.white)
                    }
                    moreMenu
                    Button {
                        vm.locked = true
                        vm.showChrome = true
                    } label: { Image(systemName: "lock.open").padding(8) }
                    Button { vm.pip?.startPictureInPicture() } label: { Image(systemName: "pip.enter").padding(8) }
                }
            }
            .padding(.horizontal, 8)
            .padding(.top, 6)
            .background(LinearGradient(colors: [.black.opacity(0.7), .clear], startPoint: .top, endPoint: .bottom))

            Spacer()

            if !vm.locked {
                HStack(spacing: 28) {
                    Button { vm.skip(-10) } label: { Image(systemName: "gobackward.10").font(.title) }
                    Button { vm.toggle() } label: {
                        Image(systemName: vm.isPlaying ? "pause.circle.fill" : "play.circle.fill")
                            .font(.system(size: 64))
                    }
                    Button { vm.skip(10) } label: { Image(systemName: "goforward.10").font(.title) }
                }
                .padding(.bottom, 8)

                VStack(spacing: 6) {
                    Slider(value: Binding(get: { vm.current }, set: { vm.seek(to: $0) }), in: 0...max(vm.duration, 1))
                        .tint(V2Theme.accent)
                    HStack {
                        Text(vm.fmt(vm.current)).font(.caption.monospacedDigit())
                        Spacer()
                        Text("-" + vm.fmt(max(0, vm.duration - vm.current))).font(.caption.monospacedDigit())
                    }
                    HStack {
                        Button { showSpeed = true } label: {
                            Text(vm.rate == 1 ? lang.t("pl.speed.label") : String(format: "×%.2g", vm.rate)).font(.caption.bold())
                        }
                        Button { vm.toggleMute() } label: {
                            Image(systemName: vm.mutedUser ? "speaker.slash.fill" : "speaker.wave.3.fill")
                                .font(.caption.bold())
                        }
                        Spacer()
                        if vm.hasSubtitles {
                            Menu {
                                Picker(lang.t("pl.sub.mode"), selection: subModeBinding) {
                                    ForEach(vm.availableModes) { m in
                                        Label(m.titleAR, systemImage: m.icon).tag(m.rawValue)
                                    }
                                }
                                Divider()
                                Picker(lang.t("pl.sub.font"), selection: subFontSizeBinding) {
                                    Text(lang.t("pl.sub.small")).tag(14)
                                    Text(lang.t("pl.sub.medium")).tag(18)
                                    Text(lang.t("pl.sub.large")).tag(23)
                                    Text(lang.t("pl.sub.xlarge")).tag(28)
                                }
                            } label: {
                                Image(systemName: vm.subMode == .off ? "captions.bubble" : "captions.bubble.fill")
                                    .font(.caption.bold())
                            }
                            Spacer()
                        }
                        Button {
                            vm.looping.toggle()
                            vm.flash(vm.looping ? lang.t("pl.repeat.on") : lang.t("pl.repeat.off"))
                        } label: {
                            Image(systemName: vm.looping ? "repeat.1" : "repeat")
                        }
                        Spacer()
                        Text(ByteCountFormatter.string(fromByteCount: video.fileSize, countStyle: .file))
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }
                }
                .padding(.horizontal, 16)
                .padding(.bottom, 18)
                .background(LinearGradient(colors: [.clear, .black.opacity(0.75)], startPoint: .top, endPoint: .bottom))
            }
        }
        .foregroundStyle(.white)
    }
}

struct SubtitleSearchSheet: View {
    @ObservedObject var vm: OfflinePlayerModel
    @Environment(\.dismiss) private var dismiss
    @State private var query = ""

    var body: some View {
        NavigationStack {
            List {
                if query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    Text(vm.t("pl.search.hint"))
                        .foregroundStyle(.secondary)
                } else {
                    let hits = vm.searchHits(query: query)
                    if hits.isEmpty {
                        Text(vm.t("pl.search.empty"))
                            .foregroundStyle(.secondary)
                    } else {
                        ForEach(hits) { hit in
                            Button {
                                vm.seek(to: hit.start)
                                dismiss()
                            } label: {
                                VStack(alignment: .leading, spacing: 4) {
                                    Text(vm.fmt(hit.start))
                                        .font(.caption.monospacedDigit())
                                        .foregroundStyle(V2Theme.gold)
                                    Text(hit.text)
                                        .foregroundStyle(.primary)
                                        .lineLimit(3)
                                }
                            }
                        }
                    }
                }
            }
            .searchable(text: $query, prompt: Text(vm.t("pl.search.prompt")))
            .navigationTitle(vm.t("pl.search.title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(vm.t("nav.close")) { dismiss() }
                }
            }
        }
    }
}

private extension MPVolumeView {
    static func setVolume(_ value: Float) {
        let v = MPVolumeView(frame: .zero)
        if let sl = v.subviews.first(where: { $0 is UISlider }) as? UISlider {
            sl.value = value
        }
    }
}

// MARK: - اختيار جهاز AirPlay

struct AirPlayPicker: UIViewRepresentable {
    func makeUIView(context: Context) -> AVRoutePickerView {
        let v = AVRoutePickerView()
        hideArtwork(in: v)
        return v
    }
    func updateUIView(_ uiView: AVRoutePickerView, context: Context) {
        hideArtwork(in: uiView)
    }

    /// AVRoutePickerView يعرض صورة غلاف "Now Playing" فوق قائمة الأجهزة،
    /// ولا توجد API عامة لإخفائها — نبحث عن UIImageView داخل شجرة الـ subviews.
    private func hideArtwork(in view: UIView) {
        for sub in view.subviews {
            if sub is UIImageView {
                sub.isHidden = true
            } else {
                hideArtwork(in: sub)
            }
        }
    }
}
