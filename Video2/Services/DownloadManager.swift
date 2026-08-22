import Foundation
import Combine
import AVFoundation

@MainActor
final class DownloadManager: ObservableObject {
    @Published var jobs: [DownloadJob] = []
    private weak var library: LibraryStore?
    private let hls = HLSDownloader()
    private var running = false
    private var runningJobID: UUID?
    private var currentTask: Task<Void, Never>?

    func attach(library: LibraryStore) {
        self.library = library
    }

    /// يوقف تحميلاً جارياً/في الانتظار ويُبقيه في حالة "متوقف" حتى لا يبقى
    /// عالقاً بنسبة ثابتة إلى الأبد.
    func cancel(jobID: UUID) {
        guard let i = jobs.firstIndex(where: { $0.id == jobID }), jobs[i].state.isBusy else { return }
        // لا نُلغِ إلا مهمة التحميل الجارية فعلاً — لو كان العنصر في الانتظار فلم تبدأ بعد.
        if jobID == runningJobID {
            currentTask?.cancel()
            currentTask = nil
        }
        jobs[i].state = .paused
        jobs[i].errorMessage = nil
    }

    func remove(jobID: UUID) {
        guard let i = jobs.firstIndex(where: { $0.id == jobID }) else { return }
        if jobs[i].state.isBusy { cancel(jobID: jobID) }
        jobs.removeAll { $0.id == jobID }
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
        let startingID = job.id
        runningJobID = startingID
        currentTask = Task { await run(jobID: startingID) }
    }

    private func run(jobID: UUID) async {
        defer {
            if runningJobID == jobID { runningJobID = nil }
            currentTask = nil
            running = false
            pump()
        }
        guard let idx = jobs.firstIndex(where: { $0.id == jobID }) else { return }
        let job = jobs[idx]
        do {
            let saved = try await perform(job)
            if let i = jobs.firstIndex(where: { $0.id == jobID }) {
                jobs[i].state = .completed
                jobs[i].progress = 1
            }
            library?.add(saved)
        } catch {
            // أُلغِي يدوياً — سواءً عبر CancellationError أو لطّ خيط URLSession العائد بـ .cancelled.
            let cancelled = error is CancellationError || (error as? URLError)?.code == .cancelled
            if let i = jobs.firstIndex(where: { $0.id == jobID }) {
                if cancelled {
                    jobs[i].state = .paused
                    jobs[i].errorMessage = nil
                } else if let h = error as? HLSError, case .drmProtected(let k) = h {
                    jobs[i].state = .blockedDRM
                    jobs[i].errorMessage = k.messageAR
                } else {
                    jobs[i].state = .failed
                    jobs[i].errorMessage = error.localizedDescription
                }
            }
        }
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
        // تنزيل ملف واحد الذي يبلغ حجمه مئات الميجا يبقى عند نسبة ثابتة (غالباً 0) لأن
        // URLSession.shared.download لا يبلغ عن التقدّم — فيبدو وكأنه متوقّف. نستخدم
        // downloadTask مع delegate لبلّغ عن البايتات فعلياً (مع حفظ نسبة كل ~0.2 ثانية).
        try await downloadFileWithProgress(from: remote, to: dest, jobID: job.id)
        let bytes = (try? dest.resourceValues(forKeys: [.fileSizeKey]).fileSize).map { Int64($0) } ?? 0
        publishProgress(1, for: job.id)
        if let i = jobs.firstIndex(where: { $0.id == job.id }) {
            jobs[i].bytesWritten = bytes
        }
        return SavedVideo(id: id, title: title, sourceURL: job.media.url, pageURL: job.media.pageURL, localRelativePath: "Videos/\(id.uuidString).\(ext)", thumbnailRelativePath: nil, kind: job.media.kind, createdAt: Date(), duration: job.media.duration, fileSize: bytes, lastPosition: 0, extractionMethod: job.media.extractionMethod)
    }

    /// تنزيل ملف واحد مع تقدّم بالبايتات — يمنع أن يظهر التقدّم عالقاً عند نسبة ثابتة
    /// في الملفات الكبيرة. يبلّغ عن التقدّم كل ~0.25 ثانية (لا flood للـ main actor).
    private func downloadFileWithProgress(from remote: URL, to dest: URL, jobID: UUID) async throws {
        let delegate = FileDownloadDelegate()
        let gate = DownloadProgressGate()
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 60
        config.timeoutIntervalForResource = 24 * 3600
        let session = URLSession(configuration: config, delegate: delegate, delegateQueue: OperationQueue())

        delegate.onProgress = { [weak self] written, expected in
            let p = expected > 0 ? min(1, Double(written) / Double(expected)) : 0
            guard gate.shouldEmit(isFinal: p >= 1) else { return }
            guard let self else { return }
            if Task.isCancelled { return }
            Task { @MainActor in self.publishProgress(p, for: jobID) }
        }

        let box = DownloadTaskBox()
        let location: URL = try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (c: CheckedContinuation<URL, Error>) in
                delegate.onFinish = { result in
                    switch result {
                    case .success(let u): c.resume(returning: u)
                    case .failure(let e): c.resume(throwing: e)
                    }
                }
                let t = session.downloadTask(with: remote)
                box.task = t
                t.resume()
            }
        } onCancel: {
            if let t = box.task { t.cancel() }
            session.invalidateAndCancel()
        }
        defer { session.invalidateAndCancel() }

        try? FileManager.default.removeItem(at: dest)
        try FileManager.default.moveItem(at: location, to: dest)
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

/// Delegate لتنزيل ملف واحد مع تقدّم بالبايتات (يبلغ عن كل دفعة كتبها URLSession).
final class FileDownloadDelegate: NSObject, URLSessionDownloadDelegate, @unchecked Sendable {
    var onProgress: ((Int64, Int64) -> Void)?
    var onFinish: ((Result<URL, Error>) -> Void)?
    private var location: URL?

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {
        self.location = location
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        if let error {
            onFinish?(.failure(error))
        } else if let location {
            onFinish?(.success(location))
        } else {
            onFinish?(.failure(URLError(.unknown)))
        }
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask,
                    didWriteData bytesWritten: Int64,
                    totalBytesWritten: Int64,
                    totalBytesExpectedToWrite: Int64) {
        onProgress?(totalBytesWritten, totalBytesExpectedToWrite)
    }
}

/// بوابة خفيفة تُحدّد متى يُبثّ تحديث التقدّم (كل ~0.25 ثانية أو عند الاكتمال) بأمان من خيط الخلفية.
/// متزامنة عبر NSLock لأن onProgress يُستدعى من خيط URLSession الخلفي.
final class DownloadProgressGate: @unchecked Sendable {
    private let lock = NSLock()
    private var last = Date.distantPast

    func shouldEmit(isFinal: Bool) -> Bool {
        let now = Date()
        lock.lock()
        defer { lock.unlock() }
        if isFinal || now.timeIntervalSince(last) >= 0.25 {
            last = now
            return true
        }
        return false
    }
}

/// يحمل مرجعاً إلى task التحميل حتى يمكن إلغاؤه من onCancel في withTaskCancellationHandler.
final class DownloadTaskBox: @unchecked Sendable {
    var task: URLSessionDownloadTask?
}
