import SwiftUI

struct BrowserView: View {
    @EnvironmentObject var browser: BrowserModel
    @EnvironmentObject var downloads: DownloadManager
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
                }
                if browser.showDRMBanner {
                    drmBanner
                }
                WebView(tab: browser.current, model: browser)
                    .ignoresSafeArea(edges: .bottom)
            }
            .background(V2Theme.bg)
            .navigationBarHidden(true)
            .sheet(isPresented: $browser.showDetector) { DetectorSheet() }
            .sheet(isPresented: $showTabs) { TabsSheet() }
            .alert("لصق رابط فيديو", isPresented: $showManual) {
                TextField("https://...", text: $manualURL)
                Button("تحميل") {
                    downloads.enqueueManual(urlString: manualURL, title: browser.current.title, page: browser.current.urlString)
                    manualURL = ""
                }
                Button("إلغاء", role: .cancel) {}
            } message: {
                Text("ضع رابط MP4 أو m3u8 مباشراً إن لم يُكتشف تلقائياً.")
            }
            .onAppear { address = browser.current.urlString }
            .onChange(of: browser.selectedID) { _ in address = browser.current.urlString }
        }
    }

    private var addressBar: some View {
        HStack(spacing: 8) {
            Button { browser.current.webView.goBack() } label: {
                Image(systemName: "chevron.forward")
            }
            Button { browser.current.webView.goForward() } label: {
                Image(systemName: "chevron.backward")
            }
            HStack {
                Image(systemName: "lock.fill").font(.caption2).foregroundStyle(.secondary)
                TextField("ابحث أو اكتب موقعاً", text: $address)
                    .textInputAutocapitalization(.never)
                    .keyboardType(.URL)
                    .submitLabel(.go)
                    .onSubmit { browser.current.load(address) }
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
                Text("تحذير DRM").font(.headline)
                Text(browser.lastDRM.messageAR).font(.caption).foregroundStyle(.secondary)
            }
            Spacer()
            Button("إخفاء") { browser.showDRMBanner = false }
                .font(.caption.bold())
        }
        .padding(12)
        .background(Color.orange.opacity(0.18))
    }
}

struct DetectorSheet: View {
    @EnvironmentObject var browser: BrowserModel
    @EnvironmentObject var downloads: DownloadManager
    @Environment(\.dismiss) var dismiss

    var body: some View {
        NavigationStack {
            List {
                if browser.current.drmAlert.isProtected {
                    Section {
                        Label(browser.current.drmAlert.messageAR, systemImage: "exclamationmark.shield.fill")
                            .foregroundStyle(V2Theme.gold)
                    }
                }
                Section("المصادر المكتشفة") {
                    if browser.current.detected.isEmpty {
                        Text("لا يوجد فيديو بعد. شغّل المقطع في الصفحة ثم اضغط تحديث، أو الصق الرابط يدوياً.")
                            .foregroundStyle(.secondary)
                    }
                    ForEach(browser.current.detected) { item in
                        VStack(alignment: .leading, spacing: 8) {
                            Text(item.title).font(.headline)
                            Text(item.url).font(.caption2).foregroundStyle(.secondary).lineLimit(2)
                            HStack {
                                chip(item.kind.titleAR)
                                chip(item.extractionMethod)
                                if let q = item.qualityLabel, !q.isEmpty { chip(q) }
                                if item.drm.isProtected {
                                    chip("DRM")
                                }
                            }
                            Button {
                                downloads.enqueue(item)
                                dismiss()
                            } label: {
                                Label(item.canDownload ? "تحميل وحفظ في المكتبة" : "محمي — لا يمكن التحميل", systemImage: item.canDownload ? "arrow.down.circle.fill" : "lock.fill")
                            }
                            .disabled(!item.canDownload)
                            .buttonStyle(.borderedProminent)
                            .tint(item.canDownload ? V2Theme.accent : .gray)
                        }
                        .padding(.vertical, 6)
                    }
                }
            }
            .navigationTitle("استخراج الفيديو")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("إغلاق") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("مسح") { browser.current.detected.removeAll() }
                }
            }
        }
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
                        Button(role: .destructive) { browser.close(tab.id) } label: { Text("إغلاق") }
                    }
                }
            }
            .navigationTitle("التبويبات")
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
