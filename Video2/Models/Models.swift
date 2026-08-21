import Foundation

enum MediaKind: String, Codable, CaseIterable {
    case mp4, hls, dash, webm, other

    var titleAR: String {
        switch self {
        case .mp4: return "ملف مباشر (MP4)"
        case .hls: return "بث HLS (m3u8)"
        case .dash: return "بث DASH (mpd)"
        case .webm: return "WebM"
        case .other: return "وسائط أخرى"
        }
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

    var canDownload: Bool { !drm.isProtected && (kind == .mp4 || kind == .hls || kind == .webm || kind == .other) }
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
