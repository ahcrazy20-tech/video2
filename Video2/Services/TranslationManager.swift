import Foundation
import Combine
import UIKit

/// مدير مهام الترجمة: طابور تسلسلي، حفظ واستئناف، تتبع تقدم لكل مرحلة (استخراج -> تفريغ -> مراجعة وتدقيق نصوص -> ترجمة -> حفظ).
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
            // نحافظ على ترتيب المزودات القديمة؛ المزودان الجديدان يأتيان بعد
            // الخيارات الموجودة حتى لا يتغير سلوك Auto للمستخدم الحالي.
            if KeychainStore.has("assemblyai") { return .assemblyai }
            if KeychainStore.has("sttai") { return .sttai }
            if KeychainStore.has("speechmatics") { return .speechmatics }
            if KeychainStore.has("groq") { return .groq }
            if KeychainStore.has("deepgram") { return .deepgram }
            if KeychainStore.has("azure") { return .azure }
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
                  refiner: SubtitleRefinerKind = .auto,
                  translator: TranslatorKind) {

        guard validationMessage(for: video, target: target) == nil else { return }

        let resolvedSTT = Self.resolvedSTT(stt)
        guard Self.sttHasKey(resolvedSTT) != nil else { return }

        let resolvedRefiner = SubtitleRefineService.resolved(provider: refiner)
        let resolvedTranslator = TranslateService.resolved(provider: translator)

        let job = TranslationJob(id: UUID(),
                                 videoID: video.id,
                                 videoTitle: video.title,
                                 isHLS: video.kind == .hls,
                                 sourceLang: source,
                                 targetLang: target,
                                 sttProvider: resolvedSTT,
                                 refinerProvider: resolvedRefiner,
                                 translator: resolvedTranslator,
                                 state: .queued,
                                 progress: 0,
                                 totalChunks: 0,
                                 doneChunks: 0,
                                 totalRefineBatches: 0,
                                 doneRefineBatches: 0,
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
        let rawCuesURL = dir.appendingPathComponent("cues-raw.json")
        let cuesURL = dir.appendingPathComponent("cues.json")
        var allCues: [SubCue] = []
        var detected: String? = job.detectedLang
        var duration: Double = 0
        var audioChunks: [AudioChunk] = []

        if let data = try? Data(contentsOf: cuesURL),
           let cached = try? JSONDecoder().decode([SubCue].self, from: data), !cached.isEmpty {
            allCues = cached
        } else if let rawData = try? Data(contentsOf: rawCuesURL),
                  let rawCached = try? JSONDecoder().decode([SubCue].self, from: rawData), !rawCached.isEmpty {
            allCues = rawCached
        }

        do {
            // نتأكد من Gemini قبل أي استخراج HLS طويل. بعض مفاتيح المشاريع
            // تحتفظ باسم موديل قديم في الإعدادات فيظهر 404 بعد دقائق من التحويل.
            var verifiedGeminiModel: String?
            if TranslateService.resolved(provider: job.translator) == .gemini ||
                SubtitleRefineService.resolved(provider: job.refinerProvider) == .gemini {
                setJob(jobID) { j in
                    j.state = .preparing
                    j.progress = min(max(j.progress, 0.01), 0.02)
                    j.errorMessage = nil
                }
                saveIndex()
                do {
                    verifiedGeminiModel = try await TranslateService.preflightGeminiModel()
                } catch where TranslateService.isQuotaOrLimitError(error) {
                    setJob(jobID) { j in
                        j.errorMessage = "Gemini وصل حدّه المجاني حالياً — سيُستخدم مزود بديل تلقائياً."
                    }
                    saveIndex()
                }
            }

            // ——— المرحلة 1: الصوت (تُتخطى إذا كانت الجُمل جاهزة) ———
            if allCues.isEmpty {
                setJob(jobID) { $0.state = .extracting; $0.progress = 0.02 }
                saveIndex()

                let singleFile = job.sttProvider == .assemblyai || job.sttProvider == .sttai || job.sttProvider == .speechmatics
                // Azure Speech's short-audio REST endpoint accepts clips below one
                // minute, so create 50-second chunks with a little margin. Deepgram
                // keeps the normal long chunks and therefore preserves its throughput.
                let chunkDuration = job.sttProvider == .azure ? 50.0 : AudioPipeline.chunkSeconds
                let (chunks, dur) = try await AudioPipeline.extractChunks(
                    from: video.localURL,
                    into: dir,
                    singleFile: singleFile,
                    chunkDuration: chunkDuration,
                    isHLS: video.kind == .hls || video.localURL.pathExtension.lowercased() == "m3u8") { [weak self] p in
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
                    j.progress = 0.12
                }
                saveIndex()
            } else {
                audioChunks = (try? JSONDecoder().decode([AudioChunk].self,
                                                         from: Data(contentsOf: dir.appendingPathComponent("chunks.json")))) ?? []
                setJob(jobID) { j in
                    j.cueCount = allCues.count
                    j.progress = 0.60
                }
                saveIndex()
            }

            // ——— المرحلة 2: التفريغ الصوتي ———
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
                        j.progress = 0.60
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
                        j.progress = 0.60
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
                        j.progress = 0.60
                    }
                } else if job.sttProvider == .deepgram {
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

                    let key = Self.sttHasKey(.deepgram) ?? ""
                    let result = try await STTService.deepgramTranscribe(
                        chunks: pending,
                        chunksDir: chunksDir,
                        language: job.sourceLang,
                        apiKey: key,
                        concurrency: sttConcurrency) { [weak self] _ in
                            Task { @MainActor in
                                self?.setJob(jobID) { j in
                                    j.doneChunks += 1
                                    let total = max(1, j.totalChunks)
                                    j.progress = 0.12 + 0.48 * Double(j.doneChunks) / Double(total)
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
                        j.progress = 0.60
                    }
                } else if job.sttProvider == .azure {
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

                    let key = Self.sttHasKey(.azure) ?? ""
                    let result = try await STTService.azureTranscribe(
                        chunks: pending,
                        chunksDir: chunksDir,
                        language: job.sourceLang,
                        apiKey: key,
                        concurrency: min(sttConcurrency, 2)) { [weak self] _ in
                            Task { @MainActor in
                                self?.setJob(jobID) { j in
                                    j.doneChunks += 1
                                    let total = max(1, j.totalChunks)
                                    j.progress = 0.12 + 0.48 * Double(j.doneChunks) / Double(total)
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
                        j.progress = 0.60
                    }
                } else if job.sttProvider == .siliconflow {
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
                                    j.progress = 0.12 + 0.48 * Double(j.doneChunks) / Double(total)
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
                        j.progress = 0.60
                    }
                } else {
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
                                    j.progress = 0.12 + 0.48 * Double(j.doneChunks) / Double(total)
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
                        j.progress = 0.60
                    }
                }

                guard !allCues.isEmpty else {
                    throw APIError(status: 0, body: "لم يُكتشف أي كلام قابل للتفريغ في الفيديو.")
                }

                // حفظ النص الخام الأصلي للتفريغ
                if let rawData = try? JSONEncoder().encode(allCues) {
                    try? rawData.write(to: rawCuesURL, options: .atomic)
                }
            }

            // ——— المرحلة 3: مراجعة وتدقيق نصوص التفريغ بالذكاء الاصطناعي ———
            let resolvedRefiner = SubtitleRefineService.resolved(provider: job.refinerProvider)
            if resolvedRefiner != .off {
                setJob(jobID) { $0.state = .refining }
                saveIndex()

                let refineBatches = SubtitleRefineService.makeBatches(cues: allCues)
                setJob(jobID) { j in
                    j.totalRefineBatches = refineBatches.count
                }
                saveIndex()

                var refinedByStart: [Int: [String]] = [:]
                for b in refineBatches {
                    let f = dir.appendingPathComponent("refine-\(b.startIndex).json")
                    if let data = try? Data(contentsOf: f),
                       let cached = try? JSONDecoder().decode([String].self, from: data),
                       cached.count == b.cueIDs.count {
                        refinedByStart[b.startIndex] = cached
                    }
                }

                let pendingRefine = refineBatches.filter { refinedByStart[$0.startIndex] == nil }
                var doneRefine = refineBatches.count - pendingRefine.count
                setJob(jobID) { j in
                    j.doneRefineBatches = doneRefine
                    let total = max(1, j.totalRefineBatches)
                    j.progress = 0.60 + 0.15 * Double(doneRefine) / Double(total)
                }

                let refineChain = SubtitleRefineService.failoverChain(from: job.refinerProvider)
                if !refineChain.isEmpty && refineChain != [.off] {
                    var rChainIndex = 0
                    var activeRefineConfig = SubtitleRefineService.Config(
                        provider: refineChain[0],
                        model: SubtitleRefineService.modelSelection(for: refineChain[0],
                                                                    geminiOverride: verifiedGeminiModel),
                        temperature: 0.1,
                        maxOutputTokens: 4096
                    )
                    var currentRefineProviderName = SubtitleRefineService.providerName(refineChain[0])

                    func runRefineWindow(_ window: [SubtitleRefineService.Batch],
                                         tail: [String],
                                         source: SubLang,
                                         title: String) async throws {
                        while true {
                            do {
                                let cfg = activeRefineConfig
                                try await withThrowingTaskGroup(of: (Int, [String]).self) { group in
                                    for batch in window {
                                        group.addTask {
                                            let out = try await SubtitleRefineService.refineBatch(
                                                config: cfg,
                                                batch: batch,
                                                contextTail: tail,
                                                source: source,
                                                videoTitle: title)
                                            return (batch.startIndex, out)
                                        }
                                    }
                                    for try await (startIdx, refined) in group {
                                        refinedByStart[startIdx] = refined
                                        if let data = try? JSONEncoder().encode(refined) {
                                            try? data.write(to: dir.appendingPathComponent("refine-\(startIdx).json"), options: .atomic)
                                        }
                                        doneRefine += 1
                                        let dr = doneRefine
                                        let totalR = max(1, refineBatches.count)
                                        let label = currentRefineProviderName
                                        setJob(jobID) { j in
                                            j.doneRefineBatches = dr
                                            j.progress = 0.60 + 0.15 * Double(dr) / Double(totalR)
                                            if dr < totalR {
                                                j.errorMessage = "\(label): اكتملت مراجعة الدفعة \(dr) من \(totalR)…"
                                            } else {
                                                j.errorMessage = nil
                                            }
                                        }
                                    }
                                }
                                return
                            } catch where Task.isCancelled {
                                throw CancellationError()
                            } catch {
                                guard SubtitleRefineService.isQuotaOrLimitError(error),
                                      rChainIndex + 1 < refineChain.count else {
                                    // إذا فشل المزود ولا يوجد بديل، نكمل بالنص الأصلي دون إسقاط المهمة
                                    break
                                }
                                let failed = SubtitleRefineService.providerName(refineChain[rChainIndex])
                                rChainIndex += 1
                                let next = refineChain[rChainIndex]
                                activeRefineConfig = SubtitleRefineService.Config(
                                    provider: next,
                                    model: SubtitleRefineService.modelSelection(for: next),
                                    temperature: 0.1,
                                    maxOutputTokens: 4096)
                                currentRefineProviderName = SubtitleRefineService.providerName(next)
                                let note = "\(failed) وصل حدّه — تحوّل تلقائي إلى \(currentRefineProviderName) لمراجعة النصوص…"
                                setJob(jobID) { j in j.errorMessage = note }
                                saveIndex()
                                try? await Task.sleep(nanoseconds: 3_000_000_000)
                            }
                        }
                    }

                    var rw = 0
                    var refineContextTail: [String] = []
                    while rw < pendingRefine.count {
                        if Task.isCancelled { throw CancellationError() }
                        let span = min(rw + 2, pendingRefine.count)
                        let window = Array(pendingRefine[rw..<span])
                        rw += window.count
                        let firstInFlight = doneRefine + 1
                        let lastInFlight = min(doneRefine + window.count, refineBatches.count)
                        let label = currentRefineProviderName
                        setJob(jobID) { j in
                            if firstInFlight == lastInFlight {
                                j.errorMessage = "\(label): جارٍ مراجعة وتدقيق الدفعة \(firstInFlight) من \(refineBatches.count)…"
                            } else {
                                j.errorMessage = "\(label): جارٍ مراجعة الدفعات \(firstInFlight)–\(lastInFlight) من \(refineBatches.count)…"
                            }
                        }
                        saveIndex()
                        try await runRefineWindow(window,
                                                  tail: refineContextTail,
                                                  source: job.sourceLang,
                                                  title: job.videoTitle)
                        if let lastStart = window.map(\.startIndex).max(),
                           let arr = refinedByStart[lastStart], let lastLine = arr.last, !lastLine.isEmpty {
                            refineContextTail.append(lastLine)
                        }
                        saveIndex()
                    }

                    // تطبيق النصوص المراجعة على مصفوفة الجمل
                    for b in refineBatches {
                        guard let arr = refinedByStart[b.startIndex] else { continue }
                        for (offset, cueID) in b.cueIDs.enumerated() {
                            if let idx = allCues.firstIndex(where: { $0.id == cueID }) {
                                let t = arr[offset].trimmingCharacters(in: .whitespacesAndNewlines)
                                if !t.isEmpty {
                                    allCues[idx].text = t
                                }
                            }
                        }
                    }
                }

                // حفظ الجمل بعد المراجعة
                if let data = try? JSONEncoder().encode(allCues) {
                    try? data.write(to: cuesURL, options: .atomic)
                }
            } else {
                // حفظ الجمل مباشرة بدون مراجعة
                if let data = try? JSONEncoder().encode(allCues) {
                    try? data.write(to: cuesURL, options: .atomic)
                }
            }

            // ——— المرحلة 4: الترجمة النصية السياقية ———
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

            let pendingBatches = batches.filter { translationsByStart[$0.startIndex] == nil }
            var doneBatches = batches.count - pendingBatches.count
            setJob(jobID) { j in
                j.doneBatches = doneBatches
                let total = max(1, j.totalBatches)
                j.progress = 0.75 + 0.20 * Double(doneBatches) / Double(total)
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

            let chain = TranslateService.failoverChain(from: job.translator)
            guard !chain.isEmpty else {
                throw APIError(status: 401,
                               body: "لا يوجد مزود ترجمة بمفتاح مُدخل. أدخل مفتاحاً واحداً على الأقل من الإعدادات (Groq أو Gemini أو OpenRouter أو Cerebras أو SambaNova).")
            }
            var chainIndex = 0
            var activeConfig = TranslateService.Config(
                provider: chain[0],
                model: TranslateService.modelSelection(for: chain[0],
                                                       geminiOverride: verifiedGeminiModel),
                temperature: 0.15,
                maxOutputTokens: 4096)
            let chainCount = chain.count
            var currentProviderName = TranslateService.providerName(chain[0])
            var maxConcurrentBatches = chain[0] == .gemini ? 1 : 2

            func runWindow(_ window: [TranslateService.Batch],
                           tail: [(String, String)],
                           source: SubLang,
                           target: SubLang,
                           title: String) async throws {
                while true {
                    do {
                        let cfg = activeConfig
                        try await withThrowingTaskGroup(of: (Int, [String]).self) { group in
                            for batch in window {
                                group.addTask {
                                    let out = try await TranslateService.translateBatch(
                                        config: cfg,
                                        batch: batch,
                                        contextTail: tail,
                                        source: source,
                                        target: target,
                                        videoTitle: title)
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
                                    contextTail = Array(zip(b.texts.suffix(1), arr.suffix(1).map { $0.isEmpty ? b.texts.last ?? "" : $0 }))
                                }
                                doneBatches += 1
                                let db = doneBatches
                                let totalB = max(1, batches.count)
                                let label = currentProviderName
                                setJob(jobID) { j in
                                    j.doneBatches = db
                                    j.progress = 0.75 + 0.20 * Double(db) / Double(totalB)
                                    if db < totalB {
                                        j.errorMessage = "\(label): اكتملت الدفعة \(db) من \(totalB)…"
                                    } else {
                                        j.errorMessage = nil
                                    }
                                }
                            }
                        }
                        return
                    } catch where Task.isCancelled {
                        throw CancellationError()
                    } catch {
                        guard TranslateService.isQuotaOrLimitError(error),
                              chainIndex + 1 < chainCount else { throw error }
                        let failed = TranslateService.providerName(chain[chainIndex])
                        chainIndex += 1
                        let next = chain[chainIndex]
                        activeConfig = TranslateService.Config(
                            provider: next,
                            model: TranslateService.modelSelection(for: next),
                            temperature: 0.15,
                            maxOutputTokens: 4096)
                        currentProviderName = TranslateService.providerName(next)
                        maxConcurrentBatches = next == .gemini ? 1 : 2
                        let note = "\(failed) وصل حدّه المجاني — تحوّل تلقائي إلى \(currentProviderName) (\(chainIndex + 1)/\(chainCount)) والمتابعة من نفس النقطة…"
                        setJob(jobID) { j in j.errorMessage = note }
                        saveIndex()
                        try? await Task.sleep(nanoseconds: 5_000_000_000)
                    }
                }
            }

            var w = 0
            while w < pendingBatches.count {
                if Task.isCancelled { throw CancellationError() }
                let span = min(w + maxConcurrentBatches, pendingBatches.count)
                let window = Array(pendingBatches[w..<span])
                w += window.count
                let firstInFlight = doneBatches + 1
                let lastInFlight = min(doneBatches + window.count, batches.count)
                let label = currentProviderName
                setJob(jobID) { j in
                    if firstInFlight == lastInFlight {
                        j.errorMessage = "\(label): جارٍ إرسال وترجمة الدفعة \(firstInFlight) من \(batches.count)…"
                    } else {
                        j.errorMessage = "\(label): جارٍ إرسال الدفعات \(firstInFlight)–\(lastInFlight) من \(batches.count)…"
                    }
                }
                saveIndex()
                try await runWindow(window,
                                    tail: contextTail,
                                    source: job.sourceLang,
                                    target: job.targetLang,
                                    title: job.videoTitle)
                saveIndex()
            }
            if job.translator != chain[chainIndex] {
                setJob(jobID) { j in j.translator = chain[chainIndex] }
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

            // ——— المرحلة 5: الحفظ ———
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

    // MARK: حفظ ترجمات معدلة يدوياً

    func saveEditedSubtitles(video: SavedVideo, cues: [SubCue]) {
        let subsDir = LibraryStore.documents.appendingPathComponent("Subtitles", isDirectory: true)
        let videoSubsDir = subsDir.appendingPathComponent(video.id.uuidString, isDirectory: true)
        try? FileManager.default.createDirectory(at: videoSubsDir, withIntermediateDirectories: true)
        let lang = video.subtitleTargetLang ?? "ar"
        let origURL = videoSubsDir.appendingPathComponent("orig.srt")
        let targetURL = videoSubsDir.appendingPathComponent("\(lang).srt")
        let biURL = videoSubsDir.appendingPathComponent("\(lang)-bilingual.srt")

        try? SubtitleCodec.writeSRT(cues, translated: false, bilingual: false)
            .write(to: origURL, atomically: true, encoding: .utf8)
        try? SubtitleCodec.writeSRT(cues, translated: true, bilingual: false)
            .write(to: targetURL, atomically: true, encoding: .utf8)
        try? SubtitleCodec.writeSRT(cues, translated: false, bilingual: true)
            .write(to: biURL, atomically: true, encoding: .utf8)

        var updated = video
        updated.subtitleFiles = [
            "orig": "Subtitles/\(video.id.uuidString)/orig.srt",
            "target": "Subtitles/\(video.id.uuidString)/\(lang).srt",
            "bilingual": "Subtitles/\(video.id.uuidString)/\(lang)-bilingual.srt"
        ]
        library?.update(updated)
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
