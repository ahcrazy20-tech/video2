import SwiftUI

// MARK: - شاشة اختيار الموديل لكل مزوّد

/// شاشة كاملة لاختيار الموديل من مزوّد معيّن. تظهر الموديلات النشطة فقط
/// مع تمييز "مناسب للبرنامج" ⭐ بناءً على القدرات + ترشيحات مخصصة.
struct ModelPickerView: View {
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var lang: LanguageStore
    @StateObject private var catalog = ModelCatalog.shared

    let provider: ModelProvider
    let purpose: ModelPurpose

    @State private var selectedCapability: ModelCapability? = nil
    @State private var onlyRecommended = true
    @State private var searchText = ""

    enum ModelPurpose: String, CaseIterable, Identifiable {
        case translation
        case transcription
        case tts
        case any

        var id: String { rawValue }

        var titleAR: String {
            switch self {
            case .translation: return "الترجمة النصية"
            case .transcription: return "التفريغ الصوتي"
            case .tts: return "تحويل النص إلى كلام (دبلجة)"
            case .any: return "أي استخدام"
            }
        }

        /// الفلاتر الافتراضية لكل غرض
        var defaultCapabilities: [ModelCapability] {
            switch self {
            case .translation: return [.translation]
            case .transcription: return [.transcription]
            case .tts: return [.tts]
            case .any: return []
            }
        }

        /// مفتاح التخزين في UserDefaults
        var defaultsKey: String {
            switch self {
            case .translation: return "translator.model"
            case .transcription: return "stt.model"
            case .tts: return "tts.model"
            case .any: return "model.generic"
            }
        }
    }

    var body: some View {
        NavigationStack {
            content
                .background(V2Theme.bg.ignoresSafeArea())
                .navigationTitle("اختر موديل \(provider.titleAR)")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("إلغاء") { dismiss() }
                    }
                    ToolbarItem(placement: .primaryAction) {
                        Button {
                            Task { await catalog.refresh(provider) }
                        } label: {
                            if catalog.loading.contains(provider) {
                                ProgressView().controlSize(.small)
                            } else {
                                Label("تحديث", systemImage: "arrow.clockwise")
                            }
                        }
                    }
                }
                .searchable(text: $searchText, prompt: "ابحث في الموديلات")
        }
        // نحافظ على إحداثيات الشاشة LTR هنا لأن iOS 16 كان يعكس الـ List كاملة
        // داخل الـ sheet في الواجهة العربية (فتظهر حتى أسماء الموديلات بالمقلوب).
        // النص العربي نفسه يظل يُرسم RTL تلقائياً بواسطة Unicode.
        .environment(\.layoutDirection, .leftToRight)
        .task {
            // اجلب الموديلات تلقائياً عند الفتح إذا لم تكن محفوظة أو انتهت صلاحيتها
            if catalog.models(for: provider).isEmpty {
                await catalog.refresh(provider)
            }
        }
    }

    @ViewBuilder private var content: some View {
        // Qwen-MT قائمة موثقة ثابتة؛ نسمح بمقارنة موديلاتها وحصصها قبل حفظ
        // المفتاح، بينما يبقى التشغيل/اختبار المفتاح محمياً في الإعدادات.
        if !provider.isAvailable && provider != .dashscope {
            missingKeyView
        } else if let error = catalog.lastError[provider], catalog.models(for: provider).isEmpty {
            errorView(error)
        } else if catalog.models(for: provider).isEmpty {
            loadingOrEmpty
        } else {
            listView
        }
    }

    private var missingKeyView: some View {
        VStack(spacing: 14) {
            Image(systemName: "key.slash")
                .font(.system(size: 48))
                .foregroundStyle(V2Theme.gold)
            Text("أدخل مفتاح \(provider.titleAR) من الإعدادات أولاً")
                .font(.headline)
                .multilineTextAlignment(.center)
            Text("بعد حفظ المفتاح، افتح هذه الشاشة مرة أخرى لجلب الموديلات المتاحة.")
                .font(.caption)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 30)
        }
    }

    private func errorView(_ error: String) -> some View {
        VStack(spacing: 14) {
            Image(systemName: "exclamationmark.triangle.fill")
                .font(.system(size: 48))
                .foregroundStyle(.red)
            Text("تعذر جلب الموديلات")
                .font(.headline)
            Text(error)
                .font(.caption)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 30)
            Button {
                Task { await catalog.refresh(provider) }
            } label: {
                Label("إعادة المحاولة", systemImage: "arrow.clockwise")
            }
            .buttonStyle(.borderedProminent)
        }
    }

    private var loadingOrEmpty: some View {
        VStack(spacing: 14) {
            ProgressView()
                .controlSize(.large)
            Text("جاري جلب الموديلات من \(provider.titleAR)…")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
    }

    private var listView: some View {
        List {
            Section {
                Picker("الاستخدام", selection: $selectedCapability) {
                    Text("الكل").tag(ModelCapability?.none)
                    ForEach(purpose.defaultCapabilities, id: \.self) { cap in
                        Text(cap.titleAR).tag(ModelCapability?.some(cap))
                    }
                    ForEach(otherCapabilities, id: \.self) { cap in
                        Text(cap.titleAR).tag(ModelCapability?.some(cap))
                    }
                }
                .pickerStyle(.menu)

                Toggle("⭐ الموصى بها للبرنامج فقط", isOn: $onlyRecommended)
            }

            Section("السعر والحصة") {
                Label("كل موديل يعرض حالته: مجاني، حصة محدودة، مدفوع، أو حسب الحساب.", systemImage: "creditcard")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Label("المتبقي الحقيقي يظهر في الإعدادات ← الرصيد والحدود بعد تحديثه.", systemImage: "gauge.with.dots.needle.50percent")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            if let lastFetched = catalog.lastFetched[provider] {
                Section {
                    HStack {
                        Image(systemName: "clock.arrow.circlepath")
                            .foregroundStyle(.secondary)
                        Text("آخر تحديث: \(lastFetched.formatted(date: .abbreviated, time: .shortened))")
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                        Spacer()
                        Text("\(filtered.count) موديل")
                            .font(.caption2.monospacedDigit())
                            .foregroundStyle(.secondary)
                    }
                }
            }

            ForEach(groupedSections, id: \.0) { group in
                Section(groupTitle(group.0)) {
                    ForEach(group.1) { entry in
                        ModelRow(entry: entry,
                                 isSelected: entry.rawID == currentSelection) {
                            select(entry)
                        }
                    }
                }
            }

            if filtered.isEmpty {
                Section {
                    Label("لا توجد نتائج مطابقة. عطّل فلتر ⭐ أو غيّر الاستخدام.", systemImage: "magnifyingglass")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
    }

    // MARK: - تجميع الموديلات

    private var filtered: [ModelEntry] {
        var list = catalog.models(for: provider)
        if let cap = selectedCapability { list = list.filter { $0.capabilities.contains(cap) } }
        else if !purpose.defaultCapabilities.isEmpty {
            list = list.filter { e in purpose.defaultCapabilities.contains(where: e.capabilities.contains) }
        }
        if onlyRecommended { list = list.filter { $0.recommended } }
        if !searchText.isEmpty {
            let q = searchText.lowercased()
            list = list.filter { e in
                e.rawID.lowercased().contains(q) ||
                e.displayName.lowercased().contains(q) ||
                (e.descriptionAR?.lowercased().contains(q) ?? false)
            }
        }
        return list
    }

    private var otherCapabilities: [ModelCapability] {
        ModelCapability.allCases.filter { !purpose.defaultCapabilities.contains($0) }
    }

    private var groupedSections: [(String, [ModelEntry])] {
        let recs = filtered.filter { $0.recommended }
        let others = filtered.filter { !$0.recommended }
        var out: [(String, [ModelEntry])] = []
        if !recs.isEmpty { out.append(("⭐ موصى به للبرنامج", recs)) }
        if !others.isEmpty { out.append(("موديلات أخرى", others)) }
        return out
    }

    private func groupTitle(_ s: String) -> String {
        switch s {
        case "⭐ موصى به للبرنامج": return "⭐ موصى به للبرنامج"
        default: return s
        }
    }

    // MARK: - الاختيار

    private var currentSelection: String {
        UserDefaults.standard.string(forKey: ModelSelection.key(purpose: selectionPurpose, provider: provider)) ?? ""
    }

    private var selectionPurpose: String {
        switch purpose {
        case .translation: return "translator"
        case .transcription: return "stt"
        case .tts: return "tts"
        case .any: return "generic"
        }
    }

    private func select(_ entry: ModelEntry) {
        ModelSelection.save(entry.rawID, purpose: selectionPurpose, provider: provider)
        if provider == .gemini && purpose == .translation {
            UserDefaults.standard.set(entry.rawID, forKey: "gemini.model")
        }
        dismiss()
    }
}

// MARK: - صف موديل

private struct ModelRow: View {
    let entry: ModelEntry
    let isSelected: Bool
    let onSelect: () -> Void

    var body: some View {
        Button(action: onSelect) {
            HStack(alignment: .top, spacing: 10) {
                if entry.recommended {
                    Image(systemName: "star.fill")
                        .foregroundStyle(V2Theme.gold)
                        .font(.caption)
                        .padding(.top, 2)
                } else {
                    Image(systemName: "circle.dotted")
                        .foregroundStyle(.secondary)
                        .font(.caption)
                        .padding(.top, 2)
                }

                VStack(alignment: .leading, spacing: 4) {
                    HStack {
                        Text(entry.rawID)
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(.primary)
                            .lineLimit(2)
                            .multilineTextAlignment(.leading)
                        Spacer(minLength: 6)
                        if isSelected {
                            Image(systemName: "checkmark.circle.fill")
                                .foregroundStyle(.green)
                        }
                    }
                    if let reason = entry.recommendedReasonAR {
                        Text(reason)
                            .font(.caption2)
                            .foregroundStyle(V2Theme.gold)
                    }
                    ModelBillingPill(info: entry.billing)
                    Text(entry.billing.detailAR)
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                    HStack(spacing: 6) {
                        ForEach(entry.capabilities.prefix(4), id: \.self) { cap in
                            Label(cap.titleAR, systemImage: cap.systemImage)
                                .font(.system(size: 9, weight: .medium))
                                .padding(.horizontal, 5)
                                .padding(.vertical, 2)
                                .background(V2Theme.card, in: Capsule())
                                .foregroundStyle(.secondary)
                        }
                    }
                    if let ctx = entry.contextWindow {
                        Text("سياق \(ctx.formatted()) token")
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }
                }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

private struct ModelBillingPill: View {
    let info: ModelBillingInfo

    var body: some View {
        Label(info.kind.titleAR, systemImage: icon)
            .font(.system(size: 10, weight: .semibold))
            .padding(.horizontal, 6)
            .padding(.vertical, 3)
            .foregroundStyle(color)
            .background(color.opacity(0.12), in: Capsule())
    }

    private var icon: String {
        switch info.kind {
        case .free: return "gift.fill"
        case .trialQuota: return "timer"
        case .paid: return "creditcard.fill"
        case .accountDependent: return "person.crop.circle.badge.questionmark"
        case .deprecated: return "exclamationmark.triangle.fill"
        case .unknown: return "questionmark.circle"
        }
    }

    private var color: Color {
        switch info.kind {
        case .free: return .green
        case .trialQuota: return V2Theme.gold
        case .paid: return .orange
        case .accountDependent, .unknown: return .secondary
        case .deprecated: return .red
        }
    }
}
