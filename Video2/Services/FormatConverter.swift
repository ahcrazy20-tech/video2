import Foundation
import AVFoundation
import Combine

// MARK: - صيغ الإخراج المدعومة

enum OutputFormat: String, CaseIterable, Identifiable, Codable {
    case mp4, mov, m4v

    var id: String { rawValue }

    var titleAR: String {
        switch self {
        case .mp4: return "MP4"
        case .mov: return "MOV"
        case .m4v: return "M4V"
        }
    }

    var fileExtension: String { rawValue }

    var avFileType: AVFileType {
        switch self {
        case .mp4: return .mp4
        case .mov: return .mov
        case .m4v: return .mp4
        }
    }
}

// MARK: - حالة مهمة التحويل

enum ConversionPhase: String, Codable {
    case queued
    case converting
    case done
    case failed
    case cancelled

    var titleAR: String {
        switch self {
        case .queued: return "في الانتظار"
        case .converting: return "جارٍ التحويل…"
        case .done: return "اكتمل التحويل"
        case .failed: return "فشل التحويل"
        case .cancelled: return "أُلغي"
        }
    }

    var isBusy: Bool {
        self == .queued || self == .converting
    }
}

// MARK: - مهمة تحويل

struct ConversionJob: Identifiable, Codable {
    var id: UUID
    var videoID: UUID
    var videoTitle: String
    var sourceKind: MediaKind
    var outputFormat: OutputFormat
    var phase: ConversionPhase
    var progress: Double
    var errorMessage: String?
    var outputRelativePath: String?
    var outputSize: Int64?
    var createdAt: Date
    var finishedAt: Date?
}

// MARK: - أخطاء التحويل

enum ConversionError: LocalizedError {
    case fileNotFound
    case noExportSession
    case exportFailed(String)
    case presetNotSupported

    var errorDescription: String? {
        switch self {
        case .fileNotFound:
            return "ملف الفيديو غير موجود على الجهاز."
        case .noExportSession:
            return "تعذر إنشاء جلسة التحويل لهذه الصيغة."
        case .exportFailed(let msg):
            return "فشل التحويل: \(msg)"
        case .presetNotSupported:
            return "هذه الصيغة غير مدعومة للتحويل على هذا الجهاز."
        }
    }
}

// MARK: - مدير التحويلات

@MainActor
final class FormatConverter: ObservableObject {

    @Published var jobs: [ConversionJob] = []

    private weak var library: LibraryStore?
    private var running = false
    private var currentTask: Task<Void, Never>? = nil

    nonisolated static let root = LibraryStore.documents.appendingPathComponent("Conversions", isDirectory: true)

    func attach(library: LibraryStore) {
        self.library = library
    }

    func load() {
        try? FileManager.default.createDirectory(at: Self.root, withIntermediateDirectories: true)
        let url = Self.root.appendingPathComponent("jobs.json")
        guard let data = try? Data(contentsOf: url),
              let list = try? JSONDecoder().decode([ConversionJob].self, from: data) else { return }
        jobs = list.map { job in
            var j = job
            if j.phase.isBusy {
                j.phase = .cancelled
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

    // MARK: بدء تحويل

    func convert(video: SavedVideo, to format: OutputFormat) {
        let job = ConversionJob(
            id: UUID(),
            videoID: video.id,
            videoTitle: video.title,
            sourceKind: video.kind,
            outputFormat: format,
            phase: .queued,
            progress: 0,
            errorMessage: nil,
            outputRelativePath: nil,
            outputSize: nil,
            createdAt: Date(),
            finishedAt: nil
        )
        jobs.insert(job, at: 0)
        saveIndex()
        pump()
    }

    func cancel(_ jobID: UUID) {
        guard let i = jobs.firstIndex(where: { $0.id == jobID }) else { return }
        if jobs[i].phase.isBusy {
            currentTask?.cancel()
            currentTask = nil
            running = false
        }
        jobs[i].phase = .cancelled
        saveIndex()
        pump()
    }

    func delete(_ jobID: UUID) {
        guard let i = jobs.firstIndex(where: { $0.id == jobID }) else { return }
        let job = jobs[i]
        if job.phase.isBusy { cancel(jobID) }
        if let rel = job.outputRelativePath {
            try? FileManager.default.removeItem(at: LibraryStore.documents.appendingPathComponent(rel))
        }
        jobs.removeAll { $0.id == jobID }
        saveIndex()
    }

    /// استبدال الفيديو الأصلي بالنسخة المحوّلة في المكتبة
    func replaceOriginal(_ jobID: UUID) {
        guard let i = jobs.firstIndex(where: { $0.id == jobID }) else { return }
        let job = jobs[i]
        guard job.phase == .done,
              let outRel = job.outputRelativePath,
              var video = library?.videos.first(where: { $0.id == job.videoID }) else { return }

        let outURL = LibraryStore.documents.appendingPathComponent(outRel)
        guard FileManager.default.fileExists(atPath: outURL.path) else { return }

        // نقل الملف الجديد لمكان الفيديو الحالي (قبل حذف القديم)
        let newKind = MediaKind.infer(url: outRel, mime: nil)
        let destURL: URL
        if video.kind == .hls {
            // HLS: نستبدل الفولدر كامل بملف واحد
            destURL = video.localURL.deletingLastPathComponent()
                .appendingPathComponent("\(video.id.uuidString).\(job.outputFormat.fileExtension)")
        } else {
            destURL = video.localURL.deletingPathExtension()
                .appendingPathExtension(job.outputFormat.fileExtension)
        }
        do {
            try FileManager.default.moveItem(at: outURL, to: destURL)
        } catch {
            try? FileManager.default.copyItem(at: outURL, to: destURL)
            try? FileManager.default.removeItem(at: outURL)
        }

        // حذف الملف القديم (أو المجلد بالكامل إذا كان HLS) بعد نجاح النقل
        if video.kind == .hls {
            let hlsFolder = video.localURL.deletingLastPathComponent()
            if hlsFolder != destURL.deletingLastPathComponent() {
                try? FileManager.default.removeItem(at: hlsFolder)
            }
        } else {
            if video.localURL != destURL {
                try? FileManager.default.removeItem(at: video.localURL)
            }
        }

        let bytes = (try? destURL.resourceValues(forKeys: Set([URLResourceKey.fileSizeKey])).fileSize).map { Int64($0) } ?? 0
        video.localRelativePath = destURL.v2RelativePath(from: LibraryStore.documents)
        video.kind = newKind
        video.fileSize = bytes
        library?.update(video)

        // تنظيف
        try? FileManager.default.removeItem(at: Self.root.appendingPathComponent(job.id.uuidString, isDirectory: true))
        jobs.remove(at: i)
        saveIndex()
    }

    // MARK: الطابور

    private func pump() {
        guard !running else { return }
        guard let idx = jobs.firstIndex(where: { $0.phase == .queued }) else { return }
        running = true
        let jobID = jobs[idx].id
        let task = Task { await runConversion(jobID: jobID) }
        currentTask = task
    }

    // MARK: تنفيذ التحويل

    private func runConversion(jobID: UUID) async {
        defer {
            running = false
            currentTask = nil
            pump()
        }

        guard let i = jobs.firstIndex(where: { $0.id == jobID }) else { return }
        let job = jobs[i]

        guard let video = library?.videos.first(where: { $0.id == job.videoID }) else {
            jobs[i].phase = .failed
            jobs[i].errorMessage = "الفيديو غير موجود في المكتبة."
            jobs[i].finishedAt = Date()
            saveIndex()
            return
        }

        guard FileManager.default.fileExists(atPath: video.localURL.path) else {
            jobs[i].phase = .failed
            jobs[i].errorMessage = ConversionError.fileNotFound.localizedDescription
            jobs[i].finishedAt = Date()
            saveIndex()
            return
        }

        // HLS يحتاج معالجة خاصة — تحويل من ملف محلي m3u8
        var sourceURL = video.localURL
        var tempHLSFile: URL? = nil

        if video.kind == .hls {
            // لا ندمج أجزاء HLS يدوياً: هذا يكسر الـ timestamps والصوت و EXT-X-MAP.
            // AVFoundation يفهم الـ playlist المحلي ويختار الـ audio/video renditions
            // ويحافظ على التزامن. نستخدم المسار القديم فقط كـ fallback للـ playlists
            // التي لا يستطيع النظام فتحها.
            jobs[i].phase = .converting
            jobs[i].progress = 0.05
            saveIndex()
            sourceURL = video.localURL
        }

        var asset = AVURLAsset(url: sourceURL)
        if video.kind == .hls {
            do {
                let playable = try await asset.load(.isPlayable)
                guard playable else { throw ConversionError.exportFailed("قائمة HLS غير قابلة للتشغيل") }
            } catch {
                do {
                    tempHLSFile = try await AudioPipeline.exportHLSToTempMP4(video.localURL)
                    sourceURL = tempHLSFile!
                    asset = AVURLAsset(url: sourceURL)
                } catch {
                    jobs[i].phase = .failed
                    jobs[i].errorMessage = error.localizedDescription
                    jobs[i].finishedAt = Date()
                    saveIndex()
                    return
                }
            }
        }

        // اختيار أفضل preset متاح
        let compatiblePresets = AVAssetExportSession.exportPresets(compatibleWith: asset)
        let preferredPresets = [
            AVAssetExportPresetPassthrough,
            AVAssetExportPresetHighestQuality,
            AVAssetExportPresetMediumQuality
        ]
        let preset = preferredPresets.first { compatiblePresets.contains($0) } ?? AVAssetExportPresetHighestQuality

        guard let session = AVAssetExportSession(asset: asset, presetName: preset) else {
            jobs[i].phase = .failed
            jobs[i].errorMessage = ConversionError.noExportSession.localizedDescription
            jobs[i].finishedAt = Date()
            saveIndex()
            if let tempHLSFile { try? FileManager.default.removeItem(at: tempHLSFile) }
            return
        }

        let supportedTypes = session.supportedFileTypes
        var fileType = job.outputFormat.avFileType
        if !supportedTypes.contains(fileType) {
            // محاولة بديلة: إذا كان MP4 غير مدعوم، جرّب MOV والعكس
            let altType: AVFileType = fileType == .mov ? .mp4 : .mov
            guard supportedTypes.contains(altType) else {
                jobs[i].phase = .failed
                jobs[i].errorMessage = ConversionError.presetNotSupported.localizedDescription
                jobs[i].finishedAt = Date()
                saveIndex()
                if let tempHLSFile { try? FileManager.default.removeItem(at: tempHLSFile) }
                return
            }
            fileType = altType
        }

        // ملف الإخراج
        let jobDir = Self.root.appendingPathComponent(job.id.uuidString, isDirectory: true)
        try? FileManager.default.createDirectory(at: jobDir, withIntermediateDirectories: true)
        let outputURL = jobDir.appendingPathComponent("converted.\(job.outputFormat.fileExtension)")
        try? FileManager.default.removeItem(at: outputURL)

        session.outputURL = outputURL
        session.outputFileType = fileType
        session.shouldOptimizeForNetworkUse = true

        // مراقبة التقدم
        let progressTask = Task { [weak self] in
            while !Task.isCancelled {
                guard let self else { return }
                let p = Double(session.progress)
                await MainActor.run {
                    if let idx = self.jobs.firstIndex(where: { $0.id == jobID }) {
                        self.jobs[idx].progress = max(0.05, min(0.98, p))
                    }
                }
                if session.status != .exporting && session.status != .waiting { break }
                try? await Task.sleep(nanoseconds: 500_000_000)
            }
        }

        await withCheckedContinuation { (cont: CheckedContinuation<Void, Never>) in
            session.exportAsynchronously {
                cont.resume()
            }
        }

        progressTask.cancel()

        // تنظيف ملف HLS المؤقت
        if let tempHLSFile { try? FileManager.default.removeItem(at: tempHLSFile) }

        if session.status == .completed,
           FileManager.default.fileExists(atPath: outputURL.path) {
            let bytes = (try? outputURL.resourceValues(forKeys: Set([URLResourceKey.fileSizeKey])).fileSize).map { Int64($0) } ?? 0
            let relPath = outputURL.v2RelativePath(from: LibraryStore.documents)

            guard let idx = jobs.firstIndex(where: { $0.id == jobID }) else { return }
            jobs[idx].phase = .done
            jobs[idx].progress = 1
            jobs[idx].outputRelativePath = relPath
            jobs[idx].outputSize = bytes
            jobs[idx].finishedAt = Date()
        } else {
            guard let idx = jobs.firstIndex(where: { $0.id == jobID }) else { return }
            if Task.isCancelled {
                jobs[idx].phase = .cancelled
            } else {
                jobs[idx].phase = .failed
                jobs[idx].errorMessage = session.error?.localizedDescription ?? "فشل غير معروف"
            }
            jobs[idx].finishedAt = Date()
        }
        saveIndex()
    }
}

// MARK: - Helpers

extension URL {
    func v2RelativePath(from base: URL) -> String {
        let basePath = base.standardizedFileURL.path
        let selfPath = self.standardizedFileURL.path
        if selfPath.hasPrefix(basePath) {
            var rel = String(selfPath.dropFirst(basePath.count))
            if rel.hasPrefix("/") { rel.removeFirst() }
            return rel
        }
        return selfPath
    }
}
