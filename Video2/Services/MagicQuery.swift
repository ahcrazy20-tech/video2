import Foundation

// MARK: - صيغة البحث الموسّعة (Magic Query DSL)
//
// حقل البحث في «البحث السحري» يقبل الآن اسم الفيديو/الفيلم + أوامر قصيرة بجواره،
// فتتحوّل الكلمة المفتاحية إلى بحث مُوجَّه عبر كل المصادر والمحركات.
//
//   اسم الفيلم                                    ← بحث عادي
//   اسم الفيلم  مدة:2:28                          ← هدف المدة (ترتيب + شارات)
//   اسم الفيلم  min:1:00:00  max:2:30:00          ← حصر المدة بين حدّين
//   اسم الفيلم  سنة:1995                          ← يشترط ذكر السنة في العنوان
//   اسم الفيلم  موقع:archive.org                  ← نتائج من نطاق بعينه (أو عدة نطاقات بـ |)
//   اسم الفيلم  جودة:720                          ← تفضيل جودة بعينها عند التشغيل/التحميل
//   اسم الفيلم  استبعد:react|ترجمة                ← استبعاد نتائج تحتوي كلمات معينة
//   اسم الفيلم  مصدر:ar|yt                        ← تقييد المصادر
//   اسم الفيلم  ترتيب:مشاهدات                     ← ترتيب بديل
//   https://site.com/watch/xyz                    ← رابط مباشر: يُصيد منه الفيديو فوراً
//
// الأوامر تُقرأ بالعربي والإنجليزي، والأرقام تقبل الحروف العربية (٢:٢٨) والصيغ
// (2h28m / 148 / 1:30:00 / 1س30د).

struct MagicQuery {

    enum Sort: String, CaseIterable {
        case relevance, duration, views
    }

    /// النص الذي يُرسل للمحركات والمصادر (بدون أوامر الفلترة).
    var terms: String
    /// نص المستخدم الأصلي كما كُتب.
    var raw: String = ""

    var targetDuration: Double?
    var minDuration: Double?
    var maxDuration: Double?
    var year: Int?
    var hosts: [String] = []
    var preferredHeight: Int?
    var excluded: [String] = []
    var sources: [MagicSource]?
    var sort: Sort = .relevance
    /// هل العنوان يجب أن يحتوي عبارة حرفية بين علامتي اقتباس.
    var mustContain: [String] = []
    /// هل أدخل المستخدم رابطاً مباشراً (صفحة أو ملف وسائط).
    var directURL: String?
    /// هل الرابط المُدخل ملف وسائط مباشر وليس صفحة.
    var directURLIsMedia: Bool = false

    var isEmpty: Bool {
        terms.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && directURL == nil
    }

    /// صيغة للبحث النصي: تُضاف إليها قيود النطاق ومحركات الويب.
    var webTerms: String {
        var t = terms
        if !hosts.isEmpty {
            let clause = hosts.map { "site:\($0)" }.joined(separator: " OR ")
            t += " " + clause
        }
        return t.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    // MARK: التحليل

    static func parse(_ input: String) -> MagicQuery {
        let normalized = MagicDuration.normalizeDigits(input)
        let trimmed = normalized.trimmingCharacters(in: .whitespacesAndNewlines)
        var q = MagicQuery(terms: trimmed, raw: input)

        // رابط مباشر؟ (كلمة واحدة تبدأ بـ http أو نطاق معروف مع مسار)
        if let url = directURL(from: trimmed) {
            q.directURL = url
            q.directURLIsMedia = MediaKind.infer(url: url.lowercased(), mime: nil) != .other
                || url.lowercased().contains(".m3u8") || url.lowercased().contains("videoplayback")
            q.terms = ""
            return q
        }

        // استخراج علامات الاقتباس أولاً حتى لا تتقطع بعبارات الفلترة
        var quoted: [String] = []
        var working = trimmed
        if let re = try? NSRegularExpression(pattern: "\"([^\"]{2,120})\"") {
            let ns = working as NSString
            var cuts: [NSRange] = []
            re.enumerateMatches(in: working, range: NSRange(location: 0, length: ns.length)) { m, _, _ in
                guard let m else { return }
                if m.numberOfRanges > 1 {
                    quoted.append(ns.substring(with: m.range(at: 1)).trimmingCharacters(in: .whitespaces))
                    cuts.append(m.range)
                }
            }
            for r in cuts.reversed() {
                working = working.replacingCharacters(in: Range(r, in: working) ?? working.startIndex..<working.endIndex, with: " ")
            }
        }
        q.mustContain = quoted.filter { !$0.isEmpty }

        var kept: [String] = []
        for tokenRaw in working.split(whereSeparator: { $0 == " " || $0 == "\t" || $0 == "\n" }) {
            var token = String(tokenRaw)
            if token.isEmpty { continue }
            if consume(token, into: &q) { continue }
            kept.append(token)
        }

        var termsText = kept.joined(separator: " ")
        if !q.mustContain.isEmpty {
            termsText += " " + q.mustContain.map { "\"\($0)\"" }.joined(separator: " ")
        }
        q.terms = termsText.trimmingCharacters(in: .whitespacesAndNewlines)

        // رقم مجرد في نهاية النص = مدة بالدقائق (سلوك النسخة الأولى: «اسم 148»)
        if q.targetDuration == nil, let last = kept.last,
           let minutes = Double(last), minutes > 1, minutes < 24 * 60, kept.count > 1 {
            q.targetDuration = minutes * 60
            q.terms = kept.dropLast().joined(separator: " ")
        }
        return q
    }

    /// يحلل أمراً واحداً `مفتاح:قيمة`. يعيد true لو كان أمراً معروفاً.
    private static func consume(_ token: String, into q: inout MagicQuery) -> Bool {
        // يقبل النقطتين العربيتين وعلامة التساوي أيضاً
        var sepRange: Range<String.Index>?
        for sep in [":", "：", "=", "＝"] {
            guard let r = token.range(of: sep) else { continue }
            if sepRange == nil || r.lowerBound < sepRange!.lowerBound { sepRange = r }
        }
        guard let hit = sepRange else { return false }
        let key = String(token[token.startIndex..<hit.lowerBound])
            .lowercased()
            .trimmingCharacters(in: .punctuationMarks)
        let value = String(token[hit.upperBound...])
            .trimmingCharacters(in: .whitespacesAndNewlines)
        guard !value.isEmpty else { return false }

        switch key {
        case "مدة", "المدة", "المدد", "dur", "duration", "length", "len", "time":
            q.targetDuration = MagicDuration.parse(value)
        case "min", "أدنى", "ادنى", "أقل", "اقل", "من", "least", "atleast":
            q.minDuration = MagicDuration.parse(value).map { max(0, $0) }
        case "max", "أعلى", "اعلي", "أقصي", "اقصي", "up_to", "atmost":
            q.maxDuration = MagicDuration.parse(value)
        case "سنة", "السنه", "سنه", "year", "yr":
            if let y = Int(value.filter { $0.isNumber }), y > 1880, y < 2100 { q.year = y }
        case "موقع", "الموقع", "نطاق", "site", "host", "domain", "from":
            q.hosts = value
                .lowercased()
                .components(separatedBy: CharacterSet(charactersIn: "|,"))
                .map { $0.trimmingCharacters(in: .whitespaces).replacingOccurrences(of: "www.", with: "") }
                .filter { !$0.isEmpty && $0.contains(".") }
        case "جودة", "الجودة", "q", "quality", "res", "height", "p":
            let h = value.lowercased()
            if h.contains("4k") || h.contains("2160") { q.preferredHeight = 2160 }
            else if h.contains("fhd") || h.contains("1080") { q.preferredHeight = 1080 }
            else if h.contains("hd") || h.contains("720") { q.preferredHeight = 720 }
            else if h.contains("480") { q.preferredHeight = 480 }
            else if h.contains("360") { q.preferredHeight = 360 }
            else if let n = Int(value.filter { $0.isNumber }), n > 0 { q.preferredHeight = n }
        case "استبعد", "استبعاد", "بدون", "من غير", "not", "exclude", "without":
            q.excluded = value
                .lowercased()
                .components(separatedBy: CharacterSet(charactersIn: "|,"))
                .map { $0.trimmingCharacters(in: .whitespaces) }
                .filter { $0.count >= 2 }
        case "مصدر", "المصادر", "sources", "source", "src":
            q.sources = parseSources(value)
        case "ترتيب", "الترتيب", "sort", "order", "by":
            switch value.lowercased() {
            case "مشاهدات", "views", "popular", "شعبية", "popularity": q.sort = .views
            case "مدة", "duration", "length", "الاطول", "اطول": q.sort = .duration
            default: q.sort = .relevance
            }
        default:
            return false
        }
        return true
    }

    private static func parseSources(_ value: String) -> [MagicSource]? {
        let aliases: [String: MagicSource] = [
            "ارشيف": .archive, "أرشيف": .archive, "الأرشيف": .archive, "archive": .archive, "ia": .archive, "ar": .archive,
            "يوتيوب": .youtube, "يوتيوب-قصير": .youtube, "youtube": .youtube, "yt": .youtube, "piped": .youtube, "invidious": .youtube,
            "داليموشن": .dailymotion, "dailymotion": .dailymotion, "dm": .dailymotion, "دايلي": .dailymotion,
            "بيروتوب": .peertube, "peertube": .peertube, "fediverse": .peertube, "fedi": .peertube,
            "فيميو": .vimeo, "vimeo": .vimeo,
            "ويب": .web, "الويب": .web, "web": .web, "net": .web, "كل": .web, "google": .web, "bing": .web,
        ]
        var out: [MagicSource] = []
        for part in value.lowercased().components(separatedBy: CharacterSet(charactersIn: "|,+")) {
            let p = part.trimmingCharacters(in: .whitespacesAndNewlines)
            if let s = aliases[p], !out.contains(s) { out.append(s) }
        }
        return out.isEmpty ? nil : out
    }

    /// يميّز الروابط التي يلصقها المستخدم مباشرة في حقل البحث.
    private static func directURL(from text: String) -> String? {
        let single = text.split(whereSeparator: { $0 == " " || $0 == "\t" }).first.map(String.init) ?? ""
        let candidate = single.hasPrefix("http://") || single.hasPrefix("https://") ? single : "https://" + text
        guard text.split(separator: " ").count == 1 else { return nil }
        guard let url = URL(string: candidate), let host = url.host, host.contains(".") else { return nil }
        // لا نعتبر كلمة مثل "الجزيرة.نت" رابطاً؛ ونقبل فقط المضيف ASCII المعروف
        guard host.allSatisfy({ $0.isLetter || $0.isNumber || $0 == "." || $0 == "-" }) else { return nil }
        let tld = host.split(separator: ".").last.map(String.init) ?? ""
        guard tld.count >= 2, tld.allSatisfy({ $0.isLetter }) else { return nil }
        return url.absoluteString
    }

    // MARK: فلترة النتائج

    /// هل تمرّ هذه النتيجة على المرشحات؟
    func accepts(_ r: MagicSearchResult) -> Bool {
        if r.isShort { return false }
        if let minD = minDuration, let d = r.duration, d < minD * 0.95 { return false }
        if let maxD = maxDuration, let d = r.duration, d > maxD * 1.15 { return false }
        if let year {
            let has = r.title.contains("\(year)") || (r.snippet ?? "").contains("\(year)")
            if !has { return false }
        }
        if !hosts.isEmpty {
            let host = (URL(string: r.pageURL)?.host ?? "").lowercased()
            let hit = hosts.contains { h in host == h || host.hasSuffix("." + h) || host.contains(h) }
            if !hit { return false }
        }
        if !excluded.isEmpty {
            let hay = (r.title + " " + (r.snippet ?? "") + " " + (r.uploader ?? "")).lowercased()
            if excluded.contains(where: { hay.contains($0) }) { return false }
        }
        if !mustContain.isEmpty {
            let hay = r.title.lowercased()
            if !mustContain.allSatisfy({ hay.contains($0.lowercased()) }) { return false }
        }
        return true
    }

    /// بُعد النتيجة عن المدة الهدف (∞ إن لم تُحدَّد مدة).
    func distance(from r: MagicSearchResult) -> Double {
        guard let target = targetDuration else { return 0 }
        guard let d = r.duration else { return .infinity }
        return abs(d - target)
    }

}
