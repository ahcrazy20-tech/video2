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
    // MARK: الحل الأول: CloudConvert API (الأسهل والأضمن)
    // ═════════════════════════════════════════════════════════════════
    
    /// يحول HLS إلى MP4 باستخدام CloudConvert API
    /// مجاني 25 conversions/day — سجل في https://cloudconvert.com واحصل على API key
    static func exportHLSWithCloudConvert(_ url: URL, apiKey: String? = nil) async throws -> URL {
        
        guard let key = apiKey ?? Bundle.main.infoDictionary?["CLOUDCONVERT_API_KEY"] as? String else {
            throw AudioPipelineError.exportFailed("لم يتم تكوين CloudConvert API key — أضفه في Info.plist")
        }
        
        print("[AudioPipeline] ═══════════════════════════════════════")
        print("[AudioPipeline] Starting CloudConvert HLS → MP4")
        
        // نرفع الملف لـ CloudConvert
        let m3u8Data = try Data(contentsOf: url)
        let folder = url.deletingLastPathComponent()
        
        // نحضر كل الملفات المطلوبة
        let fileManager = FileManager.default
        guard let files = try? fileManager.contentsOfDirectory(atPath: folder.path) else {
            throw AudioPipelineError.exportFailed("تعذر قراءة مجلد HLS")
        }
        
        print("[AudioPipeline] Found \(files.count) files in folder")
        
        // نبني multipart form
        var request = URLRequest(url: URL(string: "https://api.cloudconvert.com/v2/jobs")!)
        request.httpMethod = "POST"
        request.setValue("Bearer \(key)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        
        // نبني job payload
        let job: [String: Any] = [
            "tasks": [
                "import-file": [
                    "operation": "import/upload",
                    "filename": "video.m3u8"
                ],
                "convert": [
                    "operation": "convert",
                    "input_format": "m3u8",
                    "output_format": "mp4",
                    "input": ["import-file"],
                    "filename": "video.mp4"
                ],
                "export-file": [
                    "operation": "export/url",
                    "input": ["convert"],
                    "file": "video.mp4"
                ]
            ]
        ]
        
        request.httpBody = try JSONSerialization.data(withJSONObject: job)
        
        let (data, response) = try await URLSession.shared.data(for: request)
        
        guard let httpResponse = response as? HTTPURLResponse else {
            throw AudioPipelineError.exportFailed("استجابة غير صالحة")
        }
        
        print("[AudioPipeline] CloudConvert response: \(httpResponse.statusCode)")
        
        guard httpResponse.statusCode == 200 || httpResponse.statusCode == 201 else {
            let errorMsg = String(data: data, encoding: .utf8) ?? "غير معروف"
            print("[AudioPipeline] ❌ Error: \(errorMsg)")
            throw AudioPipelineError.exportFailed("CloudConvert فشل: HTTP \(httpResponse.statusCode)")
        }
        
        // نحصل على job ID
        guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let jobData = json["data"] as? [String: Any],
              let jobID = jobData["id"] as? String else {
            throw AudioPipelineError.exportFailed("استجابة غير صالحة")
        }
        
        print("[AudioPipeline] ✅ Job created: \(jobID)")
        
        // نرفع الملفات
        // ... (التفاصيل في الحل الكامل)
        
        throw AudioPipelineError.exportFailed("CloudConvert API implementation incomplete")
    }

    // MARK: ═══════════════════════════════════════════════════════════
    // MARK: الحل الثاني: Native - قراءة .ts segments واحد واحد
    // ═════════════════════════════════════════════════════════════════
    
    /// نقرأ كل .ts segment لوحده بـ AVAssetReader، ونجمع الـ audio samples
    /// هذا الحل يتجاوز مشكلة AVFoundation مع HLS playlists
    static func exportHLSToTempMP4(_ url: URL) async throws -> URL {
        
        guard FileManager.default.fileExists(atPath: url.path) else {
            throw AudioPipelineError.exportFailed("ملف HLS غير موجود: \(url.lastPathComponent)")
        }

        print("[AudioPipeline] ═══════════════════════════════════════")
        print("[AudioPipeline] Starting HLS → MP4 (Native method)")
        print("[AudioPipeline] m3u8 file: \(url.lastPathComponent)")

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
            throw AudioPipelineError.exportFailed("ملف m3u8 فارغ")
        }

        print("[AudioPipeline] Found \(segmentPaths.count) segments")

        let m3u8Folder = url.deletingLastPathComponent()
        
        // نحاول حلين:
        // 1. AVMutableComposition (الأسرع لو نجح)
        // 2. قراءة كل segment لوحده (أبطأ بس أكيد شغال)
        
        do {
            return try await tryCompositionMethod(url: url, segments: segmentPaths, folder: m3u8Folder)
        } catch {
            print("[AudioPipeline] ⚠️ Composition method failed: \(error.localizedDescription)")
            print("[AudioPipeline] Falling back to segment-by-segment method...")
            return try await trySegmentBySegmentMethod(segments: segmentPaths, folder: m3u8Folder)
        }
    }
    
    // MARK: ── الطريقة الأولى: AVMutableComposition ──
    
    private static func tryCompositionMethod(url: URL, segments: [String], folder: URL) async throws -> URL {
        print("[AudioPipeline] Trying AVMutableComposition method...")
        
        let composition = AVMutableComposition()
        var currentTime = CMTime.zero
        var addedCount = 0
        
        for (index, segPath) in segments.enumerated() {
            if Task.isCancelled { throw CancellationError() }
            
            print("[AudioPipeline] Processing segment \(index + 1)/\(segments.count)")
            
            let segURL: URL
            if segPath.hasPrefix("/") {
                segURL = URL(fileURLWithPath: segPath)
            } else {
                segURL = folder.appendingPathComponent(segPath)
            }
            
            guard FileManager.default.fileExists(atPath: segURL.path) else {
                print("[AudioPipeline] ⚠️ Segment not found: \(segPath)")
                continue
            }
            
            let segAsset = AVURLAsset(url: segURL)
            
            do {
                let duration = try await segAsset.load(.duration)
                let durationSec = CMTimeGetSeconds(duration)
                
                guard durationSec > 0 else { continue }
                
                // نضيف video tracks
                let videoTracks = try await segAsset.loadTracks(withMediaType: .video)
                for srcTrack in videoTracks {
                    guard let compTrack = composition.addMutableTrack(
                        withMediaType: .video,
                        preferredTrackID: kCMPersistentTrackID_Invalid
                    ) else { continue }
                    
                    let timeRange = try await srcTrack.load(.timeRange)
                    try compTrack.insertTimeRange(timeRange, of: srcTrack, at: currentTime)
                }
                
                // نضيف audio tracks
                let audioTracks = try await segAsset.loadTracks(withMediaType: .audio)
                for srcTrack in audioTracks {
                    guard let compTrack = composition.addMutableTrack(
                        withMediaType: .audio,
                        preferredTrackID: kCMPersistentTrackID_Invalid
                    ) else { continue }
                    
                    let timeRange = try await srcTrack.load(.timeRange)
                    try compTrack.insertTimeRange(timeRange, of: srcTrack, at: currentTime)
                }
                
                currentTime = CMTimeAdd(currentTime, duration)
                addedCount += 1
                
                print("[AudioPipeline] ✅ Added segment \(index + 1), duration: \(CMTimeGetSeconds(currentTime))s")
            } catch {
                print("[AudioPipeline] ⚠️ Failed to add segment \(index + 1): \(error.localizedDescription)")
                continue
            }
        }
        
        guard addedCount > 0 else {
            throw AudioPipelineError.exportFailed("لم يتم إضافة أي segment")
        }
        
        print("[AudioPipeline] Composition complete: \(addedCount) segments, \(CMTimeGetSeconds(currentTime))s")
        
        // نصدر الcomposition كـ .mp4
        let out = FileManager.default.temporaryDirectory
            .appendingPathComponent("v2-hls-\(UUID().uuidString).mp4")
        try? FileManager.default.removeItem(at: out)
        
        let presets: [String] = [
            AVAssetExportPresetPassthrough,
            AVAssetExportPresetHighestQuality,
            AVAssetExportPresetMediumQuality
        ]
        
        let compatiblePresets = AVAssetExportSession.exportPresets(compatibleWith: composition)
        
        for preset in presets {
            guard compatiblePresets.contains(preset) else { continue }
            
            guard let session = AVAssetExportSession(asset: composition, presetName: preset) else { continue }
            
            let supportedTypes = session.supportedFileTypes
            guard supportedTypes.contains(.mp4) else { continue }
            
            session.outputURL = out
            session.outputFileType = .mp4
            session.shouldOptimizeForNetworkUse = true
            
            await withCheckedContinuation { (cont: CheckedContinuation<Void, Never>) in
                session.exportAsynchronously { cont.resume() }
            }
            
            if session.status == .completed,
               FileManager.default.fileExists(atPath: out.path) {
                let fileSize = (try? out.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
                if fileSize > 0 {
                    print("[AudioPipeline] ✅ HLS → MP4 SUCCESS! Size: \(fileSize / 1024 / 1024) MB")
                    return out
                }
            }
            
            try? FileManager.default.removeItem(at: out)
        }
        
        throw AudioPipelineError.exportFailed("فشل تصدير composition")
    }
    
    // MARK: ── الطريقة الثانية: قراءة كل segment لوحده ──
    
    private static func trySegmentBySegmentMethod(segments: [String], folder: URL) async throws -> URL {
        print("[AudioPipeline] Trying segment-by-segment method...")
        
        // ننشئ ملف إخراج واحد
        let out = FileManager.default.temporaryDirectory
            .appendingPathComponent("v2-hls-\(UUID().uuidString).m4a")
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
        
        guard writer.canAdd(writerInput) else {
            throw AudioPipelineError.writerFailed("لا يمكن إضافة writer input")
        }
        writer.add(writerInput)
        writer.shouldOptimizeForNetworkUse = true
        
        guard writer.startWriting() else {
            throw AudioPipelineError.writerFailed(writer.error?.localizedDescription ?? "فشل بدء الكتابة")
        }
        
        var currentTime = CMTime.zero
        var totalSamples = 0
        
        for (index, segPath) in segments.enumerated() {
            if Task.isCancelled {
                try? FileManager.default.removeItem(at: out)
                throw CancellationError()
            }
            
            print("[AudioPipeline] Reading segment \(index + 1)/\(segments.count)")
            
            let segURL: URL
            if segPath.hasPrefix("/") {
                segURL = URL(fileURLWithPath: segPath)
            } else {
                segURL = folder.appendingPathComponent(segPath)
            }
            
            guard FileManager.default.fileExists(atPath: segURL.path) else {
                print("[AudioPipeline] ⚠️ Segment not found: \(segPath)")
                continue
            }
            
            let segAsset = AVURLAsset(url: segURL)
            
            do {
                // نتحقق من duration
                let duration = try await segAsset.load(.duration)
                let durationSec = CMTimeGetSeconds(duration)
                
                guard durationSec > 0 else {
                    print("[AudioPipeline] ⚠️ Invalid duration")
                    continue
                }
                
                // نحاول نقرأ audio tracks
                let audioTracks = try await segAsset.loadTracks(withMediaType: .audio)
                guard let audioTrack = audioTracks.first else {
                    print("[AudioPipeline] ⚠️ No audio track")
                    continue
                }
                
                // ننشئ reader
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
                
                guard reader.startReading() else {
                    print("[AudioPipeline] ⚠️ Failed to start reading: \(reader.error?.localizedDescription ?? "unknown")")
                    continue
                }
                
                // نبدأ session للwriter
                writer.startSession(atSourceTime: currentTime)
                
                var segmentSamples = 0
                
                // نقرأ كل samples من الsegment
                while let sampleBuffer = readerOutput.copyNextSampleBuffer() {
                    while !writerInput.isReadyForMoreMediaData {
                        try await Task.sleep(nanoseconds: 5_000_000)
                    }
                    
                    if !writerInput.append(sampleBuffer) {
                        print("[AudioPipeline] ⚠️ Failed to append sample")
                        break
                    }
                    
                    segmentSamples += 1
                    totalSamples += 1
                }
                
                if reader.status == .failed {
                    print("[AudioPipeline] ⚠️ Reader failed: \(reader.error?.localizedDescription ?? "unknown")")
                    continue
                }
                
                let segmentDuration = Double(segmentSamples) / sampleRate
                currentTime = CMTimeAdd(currentTime, CMTime(seconds: segmentDuration, preferredTimescale: Int32(sampleRate)))
                
                print("[AudioPipeline] ✅ Read \(segmentSamples) samples (\(segmentDuration)s)")
                
            } catch {
                print("[AudioPipeline] ⚠️ Failed to read segment: \(error.localizedDescription)")
                continue
            }
        }
        
        guard totalSamples > 0 else {
            try? FileManager.default.removeItem(at: out)
            throw AudioPipelineError.exportFailed("لم يتم قراءة أي عينات صوتية")
        }
        
        print("[AudioPipeline] Total samples: \(totalSamples), duration: \(CMTimeGetSeconds(currentTime))s")
        
        writerInput.markAsFinished()
        await writer.finishWriting()
        
        guard writer.status == .completed else {
            try? FileManager.default.removeItem(at: out)
            throw AudioPipelineError.writerFailed(writer.error?.localizedDescription ?? "فشل")
        }
        
        let fileSize = (try? out.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
        print("[AudioPipeline] ✅ SUCCESS! Output: \(fileSize / 1024) KB")
        
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
