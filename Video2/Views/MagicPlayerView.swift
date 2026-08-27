import SwiftUI
import AVKit
import AVFoundation
import MediaPlayer
import UIKit

// MARK: - مشغّل البحث الداخلي
//
// المشغّل يعمل داخل تبويب «البحث السحري» بلا الخروج للمتصفح:
// • يفتح مصدر التشغيل الذي صنعه MagicResolver (ملف مباشر أو قائمة HLS).
// • يبدّل الجودة من قائمة الجودات مع الحفاظ على الموضع.
// • إن فشل المسار المباشر يعيد المحاولة عبر الوسيط المحلي (بترويسات الموقع) تلقائياً،
//   ثم ينتقل للمصدر التالي، ثم يعرض بديلاً: تحميل / صيد أعمق / فتح في المتصفح.
// • يبقى الصوت شغّالاً بعد إغلاق نافذة المشغّل حتى تستطيع مواصلة البحث،
//   مع شريط صغير للتحكم أسفل التبويب.

final class MagicPlaybackModel: ObservableObject {   // ليس NSObject: نستخدم NSKeyValueObservation فقط

    let title: String
    let pageURL: String?
    let posterURL: String?
    let player = AVPlayer()
    private let layer: AVPlayerLayer
    private var timeObs: Any?
    private var endObs: NSObjectProtocol?
    private var statusObs: NSKeyValueObservation?
    private var bufferObs: NSKeyValueObservation?
    private var keepUpObs: NSKeyValueObservation?
    private var triedURLs = Set<String>()
    private var hideWork: DispatchWorkItem?

    @Published var variants: [MagicStreamVariant]
    @Published var selected: MagicStreamVariant?
    @Published var isPlaying = false
    @Published var buffering = false
    @Published var failed = false
    @Published var message: String?
    @Published var current: Double = 0
    @Published var duration: Double = 0
    @Published var rate: Float = 1
    @Published var muted = false
    @Published var fill = false
    @Published var showChrome = true
    @Published var usingProxy = false
    /// يملؤه صاحب الشاشة لفتح الصفحة في المتصفح عند الحاجة.
    var onOpenPage: ((String) -> Void)?

    init(title: String, pageURL: String?, posterURL: String?,
         variants: [MagicStreamVariant], selected: MagicStreamVariant) {
        self.title = title
        self.pageURL = pageURL
        self.posterURL = posterURL
        self.variants = variants
        self.selected = selected
        self.layer = AVPlayerLayer(player: player)
        layer.videoGravity = .resizeAspect
        player.actionAtItemEnd = .pause
    }

    var playerLayer: AVPlayerLayer { layer }
    var hasMultipleSources: Bool { variants.count > 1 }

    // MARK: التجهيز والتشغيل

    func start() {
        if let v = selected { load(v, forceProxy: false) }
        UIApplication.shared.isIdleTimerDisabled = true
        setupRemoteCommands()
        timeObs = player.addPeriodicTimeObserver(forInterval: CMTime(seconds: 0.25, preferredTimescale: 600),
                                                 queue: .main) { [weak self] t in
            guard let self else { return }
            self.current = t.seconds
            if let d = self.player.currentItem?.duration.seconds, d.isFinite, d > 0 {
                if abs(self.duration - d) > 0.4 { self.duration = d }
            }
            self.publishNowPlaying()
        }
    }

    func stop() {
        UIApplication.shared.isIdleTimerDisabled = false
        if let timeObs { player.removeTimeObserver(timeObs) }
        self.timeObs = nil
        if let endObs { NotificationCenter.default.removeObserver(endObs) }
        self.endObs = nil
        statusObs?.invalidate(); statusObs = nil
        bufferObs?.invalidate(); bufferObs = nil
        keepUpObs?.invalidate(); keepUpObs = nil
        player.pause()
        player.replaceCurrentItem(with: nil)
        hideWork?.cancel()
        teardownRemoteCommands()
    }

    /// يحمّل مصدراً معيّناً. `forceProxy` يجبر المرور بالوسيط المحلي.
    func load(_ variant: MagicStreamVariant, forceProxy: Bool) {
        selected = variant
        usingProxy = forceProxy || variant.isHLS || !variant.headers.isEmpty
        failed = false
        message = nil
        let urlString = variant.playbackURL(forceProxy: forceProxy) ?? variant.url
        guard let url = MagicStreamProxy.parse(urlString) else {
            failed = true
            message = "magic.play.noURL"
            return
        }
        let item = AVPlayerItem(url: url)
        triedURLs.insert(variant.url)
        player.replaceCurrentItem(with: item)
        observe(item, variant: variant)
        play()
    }

    private func observe(_ item: AVPlayerItem, variant: MagicStreamVariant) {
        statusObs?.invalidate()
        bufferObs?.invalidate()
        keepUpObs?.invalidate()
        if let endObs { NotificationCenter.default.removeObserver(endObs) }
        statusObs = item.observe(\.status, options: [.new]) { [weak self] item, _ in
            DispatchQueue.main.async {
                guard let self else { return }
                switch item.status {
                case .failed:
                    self.handleFailure(item: item, variant: variant)
                case .readyToPlay:
                    self.failed = false
                    if let d = item.duration.seconds, d.isFinite, d > 0 { self.duration = d }
                    self.player.isMuted = self.muted
                    self.play()
                default:
                    break
                }
            }
        }
        bufferObs = item.observe(\.isPlaybackBufferEmpty, options: [.new]) { [weak self] item, _ in
            DispatchQueue.main.async { [weak self] in
                guard let self, self.isPlaying else { return }
                self.buffering = item.isPlaybackBufferEmpty || !item.isPlaybackLikelyToKeepUp
            }
        }
        keepUpObs = item.observe(\.isPlaybackLikelyToKeepUp, options: [.new]) { [weak self] item, _ in
            DispatchQueue.main.async { [weak self] in
                guard let self else { return }
                if item.isPlaybackLikelyToKeepUp { self.buffering = false }
            }
        }
        let endName = Notification.Name.AVPlayerItemDidPlayToEndTime
        endObs = NotificationCenter.default.addObserver(forName: endName, object: item, queue: .main) { [weak self] _ in
            guard let self else { return }
            self.isPlaying = false
            self.showChrome = true
        }
    }

    /// سلسلة النجاة: مباشر → وسيط محلي بالترويسات → مصدر آخر → رسالة خطأ مع بدائل.
    private func handleFailure(item: AVPlayerItem, variant: MagicStreamVariant) {
        if !usingProxy {
            load(variant, forceProxy: true)
            message = "magic.play.retryProxy"
            return
        }
        if let next = variants.first(where: { $0.isPlayableByEngine && !triedURLs.contains($0.url) }) {
            load(next, forceProxy: false)
            message = "magic.play.switchSource"
            return
        }
        failed = true
        isPlaying = false
        showChrome = true
        message = item.error?.localizedDescription ?? "magic.play.fail"
    }

    func retry() {
        guard let v = selected else { return }
        load(v, forceProxy: true)
    }

    func select(_ variant: MagicStreamVariant) {
        let position = current
        load(variant, forceProxy: false)
        if position > 1 { seek(to: position) }
    }

    // MARK: تحكم

    func play() {
        player.rate = rate
        player.play()
        isPlaying = true
        scheduleHide()
        publishNowPlaying()
    }

    func pause() {
        player.pause()
        isPlaying = false
        showChrome = true
        publishNowPlaying()
    }

    func toggle() {
        isPlaying ? pause() : play()
    }

    func seek(to seconds: Double) {
        guard seconds.isFinite else { return }
        let t = min(max(0, seconds), max(duration, 0.1))
        player.seek(to: CMTime(seconds: t, preferredTimescale: 600), toleranceBefore: .zero, toleranceAfter: .zero)
        current = t
    }

    func skip(_ delta: Double) {
        seek(to: current + delta)
        flash(delta > 0 ? "+\(Int(delta))s" : "\(Int(delta))s")
    }

    func setRate(_ r: Float) {
        rate = r
        UserDefaults.standard.set(r, forKey: "player.rate")
        if isPlaying { player.rate = r }
        flash("×\(r)")
    }

    func toggleMute() {
        muted.toggle()
        player.isMuted = muted
        flash(muted ? "pl.mute" : "pl.unmute")
    }

    func toggleFill() {
        fill.toggle()
        layer.videoGravity = fill ? .resizeAspectFill : .resizeAspect
    }

    @MainActor
    func rotate() {
        let landscape = UIScreen.main.bounds.width > UIScreen.main.bounds.height
        OrientationLock.shared.toggleLandscape(currentlyLandscape: landscape)
    }

    func bumpChrome() {
        showChrome = true
        scheduleHide()
    }

    private func scheduleHide() {
        hideWork?.cancel()
        guard isPlaying else { return }
        let w = DispatchWorkItem { [weak self] in self?.showChrome = false }
        hideWork = w
        DispatchQueue.main.asyncAfter(deadline: .now() + 3.5, execute: w)
    }

    func flash(_ text: String) {
        message = text
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.8) { [weak self] in
            guard let self, self.message == text else { return }
            self.message = nil
        }
    }

    func fmt(_ s: Double) -> String {
        guard s.isFinite else { return "0:00" }
        let n = Int(max(0, s))
        let h = n / 3600, m = (n % 3600) / 60, sec = n % 60
        if h > 0 { return String(format: "%d:%02d:%02d", h, m, sec) }
        return String(format: "%d:%02d", m, sec)
    }

    /// يوقف الصوت مؤقتاً (مع بقاء الجلسة) — للشريط المصغّر.
    func suspendForBackground() {
        player.pause()
        isPlaying = false
    }

    // MARK: Now Playing

    private var nowPlayingAt = Date.distantPast

    private func setupRemoteCommands() {
        UIApplication.shared.beginReceivingRemoteControlEvents()
        let c = MPRemoteCommandCenter.shared()
        c.playCommand.isEnabled = true
        c.pauseCommand.isEnabled = true
        c.togglePlayPauseCommand.isEnabled = true
        c.skipForwardCommand.isEnabled = true
        c.skipBackwardCommand.isEnabled = true
        c.skipForwardCommand.preferredIntervals = [15]
        c.skipBackwardCommand.preferredIntervals = [15]
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
            DispatchQueue.main.async { self?.skip(15) }
            return .success
        }
        c.skipBackwardCommand.addTarget { [weak self] _ in
            DispatchQueue.main.async { self?.skip(-15) }
            return .success
        }
        c.changePlaybackPositionCommand.addTarget { [weak self] event in
            guard let ev = event as? MPChangePlaybackPositionCommandEvent else { return .commandFailed }
            DispatchQueue.main.async { self?.seek(to: ev.positionTime) }
            return .success
        }
        publishNowPlaying()
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

    private func publishNowPlaying() {
        let now = Date()
        if now.timeIntervalSince(nowPlayingAt) < 1 { return }
        nowPlayingAt = now
        var info: [String: Any] = [
            MPMediaItemPropertyTitle: title,
            MPMediaItemPropertyAlbumTitle: "فيديو ٢ · بحث سحري",
            MPNowPlayingInfoPropertyElapsedPlaybackTime: current,
            MPMediaItemPropertyPlaybackDuration: max(duration, 0),
            MPNowPlayingInfoPropertyPlaybackRate: isPlaying ? Double(rate) : 0,
        ]
        if let posterURL, let url = URL(string: posterURL) {
            DispatchQueue.global(qos: .utility).async {
                guard let data = try? Data(contentsOf: url), let img = UIImage(data: data) else { return }
                DispatchQueue.main.async {
                    var updated = MPNowPlayingInfoCenter.default().nowPlayingInfo ?? [:]
                    updated[MPMediaItemPropertyArtwork] = MPMediaItemArtwork(boundsSize: img.size) { _ in img }
                    MPNowPlayingInfoCenter.default().nowPlayingInfo = updated
                }
            }
        }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
    }
}

// MARK: - الشاشة

struct MagicPlayerView: View {
    @EnvironmentObject var lang: LanguageStore
    @ObservedObject var vm: MagicPlaybackModel
    @Environment(\.dismiss) private var dismiss
    @State private var showSpeed = false
    @State private var showSources = false
    var onDownload: (MagicStreamVariant) -> Void = { _ in }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            PlayerLayerView(layer: vm.playerLayer)
                .ignoresSafeArea()
                .gesture(tapGestures)

            if vm.buffering && !vm.failed {
                ProgressView().tint(.white).scaleEffect(1.3)
            }

            posterUnderlay

            if vm.showChrome { chrome }

            if let key = vm.message {
                messageBubble(key)
            }
        }
        .foregroundStyle(.white)
        .statusBarHidden(true)
        .persistentSystemOverlays(.hidden)
        .confirmationDialog(lang.t("pl.speed"), isPresented: $showSpeed, titleVisibility: .visible) {
            ForEach([0.5, 0.75, 1.0, 1.25, 1.5, 2.0], id: \.self) { r in
                Button(r == 1.0 ? lang.t("pl.normal") : "×\(r)") { vm.setRate(Float(r)) }
            }
            Button(lang.t("nav.cancel"), role: .cancel) {}
        }
    }

    /// صورة مصغّرة خلف المشغّل حتى تبدأ اللقطات الأولى
    private var posterUnderlay: some View {
        Group {
            if vm.current < 0.6, let poster = vm.posterURL, let url = URL(string: poster) {
                AsyncImage(url: url) { image in
                    image.resizable().scaledToFit()
                } placeholder: {
                    Color.black
                }
                .opacity(vm.isPlaying ? 0 : 0.9)
                .allowsHitTesting(false)
                .ignoresSafeArea()
            }
        }
    }

    private func messageBubble(_ key: String) -> some View {
        VStack {
            Spacer()
            HStack(spacing: 10) {
                if vm.failed {
                    Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(V2Theme.gold)
                }
                Text(key.contains(".") ? lang.t(key) : key)
                    .font(.footnote)
                    .multilineTextAlignment(.center)
                if vm.failed {
                    Button(lang.t("magic.play.retry")) { vm.retry() }
                        .font(.caption.bold())
                }
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 9)
            .background(.ultraThinMaterial, in: Capsule())
            .padding(.bottom, 140)
        }
        .allowsHitTesting(vm.failed)
    }

    private var tapGestures: some Gesture {
        SpatialTapGesture(count: 2).onEnded { value in
            let w = UIScreen.main.bounds.width
            if value.location.x < w * 0.33 { vm.skip(-10) }
            else if value.location.x > w * 0.66 { vm.skip(10) }
            else { vm.toggle() }
        }
        .exclusively(before: TapGesture().onEnded {
            withAnimation(.easeOut(duration: 0.12)) { vm.bumpChrome() }
        })
    }

    private var chrome: some View {
        VStack(spacing: 0) {
            HStack(spacing: 6) {
                Button { dismiss() } label: {
                    Image(systemName: "chevron.forward")
                        .font(.title2.bold())
                        .padding(12)
                }
                VStack(alignment: .leading, spacing: 1) {
                    Text(vm.title).font(.subheadline.bold()).lineLimit(1)
                    HStack(spacing: 5) {
                        if let v = vm.selected {
                            Text(v.label).font(.caption2).foregroundStyle(V2Theme.gold)
                            if vm.usingProxy {
                                Text(lang.t("magic.play.viaProxy")).font(.caption2).foregroundStyle(.secondary)
                            }
                        }
                    }
                }
                Spacer()
                if vm.hasMultipleSources {
                    Button { showSources.toggle() } label: {
                        Image(systemName: "square.stack.3d.up").padding(8)
                    }
                }
                if let v = vm.selected, v.downloadable {
                    Button { onDownload(v) } label: {
                        Image(systemName: "arrow.down.circle").padding(8)
                    }
                }
                Button { vm.toggleMute() } label: {
                    Image(systemName: vm.muted ? "speaker.slash.fill" : "speaker.wave.3.fill").padding(8)
                }
                Button { vm.toggleFill() } label: {
                    Image(systemName: vm.fill ? "arrow.down.right.and.arrow.up.left" : "arrow.up.left.and.arrow.down.right")
                        .padding(8)
                }
                Menu {
                    Button { vm.rotate() } label: { Label(lang.t("pl.landscape"), systemImage: "rotate.right") }
                    if let page = vm.pageURL {
                        Button { vm.onOpenPage?(page) } label: {
                            Label(lang.t("magic.open.verify"), systemImage: "safari")
                        }
                    }
                    if let v = vm.selected {
                        ForEach([0.5, 0.75, 1.0, 1.25, 1.5, 2.0], id: \.self) { r in
                            Button("×\(r)") { vm.setRate(Float(r)) }
                        }
                        Divider()
                        Button(lang.t("magic.play.copyLink")) {
                            UIPasteboard.general.string = v.url
                        }
                    }
                } label: {
                    Image(systemName: "ellipsis.circle").padding(8)
                }
            }
            .padding(.horizontal, 6)
            .padding(.top, 6)
            .background(LinearGradient(colors: [.black.opacity(0.75), .clear], startPoint: .top, endPoint: .bottom))

            Spacer()

            HStack(spacing: 26) {
                Button { vm.skip(-10) } label: { Image(systemName: "gobackward.10").font(.title) }
                Button { vm.toggle() } label: {
                    Image(systemName: vm.isPlaying ? "pause.circle.fill" : "play.circle.fill")
                        .font(.system(size: 58))
                }
                Button { vm.skip(10) } label: { Image(systemName: "goforward.10").font(.title) }
            }
            .padding(.bottom, 6)

            VStack(spacing: 6) {
                Slider(value: Binding(get: { vm.current }, set: { vm.seek(to: $0) }), in: 0...max(vm.duration, 1))
                    .tint(V2Theme.accent)
                HStack {
                    Text(vm.fmt(vm.current)).font(.caption.monospacedDigit())
                    Spacer()
                    if let v = vm.selected, !v.sizeText.isEmpty {
                        Text(v.sizeText).font(.caption2).foregroundStyle(.secondary)
                    }
                    Spacer()
                    Text("-" + vm.fmt(max(0, vm.duration - vm.current))).font(.caption.monospacedDigit())
                }
                HStack {
                    Button { showSpeed = true } label: {
                        Text(vm.rate == 1 ? lang.t("pl.speed.label") : "×\(vm.rate)")
                            .font(.caption.bold())
                    }
                    if let v = vm.selected {
                        Text(v.qualityText).font(.caption2).foregroundStyle(.secondary)
                    }
                    Spacer()
                    if let page = vm.pageURL, !vm.failed {
                        Button { vm.onOpenPage?(page) } label: {
                            Image(systemName: "safari").font(.caption.bold())
                        }
                    }
                }
                .padding(.horizontal, 4)
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 18)
            .background(LinearGradient(colors: [.clear, .black.opacity(0.8)], startPoint: .top, endPoint: .bottom))
        }
        .popover(isPresented: $showSources, arrowEdge: .bottom) {
            sourcesList
        }
    }

    private var sourcesList: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(lang.t("magic.play.sources"))
                .font(.caption.bold())
                .padding(.horizontal, 12)
                .padding(.top, 10)
            ForEach(vm.variants) { v in
                Button {
                    showSources = false
                    vm.select(v)
                } label: {
                    HStack(spacing: 8) {
                        Image(systemName: v.isHLS ? "antenna.radiowaves.left.and.right" : "play.rectangle")
                            .font(.caption)
                        VStack(alignment: .leading, spacing: 1) {
                            Text(v.label).font(.caption)
                            if !v.sizeText.isEmpty {
                                Text(v.sizeText).font(.caption2).foregroundStyle(.secondary)
                            }
                        }
                        Spacer()
                        if vm.selected?.url == v.url {
                            Image(systemName: "checkmark.circle.fill").foregroundStyle(V2Theme.mint)
                        }
                    }
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                Divider()
            }
            if let v = vm.selected, v.downloadable {
                Button {
                    showSources = false
                    onDownload(v)
                } label: {
                    Label(lang.t("magic.download.quality"), systemImage: "arrow.down.circle.fill")
                        .font(.caption.bold())
                        .padding(12)
                }
            }
        }
        .frame(minWidth: 260)
        .background(V2Theme.card)
    }
}

// MARK: - شريط «يُشغَّل الآن» أسفل تبويب البحث

struct MagicNowPlayingBar: View {
    @EnvironmentObject var lang: LanguageStore
    @ObservedObject var vm: MagicPlaybackModel
    let onResume: () -> Void
    let onClose: () -> Void

    var body: some View {
        HStack(spacing: 10) {
            Button { vm.toggle() } label: {
                Image(systemName: vm.isPlaying ? "pause.circle.fill" : "play.circle.fill")
                    .font(.title2)
                    .foregroundStyle(V2Theme.gold)
            }
            VStack(alignment: .leading, spacing: 1) {
                Text(vm.title).font(.caption.bold()).lineLimit(1)
                HStack(spacing: 5) {
                    Text("\(vm.fmt(vm.current)) / \(vm.fmt(vm.duration))")
                        .font(.system(size: 9)).foregroundStyle(.secondary)
                    if let v = vm.selected {
                        Text(v.qualityText).font(.system(size: 9)).foregroundStyle(V2Theme.mint)
                    }
                    if vm.buffering {
                        ProgressView().controlSize(.mini).scaleEffect(0.6)
                    }
                }
            }
            Spacer()
            Button { onResume() } label: {
                Image(systemName: "play.rectangle.fill").font(.subheadline)
            }
            Button { onClose() } label: {
                Image(systemName: "xmark.circle.fill").font(.subheadline).foregroundStyle(.secondary)
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(V2Theme.card.opacity(0.98), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
    }
}
