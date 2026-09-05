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
            if text.unicodeScalars.count > 140 || duration > 12 {
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

    /// يقسم النص إلى قطع مريحة لا تتجاوز 140 حرفاً. هذا أقل عمداً من حد
    /// Groq Orpheus (200 حرف) حتى تظل كل جملة صالحة للدبلجة أيضاً، وليس فقط
    /// للعرض. التقسيم حسب المسافات أولاً ثم حسب الأحرف للكلمة غير المنقطعة.
    private static func splitLongText(_ text: String, maximumCharacters: Int = 140) -> [String] {
        let words = text.split(whereSeparator: { $0.isWhitespace }).map(String.init)
        guard !words.isEmpty else { return [] }

        // إذا كان التقسيم مطلوباً لطول التوقيت فقط، نقسم الجملة القصيرة إلى
        // نصفين بدلاً من إبقائها كقطعة واحدة طويلة زمنياً.
        if scalarCount(text) <= maximumCharacters, words.count > 1 {
            let midpoint = words.count / 2
            return [words[..<midpoint].joined(separator: " "),
                    words[midpoint...].joined(separator: " ")]
                .filter { !$0.isEmpty }
        }

        var pieces: [String] = []
        var current = ""
        for word in words {
            // كلمة أو رابط بلا مسافة: لا نسمح له بتجاوز السقف وحده.
            if scalarCount(word) > maximumCharacters {
                if !current.isEmpty {
                    pieces.append(current)
                    current = ""
                }
                pieces.append(contentsOf: splitUnbrokenText(word, maximumCharacters: maximumCharacters))
                continue
            }
            let candidate = current.isEmpty ? word : "\(current) \(word)"
            if scalarCount(candidate) > maximumCharacters, !current.isEmpty {
                pieces.append(current)
                current = word
            } else {
                current = candidate
            }
        }
        if !current.isEmpty { pieces.append(current) }
        return pieces
    }

    private static func splitUnbrokenText(_ text: String, maximumCharacters: Int) -> [String] {
        var pieces: [String] = []
        var current = ""
        var currentScalars = 0
        for character in text {
            let fragment = String(character)
            let fragmentScalars = scalarCount(fragment)
            if currentScalars + fragmentScalars > maximumCharacters, !current.isEmpty {
                pieces.append(current)
                current = fragment
                currentScalars = fragmentScalars
            } else {
                current += fragment
                currentScalars += fragmentScalars
            }
        }
        if !current.isEmpty { pieces.append(current) }
        return pieces
    }

    private static func scalarCount(_ text: String) -> Int {
        text.unicodeScalars.count
    }

    // MARK: - ترتيب ودمج

    static func sortedAndMerged(_ cues: [SubCue]) -> [SubCue] {
        let sorted = cues.sorted { $0.start < $1.start }
        var out: [SubCue] = []
        out.reserveCapacity(sorted.count)
        for var c in sorted {
            if c.end <= c.start { c.end = c.start + 0.8 }
            if let last = out.last, c.start < last.end - 0.05, last.end - last.start < 0.2 {
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
