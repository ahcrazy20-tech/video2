import Foundation
import CommonCrypto

/// استخراج صوت محلي من بث HLS بنوع MPEG-TS بدون رفع لأي سحابة.
/// يفك AES-128 إن وُجد `key.bin`، ثم يخرج إطارات ADTS AAC يقرأها AVFoundation.
enum MPEGTSDemuxer {

    enum DemuxError: LocalizedError {
        case playlistUnreadable
        case noSegments
        case noAudio
        case decryptFailed
        case writeFailed

        var errorDescription: String? {
            switch self {
            case .playlistUnreadable: return "تعذر قراءة قائمة HLS"
            case .noSegments: return "قائمة HLS لا تحتوي أجزاء"
            case .noAudio: return "لم يُعثر على صوت AAC داخل أجزاء MPEG-TS"
            case .decryptFailed: return "فشل فك تشفير جزء HLS بـ AES-128"
            case .writeFailed: return "تعذر كتابة ملف الصوت المستخرج"
            }
        }
    }

    /// يستخرج ملفاً صوتياً (.aac) من قائمة m3u8 محلية.
    static func extractAudio(fromPlaylist playlist: URL) async throws -> URL {
        guard let text = try? String(contentsOf: playlist, encoding: .utf8) else {
            throw DemuxError.playlistUnreadable
        }
        let folder = playlist.deletingLastPathComponent()
        let items = parse(playlist: text, folder: folder)
        guard !items.isEmpty else { throw DemuxError.noSegments }

        let output = FileManager.default.temporaryDirectory
            .appendingPathComponent("hls-ts-audio-\(UUID().uuidString).aac")
        FileManager.default.createFile(atPath: output.path, contents: nil)
        let handle = try FileHandle(forWritingTo: output)
        defer { try? handle.close() }

        var leftover = Data()
        var written = 0
        var audioPID: UInt16?
        print("[MPEGTS] Extracting AAC from \(items.count) HLS segments…")

        for (index, item) in items.enumerated() {
            if Task.isCancelled {
                try? FileManager.default.removeItem(at: output)
                throw CancellationError()
            }
            if index == 0 || index + 1 == items.count || (index + 1).isMultiple(of: 50) {
                print("[MPEGTS] segment \(index + 1)/\(items.count)")
            }

            var data = try Data(contentsOf: item.url, options: [.mappedIfSafe])
            if let key = item.key {
                guard let plain = decryptAES128(data, key: key, iv: item.iv) else {
                    throw DemuxError.decryptFailed
                }
                data = plain
            }

            let (pes, discoveredPID) = extractAudioPES(from: data, preferredPID: audioPID)
            if audioPID == nil { audioPID = discoveredPID }
            leftover.append(pes)
            let consumed = writeADTSFrames(from: &leftover, to: handle)
            written += consumed
        }

        if leftover.count >= 7 {
            written += writeADTSFrames(from: &leftover, to: handle, flushTail: true)
        }
        try? handle.close()

        let size = (try? output.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
        guard written > 0, size > 32 else {
            try? FileManager.default.removeItem(at: output)
            throw DemuxError.noAudio
        }
        print("[MPEGTS] ✅ extracted \(size / 1024) KB AAC")
        return output
    }

    // MARK: - Playlist

    private struct Segment {
        let url: URL
        let key: Data?
        let iv: Data
    }

    private static func parse(playlist text: String, folder: URL) -> [Segment] {
        var mediaSequence = 0
        var method = "NONE"
        var keyData: Data?
        var explicitIV: Data?
        var out: [Segment] = []

        func resolve(_ raw: String) -> URL {
            if raw.hasPrefix("http://") || raw.hasPrefix("https://") {
                return URL(string: raw) ?? folder.appendingPathComponent(raw)
            }
            if raw.hasPrefix("/") { return URL(fileURLWithPath: raw) }
            return folder.appendingPathComponent(raw)
        }

        for raw in text.components(separatedBy: .newlines) {
            let line = raw.trimmingCharacters(in: .whitespaces)
            if line.uppercased().hasPrefix("#EXT-X-MEDIA-SEQUENCE:") {
                mediaSequence = Int(line.split(separator: ":").last ?? "0") ?? 0
            } else if line.uppercased().hasPrefix("#EXT-X-KEY:") {
                let attrs = parseAttrs(line)
                method = (attrs["METHOD"] ?? "NONE").uppercased()
                if method == "NONE" {
                    keyData = nil
                    explicitIV = nil
                } else if method.contains("AES-128") {
                    if let uri = attrs["URI"] {
                        let keyURL = resolve(uri.trimmingCharacters(in: CharacterSet(charactersIn: "\"")))
                        keyData = try? Data(contentsOf: keyURL)
                    }
                    if let ivHex = attrs["IV"]?.trimmingCharacters(in: CharacterSet(charactersIn: "\"")),
                       ivHex.lowercased().hasPrefix("0x") {
                        explicitIV = dataFromHex(String(ivHex.dropFirst(2)))
                    } else {
                        explicitIV = nil
                    }
                }
            } else if !line.isEmpty && !line.hasPrefix("#") {
                let iv = explicitIV ?? sequenceIV(mediaSequence)
                out.append(Segment(url: resolve(line), key: keyData, iv: iv))
                mediaSequence += 1
            }
        }
        return out
    }

    private static func parseAttrs(_ line: String) -> [String: String] {
        guard let idx = line.firstIndex(of: ":") else { return [:] }
        var map: [String: String] = [:]
        let body = line[line.index(after: idx)...]
        var current = ""
        var inQuote = false
        func flush(_ pair: String) {
            let parts = pair.split(separator: "=", maxSplits: 1).map(String.init)
            guard parts.count == 2 else { return }
            map[parts[0].uppercased()] = parts[1]
        }
        for ch in body {
            if ch == "\"" { inQuote.toggle(); current.append(ch); continue }
            if ch == "," && !inQuote { flush(current); current = ""; continue }
            current.append(ch)
        }
        if !current.isEmpty { flush(current) }
        return map
    }

    private static func sequenceIV(_ seq: Int) -> Data {
        var data = Data(count: 16)
        var value = UInt64(seq).bigEndian
        withUnsafeBytes(of: &value) { bytes in
            data.replaceSubrange(8..<16, with: bytes)
        }
        return data
    }

    private static func dataFromHex(_ hex: String) -> Data? {
        var raw = hex.trimmingCharacters(in: .whitespacesAndNewlines)
        if raw.count % 2 != 0 { raw = "0" + raw }
        var data = Data()
        data.reserveCapacity(raw.count / 2)
        var index = raw.startIndex
        while index < raw.endIndex {
            let next = raw.index(index, offsetBy: 2)
            guard next <= raw.endIndex, let byte = UInt8(raw[index..<next], radix: 16) else { return nil }
            data.append(byte)
            index = next
        }
        return data
    }

    // MARK: - AES-128

    private static func decryptAES128(_ data: Data, key: Data, iv: Data) -> Data? {
        guard key.count == kCCKeySizeAES128, iv.count == 16, !data.isEmpty else { return nil }
        if let padded = crypt(data, key: key, iv: iv, options: CCOptions(kCCOptionPKCS7Padding)) {
            return padded
        }
        return crypt(data, key: key, iv: iv, options: 0)
    }

    private static func crypt(_ data: Data, key: Data, iv: Data, options: CCOptions) -> Data? {
        var out = Data(count: data.count + kCCBlockSizeAES128)
        var moved = 0
        let status: CCCryptorStatus = out.withUnsafeMutableBytes { outBytes in
            data.withUnsafeBytes { dataBytes in
                key.withUnsafeBytes { keyBytes in
                    iv.withUnsafeBytes { ivBytes -> CCCryptorStatus in
                        CCCrypt(CCOperation(kCCDecrypt),
                                CCAlgorithm(kCCAlgorithmAES),
                                options,
                                keyBytes.baseAddress, kCCKeySizeAES128,
                                ivBytes.baseAddress,
                                dataBytes.baseAddress, data.count,
                                outBytes.baseAddress, out.count,
                                &moved)
                    }
                }
            }
        }
        guard status == kCCSuccess else { return nil }
        out.removeSubrange(moved..<out.count)
        return out
    }

    // MARK: - MPEG-TS → audio PES

    private static func extractAudioPES(from ts: Data, preferredPID: UInt16?) -> (Data, UInt16?) {
        var audioPIDs = Set<UInt16>()
        if let preferredPID { audioPIDs.insert(preferredPID) }
        var pmtPIDs = Set<UInt16>()
        var pes = Data()
        pes.reserveCapacity(min(ts.count, 256 * 1024))

        var offset = 0
        let bytes = [UInt8](ts)
        while offset + 188 <= bytes.count {
            if bytes[offset] != 0x47 {
                if let next = (offset + 1..<min(offset + 188, bytes.count)).first(where: {
                    bytes[$0] == 0x47 && $0 + 188 < bytes.count && bytes[$0 + 188] == 0x47
                }) {
                    offset = next
                    continue
                }
                break
            }
            let packet = Array(bytes[offset..<(offset + 188)])
            offset += 188
            let pid = (UInt16(packet[1] & 0x1F) << 8) | UInt16(packet[2])
            let start = (packet[1] & 0x40) != 0
            let adaptation = (packet[3] >> 4) & 0x03
            var payloadStart = 4
            if adaptation == 2 || adaptation == 3 {
                let len = Int(packet[4])
                payloadStart = 5 + len
            }
            guard payloadStart < 188 else { continue }
            let payload = Data(packet[payloadStart..<188])

            if pid == 0 {
                pmtPIDs.formUnion(parsePAT(payload, payloadUnitStart: start))
                continue
            }
            if pmtPIDs.contains(pid) {
                audioPIDs.formUnion(parsePMT(payload, payloadUnitStart: start))
                continue
            }
            if audioPIDs.isEmpty {
                if start, payload.count >= 4,
                   payload[0] == 0x00, payload[1] == 0x00, payload[2] == 0x01 {
                    let streamID = payload[3]
                    if (0xC0...0xDF).contains(streamID) || streamID == 0xBD {
                        audioPIDs.insert(pid)
                    }
                }
            }
            guard audioPIDs.contains(pid) else { continue }
            if start {
                if let body = pesPayload(payload) {
                    pes.append(body)
                }
            } else {
                pes.append(payload)
            }
        }
        return (pes, audioPIDs.first)
    }

    private static func pointerSkipped(_ payload: Data, payloadUnitStart: Bool) -> Data {
        guard payloadUnitStart, let first = payload.first else { return payload }
        let skip = Int(first) + 1
        guard skip < payload.count else { return Data() }
        return payload.subdata(in: skip..<payload.count)
    }

    private static func parsePAT(_ payload: Data, payloadUnitStart: Bool) -> Set<UInt16> {
        let data = pointerSkipped(payload, payloadUnitStart: payloadUnitStart)
        guard data.count >= 8 else { return [] }
        let sectionLen = (Int(data[1] & 0x0F) << 8) | Int(data[2])
        var pids = Set<UInt16>()
        var i = 8
        let end = min(data.count - 4, 3 + sectionLen)
        while i + 4 <= end {
            let program = (UInt16(data[i]) << 8) | UInt16(data[i + 1])
            let pid = (UInt16(data[i + 2] & 0x1F) << 8) | UInt16(data[i + 3])
            if program != 0 { pids.insert(pid) }
            i += 4
        }
        return pids
    }

    private static func parsePMT(_ payload: Data, payloadUnitStart: Bool) -> Set<UInt16> {
        let data = pointerSkipped(payload, payloadUnitStart: payloadUnitStart)
        guard data.count >= 12 else { return [] }
        let sectionLen = (Int(data[1] & 0x0F) << 8) | Int(data[2])
        let programInfo = (Int(data[10] & 0x0F) << 8) | Int(data[11])
        var i = 12 + programInfo
        let end = min(data.count - 4, 3 + sectionLen)
        var audio = Set<UInt16>()
        while i + 5 <= end {
            let streamType = data[i]
            let pid = (UInt16(data[i + 1] & 0x1F) << 8) | UInt16(data[i + 2])
            let esInfo = (Int(data[i + 3] & 0x0F) << 8) | Int(data[i + 4])
            // 0x0F AAC ADTS, 0x11 AAC LATM, 0x03/0x04 MPEG audio, 0x81 AC-3
            if streamType == 0x0F || streamType == 0x11 || streamType == 0x03 || streamType == 0x04 || streamType == 0x81 {
                audio.insert(pid)
            }
            i += 5 + esInfo
        }
        return audio
    }

    private static func pesPayload(_ packet: Data) -> Data? {
        guard packet.count >= 9,
              packet[0] == 0x00, packet[1] == 0x00, packet[2] == 0x01 else { return nil }
        let headerLen = Int(packet[8])
        let start = 9 + headerLen
        guard start <= packet.count else { return nil }
        return packet.subdata(in: start..<packet.count)
    }

    // MARK: - ADTS

    @discardableResult
    private static func writeADTSFrames(from buffer: inout Data, to handle: FileHandle, flushTail: Bool = false) -> Int {
        var written = 0
        var i = 0
        let bytes = [UInt8](buffer)
        while i + 7 <= bytes.count {
            if bytes[i] == 0xFF && (bytes[i + 1] & 0xF0) == 0xF0 {
                let len = ((Int(bytes[i + 3]) & 0x03) << 11)
                    | (Int(bytes[i + 4]) << 3)
                    | ((Int(bytes[i + 5]) & 0xE0) >> 5)
                if len < 7 || len > 8 * 1024 {
                    i += 1
                    continue
                }
                if i + len > bytes.count {
                    break
                }
                try? handle.write(contentsOf: Data(bytes[i..<(i + len)]))
                written += len
                i += len
            } else {
                i += 1
            }
        }
        if flushTail {
            buffer.removeAll(keepingCapacity: false)
        } else if i > 0 {
            buffer = Data(bytes[i...])
        }
        return written
    }
}
