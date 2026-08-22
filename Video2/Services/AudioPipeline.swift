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

    // MARK: تجميع HLS segments في ملف .ts واحد

    /// نقرأ ملف m3u8 يدوياً، نستخرج قائمة .ts segments، نلحمهم في ملف واحد.
    /// AVFoundation يقدر يقرأ ملف .ts عادي — مفيش حاجة لـ HTTP server أو AVAssetExportSession.
    static func mergeHLSToSingleTS(m3u8URL: URL, outputDir: URL) async throws -> URL {
        
        print("[AudioPipeline] Reading m3u8 playlist: \(m3u8URL.lastPathComponent)")
        
        // نقرأ ملف m3u8
        guard let playlistContent = try? String(contentsOf: m3u8URL, encoding: .utf8) else {
            throw AudioPipelineError.exportFailed("تعذر قراءة ملف m3u8")
        }
        
        // نستخرج قائمة .ts segments
        let lines = playlistContent.components(separatedBy: .newlines)
        let tsPaths: [String] = lines.compactMap { line in
            let trimmed = line.trimmingCharacters(in: .whitespaces)
            // نتجاهل التعليقات والـ #EXTINF
            guard !trimmed.isEmpty, !trimmed.hasPrefix("#") else { return nil }
            return trimmed
        }
        
        guard !tsPaths.isEmpty else {
            throw AudioPipelineError.exportFailed("ملف m3u8 فارغ أو لا يحتوي segments")
        }
        
        print("[AudioPipeline] Found \(tsPaths.count) TS segments")
        
        // الملف الناتج
        let outputURL = outputDir.appendingPathComponent("merged.ts")
        try? FileManager.default.removeItem(at: outputURL)
        
        // ننشئ الملف
        guard FileManager.default.createFile(atPath: outputURL.path, contents: nil, attributes: nil) else {
            throw AudioPipelineError.exportFailed("تعذر إنشاء ملف المخرج")
        }
        
        let fileHandle = try FileHandle(forWritingTo: outputURL)
        defer { try? fileHandle.close() }
        
        // نلحم كل segment
        let m3u8Folder = m3u8URL.deletingLastPathComponent()
        for (index, tsPath) in tsPaths.enumerated() {
            if Task.isCancelled {
                throw CancellationError()
            }
            
            // نبني المسار الكامل
            let tsURL: URL
            if tsPath.hasPrefix("http://") || tsPath.hasPrefix("https://") {
                tsURL = URL(string: tsPath)!
                // نحتاج ننزله مؤقتاً
                let (tempData, _) = try await URLSession.shared.data(from: tsURL)
                fileHandle.write(tempData)
            } else {
                // مسار محلي
                tsURL = m3u8Folder.appendingPathComponent(tsPath)
                let tsData = try Data(contentsOf: tsURL)
                fileHandle.write(tsData)
            }
            
            print("[AudioPipeline] Merged segment \(index + 1)/\(tsPaths.count)")
        }
        
        let fileSize = (try? outputURL.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
        print("[AudioPipeline] Merged TS file created: \(fileSize) bytes")
        
        return outputURL
    }

    // MARK: استخراج الصوت من ملف .ts (أو أي فيديو)

    /// نقرأ ملف .ts (أو MP4) ونستخرج الصوت كـ m4a chunks
    static func extractAudioFromVideo(videoURL: URL, outputDir: URL) async throws -> AVURLAsset {
        
        print("[AudioPipeline] Creating asset from: \(videoURL.lastPathComponent)")
        
        let asset = AVURLAsset(url: videoURL)
        
        // تحقق أن الفيديو يحتوي مسارات صالحة
        do {
            let videoTracks = try await asset.loadTracks(withMediaType: .video)
            let audioTracks = try await asset.loadTracks(withMediaType: .audio)
            
            print("[AudioPipeline] Video tracks: \(videoTracks.count), Audio tracks: \(audioTracks.count)")
            
            guard !videoTracks.isEmpty || !audioTracks.isEmpty else {
                throw AudioPipelineError.exportFailed("الفيديو لا يحتوي مسارات صالحة")
            }
            guard !audioTracks.isEmpty else {
                throw AudioPipelineError.noAudioTrack
            }
        } catch let e as AudioPipelineError {
            throw e
        } catch {
            throw AudioPipelineError.readerFailed("تعذر قراءة مسارات الفيديو: \(error.localizedDescription)")
        }
        
        return asset
    }

    // MARK: الاستخراج والتقطيع

    /// يقرأ الصوت ويكتب أجزاء m4a في المجلد المطلوب، ويعيد قائمة الأجزاء.
    /// `singleFile: true` ينتج ملفاً واحداً كاملاً (لخدمة AssemblyAI).
    /// يستأنف تلقائياً إذا وُجد manifest مطابق من تشغيل سابق.
    static func extractChunks(from videoURL: URL,
                              into dir: URL,
                              singleFile: Bool,
                              progress: @escaping (Double) -> Void) async throws -> (chunks: [AudioChunk], duration: Double) {

        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let chunksDir = dir.appendingPathComponent("chunks", isDirectory: true)
        try FileManager.default.createDirectory(at: chunksDir, withIntermediateDirectories: true)
        let manifestURL = dir.appendingPathComponent("chunks.json")

        var sourceURL = videoURL
        
        // لو HLS (m3u8)، نلحم كل الـ segments في ملف .ts واحد
        if videoURL.pathExtension.lowercased() == "m3u8" {
            let cached = dir.appendingPathComponent("merged.ts")
            if FileManager.default.fileExists(atPath: cached.path) {
                sourceURL = cached
            } else {
                progress(0.02)
                sourceURL = try await mergeHLSToSingleTS(m3u8URL: videoURL, outputDir: dir)
                do { try FileManager.default.moveItem(at: sourceURL, to: cached) }
                catch { 
                    // لو move فشل، نستخدم الملف اللي عندنا
                    sourceURL = dir.appendingPathComponent("merged.ts")
                }
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

        let asset = try await extractAudioFromVideo(videoURL: sourceURL, outputDir: dir)
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
        try? FileManager.default.removeItem(at: dir.appendingPathComponent("merged.ts"))
        try? FileManager.default.removeItem(at: dir.appendingPathComponent("hls-source.mp4"))
        try? FileManager.default.removeItem(at: dir.appendingPathComponent("chunks.json"))
        try? FileManager.default.removeItem(at: dir.appendingPathComponent("duration.txt"))
    }
}
