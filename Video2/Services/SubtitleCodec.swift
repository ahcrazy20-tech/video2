import Foundation

/// ترميز وفك ترميز ملفات SRT.
enum SubtitleCodec {

    // MARK: - تنسيق الوقت

    /// 00:01:02,345
    static func srtTime(_ seconds: Double) -> String {
        let t = max(0, seconds)
        let ms = Int((t * 1000).rounded())
        let h = ms / 3_600_000
        let m = (ms % 3_600_000) / 60_000
        let s = (ms % 60_000) / 1000
        let milli = ms % 1000
        return String(format: "%02d:%02d:%02d,%03d", h, m, s, milli)
    }

    static func parseSRTTime(_ raw: String) -> Double? {
        // يدعم "00:01:02,345" و"00:01:02.345" و"0:01:02,345"
        let parts = raw.replacingOccurrences(of: ".", with: ",").split(separator: ",")
        guard parts.count == 2,
              let milli = Double("0." + parts[1]).map({ $0 * 1000 }) else { return nil }
        let hms = parts[0].split(separator: ":")
        guard hms.count == 3,
              let h = Double(hms[0]), let m = Double(hms[1]), let s = Double(hms[2]) else { return nil }
        return h * 3600 + m * 60 + s + milli / 1000.0
    }

    // MARK: - كتابة SRT

    static func writeSRT(_ cues: [SubCue], translated: Bool, bilingual: Bool) -> String {
        var out = ""
        for (n, cue) in cues.enumerated() {
            var text = cue.text
            if bilingual, let t = cue.translated, !t.isEmpty {
                text = t + "\n" + cue.text
            } else if translated {
                text = cue.translated ?? cue.text
            }
            let clean = text
                .replacingOccurrences(of: "\r", with: "")
                .trimmingCharacters(in: .whitespacesAndNewlines)
            guard !clean.isEmpty else { continue }
            out += "\(n + 1)\n"
            out += "\(srtTime(cue.start)) --> \(srtTime(cue.end))\n"
            out += clean + "\n\n"
        }
        return out
    }

    // MARK: - قراءة SRT

    static func parseSRT(_ text: String) -> [SubCue] {
        var cues: [SubCue] = []
        // فصل البلوكات بسطر فارغ أو أكثر
        let normalized = text.replacingOccurrences(of: "\r\n", with: "\n")
            .replacingOccurrences(of: "\r", with: "\n")
        let blocks = normalized.components(separatedBy: "\n\n")
        var index = 0
        for block in blocks {
            let lines = block.split(separator: "\n", omittingEmptySubsequences: true)
                .map { String($0).trimmingCharacters(in: .whitespaces) }
                .filter { !$0.isEmpty }
            guard lines.count >= 2 else { continue }
            // السطر الأول قد يكون رقماً اختيارياً
            var timeLineIndex = 0
            if let first = lines.first, Int(first) != nil, lines.count >= 3 {
                timeLineIndex = 1
            }
            let timeParts = lines[timeLineIndex].components(separatedBy: "-->")
            guard timeParts.count == 2,
                  let start = parseSRTTime(timeParts[0].trimmingCharacters(in: .whitespaces)),
                  let end = parseSRTTime(timeParts[1].trimmingCharacters(in: CharacterSet(charactersIn: " \t"))) else { continue }
            let body = lines[(timeLineIndex + 1)...].joined(separator: "\n")
            guard !body.isEmpty else { continue }
            cues.append(SubCue(id: index, start: start, end: end, text: body, translated: nil))
            index += 1
        }
        return cues
    }

    static func parseSRTFile(at url: URL) -> [SubCue] {
        guard let data = try? Data(contentsOf: url),
              let text = String(data: data, encoding: .utf8) ?? String(data: data, encoding: .isoLatin1) else { return [] }
        return parseSRT(text)
    }

    // MARK: - تحسين الجُمل للقراءة

    /// تقسيم الجمل الطويلة جداً وضبط التوقيتات لقُراءة مريحة.
    static func normalize(_ cues: [SubCue]) -> [SubCue] {
        var result: [SubCue] = []
        result.reserveCapacity(cues.count)
        var id = 0
        for c in cues {
            let text = c.text.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !text.isEmpty else { continue }
            let duration = c.end - c.start
            if text.count > 140 || duration > 12 {
                let pieces = splitLongText(text)
                guard !pieces.isEmpty else { continue }
                let span = max(duration, 0.6) / Double(pieces.count)
                for (i, piece) in pieces.enumerated() {
                    result.append(SubCue(id: id,
                                         start: c.start + Double(i) * span,
                                         end: c.start + Double(i + 1) * span,
                                         text: piece,
                                         translated: nil))
                    id += 1
                }
            } else {
                var cue = c
                cue.id = id
                cue.text = text
                if cue.end <= cue.start { cue.end = cue.start + 0.8 }
                result.append(cue)
                id += 1
            }
        }
        return result
    }

    private static func splitLongText(_ text: String) -> [String] {
        let words = text.components(separatedBy: " ").filter { !$0.isEmpty }
        guard words.count > 1 else { return [text] }
        let mid = words.count / 2
        let first = words[..<mid].joined(separator: " ")
        let second = words[mid...].joined(separator: " ")
        if second.count > 160 {
            // تقسيم ثلاثي للأطراف الطويلة جداً
            let third = words.count / 3
            let a = words[..<third].joined(separator: " ")
            let b = words[third..<(third * 2)].joined(separator: " ")
            let c = words[(third * 2)...].joined(separator: " ")
            return [a, b, c].filter { !$0.isEmpty }
        }
        return [first, second].filter { !$0.isEmpty }
    }

    // MARK: - ترتيب ودمج

    static func sortedAndMerged(_ cues: [SubCue]) -> [SubCue] {
        let sorted = cues.sorted { $0.start < $1.start }
        var out: [SubCue] = []
        out.reserveCapacity(sorted.count)
        for var c in sorted {
            if c.end <= c.start { c.end = c.start + 0.8 }
            if var last = out.last, c.start < last.end - 0.05, last.end - last.start < 0.2 {
                // استبدال سطر شبه الفارغ
                out.removeLast()
                c.id = last.id
                out.append(c)
                continue
            }
            out.append(c)
        }
        for i in out.indices { out[i].id = i }
        return out
    }
}
