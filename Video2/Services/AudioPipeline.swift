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
        if let key = CloudConvertService.apiKey() {
            print("[AudioPipeline] ✅ Found CloudConvert key")
            return key
        }
        print("[AudioPipeline] ❌ No CloudConvert key")
        return nil
    }
    
    /// يتحقق من توفر مفتاح CloudConvert
    private static var hasCloudConvertKey: Bool {
        cloudConvertKey() != nil
    }

    /// يجلب مفتاح ffmpeg-api.com من Keychain (أولوية) أو Info.plist — مزود سحابي
    /// احتياطي إضافي لتحويل HLS إلى MP4 عندما تنفد حصة CloudConvert المجانية.
    private static func ffmpegApiKey() -> String? {
        print("[AudioPipeline] ═══ Checking ffmpeg-api key...")
        if let key = KeychainStore.get("ffmpegapi"), !key.isEmpty {
            print("[AudioPipeline] ✅ Found ffmpeg-api key in Keychain")
            return key
        }
        if let key = Bundle.main.infoDictionary?["FFMPEG_API_KEY"] as? String,
           !key.isEmpty, !key.contains("ضع") {
            print("[AudioPipeline] ✅ Found ffmpeg-api key in Info.plist")
            return key
        }
        print("[AudioPipeline] ❌ No ffmpeg-api key")
        return nil
    }
    
    /// دمج .ts segments في ملف واحد. لا نقرأ الفيديو كله في الذاكرة، ولا نطبع
    /// سطراً لكل segment لأن تسجيل آلاف السطور كان يبطئ التحويل الطويل نفسه.
    private static func mergeTS(segments: [String], baseFolder: URL) async throws -> URL {
        print("[AudioPipeline] Merging \(segments.count) TS segments…")
        let out = FileManager.default.temporaryDirectory
            .appendingPathComponent("merge-\(UUID().uuidString).ts")
        guard FileManager.default.createFile(atPath: out.path, contents: nil) else {
            throw AudioPipelineError.exportFailed("تعذر إنشاء ملف مؤقت لدمج HLS")
        }

        do {
            let handle = try FileHandle(forWritingTo: out)
            defer { try? handle.close() }
            var totalBytes = 0

            for (index, segmentPath) in segments.enumerated() {
                if Task.isCancelled {
                    throw CancellationError()
                }
                if index == 0 || index + 1 == segments.count || (index + 1).isMultiple(of: 100) {
                    print("[AudioPipeline] HLS merge \(index + 1)/\(segments.count)")
                }

                let data: Data
                if segmentPath.hasPrefix("http://") || segmentPath.hasPrefix("https://") {
                    guard let remote = URL(string: segmentPath) else {
                        throw AudioPipelineError.exportFailed("رابط HLS غير صالح: \(segmentPath)")
                    }
                    let (downloaded, response) = try await URLSession.shared.data(from: remote)
                    guard let http = response as? HTTPURLResponse,
                          (200...299).contains(http.statusCode) else {
                        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
                        throw AudioPipelineError.exportFailed("تعذر تنزيل جزء HLS — HTTP \(status)")
                    }
                    data = downloaded
                } else {
                    let local = segmentPath.hasPrefix("/")
                        ? URL(fileURLWithPath: segmentPath)
                        : baseFolder.appendingPathComponent(segmentPath)
                    guard FileManager.default.fileExists(atPath: local.path) else {
                        throw AudioPipelineError.exportFailed("جزء HLS مفقود: \(local.lastPathComponent)")
                    }
                    data = try Data(contentsOf: local, options: [.mappedIfSafe])
                }
                guard !data.isEmpty else {
                    throw AudioPipelineError.exportFailed("جزء HLS فارغ: \(segmentPath)")
                }
                try handle.write(contentsOf: data)
                totalBytes += data.count
            }

            guard totalBytes > 0 else {
                throw AudioPipelineError.exportFailed("لم يتم دمج أي بيانات من HLS")
            }
            print("[AudioPipeline] HLS merge complete: \(totalBytes / 1024 / 1024) MB")
            return out
        } catch {
            try? FileManager.default.removeItem(at: out)
            throw error
        }
    }
    
    /// تحويل HLS عبر CloudConvert بعد إصلاح 403 (User-Agent + نطاقات بديلة + صلاحيات).
    private static func convertWithCloudConvert(m3u8URL: URL, outputFormat: String = "mp4") async throws -> URL {
        try await CloudConvertService.shared.convertHLS(m3u8URL: m3u8URL, outputFormat: outputFormat)
    }

    /// تحويل ملف وسائط محلي (TS أو MP4 مدمج) إلى MP4 عبر ffmpeg-api.com — مزود سحابي
    /// احتياطي يعمل كمستقل عن CloudConvert. يتطلّب مفتاحاً من https://ffmpeg-api.com
    private static func convertWithFFmpegAPI(fileURL: URL, apiKey: String) async throws -> URL {
        print("[ffmpeg-api] ═══════════════════════════════════════════")
        print("[ffmpeg-api] Starting file → MP4")
        print("[ffmpeg-api] File: \(fileURL.lastPathComponent) (\((try? fileURL.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0) bytes)")

        let base = "https://api.ffmpeg-api.com"
        let auth = ["Authorization": "Basic \(apiKey)"]

        // 1) احصل على رابط رفع
        let fileBody = try JSONSerialization.data(withJSONObject: ["file_name": fileURL.lastPathComponent])
        let (fileData, _) = try await HTTP.request("POST", "\(base)/file", headers: auth, body: fileBody, timeout: 60)
        let fileJson = HTTP.json(from: fileData)
        guard let fileObj = fileJson["file"] as? [String: Any],
              let filePath = fileObj["file_path"] as? String,
              let uploadObj = fileJson["upload"] as? [String: Any],
              let uploadURL = uploadObj["url"] as? String else {
            print("[ffmpeg-api] ❌ Unexpected file response: \(fileJson)")
            throw AudioPipelineError.exportFailed("ffmpeg-api: استجابة غير متوقعة عند طلب رابط الرفع")
        }
        print("[ffmpeg-api] ✅ Got upload URL")

        // 2) ارفع الملف (PUT — نقرأه من القرص مباشرةً بلا تحميله كاملًا في الذاكرة)
        guard let uploadU = URL(string: uploadURL) else {
            throw AudioPipelineError.exportFailed("ffmpeg-api: رابط رفع غير صالح")
        }
        var uploadReq = URLRequest(url: uploadU)
        uploadReq.httpMethod = "PUT"
        uploadReq.timeoutInterval = 3600
        let (_, uploadResp) = try await URLSession.shared.upload(for: uploadReq, fromFile: fileURL)
        if let uhr = uploadResp as? HTTPURLResponse, !(200..<300).contains(uhr.statusCode) {
            throw AudioPipelineError.exportFailed("ffmpeg-api: فشل رفع الملف — HTTP \(uhr.statusCode)")
        }
        print("[ffmpeg-api] ✅ Uploaded")

        // 3) ابدأ المعالجة — نعيد التغليف (copy) حتى بدون إعادة ترميز مكلفة
        let taskBody = try JSONSerialization.data(withJSONObject: [
            "task": [
                "inputs": [["file_path": filePath]],
                "outputs": [["file": "output.mp4", "options": ["-c:v", "copy", "-c:a", "copy", "-movflags", "+faststart"]]]
            ]
        ])
        let (procData, _) = try await HTTP.request("POST", "\(base)/ffmpeg/process", headers: auth, body: taskBody, timeout: 300)
        let procJson = HTTP.json(from: procData)
        guard let results = procJson["result"] as? [[String: Any]],
              let first = results.first,
              let dlURL = first["download_url"] as? String else {
            let err = procJson["error"] as? String ?? "unknown"
            print("[ffmpeg-api] ❌ Process failed: \(err)")
            throw AudioPipelineError.exportFailed("ffmpeg-api: فشل التحويل — \(err)")
        }
        print("[ffmpeg-api] ✅ Processing done")

        // 4) نزّل النتيجة إلى القرص مباشرةً؛ هذا مسار احتياطي وقد تكون النتيجة MP4 كبيرة.
        guard let resultURL = URL(string: dlURL) else {
            throw AudioPipelineError.exportFailed("ffmpeg-api: رابط تنزيل غير صالح")
        }
        var downloadRequest = URLRequest(url: resultURL)
        downloadRequest.timeoutInterval = 600
        let (temporaryResult, downloadResponse) = try await URLSession.shared.download(for: downloadRequest)
        guard let downloadHTTP = downloadResponse as? HTTPURLResponse,
              (200...299).contains(downloadHTTP.statusCode) else {
            let status = (downloadResponse as? HTTPURLResponse)?.statusCode ?? 0
            throw AudioPipelineError.exportFailed("ffmpeg-api: فشل تنزيل النتيجة — HTTP \(status)")
        }
        let out = FileManager.default.temporaryDirectory.appendingPathComponent("ffmpeg-api-\(UUID().uuidString).mp4")
        try? FileManager.default.removeItem(at: out)
        try FileManager.default.moveItem(at: temporaryResult, to: out)
        let sz = (try? out.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
        print("[ffmpeg-api] ✅ Downloaded \(sz / 1024 / 1024) MB")
        return out
    }

    // MARK: ═══════════════════════════════════════════════════════════
    // MARK: تحويل HLS إلى وسائط — صوت محلي ثم بدائل MP4
    // ═════════════════════════════════════════════════════════════════

    /// يقتطع URI المُهيّئ (EXT-X-MAP) من قائمة m3u8 — ضروري لـ fMP4/CMAF.
    private static func hlsInitURI(from content: String) -> String? {
        for line in content.components(separatedBy: .newlines) {
            let t = line.trimmingCharacters(in: .whitespaces)
            guard t.hasPrefix("#EXT-X-MAP:") else { continue }
            if let start = t.range(of: "URI=\"") {
                let rest = t[start.upperBound...]
                if let end = rest.firstIndex(of: "\"") {
                    return String(rest[..<end])
                }
            }
        }
        return nil
    }

    /// محاولة سريعة قبل أي رفع سحابي: نخدم HLS المحلي عبر localhost ثم نستخدم
    /// AVFoundation لتصدير الصوت. لا نشترط isExportable — بعض قوائم HLS
    /// playable لكن التقرير يكون false ومع ذلك ينجح التصدير.
    private static func exportLocalHLSAudio(_ playlist: URL) async throws -> URL {
        let servedPlaylist = try LocalFileServer.shared.hlsURL(forPlaylist: playlist)
        let asset = AVURLAsset(url: servedPlaylist, options: [
            AVURLAssetPreferPreciseDurationAndTimingKey: true
        ])
        _ = try? await asset.load(.isPlayable)
        let audioTracks = (try? await asset.loadTracks(withMediaType: .audio)) ?? []
        guard !audioTracks.isEmpty else {
            throw AudioPipelineError.noAudioTrack
        }

        let output = FileManager.default.temporaryDirectory
            .appendingPathComponent("hls-local-audio-\(UUID().uuidString).m4a")
        let presets = [AVAssetExportPresetAppleM4A,
                       AVAssetExportPresetHighestQuality,
                       AVAssetExportPresetMediumQuality,
                       AVAssetExportPresetPassthrough]
        var lastError = "تعذر تصدير صوت HLS محلياً"
        for preset in presets {
            try? FileManager.default.removeItem(at: output)
            guard let session = AVAssetExportSession(asset: asset, presetName: preset) else { continue }
            let fileType: AVFileType = session.supportedFileTypes.contains(.m4a) ? .m4a
                : (session.supportedFileTypes.contains(.mp4) ? .mp4 : (session.supportedFileTypes.first ?? .m4a))
            session.outputURL = output
            session.outputFileType = fileType
            session.shouldOptimizeForNetworkUse = true
            print("[AudioPipeline] Local HLS export preset=\(preset) type=\(fileType.rawValue)")
            await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
                session.exportAsynchronously { continuation.resume() }
            }
            if session.status == .completed,
               ((try? output.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0) > 0 {
                return output
            }
            lastError = session.error?.localizedDescription ?? lastError
        }
        try? FileManager.default.removeItem(at: output)
        throw AudioPipelineError.exportFailed(lastError)
    }

    private static func exportHLSToTempMedia(_ url: URL, outputFormat: String) async throws -> URL {
        print("[AudioPipeline] ═══════════════════════════════════════")
        print("[AudioPipeline] Starting HLS → \(outputFormat.uppercased()) conversion")
        print("[AudioPipeline] URL: \(url.path)")
        
        guard FileManager.default.fileExists(atPath: url.path) else {
            print("[AudioPipeline] ❌ File not found: \(url.path)")
            throw AudioPipelineError.exportFailed("ملف HLS غير موجود: \(url.lastPathComponent)")
        }
        
        print("[AudioPipeline] ✅ File exists")
        print("[AudioPipeline] m3u8: \(url.lastPathComponent)")

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

        let m3u8Folder = url.deletingLastPathComponent()
        let upper = content.uppercased()
        let isEncrypted = upper.contains("EXT-X-KEY:METHOD=AES-128") ||
            upper.contains("EXT-X-KEY:METHOD=SAMPLE-AES") ||
            upper.contains("EXT-X-KEY:METHOD=SAMPLE-AES-CTR")

        // يحدّد نوع الحاوية: fMP4/CMAF (init.mp4 + m4s) أم MPEG-TS (.ts).
        // AVFoundation لا يستطيع قراءة .ts محلياً، بينما يقرأ fMP4 بسهولة.
        let isFMP4 = hlsInitURI(from: content) != nil ||
            segmentPaths.contains { $0.lowercased().hasSuffix(".m4s") } ||
            segmentPaths.contains { $0.lowercased().hasSuffix(".mp4") }

        // HLS مشفّر AES-128: لا يمكن فكّه محلياً بدون المفتاح — نعتمد على CloudConvert
        // قدر الإمكان، ونحرّر err واضح بدل "ALL METHODS FAILED".
        if isEncrypted {
            print("[AudioPipeline] ⚠️ Detected AES-128-encrypted HLS")
        }

        if isFMP4 && !isEncrypted {
            print("[AudioPipeline] ═══ Detected fMP4/CMAF HLS → embedding (method 1)...")
            do {
                let result = try await tryFMP4Method(segments: segmentPaths, initURI: hlsInitURI(from: content), folder: m3u8Folder)
                print("[AudioPipeline] ✅ fMP4 embed succeeded!")
                return result
            } catch {
                print("[AudioPipeline] ⚠️ fMP4 embed failed: \(error.localizedDescription) — falling through")
            }
        }

        // MPEG-TS: استخراج AAC محلياً أولاً — لا يحتاج سحابة ولا مفتاح، ويفك AES-128
        // إن وُجد key.bin. هذا هو الحل النهائي لمعظم فيديوهات HLS عند الترجمة.
        if outputFormat.lowercased() == "m4a" || outputFormat.lowercased() == "aac" {
            print("[AudioPipeline] ═══ Trying local MPEG-TS AAC extract...")
            do {
                let result = try await MPEGTSDemuxer.extractAudio(fromPlaylist: url)
                guard await isReadableAudio(result) else {
                    try? FileManager.default.removeItem(at: result)
                    throw AudioPipelineError.exportFailed("ملف AAC المستخرج غير قابل للقراءة")
                }
                print("[AudioPipeline] ✅ Local MPEG-TS extract succeeded!")
                return result
            } catch {
                print("[AudioPipeline] ⚠️ Local MPEG-TS extract failed: \(error.localizedDescription)")
            }
        }

        // MPEG-TS (أو HLS مشفّر بلا مفتاح): نجرّب السحابة كبدائل لبعض.
        var cloudErrors: [String] = []

        if hasCloudConvertKey {
            print("[AudioPipeline] ═══ Trying CloudConvert API (MPEG-TS)...")
            do {
                let result = try await convertWithCloudConvert(m3u8URL: url, outputFormat: outputFormat)
                print("[AudioPipeline] ✅ CloudConvert succeeded!")
                return result
            } catch {
                cloudErrors.append("CloudConvert: \(error.localizedDescription)")
                print("[AudioPipeline] ⚠️ CloudConvert failed: \(error.localizedDescription)")
            }
        } else {
            cloudErrors.append("CloudConvert: لا يوجد مفتاح (المفتاح محفوظ في الإعدادات)")
            print("[AudioPipeline] ⚠️ CloudConvert not available (no API key) — TS يحتاج ffmpeg")
        }

        if let ffmpegKey = ffmpegApiKey() {
            print("[AudioPipeline] ═══ Trying ffmpeg-api.com (backup)...")
            do {
                let merged = try await mergeTS(segments: segmentPaths, baseFolder: m3u8Folder)
                defer { try? FileManager.default.removeItem(at: merged) }
                let result = try await convertWithFFmpegAPI(fileURL: merged, apiKey: ffmpegKey)
                print("[AudioPipeline] ✅ ffmpeg-api succeeded!")
                return result
            } catch {
                cloudErrors.append("ffmpeg-api: \(error.localizedDescription)")
                print("[AudioPipeline] ⚠️ ffmpeg-api failed: \(error.localizedDescription)")
            }
        } else {
            cloudErrors.append("ffmpeg-api: لا يوجد مفتاح")
            print("[AudioPipeline] ⚠️ ffmpeg-api not available (no API key)")
        }

        if ConvertAPIService.isAvailable {
            print("[AudioPipeline] ═══ Trying ConvertAPI (third cloud backend)...")
            do {
                let merged = try await mergeTS(segments: segmentPaths, baseFolder: m3u8Folder)
                defer { try? FileManager.default.removeItem(at: merged) }
                let result = try await ConvertAPIService.shared.convert(inputFile: merged, outputFormat: outputFormat)
                print("[AudioPipeline] ✅ ConvertAPI succeeded!")
                return result
            } catch {
                cloudErrors.append("ConvertAPI: \(error.localizedDescription)")
                print("[AudioPipeline] ⚠️ ConvertAPI failed: \(error.localizedDescription)")
            }
        } else {
            cloudErrors.append("ConvertAPI: لا يوجد مفتاح")
            print("[AudioPipeline] ⚠️ ConvertAPI not available (no API key)")
        }

        // المحاولة الأخيرة محلياً: ندمج TS ونحاول قراءته (غالباً يفشل لأن AVFoundation
        // لا يقرأ .ts، لكن نجرّب قبل الإقلاع).
        print("[AudioPipeline] ═══ Trying native merge (method 2)...")
        do {
            let merged = try await mergeTS(segments: segmentPaths, baseFolder: m3u8Folder)
            defer { try? FileManager.default.removeItem(at: merged) }
            let result = try await transcodeFileToMP4(merged)
            print("[AudioPipeline] ✅ Native merge succeeded!")
            return result
        } catch {
            print("[AudioPipeline] ⚠️ Native merge failed: \(error.localizedDescription)")
        }

        print("[AudioPipeline] ❌ ALL METHODS FAILED")
        if isEncrypted {
            throw AudioPipelineError.exportFailed("هذا البث HLS مشفّر بـ AES-128 ولا يمكن فكّه محلياً بدون مفتاح فكّ التشفير — جرّب رابطاً غير مشفّر.")
        }
        if !hasCloudConvertKey && ffmpegApiKey() == nil && !ConvertAPIService.isAvailable {
            throw AudioPipelineError.exportFailed("تعذّر التحويل المحلي لهذا البث. احفظ مفتاح CloudConvert أو ConvertAPI أو ffmpeg-api من الإعدادات، أو بدّل الشبكة إن ظهر 403.")
        }
        if !cloudErrors.isEmpty {
            throw AudioPipelineError.exportFailed("تعذّر التحويل عبر خدمات السحابة:\n" + cloudErrors.joined(separator: "\n"))
        }
        throw AudioPipelineError.exportFailed("تعذّر تحويل HLS إلى \(outputFormat.uppercased()): لا توجد خدمة تحويل متاحة.")
    }

    /// تحويل كامل إلى MP4 — يستخدم عند التصدير أو التحويل اليدوي فقط.
    static func exportHLSToTempMP4(_ url: URL) async throws -> URL {
        try await exportHLSToTempMedia(url, outputFormat: "mp4")
    }

    /// مسار الترجمة: نطلب الصوت فقط من السحابة لتجنب تنزيل فيديو محوّل كامل.
    private static func exportHLSToTempAudio(_ url: URL) async throws -> URL {
        try await exportHLSToTempMedia(url, outputFormat: "m4a")
    }

    // MARK: ── الطريقة 1 (fMP4/CMAF): دمج init.mp4 + m4s ثم إعادة تصدير ──

    /// HLS من نوع fMP4/CMAF: نلحم `init.mp4` (moov) مع كل `segment*.m4s` (moof+mdat)
    /// في ملف MP4 واحد (معطَّل التجزئة) ثم نمرّره على AVAssetExportSession.
    /// هذا يتجاوز مشكلة أن AVFoundation يرفض قراءة قائمة `.m3u8` المحلية مباشرةً.
    private static func tryFMP4Method(segments: [String], initURI: String?, folder: URL) async throws -> URL {
        print("[AudioPipeline] ═══ fMP4/CMAF: concatenating init.mp4 + segments...")

        var initURL: URL?
        if let initURI = initURI {
            let candidate: URL = initURI.hasPrefix("/")
                ? URL(fileURLWithPath: initURI)
                : folder.appendingPathComponent(initURI)
            if FileManager.default.fileExists(atPath: candidate.path) {
                initURL = candidate
            }
        }
        if initURL == nil {
            let fallback = folder.appendingPathComponent("init.mp4")
            if FileManager.default.fileExists(atPath: fallback.path) {
                initURL = fallback
            }
        }

        let out = FileManager.default.temporaryDirectory.appendingPathComponent("v2-fmp4-\(UUID().uuidString).mp4")
        guard FileManager.default.createFile(atPath: out.path, contents: nil) else {
            throw AudioPipelineError.exportFailed("تعذر إنشاء ملف مؤقت")
        }
        let fh = try FileHandle(forWritingTo: out)
        defer { try? fh.close() }

        var totalBytes = 0
        if let initURL = initURL, let data = try? Data(contentsOf: initURL) {
            fh.write(data)
            totalBytes += data.count
            print("[AudioPipeline] ✅ Init: \(initURL.lastPathComponent) (\(data.count / 1024) KB)")
        } else {
            print("[AudioPipeline] ⚠️ No init.mp4 found — writing segments only")
        }

        var added = 0
        for seg in segments {
            let segURL: URL = seg.hasPrefix("/")
                ? URL(fileURLWithPath: seg)
                : folder.appendingPathComponent(seg)
            guard FileManager.default.fileExists(atPath: segURL.path) else {
                print("[AudioPipeline]   ❌ Not found: \(segURL.lastPathComponent)")
                continue
            }
            guard let data = try? Data(contentsOf: segURL) else { continue }
            fh.write(data)
            totalBytes += data.count
            added += 1
            print("[AudioPipeline]   ✅ \(segURL.lastPathComponent) (\(data.count / 1024) KB)")
        }

        try? fh.close()
        guard added > 0, totalBytes > 0 else {
            try? FileManager.default.removeItem(at: out)
            throw AudioPipelineError.exportFailed("لم يتم لحم أي segment")
        }
        print("[AudioPipeline] ✅ Concatenated \(added) segments (\(totalBytes / 1024 / 1024) MB)")

        // أي وظيفة تصدير/فكّ التجزئة، مع حذف الملف الملمّح بعد انتهاء التصدير.
        do {
            let result = try await transcodeFileToMP4(out)
            try? FileManager.default.removeItem(at: out)
            return result
        } catch {
            try? FileManager.default.removeItem(at: out)
            throw error
        }
    }

    /// يمرّر ملف وسائط (يفضَّل MP4 ملمّح أو مدمج) عبر AVAssetExportSession
    /// ليخرج MP4 نظيفاً — يجرّب Passthrough أولاً ثم إعادة ترميز عالية الجودة.
    private static func transcodeFileToMP4(_ input: URL) async throws -> URL {
        let asset = AVURLAsset(url: input)

        // بعض ملفات fMP4 الملمّحة تُبلّغ مدة 0 حتى تُحمَّل — لا نستمر على المدة،
        // بل نكتفي بوجود مسار صوت/فيديو واحد على الأقل.
        let hasVideo = (try? await asset.loadTracks(withMediaType: .video))?.isEmpty == false
        let hasAudio = (try? await asset.loadTracks(withMediaType: .audio))?.isEmpty == false
        guard hasVideo || hasAudio else {
            throw AudioPipelineError.exportFailed("الملف لا يحتوي مسارات وسائط قابلة للقراءة")
        }

        let out = FileManager.default.temporaryDirectory.appendingPathComponent("v2-tx-\(UUID().uuidString).mp4")
        try? FileManager.default.removeItem(at: out)

        let presets = [AVAssetExportPresetPassthrough, AVAssetExportPresetHighestQuality,
                       AVAssetExportPresetMediumQuality, AVAssetExportPresetLowQuality]

        for preset in presets {
            let compatible = AVAssetExportSession.exportPresets(compatibleWith: asset)
            guard compatible.contains(preset) else { continue }
            guard let session = AVAssetExportSession(asset: asset, presetName: preset) else { continue }
            guard session.supportedFileTypes.contains(.mp4) else { continue }

            try? FileManager.default.removeItem(at: out)
            session.outputURL = out
            session.outputFileType = .mp4
            session.shouldOptimizeForNetworkUse = true

            print("[AudioPipeline] Exporting with preset: \(preset)")
            await withCheckedContinuation { (c: CheckedContinuation<Void, Never>) in
                session.exportAsynchronously { c.resume() }
            }

            if session.status == .completed {
                let sz = (try? out.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
                if sz > 0 {
                    print("[AudioPipeline] ✅ Export succeeded (\(sz / 1024 / 1024) MB) preset=\(preset)")
                    return out
                }
            }
            print("[AudioPipeline] ❌ Export failed: \(session.error?.localizedDescription ?? "unknown")")
        }

        throw AudioPipelineError.exportFailed("فشل التصدير إلى MP4")
    }

    // MARK: ── الطريقة 2: AVMutableComposition ──

    private static func tryCompositionMethod(segments: [String], folder: URL) async throws -> URL {
        print("[AudioPipeline] Creating AVMutableComposition...")
        let composition = AVMutableComposition()
        // نستخدم مساراً واحداً لكل نوع (فيديو / صوت) بدلاً من إنشاء مسار لكل segment
        // — إنشاء مسار جديد لكل segment يجعل التصدير يفشل.
        var videoTrack: AVMutableCompositionTrack?
        var audioTrack: AVMutableCompositionTrack?
        var currentTime = CMTime.zero
        var addedCount = 0

        for (index, segPath) in segments.enumerated() {
            print("[AudioPipeline] Processing segment \(index + 1)/\(segments.count)")
            
            if Task.isCancelled { throw CancellationError() }
            
            let segURL: URL
            if segPath.hasPrefix("/") { 
                segURL = URL(fileURLWithPath: segPath) 
            } else { 
                segURL = folder.appendingPathComponent(segPath) 
            }
            
            guard FileManager.default.fileExists(atPath: segURL.path) else {
                print("[AudioPipeline]   ❌ Not found: \(segURL.lastPathComponent)")
                continue
            }
            
            print("[AudioPipeline]   ✅ Found: \(segURL.lastPathComponent)")
            
            let segAsset = AVURLAsset(url: segURL)
            
            do {
                let duration = try await segAsset.load(.duration)
                let durationSec = CMTimeGetSeconds(duration)
                print("[AudioPipeline]   Duration: \(durationSec)s")
                
                guard durationSec > 0 else { 
                    print("[AudioPipeline]   ❌ Invalid duration")
                    continue 
                }

                let videoTracks = try await segAsset.loadTracks(withMediaType: .video)
                print("[AudioPipeline]   Video tracks: \(videoTracks.count)")
                
                for srcTrack in videoTracks {
                    if videoTrack == nil {
                        videoTrack = composition.addMutableTrack(withMediaType: .video, preferredTrackID: kCMPersistentTrackID_Invalid)
                        print("[AudioPipeline]   ✅ Created video track")
                    }
                    let tr = try await srcTrack.load(.timeRange)
                    try videoTrack?.insertTimeRange(tr, of: srcTrack, at: currentTime)
                }
                
                let audioTracks = try await segAsset.loadTracks(withMediaType: .audio)
                print("[AudioPipeline]   Audio tracks: \(audioTracks.count)")
                
                for srcTrack in audioTracks {
                    if audioTrack == nil {
                        audioTrack = composition.addMutableTrack(withMediaType: .audio, preferredTrackID: kCMPersistentTrackID_Invalid)
                        print("[AudioPipeline]   ✅ Created audio track")
                    }
                    let tr = try await srcTrack.load(.timeRange)
                    try audioTrack?.insertTimeRange(tr, of: srcTrack, at: currentTime)
                }
                
                currentTime = CMTimeAdd(currentTime, duration)
                addedCount += 1
                print("[AudioPipeline]   ✅ Segment added successfully")
                
            } catch {
                print("[AudioPipeline]   ❌ Error: \(error.localizedDescription)")
                continue
            }
        }
        
        guard addedCount > 0 else { 
            print("[AudioPipeline] ❌ No segments added")
            throw AudioPipelineError.exportFailed("لم يتم إضافة أي segment") 
        }
        
        print("[AudioPipeline] ✅ Composition created: \(addedCount) segments")

        let out = FileManager.default.temporaryDirectory.appendingPathComponent("v2-comp-\(UUID().uuidString).mp4")
        try? FileManager.default.removeItem(at: out)
        
        print("[AudioPipeline] Exporting composition...")

        for preset in [AVAssetExportPresetPassthrough, AVAssetExportPresetHighestQuality, AVAssetExportPresetMediumQuality] {
            print("[AudioPipeline] Trying preset: \(preset)")
            
            let compatible = AVAssetExportSession.exportPresets(compatibleWith: composition)
            
            guard compatible.contains(preset) else { 
                print("[AudioPipeline]   ❌ Not compatible")
                continue 
            }
            
            guard let session = AVAssetExportSession(asset: composition, presetName: preset) else { 
                print("[AudioPipeline]   ❌ Failed to create session")
                continue 
            }
            
            guard session.supportedFileTypes.contains(.mp4) else { 
                print("[AudioPipeline]   ❌ MP4 not supported")
                continue 
            }
            
            session.outputURL = out
            session.outputFileType = .mp4
            session.shouldOptimizeForNetworkUse = true
            
            print("[AudioPipeline]   Exporting...")
            
            await withCheckedContinuation { (c: CheckedContinuation<Void, Never>) in
                session.exportAsynchronously { c.resume() }
            }
            
            if session.status == .completed {
                let sz = (try? out.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
                if sz > 0 { 
                    print("[AudioPipeline]   ✅ Export succeeded: \(sz / 1024 / 1024) MB")
                    return out 
                }
            }
            
            print("[AudioPipeline]   ❌ Export failed: \(session.error?.localizedDescription ?? "unknown")")
            try? FileManager.default.removeItem(at: out)
        }
        
        print("[AudioPipeline] ❌ All presets failed")
        throw AudioPipelineError.exportFailed("فشل تصدير composition")
    }

    // MARK: ── الطريقة 3: segment-by-segment ──

    private static func trySegmentBySegmentMethod(segments: [String], folder: URL) async throws -> URL {
        print("[AudioPipeline] Creating segment-by-segment output...")
        
        let out = FileManager.default.temporaryDirectory.appendingPathComponent("v2-seg-\(UUID().uuidString).m4a")
        try? FileManager.default.removeItem(at: out)
        
        print("[AudioPipeline] Output: \(out.lastPathComponent)")
        
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
            print("[AudioPipeline] ❌ Cannot add writer input")
            throw AudioPipelineError.writerFailed("لا يمكن إضافة writer input") 
        }
        
        writer.add(writerInput)
        writer.shouldOptimizeForNetworkUse = true
        
        guard writer.startWriting() else { 
            print("[AudioPipeline] ❌ Failed to start writing")
            throw AudioPipelineError.writerFailed(writer.error?.localizedDescription ?? "فشل") 
        }
        
        print("[AudioPipeline] ✅ Writer started")

        var currentTime = CMTime.zero
        var totalSamples = 0

        for (index, segPath) in segments.enumerated() {
            print("[AudioPipeline] Reading segment \(index + 1)/\(segments.count)")
            
            if Task.isCancelled { 
                try? FileManager.default.removeItem(at: out)
                throw CancellationError() 
            }
            
            let segURL: URL
            if segPath.hasPrefix("/") { 
                segURL = URL(fileURLWithPath: segPath) 
            } else { 
                segURL = folder.appendingPathComponent(segPath) 
            }
            
            guard FileManager.default.fileExists(atPath: segURL.path) else { 
                print("[AudioPipeline]   ❌ Not found")
                continue 
            }
            
            let segAsset = AVURLAsset(url: segURL)
            
            do {
                let duration = try await segAsset.load(.duration)
                let durationSec = CMTimeGetSeconds(duration)
                
                guard durationSec > 0 else { 
                    print("[AudioPipeline]   ❌ Invalid duration")
                    continue 
                }
                
                let audioTracks = try await segAsset.loadTracks(withMediaType: .audio)
                guard let audioTrack = audioTracks.first else { 
                    print("[AudioPipeline]   ❌ No audio track")
                    continue 
                }
                
                print("[AudioPipeline]   Creating reader...")
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
                    print("[AudioPipeline]   ❌ Failed to start reading")
                    continue 
                }
                
                writer.startSession(atSourceTime: currentTime)
                
                var segSamples = 0
                
                while let sb = readerOutput.copyNextSampleBuffer() {
                    while !writerInput.isReadyForMoreMediaData { 
                        try await Task.sleep(nanoseconds: 5_000_000) 
                    }
                    
                    if !writerInput.append(sb) { 
                        print("[AudioPipeline]   ❌ Failed to append sample")
                        break 
                    }
                    
                    segSamples += 1
                    totalSamples += 1
                }
                
                if reader.status == .failed { 
                    print("[AudioPipeline]   ❌ Reader failed")
                    continue 
                }
                
                let segDur = Double(segSamples) / sampleRate
                currentTime = CMTimeAdd(currentTime, CMTime(seconds: segDur, preferredTimescale: Int32(sampleRate)))
                print("[AudioPipeline]   ✅ \(segSamples) samples")
                
            } catch {
                print("[AudioPipeline]   ❌ Error: \(error.localizedDescription)")
                continue
            }
        }

        guard totalSamples > 0 else {
            print("[AudioPipeline] ❌ No samples read")
            try? FileManager.default.removeItem(at: out)
            throw AudioPipelineError.exportFailed("لم يتم قراءة أي عينات صوتية")
        }
        
        print("[AudioPipeline] Finishing writer...")
        writerInput.markAsFinished()
        await writer.finishWriting()
        
        guard writer.status == .completed else {
            print("[AudioPipeline] ❌ Writer failed")
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

    /// نفس بوابة التحويل: قائمة HLS محلية (`file://`) لا يقرأها AVFoundation.
    private static func isHLSPlaylist(_ url: URL) -> Bool {
        let ext = url.pathExtension.lowercased()
        if ext == "m3u8" || ext == "m3u" { return true }
        guard let head = try? String(contentsOf: url, encoding: .utf8) else { return false }
        return head.trimmingCharacters(in: .whitespacesAndNewlines).hasPrefix("#EXTM3U")
    }

    private static func persistHLSSource(_ temporary: URL, into dir: URL) throws -> URL {
        let ext: String
        switch temporary.pathExtension.lowercased() {
        case "m4a": ext = "m4a"
        case "aac": ext = "aac"
        default: ext = "mp4"
        }
        let destination = dir.appendingPathComponent("hls-source.\(ext)")
        if temporary.standardizedFileURL == destination.standardizedFileURL {
            return destination
        }
        try? FileManager.default.removeItem(at: destination)
        do { try FileManager.default.moveItem(at: temporary, to: destination) }
        catch { try FileManager.default.copyItem(at: temporary, to: destination) }
        guard FileManager.default.fileExists(atPath: destination.path) else {
            throw AudioPipelineError.exportFailed("فقد ملف HLS الصوتي المحوّل")
        }
        return destination
    }

    /// مسار الترجمة لـ HLS يجب أن يطابق التحويل: HTTP محلي + AVFoundation أولاً،
    /// ثم استخراج MPEG-TS، ثم السحابة. لا نحتفظ بنسخة مخبأة غير قابلة للقراءة.
    private static func resolveHLSAudioSource(playlist: URL,
                                             into dir: URL,
                                             progress: @escaping (Double) -> Void) async throws -> URL {
        let candidates = [
            dir.appendingPathComponent("hls-source.m4a"),
            dir.appendingPathComponent("hls-source.aac"),
            dir.appendingPathComponent("hls-source.mp4")
        ]
        for candidate in candidates {
            guard FileManager.default.fileExists(atPath: candidate.path) else { continue }
            if await isReadableAudio(candidate) {
                print("[AudioPipeline] Using cached HLS audio \(candidate.lastPathComponent)")
                return candidate
            }
            print("[AudioPipeline] Discarding unreadable HLS cache \(candidate.lastPathComponent)")
            try? FileManager.default.removeItem(at: candidate)
        }

        progress(0.02)
        var lastError: Error = AudioPipelineError.exportFailed("تعذّر استخراج صوت HLS")

        do {
            print("[AudioPipeline] ═══ Translation HLS: local HTTP + AVFoundation (same as convert)...")
            let temporary = try await exportLocalHLSAudio(playlist)
            guard await isReadableAudio(temporary) else {
                try? FileManager.default.removeItem(at: temporary)
                throw AudioPipelineError.exportFailed("تصدير AVFoundation أنتج ملفاً بلا مسار صوتي")
            }
            print("[AudioPipeline] ✅ Local HLS audio export succeeded")
            return try persistHLSSource(temporary, into: dir)
        } catch {
            lastError = error
            print("[AudioPipeline] ⚠️ Local HLS AVFoundation failed: \(error.localizedDescription)")
        }

        do {
            print("[AudioPipeline] ═══ Translation HLS: local MPEG-TS AAC extract...")
            let temporary = try await MPEGTSDemuxer.extractAudio(fromPlaylist: playlist)
            guard await isReadableAudio(temporary) else {
                try? FileManager.default.removeItem(at: temporary)
                throw AudioPipelineError.exportFailed("ملف AAC المستخرج غير قابل للقراءة")
            }
            print("[AudioPipeline] ✅ Local MPEG-TS AAC extract succeeded")
            return try persistHLSSource(temporary, into: dir)
        } catch {
            lastError = error
            print("[AudioPipeline] ⚠️ MPEG-TS extract failed: \(error.localizedDescription)")
        }

        do {
            print("[AudioPipeline] ═══ Translation HLS: cloud / remux fallbacks...")
            let temporary = try await exportHLSToTempAudio(playlist)
            guard await isReadableAudio(temporary) else {
                try? FileManager.default.removeItem(at: temporary)
                throw AudioPipelineError.exportFailed("ملف HLS المحوّل غير قابل للقراءة")
            }
            print("[AudioPipeline] ✅ HLS fallback conversion succeeded")
            return try persistHLSSource(temporary, into: dir)
        } catch {
            lastError = error
            print("[AudioPipeline] ⚠️ HLS fallbacks failed: \(error.localizedDescription)")
        }

        throw lastError
    }

    static func extractChunks(from videoURL: URL,
                              into dir: URL,
                              singleFile: Bool,
                              chunkDuration: Double = AudioPipeline.chunkSeconds,
                              isHLS: Bool = false,
                              progress: @escaping (Double) -> Void) async throws -> (chunks: [AudioChunk], duration: Double) {

        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let chunksDir = dir.appendingPathComponent("chunks", isDirectory: true)
        try FileManager.default.createDirectory(at: chunksDir, withIntermediateDirectories: true)
        let manifestURL = dir.appendingPathComponent("chunks.json")

        var sourceURL = videoURL
        // شريط التقدم داخل مرحلة الاستخراج: HLS يحتاج معالجة إضافية قبل AVAssetReader.
        var progressBase: Double = 0
        var progressSpan: Double = 1
        if isHLS || isHLSPlaylist(videoURL) {
            sourceURL = try await resolveHLSAudioSource(playlist: videoURL, into: dir, progress: progress)
            progressBase = 0.4
            progressSpan = 0.6
            progress(progressBase)
        }

        if let data = try? Data(contentsOf: manifestURL),
           let cached = try? JSONDecoder().decode([AudioChunk].self, from: data), !cached.isEmpty {
            let allExist = cached.allSatisfy { FileManager.default.fileExists(atPath: chunksDir.appendingPathComponent($0.fileName).path) }
            // لا تعِد استخدام manifest طويل لمهمة Azure التي تحتاج مقاطع أقل
            // من دقيقة؛ المهام القديمة تُعاد تجزئتها تلقائياً مرة واحدة.
            let cacheFitsRequestedDuration = singleFile || !cached.contains {
                $0.duration > max(1, chunkDuration) + 2
            }
            let durData = try? Data(contentsOf: dir.appendingPathComponent("duration.txt"))
            let dur = durData.flatMap { Double(String(data: $0, encoding: .utf8) ?? "") } ?? 0
            if allExist && cacheFitsRequestedDuration { progress(1); return (cached, dur) }
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

        let effectiveChunkSeconds = singleFile ? 1_000_000_000.0 : max(1.0, chunkDuration)
        var chunks: [AudioChunk] = []
        var chunkIndex = 0
        var chunkStartPTS: Double = 0
        var writer: AVAssetWriter? = nil
        var writerInput: AVAssetWriterInput? = nil
        var lastProgressFrac: Double = 0

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
            // تقدم مستمر (لكل الوضعين: ملف واحد أو مقاطع) — شريط التقدم
            // يتحرك مع الوقت الفعلي بدل الانتظار حتى نهاية الجزء.
            if duration > 0 {
                let frac = min(1.0, max(0.0, pts / duration))
                if frac - lastProgressFrac >= 0.01 {
                    lastProgressFrac = frac
                    progress(progressBase + progressSpan * frac)
                }
            }
            if !singleFile && pts - chunkStartPTS >= effectiveChunkSeconds {
                try await finishWriter()
                chunks.append(AudioChunk(index: chunkIndex, fileName: String(format: "chunk-%03d.m4a", chunkIndex), start: chunkStartPTS, duration: pts - chunkStartPTS))
                chunkIndex += 1; chunkStartPTS = pts
                let (w, i) = try makeWriter(index: chunkIndex)
                w.startWriting(); w.startSession(atSourceTime: CMTime(seconds: pts, preferredTimescale: 600))
                writer = w; writerInput = i
            }
            // حارس ضد التعليق الأبدي: لو فشل writer (امتلاء التخزين/خطأ
            // ترميز) تبقى isReadyForMoreMediaData=false إلى الأبد — نحوّلها
            // إلى خطأ قابل للالتقاط بدل تجميد المهمة في "استخراج الصوت".
            while writerInput?.isReadyForMoreMediaData == false {
                if let w = writer, w.status == .failed {
                    reader.cancelReading()
                    throw AudioPipelineError.writerFailed(w.error?.localizedDescription ?? "تعذر الكتابة إلى ملف الصوت — تحقق من مساحة التخزين")
                }
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

    /// يحوّل مقطع AAC المحلي إلى WAV PCM mono 16 kHz، وهو التنسيق الذي
    /// يتطلبه Azure Speech short-audio REST API. المقطع محدود زمنياً (تستخدم
    /// TranslationManager 50 ثانية) لذلك تبقى الذاكرة المستخدمة صغيرة.
    static func convertToAzureWAV(inputURL: URL, outputURL: URL) async throws {
        let asset = AVURLAsset(url: inputURL)
        let tracks = try await asset.loadTracks(withMediaType: .audio)
        guard let track = tracks.first else { throw AudioPipelineError.noAudioTrack }

        let reader = try AVAssetReader(asset: asset)
        let settings: [String: Any] = [
            AVFormatIDKey: kAudioFormatLinearPCM,
            AVSampleRateKey: sampleRate,
            AVNumberOfChannelsKey: 1,
            AVLinearPCMBitDepthKey: 16,
            AVLinearPCMIsFloatKey: false,
            AVLinearPCMIsBigEndianKey: false,
            AVLinearPCMIsNonInterleaved: false
        ]
        let output = AVAssetReaderTrackOutput(track: track, outputSettings: settings)
        output.alwaysCopiesSampleData = false
        guard reader.canAdd(output) else {
            throw AudioPipelineError.readerFailed("تعذر تجهيز محول WAV")
        }
        reader.add(output)
        guard reader.startReading() else {
            throw AudioPipelineError.readerFailed(reader.error?.localizedDescription ?? "تعذر بدء قراءة الصوت")
        }

        var pcm = Data()
        while let sample = output.copyNextSampleBuffer() {
            guard let block = CMSampleBufferGetDataBuffer(sample) else { continue }
            let length = CMBlockBufferGetDataLength(block)
            guard length > 0 else { continue }
            var bytes = Data(count: length)
            let status = bytes.withUnsafeMutableBytes { rawBuffer -> OSStatus in
                guard let address = rawBuffer.baseAddress else { return -1 }
                return CMBlockBufferCopyDataBytes(block,
                                                  atOffset: 0,
                                                  dataLength: length,
                                                  destination: address)
            }
            guard status == 0 else {
                throw AudioPipelineError.readerFailed("تعذر قراءة عينة PCM (\(status))")
            }
            pcm.append(bytes)
        }
        guard reader.status != .failed, !pcm.isEmpty else {
            throw AudioPipelineError.readerFailed(reader.error?.localizedDescription ?? "ملف الصوت فارغ")
        }

        var wav = Data()
        func appendASCII(_ value: String) { wav.append(contentsOf: value.utf8) }
        func appendUInt16LE(_ value: UInt16) {
            wav.append(UInt8(value & 0xff))
            wav.append(UInt8((value >> 8) & 0xff))
        }
        func appendUInt32LE(_ value: UInt32) {
            wav.append(UInt8(value & 0xff))
            wav.append(UInt8((value >> 8) & 0xff))
            wav.append(UInt8((value >> 16) & 0xff))
            wav.append(UInt8((value >> 24) & 0xff))
        }
        appendASCII("RIFF")
        appendUInt32LE(UInt32(36 + pcm.count))
        appendASCII("WAVEfmt ")
        appendUInt32LE(16) // PCM fmt chunk size
        appendUInt16LE(1)  // PCM
        appendUInt16LE(1)  // mono
        appendUInt32LE(UInt32(sampleRate))
        appendUInt32LE(UInt32(sampleRate * 2)) // byte rate
        appendUInt16LE(2)  // block alignment
        appendUInt16LE(16) // bits per sample
        appendASCII("data")
        appendUInt32LE(UInt32(pcm.count))
        wav.append(pcm)

        try? FileManager.default.removeItem(at: outputURL)
        try wav.write(to: outputURL, options: .atomic)
    }

    private static func isReadableAudio(_ url: URL) async -> Bool {
        let asset = AVURLAsset(url: url)
        let tracks = (try? await asset.loadTracks(withMediaType: .audio)) ?? []
        return !tracks.isEmpty
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
        try? FileManager.default.removeItem(at: dir.appendingPathComponent("hls-source.aac"))
        try? FileManager.default.removeItem(at: dir.appendingPathComponent("merged.ts"))
        try? FileManager.default.removeItem(at: dir.appendingPathComponent("chunks.json"))
        try? FileManager.default.removeItem(at: dir.appendingPathComponent("duration.txt"))
    }
}
