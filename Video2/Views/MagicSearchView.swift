import SwiftUI
import AVKit

// شاشة البحث السحري: بحث مجمّع في عدة مصادر بصيغة موسّعة، مع مشغّل داخلي
// وتحميل من داخل التبويب — بدون الرجوع للمتصفح. المتصفح يبقى خياراً احتياطياً
// للمواقع التي لا تعطي روابط مباشرة، وخط التحميل الأصلي كما هو دون تعديل.
struct MagicSearchView: View {
    @EnvironmentObject var lang: LanguageStore
    @EnvironmentObject var browser: BrowserModel
    @EnvironmentObject var downloads: DownloadManager
    @EnvironmentObject var router: TabRouter
    @StateObject private var store = MagicSearchStore()
    @State private var toast: String?
    @State private var showPlayer = false
    @State private var showHuntLink = false
    @State private var huntLink = ""
    @State private var hunting = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 12) {
                    searchPanel
                    if store.ranOnce { statusRow }
                    if store.ranOnce { hint }
                    resultsSection
                }
                .padding(.horizontal, 14)
                .padding(.top, 6)
                .padding(.bottom, 30)
            }
            .background(V2Theme.bg)
            .navigationTitle(lang.t("magic.title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button { store.showSyntax = true } label: {
                        Image(systemName: "text.badge.plus")
                    }
                }
            }
            .overlay(alignment: .bottom) {
                VStack(spacing: 8) {
                    if let vm = store.nowPlaying {
                        MagicNowPlayingBar(vm: vm,
                                           onResume: { showPlayer = true },
                                           onClose: { store.stopPlayback() })
                            .padding(.horizontal, 12)
                    }
                    if let toast {
                        Text(toast)
                            .font(.footnote.bold())
                            .padding(.horizontal, 14)
                            .padding(.vertical, 9)
                            .background(V2Theme.card, in: Capsule())
                    }
                }
                .padding(.bottom, 10)
                .transition(.move(edge: .bottom).combined(with: .opacity))
            }
            .sheet(isPresented: $store.showSyntax) { syntaxSheet }
            .alert(lang.t("magic.hunt.link"), isPresented: $showHuntLink) {
                TextField("https://…", text: $huntLink)
                    .textInputAutocapitalization(.never)
                    .keyboardType(.URL)
                    .environment(\.layoutDirection, .leftToRight)
                Button(hunting ? lang.t("magic.hunting") : lang.t("magic.hunt.go")) { huntFromLink() }
                Button(lang.t("lib.cancel"), role: .cancel) {}
            } message: {
                Text(lang.t("magic.hunt.link.hint"))
            }
            .fullScreenCover(isPresented: $showPlayer) {
                if let vm = store.nowPlaying {
                    MagicPlayerView(vm: vm, onDownload: { variant in
                        download(title: vm.title, page: vm.pageURL, variant: variant)
                    })
                        .environmentObject(lang)
                        .environmentObject(downloads)
                }
            }
        }
    }

    // MARK: - لوحة البحث (الاسم + المدة + أوامر سريعة)

    private var searchPanel: some View {
        GlassCard {
            VStack(spacing: 10) {
                HStack(spacing: 8) {
                    Image(systemName: "wand.and.stars")
                        .foregroundStyle(V2Theme.gold)
                    TextField(lang.t("magic.query.placeholder"), text: $store.query)
                        .submitLabel(.search)
                        .onSubmit { store.search() }
                    Button {
                        store.search()
                    } label: {
                        Group {
                            if store.phase == .running {
                                ProgressView().tint(.white)
                            } else {
                                Image(systemName: "magnifyingglass")
                            }
                        }
                        .frame(width: 34, height: 34)
                        .background(V2Theme.accent, in: Circle())
                        .foregroundStyle(.white)
                    }
                    .disabled(store.query.trimmingCharacters(in: .whitespaces).isEmpty)
                }

                // حقل المدة المطلوبة: يستبعد النتائج المقطوعة/القصيرة
                HStack(spacing: 8) {
                    Image(systemName: "clock")
                        .foregroundStyle(.secondary)
                        .font(.caption)
                    TextField(lang.t("magic.duration.placeholder"), text: $store.durationText)
                        .keyboardType(.numbersAndPunctuation)
                        .environment(\.layoutDirection, .leftToRight)
                        .font(.footnote)
                    if !store.durationText.isEmpty {
                        Button {
                            store.durationText = ""
                        } label: {
                            Image(systemName: "xmark.circle.fill").foregroundStyle(.secondary)
                        }
                    }
                    Button { showHuntLink = true } label: {
                        Image(systemName: "link.badge.plus")
                            .font(.footnote)
                            .padding(6)
                            .background(V2Theme.bg.opacity(0.6), in: Circle())
                            .foregroundStyle(V2Theme.gold)
                    }
                }
                .padding(8)
                .background(V2Theme.bg.opacity(0.6), in: RoundedRectangle(cornerRadius: 10))

                durationChips
                commandChips
                modeRow
            }
        }
    }

    private var durationChips: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                chip(label: lang.t("magic.duration.any"), active: store.minChip == nil) {
                    store.minChip = nil
                }
                chip(label: lang.t("magic.duration.min5"), active: store.minChip == 5 * 60) { store.minChip = 5 * 60 }
                chip(label: lang.t("magic.duration.min20"), active: store.minChip == 20 * 60) { store.minChip = 20 * 60 }
                chip(label: lang.t("magic.duration.min60"), active: store.minChip == 60 * 60) { store.minChip = 60 * 60 }
                chip(label: lang.t("magic.duration.min120"), active: store.minChip == 120 * 60) { store.minChip = 120 * 60 }
            }
            .padding(.vertical, 2)
        }
    }

    /// أزرار تضيف أوامر صيغة البحث داخل الحقل مباشرة.
    private var commandChips: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 7) {
                Text(lang.t("magic.commands")).font(.caption2).foregroundStyle(.secondary)
                commandChip("مدة:")
                commandChip("min:")
                commandChip("max:")
                commandChip("سنة:")
                commandChip("موقع:")
                commandChip("جودة:")
                commandChip("استبعد:")
                commandChip("مصدر:")
                commandChip("ترتيب:")
            }
            .padding(.vertical, 2)
        }
    }

    private func commandChip(_ text: String) -> some View {
        Button {
            if !store.query.hasSuffix(" ") && !store.query.isEmpty { store.query += " " }
            store.query += text
        } label: {
            Text(text)
                .font(.caption2.monospaced())
                .padding(.horizontal, 8)
                .padding(.vertical, 5)
                .background(V2Theme.bg.opacity(0.6), in: Capsule())
                .foregroundStyle(V2Theme.gold)
        }
        .buttonStyle(.plain)
    }

    /// خيارات الصيد.
    private var modeRow: some View {
        HStack(spacing: 14) {
            Toggle(isOn: Binding(get: { store.deepHunt }, set: { store.setDeepHunt($0) })) {
                VStack(alignment: .leading, spacing: 0) {
                    Text(lang.t("magic.mode.hunt")).font(.caption.bold())
                    Text(lang.t("magic.mode.hunt.hint")).font(.system(size: 9)).foregroundStyle(.secondary)
                }
            }
            .toggleStyle(.switch)
            .tint(V2Theme.mint)
            Spacer()
            Toggle(isOn: Binding(get: { store.autoPrepare }, set: { store.setAutoPrepare($0) })) {
                Text(lang.t("magic.mode.prepare")).font(.caption.bold())
            }
            .toggleStyle(.switch)
            .tint(V2Theme.mint)
        }
    }

    private func chip(label: String, active: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(label)
                .font(.caption.bold())
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                .background(active ? V2Theme.accent : V2Theme.bg.opacity(0.6), in: Capsule())
                .foregroundStyle(active ? .white : .secondary)
        }
        .buttonStyle(.plain)
    }

    // MARK: - حالة المصادر

    private var statusRow: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(store.statuses) { st in
                    HStack(spacing: 5) {
                        Image(systemName: st.source.icon).font(.caption2)
                        Text(lang.t(st.source.labelKey)).font(.caption)
                        if st.phase == .running {
                            ProgressView().scaleEffect(0.6)
                        } else if st.failed {
                            Image(systemName: "exclamationmark.triangle.fill")
                                .font(.caption2)
                                .foregroundStyle(V2Theme.gold)
                        } else {
                            Text("\(st.count)")
                                .font(.caption.bold())
                                .foregroundStyle(st.count > 0 ? V2Theme.mint : .secondary)
                        }
                    }
                    .padding(.horizontal, 10)
                    .padding(.vertical, 5)
                    .background(V2Theme.card, in: Capsule())
                    .foregroundStyle(.secondary)
                }
            }
        }
    }

    private var hint: some View {
        Text(lang.t("magic.hint"))
            .font(.caption2)
            .foregroundStyle(.secondary)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 4)
    }

    // MARK: - النتائج

    @ViewBuilder private var resultsSection: some View {
        if store.phase == .running && store.results.isEmpty {
            VStack(spacing: 10) {
                ProgressView().tint(V2Theme.gold)
                Text(lang.t("magic.searching")).font(.footnote).foregroundStyle(.secondary)
            }
            .padding(.top, 40)
        } else if store.results.isEmpty {
            emptyState
        } else {
            LazyVStack(spacing: 10) {
                Text(String(format: lang.t("magic.count"), store.results.count,
                            store.statuses.filter { $0.count > 0 }.count))
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                ForEach(store.results) { result in
                    MagicResultCard(
                        result: result,
                        badge: store.badge(for: result),
                        variants: store.variants[result.id] ?? [],
                        resolving: store.resolving.contains(result.id),
                        note: store.notes[result.id],
                        readyToPlay: store.playableCount(for: result),
                        onPlay: { play(result) },
                        onHunt: { hunt(result) },
                        onOpen: { open(result) },
                        onDownloadVariant: { variant in
                            download(title: result.title, page: result.pageURL, variant: variant)
                        },
                        onDownloadOption: { option in
                            downloadOption(result, option: option)
                        },
                        onPlayVariant: { variant in
                            play(result, variant: variant)
                        }
                    )
                }
            }
        }
    }

    @ViewBuilder private var emptyState: some View {
        VStack(spacing: 12) {
            Image(systemName: "wand.and.stars")
                .font(.system(size: 40))
                .foregroundStyle(V2Theme.gold)
            Text(store.ranOnce ? lang.t("magic.empty") : lang.t("magic.intro"))
                .font(.footnote)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 20)
            if !store.ranOnce {
                Button {
                    store.search()
                } label: {
                    Text(lang.t("magic.commands.show"))
                        .font(.caption.bold())
                        .padding(.horizontal, 14)
                        .padding(.vertical, 8)
                        .background(V2Theme.accent.opacity(0.18), in: Capsule())
                        .foregroundStyle(V2Theme.accent)
                }
            }
        }
        .padding(.top, 46)
    }

    // MARK: - الإجراءات

    @MainActor
    private func open(_ result: MagicSearchResult) {
        router.openInBrowser(result.pageURL, browser: browser)
    }

    @MainActor
    private func play(_ result: MagicSearchResult, variant: MagicStreamVariant? = nil) {
        if let vm = store.play(result, variant: variant) {
            attach(vm)
            showPlayer = true
            return
        }
        Task { @MainActor in
            await store.resolve(result, deep: store.deepHunt)
            if let vm = store.play(result, variant: variant) {
                attach(vm)
                showPlayer = true
            } else {
                showToast(lang.t("magic.play.unavailable"))
            }
        }
    }

    @MainActor
    private func hunt(_ result: MagicSearchResult) {
        Task { @MainActor in
            await store.rehunt(result)
            let count = store.variants[result.id]?.count ?? 0
            showToast(count > 0
                      ? String(format: lang.t("magic.hunt.found"), count)
                      : lang.t("magic.hunt.none"))
        }
    }

    @MainActor
    private func huntFromLink() {
        let url = huntLink.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !url.isEmpty else { return }
        hunting = true
        Task { @MainActor in
            let found = await store.importLink(url)
            hunting = false
            showHuntLink = false
            huntLink = ""
            if found.isEmpty {
                showToast(lang.t("magic.hunt.none"))
            } else {
                showToast(String(format: lang.t("magic.hunt.found"), found.count))
            }
        }
    }

    /// يربط زر «افتح في المتصفح» داخل المشغّل بنفس مسار المتصفح المعتاد.
    @MainActor
    private func attach(_ vm: MagicPlaybackModel) {
        let browser = self.browser
        let router = self.router
        vm.onOpenPage = { url in
            DispatchQueue.main.async {
                router.openInBrowser(url, browser: browser)
                showPlayer = false
            }
        }
    }

    /// تحميل عبر خط التحميل الأصلي نفسه (enqueueManual) — بلا أي تعديل فيه.
    @MainActor
    private func download(title: String, page: String?, variant: MagicStreamVariant) {
        downloads.enqueueManual(
            urlString: variant.url,
            title: "\(title) · \(variant.label)",
            page: variant.pageURL ?? page,
            auth: variant.downloadAuth,
            kindHint: variant.kind
        )
        showToast(String(format: lang.t("magic.download.started"), variant.label))
        withAnimation { showPlayer = false }
    }

    @MainActor
    private func downloadOption(_ result: MagicSearchResult, option: MagicDownloadOption) {
        downloads.enqueueManual(
            urlString: option.url,
            title: "\(result.title) · \(option.label)",
            page: result.pageURL
        )
        showToast(String(format: lang.t("magic.download.started"), option.label))
    }

    @MainActor
    private func showToast(_ message: String) {
        withAnimation { toast = message }
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 2_400_000_000)
            withAnimation { toast = nil }
        }
    }

    // MARK: - ورقة شرح الصيغة

    private var syntaxSheet: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    Text(lang.t("magic.syntax.title"))
                        .font(.headline)
                    Text(lang.t("magic.syntax.body"))
                        .font(.system(size: 12, design: .monospaced))
                        .foregroundStyle(.white.opacity(0.9))
                        .textSelection(.enabled)
                    Divider()
                    Text(lang.t("magic.syntax.notes"))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(16)
            }
            .background(V2Theme.bg)
            .navigationTitle(lang.t("magic.title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(lang.t("nav.close")) { store.showSyntax = false }
                }
            }
        }
    }
}

// MARK: - بطاقة نتيجة واحدة

struct MagicResultCard: View {
    @EnvironmentObject var lang: LanguageStore
    @EnvironmentObject var downloads: DownloadManager
    let result: MagicSearchResult
    let badge: MagicSearchStore.DurationBadge
    let variants: [MagicStreamVariant]
    let resolving: Bool
    let note: String?
    let readyToPlay: Int
    let onPlay: () -> Void
    let onHunt: () -> Void
    let onOpen: () -> Void
    let onDownloadVariant: (MagicStreamVariant) -> Void
    let onDownloadOption: (MagicDownloadOption) -> Void
    let onPlayVariant: (MagicStreamVariant) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .top, spacing: 10) {
                thumbnail
                VStack(alignment: .leading, spacing: 6) {
                    Text(result.title)
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(.white)
                        .lineLimit(2)

                    HStack(spacing: 6) {
                        durationBadgeView
                        sourceBadge
                        matchBadge
                        if result.isLive {
                            Text(lang.t("magic.live"))
                                .font(.system(size: 9, weight: .bold))
                                .padding(.horizontal, 6).padding(.vertical, 3)
                                .background(V2Theme.accent.opacity(0.18), in: Capsule())
                                .foregroundStyle(V2Theme.accent)
                        }
                    }

                    captionLine
                    actionRow
                }
            }

            if let job = activeJob {
                jobProgress(job)
            }

            if !variants.isEmpty {
                variantChips
            } else if resolving {
                HStack(spacing: 6) {
                    ProgressView().controlSize(.mini)
                    Text(lang.t("magic.hunting")).font(.caption2).foregroundStyle(.secondary)
                }
            } else if let note, note.contains(".") {
                HStack(spacing: 6) {
                    Image(systemName: "info.circle").font(.caption2)
                    Text(lang.t(note)).font(.caption2)
                }
                .foregroundStyle(V2Theme.gold)
            }
        }
        .padding(10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(V2Theme.card.opacity(0.9), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
    }

    // MARK: الأزرار

    private var actionRow: some View {
        HStack(spacing: 8) {
            Button(action: onPlay) {
                Label(readyToPlay > 0 ? lang.t("magic.play") : lang.t("magic.play.hunt"),
                      systemImage: "play.fill")
                    .font(.caption.bold())
                    .padding(.horizontal, 10)
                    .padding(.vertical, 6)
                    .background(V2Theme.mint.opacity(0.18), in: Capsule())
                    .foregroundStyle(V2Theme.mint)
            }
            .buttonStyle(.plain)

            if downloadableOptions.isEmpty {
                Button(action: onOpen) {
                    Label(lang.t("magic.browser"), systemImage: "safari")
                        .font(.caption.bold())
                        .padding(.horizontal, 10)
                        .padding(.vertical, 6)
                        .background(V2Theme.accent.opacity(0.16), in: Capsule())
                        .foregroundStyle(V2Theme.accent)
                }
                .buttonStyle(.plain)
            } else {
                Menu {
                    ForEach(downloadableOptions.prefix(10)) { option in
                        Button {
                            onDownloadOption(option)
                        } label: {
                            Label("\(option.label)  \(option.sizeText)", systemImage: "arrow.down.circle")
                        }
                    }
                } label: {
                    Label(lang.t("magic.download.quality"), systemImage: "arrow.down.circle.fill")
                        .font(.caption.bold())
                        .padding(.horizontal, 10)
                        .padding(.vertical, 6)
                        .background(V2Theme.mint.opacity(0.16), in: Capsule())
                        .foregroundStyle(V2Theme.mint)
                }
            }

            Spacer(minLength: 4)

            Menu {
                if result.canHunt || result.playableBySource {
                    Button { onHunt() } label: { Label(lang.t("magic.hunt.deep"), systemImage: "antenna.radiowaves.left.and.right") }
                }
                Button { onOpen() } label: { Label(lang.t("magic.open.verify"), systemImage: "safari") }
                if let media = result.mediaURL {
                    Button {
                        UIPasteboard.general.string = media
                    } label: { Label(lang.t("magic.copy.mediaLink"), systemImage: "link") }
                }
            } label: {
                Image(systemName: "ellipsis").font(.caption.bold()).padding(6)
                    .background(V2Theme.bg.opacity(0.5), in: Circle())
            }
        }
    }

    /// الجودات التي صيدها المحلّل — تشغيل أو تحميل بضغطة.
    private var variantChips: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 7) {
                ForEach(variants.prefix(12)) { v in
                    HStack(spacing: 0) {
                        Button {
                            onPlayVariant(v)
                        } label: {
                            HStack(spacing: 4) {
                                Image(systemName: v.isPlayableByEngine ? "play.fill" : "doc.zipper")
                                    .font(.system(size: 9))
                                Text(v.label)
                                    .font(.system(size: 10, weight: .semibold))
                                    .lineLimit(1)
                            }
                            .padding(.leading, 9)
                            .padding(.trailing, v.downloadable ? 5 : 9)
                            .padding(.vertical, 5)
                        }
                        .buttonStyle(.plain)
                        if v.downloadable {
                            Button {
                                onDownloadVariant(v)
                            } label: {
                                Image(systemName: "arrow.down.circle")
                                    .font(.system(size: 11))
                                    .padding(.trailing, 8)
                                    .padding(.leading, 2)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .background((v.isPlayableByEngine ? V2Theme.mint : V2Theme.gold).opacity(0.14), in: Capsule())
                    .foregroundStyle(v.isPlayableByEngine ? V2Theme.mint : V2Theme.gold)
                }
            }
            .padding(.vertical, 1)
        }
    }

    @ViewBuilder private func jobProgress(_ job: DownloadJob) -> some View {
        VStack(spacing: 3) {
            ProgressView(value: min(max(job.progress, 0), 1))
                .tint(job.state == .completed ? V2Theme.mint : V2Theme.accent)
            HStack {
                Text(statusText(job.state))
                if job.state != .completed {
                    Text(ByteCountFormatter.string(fromByteCount: job.bytesWritten, countStyle: .file))
                }
                Spacer()
                if let err = job.errorMessage, !err.isEmpty {
                    Text(err).lineLimit(1).foregroundStyle(.secondary)
                }
            }
            .font(.system(size: 9))
            .foregroundStyle(.secondary)
        }
    }

    private func statusText(_ state: DownloadState) -> String {
        switch state {
        case .queued: return lang.t("dl.queued")
        case .running: return lang.t("dl.running")
        case .paused: return lang.t("dl.paused")
        case .failed: return lang.t("dl.failed")
        case .completed: return lang.t("dl.done")
        case .blockedDRM: return lang.t("dl.drm")
        }
    }

    private var activeJob: DownloadJob? {
        for v in variants { if let j = downloads.job(matchingURL: v.url) { return j } }
        for o in result.downloads { if let j = downloads.job(matchingURL: o.url) { return j } }
        return nil
    }

    private var downloadableOptions: [MagicDownloadOption] { result.downloads }

    // MARK: عناصر صغيرة

    private var thumbnail: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 8)
                .fill(V2Theme.bg.opacity(0.7))
            if let urlString = result.thumbnailURL, let url = URL(string: urlString) {
                AsyncImage(url: url) { image in
                    image.resizable().scaledToFill()
                } placeholder: {
                    Image(systemName: "film")
                        .foregroundStyle(.secondary)
                }
            } else {
                Image(systemName: result.source.icon)
                    .font(.title3)
                    .foregroundStyle(.secondary)
            }
        }
        .frame(width: 118, height: 66)
        .clipShape(RoundedRectangle(cornerRadius: 8))
        .overlay(alignment: .bottomLeading) {
            if result.duration != nil {
                Text(MagicDuration.text(result.duration))
                    .font(.system(size: 9, weight: .bold))
                    .padding(.horizontal, 5)
                    .padding(.vertical, 2)
                    .background(.black.opacity(0.75), in: RoundedRectangle(cornerRadius: 4))
                    .foregroundStyle(.white)
                    .padding(3)
            }
        }
        .overlay(alignment: .topTrailing) {
            if readyToPlay > 0 {
                Image(systemName: "play.circle.fill")
                    .font(.system(size: 16))
                    .foregroundStyle(.white, V2Theme.mint.opacity(0.95))
                    .padding(4)
            }
        }
        .contentShape(RoundedRectangle(cornerRadius: 8))
        .onTapGesture(perform: onPlay)
    }

    private var durationBadgeView: some View {
        Text(MagicDuration.text(result.duration))
            .font(.system(size: 10, weight: .bold))
            .padding(.horizontal, 7)
            .padding(.vertical, 3)
            .background(badgeColor.opacity(0.16), in: Capsule())
            .foregroundStyle(badgeColor)
    }

    private var badgeColor: Color {
        switch badge {
        case .exact: return V2Theme.mint
        case .close: return V2Theme.gold
        case .short: return V2Theme.accent
        case .unknown: return .gray
        case .plain: return .secondary
        }
    }

    private var sourceBadge: some View {
        Label(lang.t(result.source.labelKey), systemImage: result.source.icon)
            .font(.system(size: 10, weight: .semibold))
            .padding(.horizontal, 7)
            .padding(.vertical, 3)
            .background(V2Theme.bg.opacity(0.55), in: Capsule())
            .foregroundStyle(.secondary)
    }

    @ViewBuilder private var matchBadge: some View {
        let text: String? = {
            switch badge {
            case .exact: return lang.t("magic.badge.exact")
            case .close: return lang.t("magic.badge.close")
            case .short: return lang.t("magic.badge.short")
            case .unknown: return lang.t("magic.badge.unknown")
            case .plain: return nil
            }
        }()
        if let text {
            Text(text)
                .font(.system(size: 9, weight: .bold))
                .lineLimit(1)
                .padding(.horizontal, 7)
                .padding(.vertical, 3)
                .background(badgeColor.opacity(0.13), in: Capsule())
                .foregroundStyle(badgeColor)
        }
    }

    private var captionParts: [String] {
        var parts: [String] = []
        if let uploader = result.uploader, !uploader.isEmpty { parts.append(uploader) }
        if !result.hostText.isEmpty { parts.append(result.hostText) }
        if let views = result.views, views > 0 { parts.append(viewsText(views)) }
        return parts
    }

    @ViewBuilder private var captionLine: some View {
        let parts = captionParts
        if parts.isEmpty && (result.snippet ?? "").isEmpty {
            EmptyView()
        } else {
            VStack(alignment: .leading, spacing: 2) {
                if !parts.isEmpty {
                    Text(parts.joined(separator: " · "))
                        .font(.system(size: 10))
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
                if let snippet = result.snippet, !snippet.isEmpty {
                    Text(snippet)
                        .font(.system(size: 10))
                        .foregroundStyle(.secondary.opacity(0.8))
                        .lineLimit(2)
                }
            }
        }
    }

    private func viewsText(_ v: Int) -> String {
        switch v {
        case 1_000_000...: return String(format: "%.1fM", Double(v) / 1_000_000)
        case 1_000...: return String(format: "%.1fK", Double(v) / 1_000)
        default: return "\(v)"
        }
    }
}
