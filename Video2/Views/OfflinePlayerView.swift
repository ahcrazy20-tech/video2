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
                    self.player.seek(to: .zero)
                    self.player.play()
                } else {
                    self.isPlaying = false
                    self.showChrome = true
                }
            }
        } catch {
            flash(t("pl.prep"))
        }
        timeObs = player.addPeriodicTimeObserver(forInterval: CMTime(seconds: 0.25, preferredTimescale: 600), queue: .main) { [weak self] t in
            guard let self else { return }
            self.current = t.seconds
            self.updateSubtitle(at: t.seconds)
            if let d = self.player.currentItem?.duration.seconds, d.isFinite, d > 0 {
                self.duration = d
            } else if let known = self.video.duration, known > 0 {
                self.duration = known
            }
        }
        play()
        scheduleHide()
    }

    func stop() {
        UIApplication.shared.isIdleTimerDisabled = false
        if let timeObs { player.removeTimeObserver(timeObs) }
        if let endObs { NotificationCenter.default.removeObserver(endObs) }
        player.pause()
        sleepWork?.cancel()
    }

    func play() {
        player.rate = rate
        player.play()
        isPlaying = true
        scheduleHide()
    }

    func pause() {
        player.pause()
        isPlaying = false
        showChrome = true
    }

    func toggle() {
        isPlaying ? pause() : play()
    }

    func seek(to seconds: Double) {
        let t = min(max(0, seconds), max(duration, 0.1))
        player.seek(to: CMTime(seconds: t, preferredTimescale: 600), toleranceBefore: .zero, toleranceAfter: .zero)
        current = t
    }

    func skip(_ delta: Double) {
        seek(to: current + delta)
        flash("\(delta > 0 ? "+" : "")\(Int(delta)) s")
    }

    func setRate(_ r: Float) {
        rate = r
        if isPlaying { player.rate = r }
        flash(String(format: "\(t("pl.speed")) ×%.2g", r))
    }

    func toggleFill() {
        fill.toggle()
        layer.videoGravity = fill ? .resizeAspectFill : .resizeAspect
        flash(fill ? t("pl.fill") : t("pl.fit"))
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

struct OfflinePlayerView: View {
    let video: SavedVideo
    @EnvironmentObject var library: LibraryStore
    @EnvironmentObject var lang: LanguageStore
    @Environment(\.dismiss) var dismiss
    @StateObject private var vm: OfflinePlayerModel
    @State private var dragStart: Double?
    @State private var showSpeed = false
    @State private var showSleep = false

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
        }
        .onDisappear {
            library.updatePosition(id: video.id, position: vm.current, duration: vm.duration)
            vm.stop()
        }
        .confirmationDialog(lang.t("pl.speed"), isPresented: $showSpeed, titleVisibility: .visible) {
            ForEach([0.5, 0.75, 1.0, 1.25, 1.5, 2.0], id: \.self) { r in
                Button(r == 1.0 ? lang.t("pl.normal") : "×\(r)") { vm.setRate(Float(r)) }
            }
            Button(lang.t("nav.cancel"), role: .cancel) {}
        }
        .confirmationDialog(lang.t("pl.sleep"), isPresented: $showSleep, titleVisibility: .visible) {
            Button("15 \(min)") { vm.startSleep(15) }
            Button("30 \(min)") { vm.startSleep(30) }
            Button("45 \(min)") { vm.startSleep(45) }
            Button("60 \(min)") { vm.startSleep(60) }
            Button(lang.t("pl.sleep.off")) { vm.startSleep(0) }
            Button(lang.t("nav.cancel"), role: .cancel) {}
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
                    Button { showSleep = true } label: { Image(systemName: "moon.zzz").padding(8) }
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

private extension MPVolumeView {
    static func setVolume(_ value: Float) {
        let v = MPVolumeView(frame: .zero)
        if let sl = v.subviews.first(where: { $0 is UISlider }) as? UISlider {
            sl.value = value
        }
    }
}
