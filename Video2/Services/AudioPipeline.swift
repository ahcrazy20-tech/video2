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
    // MARK: الخطوة 1: تجميع HLS segments في ملف .ts واحد (بدون AVFoundation)
    // MARK: ═══════════════════════════════════════════════════════════

    /// نقرأ ملف m3u8 يدوياً، نستخرج قائمة .ts segments، نلحمهم في ملف واحد.
    /// هذا الحل يعمل 100% لأنه لا يعتمد على AVFoundation لدعم HLS playlists.
    /// AVFoundation يقدر يقرأ ملف .ts عادي — فقط لا يستطيع قراءة m3u8 playlist.
    static func mergeHLSToSingleTS(m3u8URL: URL, outputDir: URL) async throws -> URL {

        print("[AudioPipeline] Reading m3u8 playlist: \(m3u8URL.lastPathComponent)")

        // نقرأ ملف m3u8
        guard let playlistContent = try? String(contentsOf: m3u8URL, encoding: .utf8) else {
            throw AudioPipelineError.exportFailed("تعذر قراءة ملف m3u8: \(m3u8URL.lastPathComponent)")
        }

        // نستخرج قائمة .ts segments (نتجاهل الأسطر اللي تبدأ بـ #)
        let lines = playlistContent.components(separatedBy: .newlines)
        let segmentPaths: [String] = lines.compactMap { line in
            let trimmed = line.trimmingCharacters(in: .whitespaces)
            guard !trimmed.isEmpty, !trimmed.hasPrefix("#") else { return nil }
            return trimmed
        }

        guard !segmentPaths.isEmpty else {
            throw AudioPipelineError.exportFailed("ملف m3u8 فارغ أو لا يحتوي segments")
        }

        print("[AudioPipeline] Found \(segmentPaths.count) TS segments")

        // الملف الناتج
        let outputURL = outputDir.appendingPathComponent("merged.ts")
        try? FileManager.default.removeItem(at: outputURL)

        // ننشئ الملف ونبدأ الكتابة
        guard FileManager.default.createFile(atPath: outputURL.path, contents: nil, attributes: nil) else {
            throw AudioPipelineError.exportFailed("تعذر إنشاء ملف المخرج")
        }

        let fileHandle = try FileHandle(forWritingTo: outputURL)
        defer { try? fileHandle.close() }

        let m3u8Folder = m3u8URL.deletingLastPathComponent()

        for (index, segPath) in segmentPaths.enumerated() {
            if Task.isCancelled {
                try? FileManager.default.removeItem(at: outputURL)
                throw CancellationError()
            }

            if segPath.hasPrefix("http://") || segPath.hasPrefix("https://") {
                // Segment على الإنترنت — ننزله
                guard let segURL = URL(string: segPath) else {
                    throw AudioPipelineError.exportFailed("رابط segment غير صالح: \(segPath)")
                }
                let (data, response) = try await URLSession.shared.data(from: segURL)
                if let httpResponse = response as? HTTPURLResponse, httpResponse.statusCode != 200 {
                    throw AudioPipelineError.exportFailed("فشل تنزيل segment \(index + 1): HTTP \(httpResponse.statusCode)")
                }
                fileHandle.write(data)
            } else {
                // Segment محلي — نقرأه مباشرة
                let segURL = m3u8Folder.appendingPathComponent(segPath)
                guard FileManager.default.fileExists(atPath: segURL.path) else {
                    throw AudioPipelineError.exportFailed("ملف segment غير موجود: \(segPath)")
                }
                let data = try Data(contentsOf: segURL)
                fileHandle.write(data)
            }

            print("[AudioPipeline] Merged segment \(index + 1)/\(segmentPaths.count)")
        }

        let fileSize = (try? outputURL.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
        print("[AudioPipeline] ✅ Merged TS file created: \(fileSize / 1024 / 1024) MB")

        return outputURL
    }

    // MARK: ═══════════════════════════════════════════════════════════
    // MARK: الخطوة 2: تحويل .ts إلى .mp4 (باستخدام AVAssetExportSession)
    // MARK: ═══════════════════════════════════════════════════════════

    /// AVAssetExportSession يقرأ ملفات .ts بشكل ممتاز (على عكس m3u8 playlists).
    /// هذه الدالة تأخذ .ts المدموج وتحويله لملف .mp4 صالح.
    static func convertTSToMP4(_ tsURL: URL) async throws -> URL {

        let asset = AVURLAsset(url: tsURL)

        // تحقق أن الملف يحتوي مسارات صالحة
        let videoTracks = try await asset.loadTracks(withMediaType: .video)
        let audioTracks = try await asset.loadTracks(withMediaType: .audio)
        print("[AudioPipeline] .ts tracks — Video: \(videoTracks.count), Audio: \(audioTracks.count)")

        guard !videoTracks.isEmpty || !audioTracks.isEmpty else {
            throw AudioPipelineError.exportFailed("ملف .ts لا يحتوي مسارات صالحة")
        }

        let presets: [String] = [
            AVAssetExportPresetPassthrough,
            AVAssetExportPresetHighestQuality,
            AVAssetExportPresetMediumQuality,
            AVAssetExportPreset960x540
        ]

        let compatiblePresets = AVAssetExportSession.exportPresets(compatibleWith: asset)

        for preset in presets {
            guard compatiblePresets.contains(preset) else { continue }

            let out = FileManager.default.temporaryDirectory
                .appendingPathComponent("v2-ts-\(UUID().uuidString).mp4")
            try? FileManager.default.removeItem(at: out)

            guard let session = AVAssetExportSession(asset: asset, presetName: preset) else { continue }

            let supportedTypes = session.supportedFileTypes
            guard supportedTypes.contains(.mp4) else { continue }

            session.outputURL = out
            session.outputFileType = .mp4
            session.shouldOptimizeForNetworkUse = true

            await withCheckedContinuation { (cont: CheckedContinuation<Void, Never>) in
                session.exportAsynchronously {
                    cont.resume()
                }
            }

            if session.status == .completed,
               FileManager.default.fileExists(atPath: out.path) {
                let fileSize = (try? out.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
                if fileSize > 0 {
                    print("[AudioPipeline] ✅ .ts → .mp4 succeeded with preset: \(preset), size: \(fileSize / 1024 / 1024) MB")
                    return out
                }
            }

            if session.status == .failed {
                let errDesc = session.error?.localizedDescription ?? "غير معروف"
                print("[AudioPipeline] ❌ .ts → .mp4 failed with preset \(preset): \(errDesc)")
            }

            try? FileManager.default.removeItem(at: out)
        }

        throw AudioPipelineError.exportFailed("تعذر تحويل .ts إلى .mp4")
    }

    // MARK: ═══════════════════════════════════════════════════════════
    // MARK: الدالة الموحدة: HLS → MP4 (للترجمة وللتحويل)
    // MARK: ═══════════════════════════════════════════════════════════

    /// تدمج كل الـ HLS segments في ملف .ts واحد ثم تحوّله لـ .mp4
    static func exportHLSToTempMP4(_ url: URL) async throws -> URL {

        guard FileManager.default.fileExists(atPath: url.path) else {
            throw AudioPipelineError.exportFailed("ملف HLS غير موجود: \(url.lastPathComponent)")
        }

        // خطوة 1: نلحم كل الـ segments في ملف .ts واحد
        let tempDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("v2-hls-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)

        let tsURL = try await mergeHLSToSingleTS(m3u8URL: url, outputDir: tempDir)

        // خطوة 2: نحول .ts إلى .mp4
        let mp4URL = try await convertTSToMP4(tsURL)

        // ننظف ملف .ts (مش محتاجينه تاني)
        try? FileManager.default.removeItem(at: tsURL)
        try? FileManager.default.removeItem(at: tempDir)

        return mp4URL
    }

    // MARK: ═══════════════════════════════════════════════════════════
    // MARK: الاستخراج والتقطيع الصوتي
    // MARK: ═══════════════════════════════════════════════════════════

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

                // خطوة 1: نلحم كل الـ segments في .ts واحد
                let tsURL = dir.appendingPathComponent("merged.ts")
                if !FileManager.default.fileExists(atPath: tsURL.path) {
                    _ = try await mergeHLSToSingleTS(m3u8URL: videoURL, outputDir: dir)
                    // mergeHLSToSingleTS بيحط الملف في merged.ts
                }

                // خطوة 2: نحول .ts إلى .mp4
                let tmp = try await convertTSToMP4(tsURL)
                do { try FileManager.default.moveItem(at: tmp, to: cached) }
                catch { try? FileManager.default.copyItem(at: tmp, to: cached) }

                // ننظف merged.ts
                try? FileManager.default.removeItem(at: tsURL)
                sourceURL = cached
            }
            guard FileManager.default.fileExists(atPath: sourceURL.path) else {
                throw AudioPipelineError.exportFailed("فقد الملف المحوّل — أعد المحاولة")
            }
        }

        // استئناف: manifest موجود وكل الملفات سليمة
        if let data = try? Data(contentsOf: manifestURL),
           let cached = try? JSONDecoder().decode([AudioChunk].self, from: data), !cached.isEmpty {
            let allExist = cached.allSatisfy { FileManager.default.fileExists(atPath: chunksDir.appendingPathComponent($0.fileName).path) }
            let durData = try? Data(contentsOf: dir.appendingPathComponent("duration.txt"))
            let dur = durData.flatMap { Double(String(data: $0, encoding: .utf8) ?? "") } ?? 0
            if allExist {
                progress(1)
                return (cached, dur)
            }
        }

        let asset = AVURLAsset(url: sourceURL)
        let duration = try await loadDuration(asset)
        try? String(duration).write(to: dir.appendingPathComponent("duration.txt"), atomically: true, encoding: .utf8)

        let audioTracks = try await asset.loadTracks(withMediaType: .audio)
        guard let track = audioTracks.first else {
            throw AudioPipelineError.noAudioTrack
        }

        let reader: AVAssetReader
        do {
            reader = try AVAssetReader(asset: asset)
        } catch {
            throw AudioPipelineError.readerFailed(error.localizedDescription)
        }

        let pcmSettings: [String: Any] = [
            AVFormatIDKey: kAudioFormatLinearPCM,
            AVSampleRateKey: sampleRate,
            AVNumberOfChannelsKey: 1,
            AVLinearPCMBitDepthKey: 16,
            AVLinearPCMIsFloatKey: false,
            AVLinearPCMIsBigEndianKey: false,
            AVLinearPCMIsNonInterleaved: false
        ]
        let output = AVAssetReaderTrackOutput(track: track, outputSettings: pcmSettings)
        output.alwaysCopiesSampleData = false
        reader.add(output)
        guard reader.startReading() else {
            throw AudioPipelineError.readerFailed(reader.error?.localizedDescription ?? "غير معروف")
        }

        let effectiveChunkSeconds = singleFile ? 1_000_000_000.0 : chunkSeconds
        var chunks: [AudioChunk] = []
        var chunkIndex = 0
        var chunkStartPTS: Double = 0
        var writer: AVAssetWriter? = nil
        var writerInput: AVAssetWriterInput? = nil

        func makeWriter(index: Int) throws -> (AVAssetWriter, AVAssetWriterInput) {
            let fileName = singleFile ? "audio-full.m4a" : String(format: "chunk-%03d.m4a", index)
            let url = chunksDir.appendingPathComponent(fileName)
            try? FileManager.default.removeItem(at: url)
            let w = try AVAssetWriter(url: url, fileType: .m4a)
            let settings: [String: Any] = [
                AVFormatIDKey: kAudioFormatMPEG4AAC,
                AVSampleRateKey: sampleRate,
                AVNumberOfChannelsKey: 1,
                AVEncoderBitRateKey: 32000
            ]
            let input = AVAssetWriterInput(mediaType: .audio, outputSettings: settings)
            input.expectsMediaDataInRealTime = false
            if w.canAdd(input) { w.add(input) }
            w.shouldOptimizeForNetworkUse = true
            return (w, input)
        }

        func finishWriter() async throws {
            writerInput?.markAsFinished()
            if let w = writer {
                await w.finishWriting()
                if w.status == .failed {
                    throw AudioPipelineError.writerFailed(w.error?.localizedDescription ?? "غير معروف")
                }
            }
            writer = nil
            writerInput = nil
        }

        while true {
            if Task.isCancelled {
                reader.cancelReading()
                try? await finishWriter()
                throw CancellationError()
            }
            guard let sample = output.copyNextSampleBuffer() else { break }
            let pts = CMTimeGetSeconds(CMSampleBufferGetPresentationTimeStamp(sample))
            if !pts.isFinite { continue }

            if writer == nil {
                chunkStartPTS = pts
                let (w, input) = try makeWriter(index: chunkIndex)
                w.startWriting()
                w.startSession(atSourceTime: CMTime(seconds: pts, preferredTimescale: 600))
                writer = w
                writerInput = input
            }

            if !singleFile && pts - chunkStartPTS >= effectiveChunkSeconds {
                try await finishWriter()
                let dur = pts - chunkStartPTS
                chunks.append(AudioChunk(index: chunkIndex,
                                         fileName: String(format: "chunk-%03d.m4a", chunkIndex),
                                         start: chunkStartPTS,
                                         duration: dur))
                chunkIndex += 1
                chunkStartPTS = pts
                let (w, input) = try makeWriter(index: chunkIndex)
                w.startWriting()
                w.startSession(atSourceTime: CMTime(seconds: pts, preferredTimescale: 600))
                writer = w
                writerInput = input
                if duration > 0 { progress(min(0.98, pts / duration)) }
            }

            while writerInput?.isReadyForMoreMediaData == false {
                try await Task.sleep(nanoseconds: 5_000_000)
                if Task.isCancelled {
                    reader.cancelReading()
                    try? await finishWriter()
                    throw CancellationError()
                }
            }
            if writerInput?.append(sample) == false {
                reader.cancelReading()
                throw AudioPipelineError.writerFailed(writer?.error?.localizedDescription ?? "تعذر إلحاق عيّنة صوت")
            }
        }

        if reader.status == .failed {
            throw AudioPipelineError.readerFailed(reader.error?.localizedDescription ?? "غير معروف")
        }

        if let w = writer {
            writerInput?.markAsFinished()
            await w.finishWriting()
            if w.status == .failed {
                throw AudioPipelineError.writerFailed(w.error?.localizedDescription ?? "غير معروف")
            }
            chunks.append(AudioChunk(index: chunkIndex,
                                     fileName: singleFile ? "audio-full.m4a" : String(format: "chunk-%03d.m4a", chunkIndex),
                                     start: chunkStartPTS,
                                     duration: max(0, duration - chunkStartPTS)))
            writer = nil
            writerInput = nil
        }

        guard !chunks.isEmpty else {
            throw AudioPipelineError.noAudioTrack
        }

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
        let chunksDir = dir.appendingPathComponent("chunks", isDirectory: true)
        try? FileManager.default.removeItem(at: chunksDir)
        try? FileManager.default.removeItem(at: dir.appendingPathComponent("hls-source.mp4"))
        try? FileManager.default.removeItem(at: dir.appendingPathComponent("hls-source.m4a"))
        try? FileManager.default.removeItem(at: dir.appendingPathComponent("merged.ts"))
        try? FileManager.default.removeItem(at: dir.appendingPathComponent("chunks.json"))
        try? FileManager.default.removeItem(at: dir.appendingPathComponent("duration.txt"))
    }
}
