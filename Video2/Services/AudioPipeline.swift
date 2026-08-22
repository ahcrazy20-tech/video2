import Foundation
import AVFoundation

// MARK: - أخطاء المحرك الصوتي

enum AudioPipelineError: LocalizedError {
    case noAudioTrack
    case exportFailed(String)
    case readerFailed(String)
    case writerFailed(String)

    var errorDescription: String? {
        switch self {
        case .noAudioTrack:
            return "الفيديو لا يحتوي مساراً صوتياً — لا يمكن تفريغ كلام بدون صوت."
        case .exportFailed(let m):
            return "فشل تحويل الفيديو: \(m)"
        case .readerFailed(let m):
            return "فشل قراءة الصوت من الفيديو: \(m)"
        case .writerFailed(let m):
            return "فشل كتابة مقاطع الصوت: \(m)"
        }
    }
}

// MARK: - وصف جزء صوتي

struct AudioChunk: Codable, Equatable {
    var index: Int
    var fileName: String
    var start: Double
    var duration: Double
}

// MARK: - خط أنابيب الصوت

enum AudioPipeline {

    static let sampleRate = 16000.0
    static let chunkSeconds = 900.0

    // MARK: ═══════════════════════════════════════════════════════════
    // MARK: CloudConvert API (مدموج)
    // ═════════════════════════════════════════════════════════════════
    
    /// يجلب مفتاح CloudConvert من Keychain (أولوية) أو Info.plist
    private static func cloudConvertKey() -> String? {
        // أولاً من Keychain
        if let key = KeychainStore.get("cloudconvert"), !key.isEmpty {
            return key
        }
        // ثانياً من Info.plist (fallback)
        guard let key = Bundle.main.infoDictionary?["CLOUDCONVERT_API_KEY"] as? String else { return nil }
        return key.isEmpty || key.contains("ضع") ? nil : key
    }
    
    /// يتحقق من توفر مفتاح CloudConvert
    private static var hasCloudConvertKey: Bool {
        cloudConvertKey() != nil
    }
    
    /// دمج .ts segments في ملف واحد
    private static func mergeTS(segments: [String], baseFolder: URL) async throws -> URL {
        let out = FileManager.default.temporaryDirectory.appendingPathComponent("merge-\(UUID().uuidString).ts")
        guard FileManager.default.createFile(atPath: out.path, contents: nil) else {
            throw AudioPipelineError.exportFailed("تعذر إنشاء ملف مؤقت")
        }
        let fh = try FileHandle(forWritingTo: out)
        defer { try? fh.close() }
        for segPath in segments {
            if Task.isCancelled { try? FileManager.default.removeItem(at: out); throw CancellationError() }
            if segPath.hasPrefix("/") {
                let data = try Data(contentsOf: URL(fileURLWithPath: segPath))
                fh.write(data)
            } else if segPath.hasPrefix("http://") || segPath.hasPrefix("https://") {
                if let u = URL(string: segPath) { let (d, _) = try await URLSession.shared.data(from: u); fh.write(d) }
            } else {
                let segURL = baseFolder.appendingPathComponent(segPath)
                guard FileManager.default.fileExists(atPath: segURL.path) else { continue }
                let data = try Data(contentsOf: segURL)
                fh.write(data)
            }
        }
        return out
    }
    
    /// تحويل HLS عبر CloudConvert
    private static func convertWithCloudConvert(m3u8URL: URL) async throws -> URL {
        guard let apiKey = cloudConvertKey() else {
            throw AudioPipelineError.exportFailed("مفتاح CloudConvert غير موجود — أضفه من الإعدادات")
        }
        print("[CloudConvert] ═══════════════════════════════════════")
        print("[CloudConvert] Starting HLS → MP4")
        
        guard let content = try? String(contentsOf: m3u8URL, encoding: .utf8) else {
            throw AudioPipelineError.exportFailed("تعذر قراءة m3u8")
        }
        let lines = content.components(separatedBy: .newlines)
        let segs: [String] = lines.compactMap { l in
            let t = l.trimmingCharacters(in: .whitespaces)
            return t.isEmpty || t.hasPrefix("#") ? nil : t
        }
        guard !segs.isEmpty else { throw AudioPipelineError.exportFailed("m3u8 فارغ") }
        print("[CloudConvert] Found \(segs.count) segments")
        
        // دمج segments
        let merged = try await mergeTS(segments: segs, baseFolder: m3u8URL.deletingLastPathComponent())
        defer { try? FileManager.default.removeItem(at: merged) }
        let size = (try? merged.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
        print("[CloudConvert] Merged TS: \(size / 1024 / 1024) MB")
        
        // إنشاء job
        let createURL = URL(string: "https://api.cloudconvert.com/v2/jobs")!
        var createReq = URLRequest(url: createURL)
        createReq.httpMethod = "POST"
        createReq.setValue("Bearer \(apiKey)", forHTTPHeaderField: "Authorization")
        createReq.setValue("application/json", forHTTPHeaderField: "Content-Type")
        createReq.timeoutInterval = 60
        
        let jobBody: [String: Any] = [
            "tasks": [
                "import": ["operation": "import/upload"],
                "convert": ["operation": "convert", "input": ["import"], "output_format": "mp4", "engine": "ffmpeg"],
                "export": ["operation": "export/url", "input": ["convert"], "multiple": false]
            ]
        ]
        createReq.httpBody = try JSONSerialization.data(withJSONObject: jobBody)
        
        let (createData, createResp) = try await URLSession.shared.data(for: createReq)
        guard let hr = createResp as? HTTPURLResponse, (200...299).contains(hr.statusCode) else {
            throw AudioPipelineError.exportFailed("CloudConvert: فشل إنشاء job")
        }
        guard let json = try? JSONSerialization.jsonObject(with: createData) as? [String: Any],
              let jobData = json["data"] as? [String: Any],
              let jobID = jobData["id"] as? String else {
            throw AudioPipelineError.exportFailed("CloudConvert: استجابة غير صالحة")
        }
        print("[CloudConvert] Job: \(jobID)")
        
        // الحصول على upload URL
        let jobURL = URL(string: "https://api.cloudconvert.com/v2/jobs/\(jobID)")!
        var jobReq = URLRequest(url: jobURL)
        jobReq.setValue("Bearer \(apiKey)", forHTTPHeaderField: "Authorization")
        let (jobData2, _) = try await URLSession.shared.data(for: jobReq)
        
        guard let j = try? JSONSerialization.jsonObject(with: jobData2) as? [String: Any],
              let rel = j["relationships"] as? [String: Any],
              let tasks = rel["tasks"] as? [String: Any],
              let tData = tasks["data"] as? [[String: Any]],
              let up = tData.first(where: { $0["operation"] as? String == "import/upload" }),
              let tid = up["id"] as? String,
              let p = up["params"] as? [String: Any],
              let us = p["upload_url"] as? String, let uploadURL = URL(string: us) else {
            throw AudioPipelineError.exportFailed("CloudConvert: لم يتم العثور على upload URL")
        }
        print("[CloudConvert] Upload URL ready")
        
        // رفع الملف
        let fileData = try Data(contentsOf: merged)
        let boundary = "Boundary-\(UUID().uuidString)"
        var uploadReq = URLRequest(url: uploadURL)
        uploadReq.httpMethod = "POST"
        uploadReq.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
        uploadReq.timeoutInterval = 600
        
        var body = Data()
        body.append("--\(boundary)\r\n".data(using: .utf8)!)
        body.append("Content-Disposition: form-data; name=\"file\"; filename=\"\(merged.lastPathComponent)\"\r\n".data(using: .utf8)!)
        body.append("Content-Type: application/octet-stream\r\n\r\n".data(using: .utf8)!)
        body.append(fileData)
        body.append("\r\n--\(boundary)--\r\n".data(using: .utf8)!)
        uploadReq.httpBody = body
        
        let (_, uploadResp) = try await URLSession.shared.data(for: uploadReq)
        guard let uhr = uploadResp as? HTTPURLResponse, (200...299).contains(uhr.statusCode) else {
            throw AudioPipelineError.exportFailed("CloudConvert: فشل رفع الملف")
        }
        print("[CloudConvert] Uploaded ✅")
        
        // انتظار التحويل
        let start = Date()
        while Date().timeIntervalSince(start) < 600 {
            var pollReq = URLRequest(url: jobURL)
            pollReq.setValue("Bearer \(apiKey)", forHTTPHeaderField: "Authorization")
            let (pollData, _) = try await URLSession.shared.data(for: pollReq)
            
            if let pj = try? JSONSerialization.jsonObject(with: pollData) as? [String: Any],
               let pjd = pj["data"] as? [String: Any],
               let status = pjd["status"] as? String {
                if status == "finished" {
                    if let rel = pj["relationships"] as? [String: Any],
                       let tasks = rel["tasks"] as? [String: Any],
                       let tData = tasks["data"] as? [[String: Any]] {
                        for t in tData {
                            if t["operation"] as? String == "export/url",
                               let r = t["result"] as? [String: Any],
                               let fs = r["files"] as? [[String: Any]],
                               let f = fs.first, let dlURL = f["url"] as? String {
                                // تنزيل النتيجة
                                guard let url = URL(string: dlURL) else {
                                    throw AudioPipelineError.exportFailed("CloudConvert: رابط تنزيل غير صالح")
                                }
                                var dlReq = URLRequest(url: url)
                                dlReq.timeoutInterval = 600
                                let (dlData, _) = try await URLSession.shared.data(for: dlReq)
                                let out = FileManager.default.temporaryDirectory
                                    .appendingPathComponent("cc-\(UUID().uuidString).mp4")
                                try dlData.write(to: out)
                                print("[CloudConvert] Downloaded ✅")
                                return out
                            }
                        }
                    }
                    throw AudioPipelineError.exportFailed("CloudConvert: لم يتم العثور على رابط تنزيل")
                }
                if status == "error" {
                    throw AudioPipelineError.exportFailed("CloudConvert: فشل التحويل")
                }
            }
            try await Task.sleep(nanoseconds: 5_000_000_000)
        }
        throw AudioPipelineError.exportFailed("CloudConvert: انتهت مهلة الانتظار")
    }

    // MARK: ═══════════════════════════════════════════════════════════
    // MARK: تحويل HLS إلى MP4 — 3 طرق
    // ═════════════════════════════════════════════════════════════════

    static func exportHLSToTempMP4(_ url: URL) async throws -> URL {
        guard FileManager.default.fileExists(atPath: url.path) else {
            throw AudioPipelineError.exportFailed("ملف HLS غير موجود: \(url.lastPathComponent)")
        }

        print("[AudioPipeline] ═══════════════════════════════════════")
        print("[AudioPipeline] Starting HLS → MP4 conversion")
        print("[AudioPipeline] m3u8: \(url.lastPathComponent)")

        // الطريقة 1: CloudConvert API (لو متاح)
        if hasCloudConvertKey {
            print("[AudioPipeline] Trying CloudConvert API...")
            do {
                let result = try await convertWithCloudConvert(m3u8URL: url)
                print("[AudioPipeline] ✅ CloudConvert succeeded!")
                return result
            } catch {
                print("[AudioPipeline] ⚠️ CloudConvert failed: \(error.localizedDescription)")
                print("[AudioPipeline] Falling back to native...")
            }
        } else {
            print("[AudioPipeline] CloudConvert not available (no API key)")
        }

        // نقرأ playlist
        guard let content = try? String(contentsOf: url, encoding: .utf8) else {
            throw AudioPipelineError.exportFailed("تعذر قراءة m3u8")
        }
        let lines = content.components(separatedBy: .newlines)
        let segmentPaths: [String] = lines.compactMap { l in
            let t = l.trimmingCharacters(in: .whitespaces)
            return t.isEmpty || t.hasPrefix("#") ? nil : t
        }
        guard !segmentPaths.isEmpty else {
            throw AudioPipelineError.exportFailed("ملف m3u8 فارغ")
        }
        print("[AudioPipeline] Found \(segmentPaths.count) segments")
        let m3u8Folder = url.deletingLastPathComponent()

        // الطريقة 2: AVMutableComposition
        print("[AudioPipeline] Trying AVMutableComposition...")
        do {
            let result = try await tryCompositionMethod(segments: segmentPaths, folder: m3u8Folder)
            print("[AudioPipeline] ✅ Composition succeeded!")
            return result
        } catch {
            print("[AudioPipeline] ⚠️ Composition failed: \(error.localizedDescription)")
        }

        // الطريقة 3: segment-by-segment
        print("[AudioPipeline] Trying segment-by-segment...")
        do {
            let result = try await trySegmentBySegmentMethod(segments: segmentPaths, folder: m3u8Folder)
            print("[AudioPipeline] ✅ Segment-by-segment succeeded!")
            return result
        } catch {
            print("[AudioPipeline] ⚠️ Segment-by-segment failed: \(error.localizedDescription)")
        }

        throw AudioPipelineError.exportFailed("كل طرق التحويل فشلت")
    }

    // MARK: ── الطريقة 2: AVMutableComposition ──

    private static func tryCompositionMethod(segments: [String], folder: URL) async throws -> URL {
        let composition = AVMutableComposition()
        var currentTime = CMTime.zero
        var addedCount = 0

        for (index, segPath) in segments.enumerated() {
            if Task.isCancelled { throw CancellationError() }
            let segURL: URL
            if segPath.hasPrefix("/") { segURL = URL(fileURLWithPath: segPath) }
            else { segURL = folder.appendingPathComponent(segPath) }
            guard FileManager.default.fileExists(atPath: segURL.path) else {
                print("[AudioPipeline] ⚠️ Segment \(index + 1) not found"); continue
            }
            let segAsset = AVURLAsset(url: segURL)
            do {
                let duration = try await segAsset.load(.duration)
                guard CMTimeGetSeconds(duration) > 0 else { continue }

                for srcTrack in try await segAsset.loadTracks(withMediaType: .video) {
                    guard let ct = composition.addMutableTrack(withMediaType: .video, preferredTrackID: kCMPersistentTrackID_Invalid) else { continue }
                    let tr = try await srcTrack.load(.timeRange)
                    try ct.insertTimeRange(tr, of: srcTrack, at: currentTime)
                }
                for srcTrack in try await segAsset.loadTracks(withMediaType: .audio) {
                    guard let ct = composition.addMutableTrack(withMediaType: .audio, preferredTrackID: kCMPersistentTrackID_Invalid) else { continue }
                    let tr = try await srcTrack.load(.timeRange)
                    try ct.insertTimeRange(tr, of: srcTrack, at: currentTime)
                }
                currentTime = CMTimeAdd(currentTime, duration)
                addedCount += 1
                print("[AudioPipeline] ✅ Segment \(index + 1)")
            } catch {
                print("[AudioPipeline] ⚠️ Segment \(index + 1) failed: \(error.localizedDescription)")
                continue
            }
        }
        guard addedCount > 0 else { throw AudioPipelineError.exportFailed("لم يتم إضافة أي segment") }

        let out = FileManager.default.temporaryDirectory.appendingPathComponent("v2-comp-\(UUID().uuidString).mp4")
        try? FileManager.default.removeItem(at: out)

        for preset in [AVAssetExportPresetPassthrough, AVAssetExportPresetHighestQuality, AVAssetExportPresetMediumQuality] {
            let compatible = AVAssetExportSession.exportPresets(compatibleWith: composition)
            guard compatible.contains(preset),
                  let session = AVAssetExportSession(asset: composition, presetName: preset),
                  session.supportedFileTypes.contains(.mp4) else { continue }
            session.outputURL = out
            session.outputFileType = .mp4
            session.shouldOptimizeForNetworkUse = true
            await withCheckedContinuation { (c: CheckedContinuation<Void, Never>) in
                session.exportAsynchronously { c.resume() }
            }
            if session.status == .completed, FileManager.default.fileExists(atPath: out.path) {
                let sz = (try? out.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
                if sz > 0 { print("[AudioPipeline] ✅ Exported: \(sz / 1024 / 1024) MB"); return out }
            }
            try? FileManager.default.removeItem(at: out)
        }
        throw AudioPipelineError.exportFailed("فشل تصدير composition")
    }

    // MARK: ── الطريقة 3: segment-by-segment ──

    private static func trySegmentBySegmentMethod(segments: [String], folder: URL) async throws -> URL {
        let out = FileManager.default.temporaryDirectory.appendingPathComponent("v2-seg-\(UUID().uuidString).m4a")
        try? FileManager.default.removeItem(at: out)
        let writer = try AVAssetWriter(url: out, fileType: .m4a)
        let settings: [String: Any] = [
            AVFormatIDKey: kAudioFormatMPEG4AAC,
            AVSampleRateKey: sampleRate,
            AVNumberOfChannelsKey: 1,
            AVEncoderBitRateKey: 32000
        ]
        let writerInput = AVAssetWriterInput(mediaType: .audio, outputSettings: settings)
        writerInput.expectsMediaDataInRealTime = false
        guard writer.canAdd(writerInput) else { throw AudioPipelineError.writerFailed("لا يمكن إضافة writer input") }
        writer.add(writerInput)
        writer.shouldOptimizeForNetworkUse = true
        guard writer.startWriting() else { throw AudioPipelineError.writerFailed(writer.error?.localizedDescription ?? "فشل") }

        var currentTime = CMTime.zero
        var totalSamples = 0

        for (index, segPath) in segments.enumerated() {
            if Task.isCancelled { try? FileManager.default.removeItem(at: out); throw CancellationError() }
            let segURL: URL
            if segPath.hasPrefix("/") { segURL = URL(fileURLWithPath: segPath) }
            else { segURL = folder.appendingPathComponent(segPath) }
            guard FileManager.default.fileExists(atPath: segURL.path) else { continue }
            let segAsset = AVURLAsset(url: segURL)
            do {
                let duration = try await segAsset.load(.duration)
                guard CMTimeGetSeconds(duration) > 0 else { continue }
                let audioTracks = try await segAsset.loadTracks(withMediaType: .audio)
                guard let audioTrack = audioTracks.first else { continue }
                let reader = try AVAssetReader(asset: segAsset)
                let pcmSettings: [String: Any] = [
                    AVFormatIDKey: kAudioFormatLinearPCM,
                    AVSampleRateKey: sampleRate,
                    AVNumberOfChannelsKey: 1,
                    AVLinearPCMBitDepthKey: 16,
                    AVLinearPCMIsFloatKey: false,
                    AVLinearPCMIsBigEndianKey: false,
                    AVLinearPCMIsNonInterleaved: false
                ]
                let readerOutput = AVAssetReaderTrackOutput(track: audioTrack, outputSettings: pcmSettings)
                reader.add(readerOutput)
                guard reader.startReading() else { print("[AudioPipeline] ⚠️ Segment \(index + 1) read failed"); continue }
                writer.startSession(atSourceTime: currentTime)
                var segSamples = 0
                while let sb = readerOutput.copyNextSampleBuffer() {
                    while !writerInput.isReadyForMoreMediaData { try await Task.sleep(nanoseconds: 5_000_000) }
                    if !writerInput.append(sb) { break }
                    segSamples += 1; totalSamples += 1
                }
                if reader.status == .failed { continue }
                let segDur = Double(segSamples) / sampleRate
                currentTime = CMTimeAdd(currentTime, CMTime(seconds: segDur, preferredTimescale: Int32(sampleRate)))
                print("[AudioPipeline] ✅ Segment \(index + 1): \(segSamples) samples")
            } catch {
                print("[AudioPipeline] ⚠️ Segment \(index + 1): \(error.localizedDescription)")
                continue
            }
        }

        guard totalSamples > 0 else {
            try? FileManager.default.removeItem(at: out)
            throw AudioPipelineError.exportFailed("لم يتم قراءة أي عينات صوتية")
        }
        writerInput.markAsFinished()
        await writer.finishWriting()
        guard writer.status == .completed else {
            try? FileManager.default.removeItem(at: out)
            throw AudioPipelineError.writerFailed(writer.error?.localizedDescription ?? "فشل")
        }
        let sz = (try? out.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
        print("[AudioPipeline] ✅ Output: \(sz / 1024) KB")
        return out
    }

    // MARK: ═══════════════════════════════════════════════════════════
    // MARK: الاستخراج والتقطيع الصوتي
    // ═════════════════════════════════════════════════════════════════

    static func extractChunks(from videoURL: URL,
                              into dir: URL,
                              singleFile: Bool,
                              progress: @escaping (Double) -> Void) async throws -> (chunks: [AudioChunk], duration: Double) {

        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let chunksDir = dir.appendingPathComponent("chunks", isDirectory: true)
        try FileManager.default.createDirectory(at: chunksDir, withIntermediateDirectories: true)
        let manifestURL = dir.appendingPathComponent("chunks.json")

        var sourceURL = videoURL
        if videoURL.pathExtension.lowercased() == "m3u8" {
            let cached = dir.appendingPathComponent("hls-source.mp4")
            if FileManager.default.fileExists(atPath: cached.path) {
                sourceURL = cached
            } else {
                progress(0.02)
                let tmp = try await exportHLSToTempMP4(videoURL)
                do { try FileManager.default.moveItem(at: tmp, to: cached) }
                catch { try? FileManager.default.copyItem(at: tmp, to: cached) }
                sourceURL = cached
            }
            guard FileManager.default.fileExists(atPath: sourceURL.path) else {
                throw AudioPipelineError.exportFailed("فقد الملف المحوّل")
            }
        }

        if let data = try? Data(contentsOf: manifestURL),
           let cached = try? JSONDecoder().decode([AudioChunk].self, from: data), !cached.isEmpty {
            let allExist = cached.allSatisfy { FileManager.default.fileExists(atPath: chunksDir.appendingPathComponent($0.fileName).path) }
            let durData = try? Data(contentsOf: dir.appendingPathComponent("duration.txt"))
            let dur = durData.flatMap { Double(String(data: $0, encoding: .utf8) ?? "") } ?? 0
            if allExist { progress(1); return (cached, dur) }
        }

        let asset = AVURLAsset(url: sourceURL)
        let duration = try await loadDuration(asset)
        try? String(duration).write(to: dir.appendingPathComponent("duration.txt"), atomically: true, encoding: .utf8)

        let audioTracks = try await asset.loadTracks(withMediaType: .audio)
        guard let track = audioTracks.first else { throw AudioPipelineError.noAudioTrack }
        let reader = try AVAssetReader(asset: asset)
        let pcmSettings: [String: Any] = [
            AVFormatIDKey: kAudioFormatLinearPCM, AVSampleRateKey: sampleRate,
            AVNumberOfChannelsKey: 1, AVLinearPCMBitDepthKey: 16,
            AVLinearPCMIsFloatKey: false, AVLinearPCMIsBigEndianKey: false, AVLinearPCMIsNonInterleaved: false
        ]
        let output = AVAssetReaderTrackOutput(track: track, outputSettings: pcmSettings)
        output.alwaysCopiesSampleData = false
        reader.add(output)
        guard reader.startReading() else { throw AudioPipelineError.readerFailed(reader.error?.localizedDescription ?? "غير معروف") }

        let effectiveChunkSeconds = singleFile ? 1_000_000_000.0 : chunkSeconds
        var chunks: [AudioChunk] = []
        var chunkIndex = 0
        var chunkStartPTS: Double = 0
        var writer: AVAssetWriter? = nil
        var writerInput: AVAssetWriterInput? = nil

        func makeWriter(index: Int) throws -> (AVAssetWriter, AVAssetWriterInput) {
            let fn = singleFile ? "audio-full.m4a" : String(format: "chunk-%03d.m4a", index)
            let u = chunksDir.appendingPathComponent(fn)
            try? FileManager.default.removeItem(at: u)
            let w = try AVAssetWriter(url: u, fileType: .m4a)
            let s: [String: Any] = [AVFormatIDKey: kAudioFormatMPEG4AAC, AVSampleRateKey: sampleRate, AVNumberOfChannelsKey: 1, AVEncoderBitRateKey: 32000]
            let i = AVAssetWriterInput(mediaType: .audio, outputSettings: s)
            i.expectsMediaDataInRealTime = false
            if w.canAdd(i) { w.add(i) }
            w.shouldOptimizeForNetworkUse = true
            return (w, i)
        }
        func finishWriter() async throws {
            writerInput?.markAsFinished()
            if let w = writer {
                await w.finishWriting()
                if w.status == .failed { throw AudioPipelineError.writerFailed(w.error?.localizedDescription ?? "") }
            }
            writer = nil; writerInput = nil
        }

        while true {
            if Task.isCancelled { reader.cancelReading(); try? await finishWriter(); throw CancellationError() }
            guard let sample = output.copyNextSampleBuffer() else { break }
            let pts = CMTimeGetSeconds(CMSampleBufferGetPresentationTimeStamp(sample))
            if !pts.isFinite { continue }
            if writer == nil {
                chunkStartPTS = pts
                let (w, i) = try makeWriter(index: chunkIndex)
                w.startWriting(); w.startSession(atSourceTime: CMTime(seconds: pts, preferredTimescale: 600))
                writer = w; writerInput = i
            }
            if !singleFile && pts - chunkStartPTS >= effectiveChunkSeconds {
                try await finishWriter()
                chunks.append(AudioChunk(index: chunkIndex, fileName: String(format: "chunk-%03d.m4a", chunkIndex), start: chunkStartPTS, duration: pts - chunkStartPTS))
                chunkIndex += 1; chunkStartPTS = pts
                let (w, i) = try makeWriter(index: chunkIndex)
                w.startWriting(); w.startSession(atSourceTime: CMTime(seconds: pts, preferredTimescale: 600))
                writer = w; writerInput = i
                if duration > 0 { progress(min(0.98, pts / duration)) }
            }
            while writerInput?.isReadyForMoreMediaData == false {
                try await Task.sleep(nanoseconds: 5_000_000)
                if Task.isCancelled { reader.cancelReading(); try? await finishWriter(); throw CancellationError() }
            }
            if writerInput?.append(sample) == false {
                reader.cancelReading()
                throw AudioPipelineError.writerFailed(writer?.error?.localizedDescription ?? "تعذر إلحاق عيّنة صوت")
            }
        }

        if reader.status == .failed { throw AudioPipelineError.readerFailed(reader.error?.localizedDescription ?? "") }
        if let w = writer {
            writerInput?.markAsFinished(); await w.finishWriting()
            if w.status == .failed { throw AudioPipelineError.writerFailed(w.error?.localizedDescription ?? "") }
            chunks.append(AudioChunk(index: chunkIndex, fileName: singleFile ? "audio-full.m4a" : String(format: "chunk-%03d.m4a", chunkIndex), start: chunkStartPTS, duration: max(0, duration - chunkStartPTS)))
        }
        guard !chunks.isEmpty else { throw AudioPipelineError.noAudioTrack }
        let data = try JSONEncoder().encode(chunks)
        try data.write(to: manifestURL, options: .atomic)
        progress(1)
        return (chunks, duration)
    }

    static func loadDuration(_ asset: AVURLAsset) async throws -> Double {
        let d = try await asset.load(.duration)
        let s = CMTimeGetSeconds(d)
        return s.isFinite ? s : 0
    }

    static func cleanupAudio(in dir: URL) {
        try? FileManager.default.removeItem(at: dir.appendingPathComponent("chunks", isDirectory: true))
        try? FileManager.default.removeItem(at: dir.appendingPathComponent("hls-source.mp4"))
        try? FileManager.default.removeItem(at: dir.appendingPathComponent("hls-source.m4a"))
        try? FileManager.default.removeItem(at: dir.appendingPathComponent("merged.ts"))
        try? FileManager.default.removeItem(at: dir.appendingPathComponent("chunks.json"))
        try? FileManager.default.removeItem(at: dir.appendingPathComponent("duration.txt"))
    }
}
