import Foundation

enum MediaKind: String, Codable, CaseIterable {
    case mp4, mov, m4v, threeGP = "3gp"
    case hls, dash, webm
    case ts, aac, mp3, wav
    case mkv, avi, other

    var titleAR: String {
        switch self {
        case .mp4: return "MP4"
        case .mov: return "MOV"
        case .m4v: return "M4V"
        case .threeGP: return "3GP"
        case .hls: return "HLS (m3u8)"
        case .dash: return "DASH"
        case .webm: return "WebM"
        case .ts: return "جزء TS"
        case .aac: return "AAC"
        case .mp3: return "MP3"
        case .wav: return "WAV"
        case .mkv: return "MKV"
        case .avi: return "AVI"
        case .other: return "أخرى"
        }
    }

    var fileExtension: String {
        switch self {
        case .mp4: return "mp4"
        case .mov: return "mov"
        case .m4v: return "m4v"
        case .threeGP: return "3gp"
        case .hls: return "m3u8"
        case .dash: return "mpd"
        case .webm: return "webm"
        case .ts: return "ts"
        case .aac: return "aac"
        case .mp3: return "mp3"
        case .wav: return "wav"
        case .mkv: return "mkv"
        case .avi: return "avi"
        case .other: return "bin"
        }
    }

    /// AVPlayer على iOS 16 يشغّل هذه دون محرك خارجي.
    var avPlayerSupported: Bool {
        switch self {
        case .mp4, .mov, .m4v, .threeGP, .hls, .aac, .mp3, .wav: return true
        case .webm, .mkv, .avi, .dash, .ts, .other: return false
        }
    }

    var isLikelyFragment: Bool {
        self == .ts
    }

    var isCompleteVideo: Bool {
        switch self {
        case .mp4, .mov, .m4v, .threeGP, .hls, .webm, .mkv, .avi: return true
        default: return false
        }
    }

    static func infer(url: String, mime: String?) -> MediaKind {
        let u = url.split(separator: "?").first.map(String.init)?.lowercased() ?? url.lowercased()
        let m = (mime ?? "").lowercased()
        if u.contains(".m3u8") || m.contains("mpegurl") { return .hls }
        if u.contains(".mpd") || m.contains("dash") { return .dash }
        if u.hasSuffix(".webm") || m.contains("webm") { return .webm }
        if u.hasSuffix(".mkv") || m.contains("matroska") { return .mkv }
        if u.hasSuffix(".avi") { return .avi }
        if u.hasSuffix(".mov") { return .mov }
        if u.hasSuffix(".m4v") { return .m4v }
        if u.hasSuffix(".3gp") || u.hasSuffix(".3gpp") { return .threeGP }
        if u.hasSuffix(".ts") { return .ts }
        if u.hasSuffix(".aac") || m.contains("aac") { return .aac }
        if u.hasSuffix(".mp3") || m.contains("mpeg") && m.contains("audio") { return .mp3 }
        if u.hasSuffix(".wav") { return .wav }
        if u.hasSuffix(".mp4") || m.contains("mp4") || m.contains("video") { return .mp4 }
        return .other
    }
}

enum DRMKind: String, Codable {
    case none
    case fairplay
    case widevine
    case playready
    case encryptedHLS
    case unknownProtected

    var isProtected: Bool { self != .none }

    var titleAR: String {
        switch self {
        case .none: return "غير محمي"
        case .fairplay: return "FairPlay (Apple DRM)"
        case .widevine: return "Widevine (Google DRM)"
        case .playready: return "PlayReady (Microsoft DRM)"
        case .encryptedHLS: return "HLS مشفّر بترخيص"
        case .unknownProtected: return "حماية DRM غير محددة"
        }
    }

    var messageAR: String {
        "هذا المحتوى محمي بنظام DRM (\(titleAR)). التطبيق لا يكسر الحماية ولن يتم التحميل. يمكنك المشاهدة داخل المتصفح إن سمح الموقع بذلك."
    }
}

struct DetectedMedia: Identifiable, Hashable, Codable {
    var id: String { url }
    var url: String
    var title: String
    var kind: MediaKind
    var mime: String?
    var qualityLabel: String?
    var drm: DRMKind
    var pageURL: String?
    var extractionMethod: String
    var duration: Double?
    var byteSize: Int64?
    var width: Int?
    var height: Int?
    var probed: Bool = false

    var canDownload: Bool {
        !drm.isProtected && !url.hasPrefix("blob:") && !url.hasPrefix("data:")
    }

    var isFragment: Bool {
        kind.isLikelyFragment || url.lowercased().contains(".m4s")
    }

    var durationText: String {
        guard let duration, duration.isFinite, duration > 0 else { return "المدة غير معروفة" }
        let n = Int(duration)
        if n >= 3600 { return String(format: "%d:%02d:%02d", n / 3600, (n % 3600) / 60, n % 60) }
        return String(format: "%d:%02d", n / 60, n % 60)
    }

    var sizeText: String {
        guard let byteSize, byteSize > 0 else { return "الحجم غير معروف" }
        return ByteCountFormatter.string(fromByteCount: byteSize, countStyle: .file)
    }

    var resolutionText: String? {
        if let qualityLabel, !qualityLabel.isEmpty { return qualityLabel }
        if let height { return "\(height)p" }
        return nil
    }
}

struct SavedVideo: Identifiable, Codable, Hashable {
    var id: UUID
    var title: String
    var sourceURL: String
    var pageURL: String?
    var localRelativePath: String
    var thumbnailRelativePath: String?
    var kind: MediaKind
    var createdAt: Date
    var duration: Double?
    var fileSize: Int64
    var lastPosition: Double
    var extractionMethod: String

    /// مسارات ملفات الترجمة نسبةً إلى مجلد المستندات: "orig" / "target" / "bilingual"
    var subtitleFiles: [String: String]?
    /// كود لغة الترجمة الهدف (مثل "ar")
    var subtitleTargetLang: String?

    var localURL: URL {
        LibraryStore.documents.appendingPathComponent(localRelativePath)
    }

    var hasSubtitles: Bool {
        guard let files = subtitleFiles else { return false }
        return files.values.contains {
            FileManager.default.fileExists(atPath: LibraryStore.documents.appendingPathComponent($0).path)
        }
    }
}

enum DownloadState: String, Codable {
    case queued, running, paused, failed, completed, blockedDRM

    var isBusy: Bool { self == .queued || self == .running }
}

struct DownloadJob: Identifiable, Codable {
    var id: UUID
    var media: DetectedMedia
    var state: DownloadState
    var progress: Double
    var bytesWritten: Int64
    var errorMessage: String?
    var createdAt: Date
}
