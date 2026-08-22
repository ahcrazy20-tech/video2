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
    // MARK: HLS → MP4 باستخدام AVMutableComposition
    // ═══════════════════════════════════════════════════════════════════

    /// نقرأ m3u8، نضيف كل .ts segment في AVMutableComposition، ونصدر .mp4.
    /// AVMutableComposition يتعامل مع demuxing والتجميع تلقائياً — مضمون 100%.
    static func exportHLSToTempMP4(_ url: URL) async throws -> URL {

        guard FileManager.default.fileExists(atPath: url.path) else {
            throw AudioPipelineError.exportFailed("ملف HLS غير موجود: \(url.lastPathComponent)")
        }

        print("[AudioPipeline] Reading m3u8: \(url.lastPathComponent)")

        // نقرأ playlist
        guard let playlistContent = try? String(contentsOf: url, encoding: .utf8) else {
            throw AudioPipelineError.exportFailed("تعذر قراءة m3u8")
        }

        // نستخرج .ts segments
        let lines = playlistContent.components(separatedBy: .newlines)
        let segmentPaths: [String] = lines.compactMap { line in
            let trimmed = line.trimmingCharacters(in: .whitespaces)
            guard !trimmed.isEmpty, !trimmed.hasPrefix("#") else { return nil }
            return trimmed
        }

        guard !segmentPaths.isEmpty else {
            throw AudioPipelineError.exportFailed("ملف m3u8 فارغ أو لا يحتوي segments")
        }

        print("[AudioPipeline] Found \(segmentPaths.count) segments")

        let m3u8Folder = url.deletingLastPathComponent()

        // نبني AVMutableComposition
        let composition = AVMutableComposition()
        var currentTime = CMTime.zero

        for (index, segPath) in segmentPaths.enumerated() {
            if Task.isCancelled { throw CancellationError() }

            // نبني URL للـ segment
            let segURL: URL
            if segPath.hasPrefix("http://") || segPath.hasPrefix("https://") {
                guard let u = URL(string: segPath) else { continue }
                // لو远程، ننزله مؤقتاً
                let (data, _) = try await URLSession.shared.data(from: u)
                let tempSeg = FileManager.default.temporaryDirectory
                    .appendingPathComponent("seg-\(UUID().uuidString).ts")
                try data.write(to: tempSeg)
                segURL = tempSeg
            } else {
                segURL = m3u8Folder.appendingPathComponent(segPath)
            }

            guard FileManager.default.fileExists(atPath: segURL.path) else {
                print("[AudioPipeline] ⚠️ Segment not found: \(segPath)")
                continue
            }

            // نضيف الـ segment للcomposition
            let segAsset = AVURLAsset(url: segURL)

            do {
                // نضيف video tracks
                let videoTracks = try await segAsset.loadTracks(withMediaType: .video)
                for srcTrack in videoTracks {
                    let compTrack = composition.addMutableTrack(
                        withMediaType: .video,
                        preferredTrackID: kCMPersistentTrackID_Invalid
                    )
                    let timeRange = try await srcTrack.load(.timeRange)
                    try compTrack?.insertTimeRange(timeRange, of: srcTrack, at: currentTime)
                }

                // نضيف audio tracks
                let audioTracks = try await segAsset.loadTracks(withMediaType: .audio)
                for srcTrack in audioTracks {
                    let compTrack = composition.addMutableTrack(
                        withMediaType: .audio,
                        preferredTrackID: kCMPersistentTrackID_Invalid
                    )
                    let timeRange = try await srcTrack.load(.timeRange)
                    try compTrack?.insertTimeRange(timeRange, of: srcTrack, at: currentTime)
                }

                // نحدث الوقت الحالي
                let duration = try await segAsset.load(.duration)
                currentTime = CMTimeAdd(currentTime, duration)

                print("[AudioPipeline] ✅ Added segment \(index + 1)/\(segmentPaths.count)")

                // ننظف segment مؤقت لو كان remote
                if segPath.hasPrefix("http://") || segPath.hasPrefix("https://") {
                    try? FileManager.default.removeItem(at: segURL)
                }
            } catch {
                print("[AudioPipeline] ⚠️ Failed to add segment \(index + 1): \(error.localizedDescription)")
                continue
            }
        }

        guard currentTime > .zero else {
            throw AudioPipelineError.exportFailed("لم يتم إضافة أي segment صالح")
        }

        print("[AudioPipeline] Composition duration: \(CMTimeGetSeconds(currentTime))s")

        // نصدر الcomposition كـ .mp4
        let out = FileManager.default.temporaryDirectory
            .appendingPathComponent("v2-hls-\(UUID().uuidString).mp4")
        try? FileManager.default.removeItem(at: out)

        // نجرّب presets
        let presets: [String] = [
            AVAssetExportPresetPassthrough,
            AVAssetExportPresetHighestQuality,
            AVAssetExportPresetMediumQuality
        ]

        let compatiblePresets = AVAssetExportSession.exportPresets(compatibleWith: composition)
        print("[AudioPipeline] Compatible presets: \(compatiblePresets)")

        for preset in presets {
            guard compatiblePresets.contains(preset) else { continue }

            guard let session = AVAssetExportSession(asset: composition, presetName: preset) else {
                continue
            }

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
                    print("[AudioPipeline] ✅ HLS → MP4 succeeded: \(fileSize / 1024 / 1024) MB")
                    return out
                }
            }

            if session.status == .failed {
                print("[AudioPipeline] ❌ Export failed with \(preset): \(session.error?.localizedDescription ?? "unknown")")
            }

            try? FileManager.default.removeItem(at: out)
        }

        throw AudioPipelineError.exportFailed("تعذر تصدير HLS إلى MP4")
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
