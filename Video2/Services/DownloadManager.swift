import Foundation
import Combine
import AVFoundation

@MainActor
final class DownloadManager: ObservableObject {
    @Published var jobs: [DownloadJob] = []
    private weak var library: LibraryStore?
    private let hls = HLSDownloader()
    private var running = false

    func attach(library: LibraryStore) {
        self.library = library
    }

    func enqueue(_ media: DetectedMedia) {
        if media.drm.isProtected {
            jobs.insert(DownloadJob(id: UUID(), media: media, state: .blockedDRM, progress: 0, bytesWritten: 0, errorMessage: media.drm.messageAR, createdAt: Date()), at: 0)
            return
        }
        jobs.insert(DownloadJob(id: UUID(), media: media, state: .queued, progress: 0, bytesWritten: 0, errorMessage: nil, createdAt: Date()), at: 0)
        pump()
    }

    func enqueueManual(urlString: String, title: String, page: String?) {
        let kind = MediaKind.infer(url: urlString.lowercased(), mime: nil)
        let media = DetectedMedia(url: urlString, title: title.isEmpty ? "رابط يدوي" : title, kind: kind, mime: nil, qualityLabel: nil, drm: .none, pageURL: page, extractionMethod: "manual-url")
        enqueue(media)
    }

    private func pump() {
        guard !running else { return }
        guard let idx = jobs.firstIndex(where: { $0.state == .queued }) else { return }
        running = true
        var job = jobs[idx]
        job.state = .running
        jobs[idx] = job
        Task { await run(jobID: job.id) }
    }

    private func run(jobID: UUID) async {
        guard let idx = jobs.firstIndex(where: { $0.id == jobID }) else { running = false; pump(); return }
        let job = jobs[idx]
        do {
            let saved = try await perform(job)
            if let i = jobs.firstIndex(where: { $0.id == jobID }) {
                jobs[i].state = .completed
                jobs[i].progress = 1
            }
            library?.add(saved)
        } catch {
            if let i = jobs.firstIndex(where: { $0.id == jobID }) {
                if let h = error as? HLSError, case .drmProtected(let k) = h {
                    jobs[i].state = .blockedDRM
                    jobs[i].errorMessage = k.messageAR
                } else {
                    jobs[i].state = .failed
                    jobs[i].errorMessage = error.localizedDescription
                }
            }
        }
        running = false
        pump()
    }

    private func perform(_ job: DownloadJob) async throws -> SavedVideo {
        guard let remote = URL(string: job.media.url) else { throw HLSError.network }
        let id = UUID()
        let title = sanitize(job.media.title)

        if job.media.kind == .hls || remote.pathExtension.lowercased() == "m3u8" || job.media.url.contains(".m3u8") {
            let folder = LibraryStore.videosDir.appendingPathComponent(id.uuidString, isDirectory: true)
            _ = try await hls.download(masterURL: remote, destFolder: folder) { [weak self] p in
                // لا ننشئ Task لكل segment — نكتفي بحساب التقدم على الخلفية
                // ونصنع تحديثاً واحداً مقيّداً على الـ main actor.
                Task { @MainActor in
                    self?.publishProgress(p, for: job.id)
                }
            }
            // حساب حجم المجلد (قد يضم مئات الملفات) لا يجب أن يجرى على الـ main actor.
            let size = await Task.detached(priority: .utility) { () -> Int64 in
                computeFolderSize(folder)
            }.value
            publishProgress(1, for: job.id)
            return SavedVideo(id: id, title: title, sourceURL: job.media.url, pageURL: job.media.pageURL, localRelativePath: "Videos/\(id.uuidString)/index.m3u8", thumbnailRelativePath: nil, kind: .hls, createdAt: Date(), duration: job.media.duration, fileSize: size, lastPosition: 0, extractionMethod: job.media.extractionMethod)
        }

        if job.media.kind == .dash {
            throw HLSError.drmProtected(.unknownProtected)
        }

        let ext = job.media.kind.fileExtension
        let dest = LibraryStore.videosDir.appendingPathComponent("\(id.uuidString).\(ext)")
        let (tmp, _) = try await URLSession.shared.download(from: remote)
        try FileManager.default.moveItem(at: tmp, to: dest)
        let bytes = (try? dest.resourceValues(forKeys: [.fileSizeKey]).fileSize).map { Int64($0) } ?? 0
        publishProgress(1, for: job.id)
        if let i = jobs.firstIndex(where: { $0.id == job.id }) {
            jobs[i].bytesWritten = bytes
        }
        return SavedVideo(id: id, title: title, sourceURL: job.media.url, pageURL: job.media.pageURL, localRelativePath: "Videos/\(id.uuidString).\(ext)", thumbnailRelativePath: nil, kind: job.media.kind, createdAt: Date(), duration: job.media.duration, fileSize: bytes, lastPosition: 0, extractionMethod: job.media.extractionMethod)
    }

    /// ينشر التقدّم بحدّ أقصى ~5 تحديثات/ثانية حتى لا يُغرق الـ main actor
    /// بعشرات المهام أثناء تنزيل مئات الـ segments.
    private var lastProgressAt = Date.distantPast
    private func publishProgress(_ p: Double, for jobID: UUID) {
        let now = Date()
        if p >= 1 || now.timeIntervalSince(lastProgressAt) >= 0.2 {
            if let i = jobs.firstIndex(where: { $0.id == jobID }) {
                jobs[i].progress = p
            }
            lastProgressAt = now
        }
    }

    private func sanitize(_ s: String) -> String {
        let t = s.trimmingCharacters(in: .whitespacesAndNewlines)
        return t.isEmpty ? "فيديو محفوظ" : String(t.prefix(120))
    }
}

/// حساب الحجم الكلي لمجلد (فيديو HLS) خارج الـ main actor — قد يضم مئات الملفات.
private func computeFolderSize(_ url: URL) -> Int64 {
    let keys: Set<URLResourceKey> = [.fileSizeKey, .isDirectoryKey]
    guard let e = FileManager.default.enumerator(at: url, includingPropertiesForKeys: Array(keys)) else { return 0 }
    var total: Int64 = 0
    for case let f as URL in e {
        if let v = try? f.resourceValues(forKeys: keys), v.isDirectory != true {
            total += Int64(v.fileSize ?? 0)
        }
    }
    return total
}
