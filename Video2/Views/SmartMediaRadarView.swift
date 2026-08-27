import SwiftUI
import UIKit
import SafariServices

/// A new, additive source-selection surface. The existing DetectorSheet remains
/// available and continues to use the original DownloadManager path.
struct SmartMediaRadarView: View {
    @EnvironmentObject private var downloads: DownloadManager
    @EnvironmentObject private var lang: LanguageStore
    @Environment(\.dismiss) private var dismiss

    @ObservedObject var tab: BrowserTab
    @StateObject private var radar: SmartMediaRadarModel
    @State private var previewMedia: DetectedMedia?
    @State private var alertMessage: String?

    init(tab: BrowserTab) {
        self.tab = tab
        _radar = StateObject(wrappedValue: SmartMediaRadarModel(tab: tab))
    }

    var body: some View {
        NavigationStack {
            List {
                overviewSection

                if radar.groups.isEmpty && !radar.isRefreshing {
                    emptySection
                } else {
                    ForEach(radar.groups) { group in
                        Section {
                            groupCard(group)
                        }
                    }
                }
            }
            .listStyle(.insetGrouped)
            .scrollContentBackground(.hidden)
            .background(V2Theme.bg)
            .navigationTitle(lang.t("radar.title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(lang.t("nav.close")) { dismiss() }
                }
                ToolbarItem(placement: .primaryAction) {
                    Button {
                        radar.refresh()
                    } label: {
                        Image(systemName: radar.isRefreshing ? "hourglass" : "arrow.clockwise")
                    }
                    .disabled(radar.isRefreshing)
                    .accessibilityLabel(lang.t("radar.refresh"))
                }
            }
        }
        .task {
            radar.refresh()
        }
        .onReceive(tab.$detected) { detected in
            radar.syncFromTab(detected)
        }
        .onDisappear {
            radar.cancel()
        }
        .sheet(item: $previewMedia) { media in
            SmartMediaSourcePreview(media: media)
        }
        .alert(lang.t("radar.notice"), isPresented: Binding(
            get: { alertMessage != nil },
            set: { if !$0 { alertMessage = nil } }
        )) {
            Button(lang.t("nav.done"), role: .cancel) {}
        } message: {
            Text(alertMessage ?? "")
        }
    }

    private var overviewSection: some View {
        Section {
            VStack(alignment: .leading, spacing: 8) {
                HStack(spacing: 10) {
                    Image(systemName: "sparkles")
                        .font(.title2)
                        .foregroundStyle(V2Theme.gold)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(lang.t("radar.heading"))
                            .font(.headline)
                        Text(tab.title)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    }
                    Spacer()
                    Text("\(radar.groups.count)")
                        .font(.title3.monospacedDigit().bold())
                        .foregroundStyle(V2Theme.accent)
                }

                if !tab.urlString.isEmpty {
                    Text(tab.urlString)
                        .font(.caption2)
                        .foregroundStyle(.tertiary)
                        .lineLimit(2)
                        .environment(\.layoutDirection, .leftToRight)
                }

                Text(lang.t("radar.explanation"))
                    .font(.caption)
                    .foregroundStyle(.secondary)

                if radar.isRefreshing {
                    HStack(spacing: 8) {
                        ProgressView()
                            .controlSize(.small)
                        Text(lang.t("radar.scanning"))
                            .font(.caption.bold())
                            .foregroundStyle(V2Theme.gold)
                    }
                } else if let date = radar.lastUpdated {
                    Text(String(format: lang.t("radar.updated"), DateFormatter.localizedString(from: date, dateStyle: .none, timeStyle: .short)))
                        .font(.caption2)
                        .foregroundStyle(.tertiary)
                }
            }
            .padding(.vertical, 4)
        }
    }

    private var emptySection: some View {
        Section {
            VStack(spacing: 10) {
                Image(systemName: "film.stack")
                    .font(.system(size: 42))
                    .foregroundStyle(V2Theme.gold)
                Text(lang.t("radar.empty"))
                    .font(.headline)
                    .multilineTextAlignment(.center)
                Text(lang.t("radar.empty.hint"))
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                Button {
                    radar.refresh()
                } label: {
                    Label(lang.t("radar.refresh"), systemImage: "arrow.clockwise")
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.small)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 22)
        }
    }

    private func groupCard(_ group: SmartMediaGroup) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .top, spacing: 8) {
                Image(systemName: "rectangle.stack.badge.play")
                    .foregroundStyle(V2Theme.accent)
                VStack(alignment: .leading, spacing: 3) {
                    Text(group.title)
                        .font(.headline)
                        .lineLimit(2)
                    Text(String(format: lang.t("radar.source.count"), group.sources.count))
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
                Spacer(minLength: 0)
            }

            if let best = group.recommended {
                sourceCard(best, recommended: true)
            }

            let otherSources = group.sources.filter { $0.url != group.recommended?.url }
            if !otherSources.isEmpty {
                DisclosureGroup {
                    VStack(alignment: .leading, spacing: 8) {
                        ForEach(otherSources) { source in
                            sourceCard(source, recommended: false)
                        }
                    }
                    .padding(.top, 8)
                } label: {
                    Text(lang.t("radar.more.sources"))
                        .font(.caption.bold())
                        .foregroundStyle(V2Theme.accent)
                }
            }
        }
        .padding(.vertical, 5)
    }

    private func sourceCard(_ media: DetectedMedia, recommended: Bool) -> some View {
        let status = SmartMediaRadar.status(for: media)
        let canDownload = SmartMediaRadar.isDownloadableByExistingPipeline(media)

        return VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                if recommended {
                    Label(lang.t("radar.recommended"), systemImage: "checkmark.seal.fill")
                        .font(.caption2.bold())
                        .foregroundStyle(V2Theme.gold)
                }
                Text(statusTitle(status))
                    .font(.caption2.bold())
                    .foregroundStyle(statusColor(status))
                Spacer(minLength: 0)
                Text(media.kind.titleAR)
                    .font(.caption2.bold())
                    .padding(.horizontal, 7)
                    .padding(.vertical, 4)
                    .background(V2Theme.card, in: Capsule())
            }

            HStack(spacing: 12) {
                metadata(label: lang.t("radar.quality"), value: media.resolutionText ?? "—")
                metadata(label: lang.t("radar.duration"), value: media.durationText)
                metadata(label: lang.t("radar.size"), value: media.sizeText)
            }

            Label(
                media.drm.isProtected ? lang.t("radar.protection.protected") : lang.t("radar.protection.none"),
                systemImage: media.drm.isProtected ? "lock.fill" : "lock.open"
            )
            .font(.caption2)
            .foregroundStyle(media.drm.isProtected ? statusColor(.protected) : .secondary)

            if let mime = media.mime, !mime.isEmpty {
                Text(mime)
                    .font(.caption2)
                    .foregroundStyle(.tertiary)
                    .environment(\.layoutDirection, .leftToRight)
            }

            if media.kind == .hls, let variants = media.variants, !variants.isEmpty {
                VStack(alignment: .leading, spacing: 5) {
                    Text(lang.t("radar.quality.pick"))
                        .font(.caption2.bold())
                        .foregroundStyle(.secondary)
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 6) {
                            qualityButton(title: lang.t("radar.quality.auto"), selected: false) {
                                enqueue(media, variant: nil)
                            }
                            .disabled(!canDownload)
                            ForEach(variants) { variant in
                                qualityButton(title: variant.qualityLabel, selected: false) {
                                    enqueue(media, variant: variant)
                                }
                                .disabled(!canDownload)
                            }
                        }
                    }
                }
            }

            HStack(spacing: 8) {
                Button {
                    enqueue(media, variant: nil)
                } label: {
                    Label(lang.t("radar.download"), systemImage: "arrow.down.circle.fill")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.small)
                .disabled(!canDownload)

                Button {
                    previewMedia = media
                } label: {
                    Image(systemName: "play.circle")
                }
                .buttonStyle(.bordered)
                .controlSize(.small)
                .disabled(URL(string: media.url) == nil)
                .accessibilityLabel(lang.t("radar.preview"))

                Button {
                    UIPasteboard.general.string = media.url
                    alertMessage = lang.t("radar.copied")
                } label: {
                    Image(systemName: "doc.on.doc")
                }
                .buttonStyle(.bordered)
                .controlSize(.small)
                .accessibilityLabel(lang.t("radar.copy"))
            }

            if !canDownload {
                Text(statusExplanation(status))
                    .font(.caption2)
                    .foregroundStyle(statusColor(status))
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(10)
        .background(recommended ? V2Theme.accent.opacity(0.10) : V2Theme.card, in: RoundedRectangle(cornerRadius: 12))
        .overlay(
            RoundedRectangle(cornerRadius: 12)
                .stroke(recommended ? V2Theme.accent.opacity(0.45) : Color.white.opacity(0.08), lineWidth: 1)
        )
    }

    private func qualityButton(title: String, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .font(.caption2.bold())
                .padding(.horizontal, 8)
                .padding(.vertical, 5)
                .background(selected ? V2Theme.accent.opacity(0.30) : V2Theme.card, in: Capsule())
        }
        .buttonStyle(.plain)
    }

    private func metadata(label: String, value: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label)
                .font(.caption2)
                .foregroundStyle(.secondary)
            Text(value)
                .font(.caption.bold())
                .lineLimit(1)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func enqueue(_ media: DetectedMedia, variant: HLSStreamVariant?) {
        var selected = media
        let maxHeight: Int?

        if let variant {
            // This is the same explicit HLS-variant selection used by the old
            // DetectorSheet. Direct URLs (including YouTube direct media URLs)
            // are passed through unchanged.
            selected.url = variant.url
            selected.qualityLabel = variant.qualityLabel
            selected.height = variant.height
            selected.width = variant.width
            maxHeight = nil
        } else {
            maxHeight = DownloadManager.preferredMaxHeight
        }

        let pageURL = tab.urlString
        Task { @MainActor in
            let auth = await BrowserAuth.snapshot(
                webView: tab.webView,
                pageURL: pageURL,
                mediaURL: selected.url
            )
            downloads.enqueue(selected, auth: auth, maxHeight: maxHeight)
            alertMessage = lang.t("radar.queued")
        }
    }

    private func statusTitle(_ status: SmartMediaStatus) -> String {
        switch status {
        case .ready: return lang.t("radar.status.ready")
        case .protected: return lang.t("radar.status.protected")
        case .dashUnsupported: return lang.t("radar.status.dash")
        case .detected: return lang.t("radar.status.detected")
        }
    }

    private func statusExplanation(_ status: SmartMediaStatus) -> String {
        switch status {
        case .ready:
            return ""
        case .protected:
            return lang.t("radar.explain.protected")
        case .dashUnsupported:
            return lang.t("radar.explain.dash")
        case .detected:
            return lang.t("radar.explain.detected")
        }
    }

    private func statusColor(_ status: SmartMediaStatus) -> Color {
        switch status {
        case .ready: return V2Theme.mint
        case .protected, .dashUnsupported: return V2Theme.gold
        case .detected: return .secondary
        }
    }
}

/// Opens a candidate in a separate Safari view instead of navigating the active
/// browser tab. This keeps the current page and its detector state intact.
struct SmartMediaSourcePreview: UIViewControllerRepresentable {
    let media: DetectedMedia

    func makeUIViewController(context: Context) -> SFSafariViewController {
        let url = URL(string: media.url) ?? URL(string: "about:blank")!
        return SFSafariViewController(url: url)
    }

    func updateUIViewController(_ uiViewController: SFSafariViewController, context: Context) {}
}
