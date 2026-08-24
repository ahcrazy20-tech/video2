import Foundation
import Combine
import UIKit

/// مدير مهام الترجمة: طابور تسلسلي، حفظ واستئناف، تتبع تقدم لكل مرحلة.
@MainActor
final class TranslationManager: ObservableObject {

    @Published var jobs: [TranslationJob] = []

    private weak var library: LibraryStore?
    private var running = false
    private var currentTask: Task<Void, Never>? = nil

    nonisolated static let root = LibraryStore.documents.appendingPathComponent("Translations", isDirectory: true)

    // MARK: دورة الحياة

    func attach(library: LibraryStore) {
        self.library = library
    }

    func load() {
        try? FileManager.default.createDirectory(at: Self.root, withIntermediateDirectories: true)
        let url = Self.root.appendingPathComponent("jobs.json")
        guard let data = try? Data(contentsOf: url),
              let list = try? JSONDecoder().decode([TranslationJob].self, from: data) else { return }
        // أي مهمة كانت تعمل قبل الإغلاق تصبح قابلة للاستئناف
        jobs = list.map { job in
            var j = job
            if j.state.isBusy {
                j.state = .paused
                j.errorMessage = nil
            }
            return j
        }
        jobs.sort { $0.createdAt > $1.createdAt }
    }

    func saveIndex() {
        let url = Self.root.appendingPathComponent("jobs.json")
        if let data = try? JSONEncoder().encode(jobs) {
            try? data.write(to: url, options: .atomic)
        }
    }

    // MARK: مسارات

    nonisolated static func jobDir(_ id: UUID) -> URL {
        root.appendingPathComponent(id.uuidString, isDirectory: true)
    }

    // MARK: التحقق قبل البدء

    /// يعيد رسالة خطأ إن تعذّر البدء، أو nil إذا كان كل شيء سليماً.
    func validationMessage(for video: SavedVideo, target: SubLang) -> String? {
        guard FileManager.default.fileExists(atPath: video.localURL.path) else {
            return "ملف الفيديو غير موجود على الجهاز."
        }
        if let active = jobs.first(where: { $0.videoID == video.id && !$0.state.isFinished && $0.state != .paused }) {
            return "توجد مهمة ترجمة قيد العمل لنفس الفيديو: \(active.videoTitle)"
        }
        if jobs.contains(where: { $0.videoID == video.id && $0.state == .paused }) {
            return nil // مسموح، سيُستأنف بدل التكرار عبر استئناف المهمة المتوقفة
        }
        return nil
    }

    static func resolvedSTT(_ kind: STTProviderKind) -> STTProviderKind {
        switch kind {
        case .auto:
            if KeychainStore.has("assemblyai") { return .assemblyai }
            if KeychainStore.has("sttai") { return .sttai }
            if KeychainStore.has("speechmatics") { return .speechmatics }
            if KeychainStore.has("groq") { return .groq }
            if KeychainStore.has("siliconflow") { return .siliconflow }
            return .groq
        default:
            return kind
        }
    }

    static func sttHasKey(_ kind: STTProviderKind) -> String? {
        let resolved = resolvedSTT(kind)
        guard let keyID = resolved.keyID else { return nil }
        return KeychainStore.get(keyID)
    }

    // MARK: إنشاء مهمة

    func startJob(for video: SavedVideo,
                  source: SubLang,
                  target: SubLang,
                  stt: STTProviderKind,
                  translator: TranslatorKind) {

        guard validationMessage(for: video, target: target) == nil else { return }

        let resolvedSTT = Self.resolvedSTT(stt)
        guard Self.sttHasKey(resolvedSTT) != nil else { return }

        let resolvedTranslator = TranslateService.resolved(provider: translator)

        let job = TranslationJob(id: UUID(),
                                 videoID: video.id,
                                 videoTitle: video.title,
                                 isHLS: video.kind == .hls,
                                 sourceLang: source,
                                 targetLang: target,
                                 sttProvider: resolvedSTT,
                                 translator: resolvedTranslator,
                                 state: .queued,
                                 progress: 0,
                                 totalChunks: 0,
                                 doneChunks: 0,
                                 totalBatches: 0,
                                 doneBatches: 0,
                                 detectedLang: nil,
                                 cueCount: 0,
                                 assemblyTranscriptID: nil,
                                 errorMessage: nil,
                                 createdAt: Date(),
                                 finishedAt: nil)
        jobs.insert(job, at: 0)
        saveIndex()
        pump()
    }

    func resume(_ jobID: UUID) {
        guard let i = jobs.firstIndex(where: { $0.id == jobID }) else { return }
        guard jobs[i].state == .paused || jobs[i].state == .failed || jobs[i].state == .cancelled || jobs[i].state == .queued else { return }
        if jobs[i].state == .failed {
            // فشل سابق: أعد المحاولة من آخر نقطة محفوظة
            jobs[i].errorMessage = nil
        }
        jobs[i].state = .queued
        saveIndex()
        pump()
    }

    func cancel(_ jobID: UUID) {
        guard let i = jobs.firstIndex(where: { $0.id == jobID }) else { return }
        guard jobs[i].state.isBusy || jobs[i].state == .queued else { return }
        if jobs[i].id == currentTaskID {
            currentTask?.cancel()
            currentTask = nil
            running = false
        }
        jobs[i].state = .paused
        saveIndex()
        updateIdleTimer()
        pump()
    }

    private var currentTaskID: UUID?

    func delete(_ jobID: UUID) {
        guard let i = jobs.firstIndex(where: { $0.id == jobID }) else { return }
        let job = jobs[i]
        if job.state.isBusy {
            cancel(jobID)
        }
        // حذف المخرجات إن كانت المهمة مكتملة
        if job.state == .done, let video = library?.videos.first(where: { $0.id == job.videoID }) {
            if let files = video.subtitleFiles {
                let dirPrefix = "Subtitles/\(job.videoID.uuidString)/"
                let isMine = files.values.contains { $0.hasPrefix(dirPrefix) }
                if isMine {
                    for rel in files.values {
                        try? FileManager.default.removeItem(at: LibraryStore.documents.appendingPathComponent(rel))
                    }
                    var v = video
                    v.subtitleFiles = nil
                    v.subtitleTargetLang = nil
                    library?.update(v)
                }
            }
        }
        try? FileManager.default.removeItem(at: Self.jobDir(jobID))
        jobs.removeAll { $0.id == jobID }
        saveIndex()
    }

    // MARK: الطابور

    private func pump() {
        guard !running else { return }
        guard let idx = jobs.firstIndex(where: { $0.state == .queued }) else {
            updateIdleTimer()
            return
        }
        running = true
        let jobID = jobs[idx].id
        currentTaskID = jobID
        updateIdleTimer()
        let task = Task { await run(jobID: jobID) }
        currentTask = task
    }

    private func updateIdleTimer() {
        UIApplication.shared.isIdleTimerDisabled = running
    }

    private func setJob(_ id: UUID, mutate: (inout TranslationJob) -> Void) {
        guard let i = jobs.firstIndex(where: { $0.id == id }) else { return }
        mutate(&jobs[i])
    }

    // MARK: تنفيذ المهمة

    private func run(jobID: UUID) async {
        defer {
            if currentTaskID == jobID {
                running = false
                currentTaskID = nil
            }
            updateIdleTimer()
            pump()
        }
        guard let i = jobs.firstIndex(where: { $0.id == jobID }) else { return }
        guard let video = library?.videos.first(where: { $0.id == jobs[i].videoID }) else {
            setJob(jobID) { j in
                j.state = .failed
                j.errorMessage = "الفيديو غير موجود في المكتبة."
                j.finishedAt = Date()
            }
            saveIndex()
            return
        }

        let job = jobs[i]
        let dir = Self.jobDir(jobID)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)

        // هل اكتمل التفريغ سابقاً؟ (إعادة ترجمة فقط بدون لمس الصوت)
        let cuesURL = dir.appendingPathComponent("cues.json")
        var allCues: [SubCue] = []
        var detected: String? = job.detectedLang
        var duration: Double = 0
        var audioChunks: [AudioChunk] = []

        if let data = try? Data(contentsOf: cuesURL),
           let cached = try? JSONDecoder().decode([SubCue].self, from: data), !cached.isEmpty {
            allCues = cached
        }

        do {
            // نتأكد من Gemini قبل أي استخراج HLS طويل. بعض مفاتيح المشاريع
            // تحتفظ باسم موديل قديم في الإعدادات فيظهر 404 بعد دقائق من التحويل.
            var verifiedGeminiModel: String?
            if TranslateService.resolved(provider: job.translator) == .gemini {
                setJob(jobID) { j in
                    j.state = .preparing
                    j.progress = min(max(j.progress, 0.01), 0.02)
                    j.errorMessage = nil
                }
                saveIndex()
                verifiedGeminiModel = try await TranslateService.preflightGeminiModel()
            }

            // ——— المرحلة 1: الصوت (تُتخطى إذا كانت الجُمل جاهزة) ———
            if allCues.isEmpty {
                setJob(jobID) { $0.state = .extracting; $0.progress = 0.02 }
                saveIndex()

                let singleFile = job.sttProvider == .assemblyai || job.sttProvider == .sttai || job.sttProvider == .speechmatics
                let (chunks, dur) = try await AudioPipeline.extractChunks(
                    from: video.localURL,
                    into: dir,
                    singleFile: singleFile) { [weak self] p in
                        Task { @MainActor in
                            self?.setJob(jobID) { j in
                                if j.state == .extracting { j.progress = 0.02 + 0.10 * p }
                            }
                        }
                    }
                audioChunks = chunks
                duration = dur

                setJob(jobID) { j in
                    j.totalChunks = chunks.count
                    j.progress = 0.13
                }
                saveIndex()
            } else {
                audioChunks = (try? JSONDecoder().decode([AudioChunk].self,
                                                         from: Data(contentsOf: dir.appendingPathComponent("chunks.json")))) ?? []
                setJob(jobID) { j in
                    j.state = .transcribing
                    j.cueCount = allCues.count
                    j.progress = 0.72
                }
                saveIndex()
            }

            // ——— المرحلة 2: التفريغ ———
            if allCues.isEmpty {
                setJob(jobID) { $0.state = .transcribing }
                saveIndex()

                if job.sttProvider == .assemblyai {
                    let audioFile = dir.appendingPathComponent("chunks/audio-full.m4a")
                    guard FileManager.default.fileExists(atPath: audioFile.path) else {
                        throw AudioPipelineError.writerFailed("ملف الصوت المجمّع مفقود — أعد المهمة")
                    }
                    let key = Self.sttHasKey(.assemblyai) ?? ""
                    let (result, tid) = try await STTService.assemblyTranscribe(
                        audioURL: audioFile,
                        language: job.sourceLang,
                        apiKey: key,
                        existingTranscriptID: job.assemblyTranscriptID,
                        estimatedDuration: duration) { [weak self] statusText in
                            Task { @MainActor in
                                self?.setJob(jobID) { j in
                                    if j.state == .transcribing {
                                        j.errorMessage = statusText
                                    }
                                }
                            }
                        }
                    allCues = SubtitleCodec.normalize(SubtitleCodec.sortedAndMerged(result.cues))
                    detected = result.detectedLang
                    setJob(jobID) { j in
                        j.assemblyTranscriptID = tid
                        j.detectedLang = detected
                        j.doneChunks = 1
                        j.totalChunks = 1
                        j.cueCount = allCues.count
                        j.errorMessage = nil
                        j.progress = 0.72
                    }
                } else if job.sttProvider == .sttai {
                    let audioFile = dir.appendingPathComponent("chunks/audio-full.m4a")
                    guard FileManager.default.fileExists(atPath: audioFile.path) else {
                        throw AudioPipelineError.writerFailed("ملف الصوت المجمّع مفقود — أعد المهمة")
                    }
                    let key = Self.sttHasKey(.sttai) ?? ""
                    let result = try await STTService.sttaiTranscribe(
                        audioURL: audioFile,
                        language: job.sourceLang,
                        apiKey: key)
                    allCues = SubtitleCodec.normalize(SubtitleCodec.sortedAndMerged(result.cues))
                    detected = result.detectedLang
                    setJob(jobID) { j in
                        j.detectedLang = detected
                        j.doneChunks = 1
                        j.totalChunks = 1
                        j.cueCount = allCues.count
                        j.errorMessage = nil
                        j.progress = 0.72
                    }
                } else if job.sttProvider == .speechmatics {
                    let audioFile = dir.appendingPathComponent("chunks/audio-full.m4a")
                    guard FileManager.default.fileExists(atPath: audioFile.path) else {
                        throw AudioPipelineError.writerFailed("ملف الصوت المجمّع مفقود — أعد المهمة")
                    }
                    let key = Self.sttHasKey(.speechmatics) ?? ""
                    let result = try await STTService.speechmaticsTranscribe(
                        audioURL: audioFile,
                        language: job.sourceLang,
                        apiKey: key)
                    allCues = SubtitleCodec.normalize(SubtitleCodec.sortedAndMerged(result.cues))
                    detected = result.detectedLang
                    setJob(jobID) { j in
                        j.detectedLang = detected
                        j.doneChunks = 1
                        j.totalChunks = 1
                        j.cueCount = allCues.count
                        j.errorMessage = nil
                        j.progress = 0.72
                    }
                } else if job.sttProvider == .siliconflow {
                    // SiliconFlow SenseVoice - تقطيع لأجزاء متوازية
                    let chunksDir = dir.appendingPathComponent("chunks", isDirectory: true)
                    var pending: [AudioChunk] = []
                    var doneCount = 0
                    for c in audioChunks {
                        let f = dir.appendingPathComponent("stt-\(String(format: "%03d", c.index)).json")
                        if let data = try? Data(contentsOf: f),
                           let cached = try? JSONDecoder().decode([SubCue].self, from: data) {
                            allCues.append(contentsOf: cached)
                            doneCount += 1
                        } else {
                            pending.append(c)
                        }
                    }
                    setJob(jobID) { j in
                        j.doneChunks = doneCount
                        j.totalChunks = audioChunks.count
                    }
                    saveIndex()

                    let key = Self.sttHasKey(.siliconflow) ?? ""
                    let model = UserDefaults.standard.string(forKey: "stt.model") ?? "FunAudioLLM/SenseVoiceSmall"
                    let result = try await STTService.siliconFlowTranscribe(
                        chunks: pending,
                        chunksDir: chunksDir,
                        language: job.sourceLang,
                        apiKey: key,
                        model: model,
                        concurrency: sttConcurrency) { [weak self] _ in
                            Task { @MainActor in
                                self?.setJob(jobID) { j in
                                    j.doneChunks += 1
                                    let total = max(1, j.totalChunks)
                                    j.progress = 0.13 + 0.57 * Double(j.doneChunks) / Double(total)
                                }
                                self?.saveIndex()
                            }
                        } chunkResult: { idx, cues, lang in
                            if let data = try? JSONEncoder().encode(cues) {
                                try? data.write(to: dir.appendingPathComponent("stt-\(String(format: "%03d", idx)).json"), options: .atomic)
                            }
                            _ = lang
                        }
                    allCues.append(contentsOf: result.cues)
                    if detected == nil { detected = result.detectedLang }
                    allCues = SubtitleCodec.normalize(SubtitleCodec.sortedAndMerged(allCues))
                    setJob(jobID) { j in
                        j.detectedLang = detected
                        j.cueCount = allCues.count
                        j.progress = 0.70
                    }
                } else {
                    // Groq مع تخطّي الأجزاء المفرّغة سابقاً (استئناف)
                    let chunksDir = dir.appendingPathComponent("chunks", isDirectory: true)
                    var pending: [AudioChunk] = []
                    var doneCount = 0
                    for c in audioChunks {
                        let f = dir.appendingPathComponent("stt-\(String(format: "%03d", c.index)).json")
                        if let data = try? Data(contentsOf: f),
                           let cached = try? JSONDecoder().decode([SubCue].self, from: data) {
                            allCues.append(contentsOf: cached)
                            doneCount += 1
                        } else {
                            pending.append(c)
                        }
                    }
                    setJob(jobID) { j in
                        j.doneChunks = doneCount
                        j.totalChunks = audioChunks.count
                    }
                    saveIndex()

                    let key = Self.sttHasKey(.groq) ?? ""
                    let result = try await STTService.groqTranscribe(
                        chunks: pending,
                        chunksDir: chunksDir,
                        language: job.sourceLang,
                        apiKey: key,
                        concurrency: sttConcurrency) { [weak self] _ in
                            Task { @MainActor in
                                self?.setJob(jobID) { j in
                                    j.doneChunks += 1
                                    let total = max(1, j.totalChunks)
                                    j.progress = 0.13 + 0.57 * Double(j.doneChunks) / Double(total)
                                }
                                self?.saveIndex()
                            }
                        } chunkResult: { idx, cues, lang in
                            // حفظ فوري لكل جزء = استئناف من نفس النقطة
                            if let data = try? JSONEncoder().encode(cues) {
                                try? data.write(to: dir.appendingPathComponent("stt-\(String(format: "%03d", idx)).json"), options: .atomic)
                            }
                            _ = lang
                        }
                    allCues.append(contentsOf: result.cues)
                    if detected == nil { detected = result.detectedLang }
                    allCues = SubtitleCodec.normalize(SubtitleCodec.sortedAndMerged(allCues))
                    setJob(jobID) { j in
                        j.detectedLang = detected
                        j.cueCount = allCues.count
                        j.progress = 0.70
                    }
                }

                guard !allCues.isEmpty else {
                    throw APIError(status: 0, body: "لم يُكتشف أي كلام قابل للتفريغ في الفيديو.")
                }

                // حفظ الجُمل المدمجة (تُستخدم مباشرة عند إعادة الترجمة)
                if let data = try? JSONEncoder().encode(allCues) {
                    try? data.write(to: cuesURL, options: .atomic)
                }
            }

            // ——— المرحلة 3: الترجمة ———
            setJob(jobID) { $0.state = .translating }
            saveIndex()

            var cues = allCues
            let batches = TranslateService.makeBatches(cues: cues)
            setJob(jobID) { j in
                j.totalBatches = batches.count
            }
            saveIndex()

            // تحميل ما تمت ترجمته سابقاً
            var translationsByStart: [Int: [String]] = [:]
            for b in batches {
                let f = dir.appendingPathComponent("trans-\(b.startIndex).json")
                if let data = try? Data(contentsOf: f),
                   let cached = try? JSONDecoder().decode([String].self, from: data),
                   cached.count == b.cueIDs.count {
                    translationsByStart[b.startIndex] = cached
                }
            }

            // لكل مزود موديله المستقل؛ يمنع إرسال اسم موديل Gemini إلى SiliconFlow.
            let resolvedTranslator = TranslateService.resolved(provider: job.translator)
            let translatorModel: String
            switch resolvedTranslator {
            case .gemini:
                translatorModel = verifiedGeminiModel
                    ?? ModelSelection.selected(purpose: "translator", provider: .gemini,
                                               fallback: TranslateService.defaultGeminiModel)
            case .groqLLM:
                translatorModel = ModelSelection.selected(purpose: "translator", provider: .groq,
                                                          fallback: "openai/gpt-oss-120b")
            case .siliconflow:
                translatorModel = ModelSelection.selected(purpose: "translator", provider: .siliconflow,
                                                          fallback: "deepseek-ai/DeepSeek-V3.2")
            case .qwenMT:
                translatorModel = ModelSelection.selected(purpose: "translator", provider: .dashscope,
                                                          fallback: TranslateService.defaultQwenMTModel)
            case .deepL:
                translatorModel = "DeepL API"
            case .auto:
                translatorModel = ""
            }
            let translatorConfig = TranslateService.Config(provider: job.translator,
                                                          model: translatorModel,
                                                          temperature: 0.15,
                                                          maxOutputTokens: 4096)
            let pendingBatches = batches.filter { translationsByStart[$0.startIndex] == nil }
            var doneBatches = batches.count - pendingBatches.count
            setJob(jobID) { j in
                j.doneBatches = doneBatches
                let total = max(1, j.totalBatches)
                j.progress = 0.70 + 0.24 * Double(doneBatches) / Double(total)
            }

            var contextTail: [(String, String)] = []
            if let last = translationsByStart.keys.max(),
               let arr = translationsByStart[last],
               let b = batches.first(where: { $0.startIndex == last }) {
                let tail = min(3, arr.count)
                if tail > 0 {
                    contextTail = Array(zip(b.texts.suffix(tail), arr.suffix(tail).map { $0.isEmpty ? b.texts.last ?? "" : $0 }))
                }
            }

            // Gemini المجاني يحدّ الطلبات/الدقيقة وقد يضع الطلبين المتوازيين في طابور.
            // نرسل دفعة واحدة له كي تظهر أول نتيجة سريعاً بدل بقاء العداد 0/N؛ بقية المزودين
            // تبقى على نافذة من طلبين للاستفادة من سرعتها.
            let maxConcurrentBatches = resolvedTranslator == .gemini ? 1 : 2
            let translatorName = TranslateService.providerName(resolvedTranslator)
            var w = 0
            while w < pendingBatches.count {
                if Task.isCancelled { throw CancellationError() }
                let window = Array(pendingBatches[w..<min(w + maxConcurrentBatches, pendingBatches.count)])
                w += window.count
                let firstInFlight = doneBatches + 1
                let lastInFlight = min(doneBatches + window.count, batches.count)
                setJob(jobID) { j in
                    if firstInFlight == lastInFlight {
                        j.errorMessage = "\(translatorName): جارٍ إرسال وترجمة الدفعة \(firstInFlight) من \(batches.count)…"
                    } else {
                        j.errorMessage = "\(translatorName): جارٍ إرسال الدفعات \(firstInFlight)–\(lastInFlight) من \(batches.count)…"
                    }
                }
                saveIndex()
                try await withThrowingTaskGroup(of: (Int, [String]).self) { group in
                    for batch in window {
                        let tail = contextTail
                        let cfg = translatorConfig
                        group.addTask {
                            let out = try await TranslateService.translateBatch(
                                config: cfg,
                                batch: batch,
                                contextTail: tail,
                                source: job.sourceLang,
                                target: job.targetLang,
                                videoTitle: job.videoTitle)
                            return (batch.startIndex, out)
                        }
                    }
                    for try await (startIdx, translated) in group {
                        translationsByStart[startIdx] = translated
                        if let data = try? JSONEncoder().encode(translated) {
                            try? data.write(to: dir.appendingPathComponent("trans-\(startIdx).json"), options: .atomic)
                        }
                        if let arr = translationsByStart[startIdx],
                           let b = batches.first(where: { $0.startIndex == startIdx }), !arr.isEmpty {
                            contextTail = Array(zip(b.texts.suffix(1), [arr.last ?? ""]).map { ($0.0, $0.1.isEmpty ? $0.0 : $0.1) })
                        }
                        doneBatches += 1
                        let db = doneBatches
                        let totalB = max(1, batches.count)
                        setJob(jobID) { j in
                            j.doneBatches = db
                            j.progress = 0.70 + 0.24 * Double(db) / Double(totalB)
                            if db < totalB {
                                j.errorMessage = "\(translatorName): اكتملت الدفعة \(db) من \(totalB)…"
                            } else {
                                j.errorMessage = nil
                            }
                        }
                    }
                }
                saveIndex()
            }

            for b in batches {
                guard let arr = translationsByStart[b.startIndex] else { continue }
                for (offset, cueID) in b.cueIDs.enumerated() {
                    if let idx = cues.firstIndex(where: { $0.id == cueID }) {
                        let t = arr[offset]
                        cues[idx].translated = t.isEmpty ? nil : t
                    }
                }
            }

            // ——— المرحلة 4: الحفظ ———
            setJob(jobID) { $0.state = .saving; $0.progress = 0.95 }
            saveIndex()

            let subsDir = LibraryStore.documents.appendingPathComponent("Subtitles", isDirectory: true)
            let videoSubsDir = subsDir.appendingPathComponent(job.videoID.uuidString, isDirectory: true)
            try FileManager.default.createDirectory(at: videoSubsDir, withIntermediateDirectories: true)
            let lang = job.targetLang.rawValue
            let origURL = videoSubsDir.appendingPathComponent("orig.srt")
            let targetURL = videoSubsDir.appendingPathComponent("\(lang).srt")
            let biURL = videoSubsDir.appendingPathComponent("\(lang)-bilingual.srt")
            try SubtitleCodec.writeSRT(cues, translated: false, bilingual: false)
                .write(to: origURL, atomically: true, encoding: .utf8)
            try SubtitleCodec.writeSRT(cues, translated: true, bilingual: false)
                .write(to: targetURL, atomically: true, encoding: .utf8)
            try SubtitleCodec.writeSRT(cues, translated: false, bilingual: true)
                .write(to: biURL, atomically: true, encoding: .utf8)

            // ربط الترجمات بالفيديو في المكتبة
            var updated = video
            updated.subtitleFiles = [
                "orig": "Subtitles/\(job.videoID.uuidString)/orig.srt",
                "target": "Subtitles/\(job.videoID.uuidString)/\(lang).srt",
                "bilingual": "Subtitles/\(job.videoID.uuidString)/\(lang)-bilingual.srt"
            ]
            updated.subtitleTargetLang = lang
            library?.update(updated)

            // تنظيف ملفات الصوت الكبيرة — تبقى النصوص فقط
            AudioPipeline.cleanupAudio(in: dir)

            setJob(jobID) { j in
                j.state = .done
                j.progress = 1
                j.errorMessage = nil
                j.finishedAt = Date()
                j.doneChunks = j.totalChunks > 0 ? j.totalChunks : 1
                j.doneBatches = j.totalBatches
            }
            saveIndex()
        } catch is CancellationError {
            setJob(jobID) { j in
                j.state = .paused
                j.errorMessage = "أُوقفت المهمة — يمكنك استئنافها من نفس النقطة."
            }
            saveIndex()
        } catch {
            setJob(jobID) { j in
                j.state = .failed
                j.errorMessage = error.localizedDescription
                j.finishedAt = Date()
            }
            saveIndex()
        }
    }

    // MARK: إعدادات

    private var sttConcurrency: Int {
        let v = UserDefaults.standard.integer(forKey: "stt.concurrency")
        return max(1, min(4, v == 0 ? 3 : v))
    }

    // MARK: مساعدات عرض

    static func detectedLangNameAR(_ code: String?) -> String? {
        guard let c = code?.lowercased() else { return nil }
        let map: [String: String] = [
            "en": "الإنجليزية", "english": "الإنجليزية",
            "hi": "الهندية", "hindi": "الهندية",
            "ar": "العربية", "arabic": "العربية",
            "ur": "الأردية", "fr": "الفرنسية", "french": "الفرنسية",
            "tr": "التركية", "de": "الألمانية", "es": "الإسبانية",
            "ru": "الروسية", "fa": "الفارسية", "id": "الإندونيسية",
            "global": "متعددة اللغات"
        ]
        return map[c] ?? c
    }

    nonisolated static func subtitleURLs(for video: SavedVideo) -> (orig: URL?, target: URL?, bilingual: URL?) {
        guard let files = video.subtitleFiles else { return (nil, nil, nil) }
        func url(_ key: String) -> URL? {
            guard let rel = files[key] else { return nil }
            let u = LibraryStore.documents.appendingPathComponent(rel)
            return FileManager.default.fileExists(atPath: u.path) ? u : nil
        }
        return (url("orig"), url("target"), url("bilingual"))
    }
}
