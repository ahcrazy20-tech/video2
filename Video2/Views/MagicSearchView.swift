import SwiftUI

// شاشة البحث السحري: بحث مجمّع في عدة مصادر مع فلترة المدة،
// والنتائج تُفتح في المتصفح للتحقق، والتحميل يمر بخط التحميل الأصلي نفسه.
struct MagicSearchView: View {
    @EnvironmentObject var lang: LanguageStore
    @EnvironmentObject var browser: BrowserModel
    @EnvironmentObject var downloads: DownloadManager
    @EnvironmentObject var router: TabRouter
    @StateObject private var store = MagicSearchStore()
    @State private var toast: String?

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
                .padding(.bottom, 28)
            }
            .background(V2Theme.bg)
            .navigationTitle(lang.t("magic.title"))
            .navigationBarTitleDisplayMode(.inline)
            .overlay(alignment: .bottom) {
                if let toast {
                    Text(toast)
                        .font(.footnote.bold())
                        .padding(.horizontal, 14)
                        .padding(.vertical, 9)
                        .background(V2Theme.card, in: Capsule())
                        .padding(.bottom, 12)
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                }
            }
        }
    }

    // MARK: - لوحة البحث (الاسم + المدة)

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
                }
                .padding(8)
                .background(V2Theme.bg.opacity(0.6), in: RoundedRectangle(cornerRadius: 10))

                durationChips
            }
        }
    }

    private var durationChips: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                chip(label: lang.t("magic.duration.any"), value: nil)
                chip(label: lang.t("magic.duration.min5"), value: 5 * 60)
                chip(label: lang.t("magic.duration.min20"), value: 20 * 60)
                chip(label: lang.t("magic.duration.min60"), value: 60 * 60)
                chip(label: lang.t("magic.duration.min120"), value: 120 * 60)
            }
            .padding(.vertical, 2)
        }
    }

    private func chip(label: String, value: Double?) -> some View {
        let selected = store.minChip == value
        return Button {
            store.minChip = value
        } label: {
            Text(label)
                .font(.caption.bold())
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                .background(selected ? V2Theme.accent : V2Theme.bg.opacity(0.6), in: Capsule())
                .foregroundStyle(selected ? .white : .secondary)
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
                Text(String(format: lang.t("magic.count"), store.results.count, store.statuses.filter { $0.count > 0 }.count))
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                ForEach(store.results) { result in
                    MagicResultCard(
                        result: result,
                        badge: store.badge(for: result),
                        onOpen: { open(result) },
                        onDownload: { option in download(result, option: option) }
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
        }
        .padding(.top, 46)
    }

    // MARK: - الإجراءات (كلها عبر المسارات الأصلية بلا تعديل)

    private func open(_ result: MagicSearchResult) {
        router.openInBrowser(result.pageURL, browser: browser)
    }

    private func download(_ result: MagicSearchResult, option: MagicDownloadOption) {
        // نفس خط التحميل الأصلي تماماً (enqueueManual) — بدون أي تغيير في DownloadManager
        downloads.enqueueManual(
            urlString: option.url,
            title: "\(result.title) · \(option.label)",
            page: result.pageURL
        )
        showToast(String(format: lang.t("magic.download.started"), option.label))
    }

    private func showToast(_ message: String) {
        withAnimation { toast = message }
        Task {
            try? await Task.sleep(nanoseconds: 2_200_000_000)
            withAnimation { toast = nil }
        }
    }
}

// MARK: - بطاقة نتيجة واحدة

struct MagicResultCard: View {
    @EnvironmentObject var lang: LanguageStore
    let result: MagicSearchResult
    let badge: MagicSearchStore.DurationBadge
    let onOpen: () -> Void
    let onDownload: (MagicDownloadOption) -> Void

    var body: some View {
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
                }

                captionLine

                HStack(spacing: 8) {
                    Button(action: onOpen) {
                        Label(lang.t("magic.open.verify"), systemImage: "safari")
                            .font(.caption.bold())
                            .padding(.horizontal, 10)
                            .padding(.vertical, 6)
                            .background(V2Theme.accent.opacity(0.18), in: Capsule())
                            .foregroundStyle(V2Theme.accent)
                    }
                    .buttonStyle(.plain)

                    if !result.downloads.isEmpty {
                        Menu {
                            ForEach(result.downloads.prefix(8)) { option in
                                Button {
                                    onDownload(option)
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
                }
            }
        }
        .padding(10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(V2Theme.card.opacity(0.9), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
    }

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
        if let host = URL(string: result.pageURL)?.host { parts.append(host) }
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
