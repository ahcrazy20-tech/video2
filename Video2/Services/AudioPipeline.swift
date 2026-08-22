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

    // MARK: تحويل HLS إلى m4a مؤقت (طريقة قوية)

    /// AVAssetReader بيدعم HLS محلي بشكل أصلي — نستخدمه مباشرة بدل AVAssetExportSession.
    /// نقرأ الـ audio track من الـ m3u8 ونكتبها كملف m4a واحد.
    static func exportHLSToTempMP4(_ url: URL) async throws -> URL {

        guard FileManager.default.fileExists(atPath: url.path) else {
            throw AudioPipelineError.exportFailed("ملف HLS غير موجود: \(url.lastPathComponent)")
        }

        let asset = AVURLAsset(url: url)

        // نحاول نقرأ الـ tracks ونطبع معلومات debug
        let allTracks: [AVAssetTrack]
        do {
            allTracks = try await asset.load(.tracks)
            print("[AudioPipeline] HLS asset tracks count: \(allTracks.count)")
            for (i, t) in allTracks.enumerated() {
                print("[AudioPipeline]   track[\(i)]: type=\(t.mediaType.rawValue), codec=\(t.formatDescriptions.first.debugDescription.prefix(60))")
            }
        } catch {
            print("[AudioPipeline] load(.tracks) failed: \(error.localizedDescription)")
            throw AudioPipelineError.exportFailed("لا يمكن قراءة مسارات ملف HLS: \(error.localizedDescription)")
        }

        // نبحث عن audio track
        let audioTracks = allTracks.filter { $0.mediaType == .audio }
        guard let audioTrack = audioTracks.first else {
            // لو مفيش audio، نشوف لو فيه video ونحاول نعمل extract منه
            // بعض ملفات HLS يكون الصوت جزء من video track (muxed)
            let videoTracks = allTracks.filter { $0.mediaType == .video }
            if !videoTracks.isEmpty {
                print("[AudioPipeline] No separate audio track, but video tracks exist: \(videoTracks.count)")
                // في حالة HLS، الصوت غالباً muxed جوه الـ segments
                // AVAssetReader هيقدر يستخرج الصوت من video track لو فيه embedded audio
            }
            throw AudioPipelineError.noAudioTrack
        }

        print("[AudioPipeline] Found \(audioTracks.count) audio track(s), using first")

        // ملف الإخراج
        let out = FileManager.default.temporaryDirectory
            .appendingPathComponent("v2-hls-\(UUID().uuidString).m4a")
        try? FileManager.default.removeItem(at: out)

        // نقرأ الـ audio من الـ m3u8 مباشرة
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
        let readerOutput = AVAssetReaderTrackOutput(track: audioTrack, outputSettings: pcmSettings)
        readerOutput.alwaysCopiesSampleData = false
        reader.add(readerOutput)

        guard reader.startReading() else {
            throw AudioPipelineError.readerFailed(reader.error?.localizedDescription ?? "لا يمكن بدء القراءة من ملف HLS")
        }

        // نكتب الصوت كـ AAC m4a
        let writer: AVAssetWriter
        do {
            writer = try AVAssetWriter(url: out, fileType: .m4a)
        } catch {
            throw AudioPipelineError.writerFailed(error.localizedDescription)
        }

        let writerSettings: [String: Any] = [
            AVFormatIDKey: kAudioFormatMPEG4AAC,
            AVSampleRateKey: sampleRate,
            AVNumberOfChannelsKey: 1,
            AVEncoderBitRateKey: 64000
        ]
        let writerInput = AVAssetWriterInput(mediaType: .audio, outputSettings: writerSettings)
        writerInput.expectsMediaDataInRealTime = false
        guard writer.canAdd(writerInput) else {
            throw AudioPipelineError.writerFailed("لا يمكن إضافة writer input")
        }
        writer.add(writerInput)
        writer.startWriting()

        var sessionStarted = false
        var sampleCount = 0

        while let sample = readerOutput.copyNextSampleBuffer() {
            if !sessionStarted {
                let t = CMTimeGetSeconds(CMSampleBufferGetPresentationTimeStamp(sample))
                if t.isFinite {
                    writer.startSession(atSourceTime: CMTime(seconds: max(0, t), preferredTimescale: 600))
                } else {
                    writer.startSession(atSourceTime: .zero)
                }
                sessionStarted = true
                print("[AudioPipeline] Started writing session at t=\(t)")
            }

            while !writerInput.isReadyForMoreMediaData {
                try? await Task.sleep(nanoseconds: 5_000_000)
            }

            if writerInput.append(sample) {
                sampleCount += 1
            }
        }

        if reader.status == .failed {
            let err = reader.error?.localizedDescription ?? "مجهول"
            print("[AudioPipeline] Reader failed: \(err)")
            writer.cancelWriting()
            throw AudioPipelineError.readerFailed(err)
        }

        reader.cancelReading()
        writerInput.markAsFinished()
        await writer.finishWriting()

        guard writer.status == .completed else {
            let err = writer.error?.localizedDescription ?? "مجهول"
            print("[AudioPipeline] Writer failed with status \(writer.status.rawValue): \(err)")
            throw AudioPipelineError.writerFailed("فشل كتابة ملف الصوت: \(err)")
        }

        let fileSize = (try? out.resourceValues(forKeys: Set([URLResourceKey.fileSizeKey])).fileSize) ?? 0
        guard fileSize > 0 else {
            throw AudioPipelineError.exportFailed("ملف الصوت الناتج فارغ (0 bytes)")
        }

        print("[AudioPipeline] HLS→m4a succeeded: \(sampleCount) samples, \(fileSize) bytes")
        return out
    }

    // MARK: الاستخراج والتقطيع

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
            let cached = dir.appendingPathComponent("hls-source.m4a")
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
        try? FileManager.default.removeItem(at: dir.appendingPathComponent("chunks.json"))
        try? FileManager.default.removeItem(at: dir.appendingPathComponent("duration.txt"))
    }
}
