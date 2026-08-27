import Foundation
import Combine
import AVFoundation
import UIKit

@MainActor
final class DownloadManager: ObservableObject {
    @Published var jobs: [DownloadJob] = []
    private weak var library: LibraryStore?
    private let hls = HLSDownloader()
    private var running = false
    private var runningJobID: UUID?
    private var currentTask: Task<Void, Never>?
    private var bgTask = UIBackgroundTaskIdentifier.invalid
    private static let indexURL = LibraryStore.documents.appendingPathComponent("downloads.json")

    func attach(library: LibraryStore) {
        self.library = library
    }

    func load() {
        guard let data = try? Data(contentsOf: Self.indexURL),
              let list = try? JSONDecoder().decode([DownloadJob].self, from: data) else { return }
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
        if let data = try? JSONEncoder().encode(jobs) {
            try? data.write(to: Self.indexURL, options: .atomic)
        }
    }

    func cancel(jobID: UUID) {
        guard let i = jobs.firstIndex(where: { $0.id == jobID }), jobs[i].state.isBusy else { return }
        if jobID == runningJobID {
            currentTask?.cancel()
            currentTask = nil
        }
        jobs[i].state = .paused
        jobs[i].errorMessage = nil
        saveIndex()
    }

    func resume(jobID: UUID) {
        guard let i = jobs.firstIndex(where: { $0.id == jobID }) else { return }
        guard jobs[i].state == .paused || jobs[i].state == .failed || jobs[i].state == .queued else { return }
        jobs[i].state = .queued
        jobs[i].errorMessage = nil
        saveIndex()
        pump()
    }

    func remove(jobID: UUID) {
        guard let i = jobs.firstIndex(where: { $0.id == jobID }) else { return }
        if jobs[i].state.isBusy { cancel(jobID: jobID) }
        if jobs[i].state != .completed, let rel = jobs[i].destRelativePath {
            let url = LibraryStore.documents.appendingPathComponent(rel)
            try? FileManager.default.removeItem(at: url)
        }
        jobs.removeAll { $0.id == jobID }
        saveIndex()
    }

    func enqueue(_ media: DetectedMedia, auth: DownloadAuth? = nil, maxHeight: Int? = nil) {
        if media.drm.isProtected {
            jobs.insert(DownloadJob(id: UUID(), media: media, state: .blockedDRM, progress: 0, bytesWritten: 0, errorMessage: media.drm.messageAR, createdAt: Date()), at: 0)
            saveIndex()
            return
        }
        let id = UUID()
        let dest: String
        if media.kind == .hls || media.url.contains(".m3u8") {
            dest = "Videos/\(id.uuidString)"
        } else {
            dest = "Videos/\(id.uuidString).\(media.kind.fileExtension)"
        }
        let height = maxHeight ?? Self.preferredMaxHeight
        jobs.insert(DownloadJob(
            id: id,
            media: media,
            state: .queued,
            progress: 0,
            bytesWritten: 0,
            errorMessage: nil,
            createdAt: Date(),
            destRelativePath: dest,
            preferredMaxHeight: height,
            auth: auth
        ), at: 0)
        saveIndex()
        pump()
    }

    func enqueueManual(urlString: String, title: String, page: String?, auth: DownloadAuth? = nil,
                       kindHint: MediaKind? = nil) {
        // kindHint اختياري: روابط CDN بلا امتداد (googlevideo مثلًا) تُسمّى .bin وإلا،
        // والتوجيه الصحيح للجودة/الامتداد يجعل المكتبة والمشغّل يتعاملان معه كما هو معتاد.
        var kind = MediaKind.infer(url: urlString.lowercased(), mime: nil)
        if let kindHint, kind == .other { kind = kindHint }
        var media = DetectedMedia(url: urlString, title: title.isEmpty ? "رابط يدوي" : title, kind: kind, mime: nil, qualityLabel: nil, drm: .none, pageURL: page, extractionMethod: "manual-url")
        var a = auth ?? .default
        if a.referer == nil { a.referer = page }
        media.pageURL = page
        enqueue(media, auth: a, maxHeight: Self.preferredMaxHeight)
    }

    /// مهمة نشطة مرتبطة برابط وسائط معيّن (لعرض شريط التقدّم داخل تبويب البحث).
    func job(matchingURL url: String) -> DownloadJob? {
        jobs.first(where: { $0.media.url == url })
    }

    static var preferredMaxHeight: Int? {
        let v = UserDefaults.standard.integer(forKey: "dl.maxHeight")
        return v > 0 ? v : nil
    }

    private func pump() {
        guard !running else { return }
        guard let idx = jobs.firstIndex(where: { $0.state == .queued }) else {
            endBackground()
            return
        }
        running = true
        beginBackground()
        var job = jobs[idx]
        job.state = .running
        jobs[idx] = job
        saveIndex()
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
            saveIndex()
        } catch {
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
            saveIndex()
        }
    }

    private func perform(_ job: DownloadJob) async throws -> SavedVideo {
        guard let remote = URL(string: job.media.url) else { throw HLSError.network }
        let id = job.id
        let title = sanitize(job.media.title)
        let auth = job.auth
        try FileManager.default.createDirectory(at: LibraryStore.videosDir, withIntermediateDirectories: true)

        if job.media.kind == .hls || remote.pathExtension.lowercased() == "m3u8" || job.media.url.contains(".m3u8") {
            let folder: URL
            if let rel = job.destRelativePath {
                folder = LibraryStore.documents.appendingPathComponent(rel, isDirectory: true)
            } else {
                folder = LibraryStore.videosDir.appendingPathComponent(id.uuidString, isDirectory: true)
            }
            _ = try await hls.download(masterURL: remote, destFolder: folder, auth: auth, maxHeight: job.preferredMaxHeight) { [weak self] p in
                Task { @MainActor in
                    self?.publishProgress(p, for: job.id)
                }
            }
            let size = await Task.detached(priority: .utility) { () -> Int64 in
                computeFolderSize(folder)
            }.value
            publishProgress(1, for: job.id)
            if let i = jobs.firstIndex(where: { $0.id == job.id }) {
                jobs[i].bytesWritten = size
            }
            return SavedVideo(id: id, title: title, sourceURL: job.media.url, pageURL: job.media.pageURL, localRelativePath: "Videos/\(id.uuidString)/index.m3u8", thumbnailRelativePath: nil, kind: .hls, createdAt: Date(), duration: job.media.duration, fileSize: size, lastPosition: 0, extractionMethod: job.media.extractionMethod)
        }

        if job.media.kind == .dash {
            throw HLSError.drmProtected(.unknownProtected)
        }

        let dest: URL
        if let rel = job.destRelativePath {
            dest = LibraryStore.documents.appendingPathComponent(rel)
        } else {
            dest = LibraryStore.videosDir.appendingPathComponent("\(id.uuidString).\(job.media.kind.fileExtension)")
        }
        try await downloadFileWithProgress(from: remote, to: dest, jobID: job.id, auth: auth)
        let bytes = (try? dest.resourceValues(forKeys: [.fileSizeKey]).fileSize).map { Int64($0) } ?? 0
        publishProgress(1, for: job.id)
        if let i = jobs.firstIndex(where: { $0.id == job.id }) {
            jobs[i].bytesWritten = bytes
        }
        let rel = dest.v2RelativePath(from: LibraryStore.documents)
        return SavedVideo(id: id, title: title, sourceURL: job.media.url, pageURL: job.media.pageURL, localRelativePath: rel, thumbnailRelativePath: nil, kind: job.media.kind, createdAt: Date(), duration: job.media.duration, fileSize: bytes, lastPosition: 0, extractionMethod: job.media.extractionMethod)
    }

    private func downloadFileWithProgress(from remote: URL, to dest: URL, jobID: UUID, auth: DownloadAuth?) async throws {
        try FileManager.default.createDirectory(at: dest.deletingLastPathComponent(), withIntermediateDirectories: true)
        if !FileManager.default.fileExists(atPath: dest.path) {
            FileManager.default.createFile(atPath: dest.path, contents: nil)
        }
        let handle = try FileHandle(forWritingTo: dest)
        let existing = Int64((try? dest.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0)
        if existing > 0 {
            try handle.seekToEnd()
        }

        let delegate = FileStreamDelegate(handle: handle, alreadyWritten: existing)
        let gate = DownloadProgressGate()
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 60
        config.timeoutIntervalForResource = 24 * 3600
        config.waitsForConnectivity = true
        let session = URLSession(configuration: config, delegate: delegate, delegateQueue: OperationQueue())

        delegate.onProgress = { [weak self] written, expected in
            let p = expected > 0 ? min(1, Double(written) / Double(expected)) : 0
            guard gate.shouldEmit(isFinal: p >= 1) else { return }
            guard let self else { return }
            if Task.isCancelled { return }
            Task { @MainActor in
                self.publishProgress(p, for: jobID)
                if let i = self.jobs.firstIndex(where: { $0.id == jobID }) {
                    self.jobs[i].bytesWritten = written
                }
            }
        }

        var req = URLRequest(url: remote)
        req.timeoutInterval = 24 * 3600
        req.cachePolicy = .reloadIgnoringLocalCacheData
        auth?.apply(to: &req)
        if existing > 0 {
            req.setValue("bytes=\(existing)-", forHTTPHeaderField: "Range")
        }

        let box = DownloadTaskBox()
        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (c: CheckedContinuation<Void, Error>) in
                delegate.onFinish = { result in
                    switch result {
                    case .success: c.resume()
                    case .failure(let e): c.resume(throwing: e)
                    }
                }
                let t = session.dataTask(with: req)
                box.dataTask = t
                t.resume()
            }
        } onCancel: {
            box.dataTask?.cancel()
            session.invalidateAndCancel()
            try? handle.close()
        }
        try? handle.close()
        session.invalidateAndCancel()
    }

    private var lastProgressAt = Date.distantPast
    private var lastSavedAt = Date.distantPast
    private func publishProgress(_ p: Double, for jobID: UUID) {
        let now = Date()
        if p >= 1 || now.timeIntervalSince(lastProgressAt) >= 0.2 {
            if let i = jobs.firstIndex(where: { $0.id == jobID }) {
                jobs[i].progress = p
            }
            lastProgressAt = now
            if p >= 1 || now.timeIntervalSince(lastSavedAt) >= 3 {
                saveIndex()
                lastSavedAt = now
            }
        }
    }

    private func sanitize(_ s: String) -> String {
        let t = s.trimmingCharacters(in: .whitespacesAndNewlines)
        return t.isEmpty ? "فيديو محفوظ" : String(t.prefix(120))
    }

    private func beginBackground() {
        if bgTask != .invalid { return }
        bgTask = UIApplication.shared.beginBackgroundTask(withName: "video2.download") { [weak self] in
            Task { @MainActor in self?.endBackground() }
        }
    }

    private func endBackground() {
        guard bgTask != .invalid else { return }
        UIApplication.shared.endBackgroundTask(bgTask)
        bgTask = .invalid
    }
}

private func computeFolderSize(_ url: URL) -> Int64 {
    StorageManager.folderSize(url)
}

final class FileStreamDelegate: NSObject, URLSessionDataDelegate, @unchecked Sendable {
    let handle: FileHandle
    var written: Int64
    var expected: Int64 = 0
    var onProgress: ((Int64, Int64) -> Void)?
    var onFinish: ((Result<Void, Error>) -> Void)?
    private var finished = false

    init(handle: FileHandle, alreadyWritten: Int64) {
        self.handle = handle
        self.written = alreadyWritten
        self.expected = alreadyWritten
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask,
                    didReceive response: URLResponse,
                    completionHandler: @escaping (URLSession.ResponseDisposition) -> Void) {
        let http = response as? HTTPURLResponse
        let status = http?.statusCode ?? 0
        if status == 200, written > 0 {
            try? handle.truncate(atOffset: 0)
            written = 0
        }
        if let lenStr = http?.value(forHTTPHeaderField: "Content-Length"), let len = Int64(lenStr) {
            expected = status == 206 ? written + len : len
        } else if let range = http?.value(forHTTPHeaderField: "Content-Range"),
                  let total = range.split(separator: "/").last,
                  let n = Int64(total) {
            expected = n
        }
        if status >= 400 {
            completionHandler(.cancel)
            finish(.failure(URLError(.badServerResponse)))
            return
        }
        completionHandler(.allow)
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        do {
            try handle.write(contentsOf: data)
            written += Int64(data.count)
            onProgress?(written, expected)
        } catch {
            finish(.failure(error))
        }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        if let error {
            finish(.failure(error))
        } else {
            finish(.success(()))
        }
    }

    private func finish(_ result: Result<Void, Error>) {
        guard !finished else { return }
        finished = true
        onFinish?(result)
    }
}

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

final class DownloadTaskBox: @unchecked Sendable {
    var task: URLSessionDownloadTask?
    var dataTask: URLSessionDataTask?
}
