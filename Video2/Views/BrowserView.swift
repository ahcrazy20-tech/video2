import SwiftUI

struct BrowserView: View {
    @EnvironmentObject var browser: BrowserModel
    @EnvironmentObject var downloads: DownloadManager
    @EnvironmentObject var lang: LanguageStore
    @State private var address: String = ""
    @State private var showTabs = false
    @State private var showManual = false
    @State private var manualURL = ""

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                addressBar
                if browser.current.isLoading {
                    ProgressView(value: browser.current.estimatedProgress)
                        .tint(V2Theme.gold)
                        .allowsHitTesting(false)
                }
                if browser.showDRMBanner {
                    drmBanner
                }
                WebView(tab: browser.current, model: browser)
                    .id(browser.selectedID) // <-- إصلاح أساسي: يعيد بناء WebView عند تبديل التاب
                    .ignoresSafeArea(edges: .bottom)
            }
            .background(V2Theme.bg)
            .navigationBarHidden(true)
            .sheet(isPresented: $browser.showDetector) { DetectorSheet() }
            .sheet(isPresented: $showTabs) { TabsSheet() }
            .alert(lang.t("paste.title"), isPresented: $showManual) {
                TextField("https://...", text: $manualURL)
                    .textInputAutocapitalization(.never)
                    .keyboardType(.URL)
                Button(lang.t("paste.download")) {
                    downloads.enqueueManual(urlString: manualURL, title: browser.current.title, page: browser.current.urlString)
                    manualURL = ""
                }
                Button(lang.t("lib.cancel"), role: .cancel) {}
            } message: {
                Text(lang.t("paste.hint"))
            }
            .onAppear { address = browser.current.urlString }
            .onChange(of: browser.selectedID) { _ in address = browser.current.urlString }
            .onChange(of: browser.current.urlString) { newURL in
                // تحديث الـ address bar لما الـ URL يتغير من الـ webView
                if newURL != address {
                    address = newURL
                }
            }
        }
    }

    private var addressBar: some View {
        HStack(spacing: 8) {
            Button {
                if browser.current.webView.canGoBack {
                    browser.current.webView.goBack()
                }
            } label: {
                Image(systemName: "chevron.backward")
            }
            .disabled(!browser.current.webView.canGoBack)

            Button {
                if browser.current.webView.canGoForward {
                    browser.current.webView.goForward()
                }
            } label: {
                Image(systemName: "chevron.forward")
            }
            .disabled(!browser.current.webView.canGoForward)

            HStack {
                Image(systemName: "lock.fill").font(.caption2).foregroundStyle(.secondary)
                TextField(lang.t("addr.placeholder"), text: $address)
                    .textInputAutocapitalization(.never)
                    .keyboardType(.URL)
                    .submitLabel(.go)
                    .onSubmit {
                        browser.current.load(address)
                    }
            }
            .padding(10)
            .background(V2Theme.card, in: Capsule())

            Button {
                browser.showDetector = true
            } label: {
                ZStack(alignment: .topTrailing) {
                    Image(systemName: "film.stack")
                    if !browser.current.detected.isEmpty {
                        Text("\(browser.current.detected.count)")
                            .font(.system(size: 9, weight: .bold))
                            .padding(3)
                            .background(V2Theme.accent, in: Circle())
                            .offset(x: 6, y: -6)
                    }
                }
            }
            Button { showManual = true } label: { Image(systemName: "link.badge.plus") }
            Button { showTabs = true } label: { Image(systemName: "square.on.square") }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .foregroundStyle(.white)
    }

    private var drmBanner: some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: "lock.trianglebadge.exclamationmark.fill")
                .foregroundStyle(V2Theme.gold)
            VStack(alignment: .leading, spacing: 4) {
                Text(lang.t("drm.title")).font(.headline)
                Text(lang.t("drm.body")).font(.caption).foregroundStyle(.secondary)
            }
            Spacer()
            Button(lang.t("drm.hide")) { browser.showDRMBanner = false }
                .font(.caption.bold())
        }
        .padding(12)
        .background(Color.orange.opacity(0.18))
        .transition(.opacity)
    }
}

struct DetectorSheet: View {
    @EnvironmentObject var browser: BrowserModel
    @EnvironmentObject var downloads: DownloadManager
    @EnvironmentObject var lang: LanguageStore
    @Environment(\.dismiss) var dismiss
    @State private var onlyPlayable = true

    var items: [DetectedMedia] {
        let all = browser.current.detected
        if onlyPlayable {
            return all.filter { $0.kind.isCompleteVideo || $0.kind == .mp3 || $0.kind == .aac }
        }
        return all
    }

    var body: some View {
        NavigationStack {
            List {
                if browser.current.drmAlert.isProtected {
                    Section {
                        Label(lang.t("drm.body"), systemImage: "exclamationmark.shield.fill")
                            .foregroundStyle(V2Theme.gold)
                    }
                }
                Section {
                    Toggle(lang.t("det.filter"), isOn: $onlyPlayable)
                }
                Section("\(lang.t("det.sources")) (\(items.count))") {
                    if items.isEmpty {
                        Text(lang.t("det.empty"))
                            .foregroundStyle(.secondary)
                    }
                    ForEach(items) { item in
                        VStack(alignment: .leading, spacing: 8) {
                            Text(item.title).font(.headline)
                            HStack(spacing: 10) {
                                labeled(lang.t("det.duration"), item.durationText)
                                labeled(lang.t("det.size"), item.sizeText)
                                if let r = item.resolutionText { labeled(lang.t("det.quality"), r) }
                            }
                            HStack {
                                chip(item.kind.titleAR)
                                chip(item.kind.avPlayerSupported ? lang.t("det.play.offline") : lang.t("det.play.maybe"))
                                if item.drm.isProtected { chip("DRM") }
                                chip(item.extractionMethod)
                            }
                            if let mime = item.mime, !mime.isEmpty {
                                Text(mime).font(.caption2).foregroundStyle(.secondary)
                            }
                            Text(item.url).font(.caption2).foregroundStyle(.tertiary).lineLimit(2)
                            Button {
                                downloads.enqueue(item)
                                dismiss()
                            } label: {
                                Label(item.canDownload ? lang.t("det.save") : lang.t("det.protected"), systemImage: item.canDownload ? "arrow.down.circle.fill" : "lock.fill")
                            }
                            .disabled(!item.canDownload)
                            .buttonStyle(.borderedProminent)
                            .tint(item.canDownload ? V2Theme.accent : .gray)
                        }
                        .padding(.vertical, 6)
                    }
                }
            }
            .navigationTitle(lang.t("det.title"))
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(lang.t("det.close")) { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(lang.t("det.clear")) { browser.current.detected.removeAll() }
                }
            }
        }
    }

    private func labeled(_ k: String, _ v: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(k).font(.caption2).foregroundStyle(.secondary)
            Text(v).font(.caption.bold())
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func chip(_ t: String) -> some View {
        Text(t)
            .font(.caption2.bold())
            .padding(.horizontal, 8)
            .padding(.vertical, 4)
            .background(V2Theme.card, in: Capsule())
    }
}

struct TabsSheet: View {
    @EnvironmentObject var browser: BrowserModel
    @EnvironmentObject var lang: LanguageStore
    @Environment(\.dismiss) var dismiss

    var body: some View {
        NavigationStack {
            List {
                ForEach(browser.tabs) { tab in
                    Button {
                        browser.selectedID = tab.id
                        dismiss()
                    } label: {
                        VStack(alignment: .leading) {
                            Text(tab.title).foregroundStyle(.primary)
                            Text(tab.urlString).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                        }
                    }
                    .swipeActions {
                        Button(role: .destructive) {
                            browser.close(tab.id)
                        } label: { Text(lang.t("tabs.close")) }
                    }
                }
            }
            .navigationTitle(lang.t("tabs.title"))
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button {
                        browser.newTab()
                        dismiss()
                    } label: { Image(systemName: "plus") }
                }
            }
        }
    }
}
