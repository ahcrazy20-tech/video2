import Foundation

// MARK: - Z.ai GLM (النطاق العالمي والصيني)
// https://open.bigmodel.cn/dev/api  ·  النطاق العالمي: https://api.z.ai/api/paas/v4
// متوافق مع OpenAI: POST {base}/chat/completions
// ملاحظة: GLM-4.7-Flash مجاني بالكامل وسياقه 200K، وطلب متزامن واحد فقط.

/// مفتاح Z.ai الواحد يعمل على النطاق العالمي api.z.ai وعلى open.bigmodel.cn،
/// لكن بعض الشبكات/الحسابات تعمل على أحدهما فقط — نجرب المحفوظ أولاً ثم الآخر.
enum ZaiAPI {
    private static let preferenceKey = "zai.api.base"
    private static let globalBase = "https://api.z.ai/api/paas/v4"
    private static let chinaBase = "https://open.bigmodel.cn/api/paas/v4"

    static func request(_ method: String,
                        path: String,
                        key: String,
                        headers: [String: String] = [:],
                        body: Data? = nil,
                        timeout: Double = 180) async throws -> (Data, HTTPURLResponse) {
        let saved = UserDefaults.standard.string(forKey: preferenceKey)
        var bases = [saved, globalBase, chinaBase].compactMap { $0 }
        bases = bases.reduce(into: []) { result, base in
            if !result.contains(base) { result.append(base) }
        }

        var lastError: Error = URLError(.userAuthenticationRequired)
        for (index, base) in bases.enumerated() {
            do {
                var allHeaders = headers
                allHeaders["Authorization"] = "Bearer \(KeychainStore.normalized(key))"
                let result = try await HTTP.request(method, base + path,
                                                    headers: allHeaders,
                                                    body: body,
                                                    timeout: timeout)
                UserDefaults.standard.set(base, forKey: preferenceKey)
                return result
            } catch let error as APIError where (error.status == 401 || error.status == 403) && index < bases.count - 1 {
                lastError = error
                continue
            } catch {
                throw error
            }
        }
        throw lastError
    }
}

// MARK: - Cohere (v2 Chat)
// https://docs.cohere.com/v2/chat  ·  POST https://api.cohere.com/v2/chat
// ليس متوافقاً مع OpenAI: رسالة الرد تأتي message.content[] {type:"text"}

enum CohereChat {
    /// ينفذ طلب chat واحد على Cohere v2 ويعيد النص المُولَّد.
    /// - Parameters:
    ///   - system: تعليمات النظام (برومبت الترجمة أو المراجعة).
    ///   - user: محتوى المستخدم.
    static func complete(key: String,
                         model: String,
                         system: String,
                         user: String,
                         temperature: Double) async throws -> String {
        let body: [String: Any] = [
            "model": model,
            "temperature": temperature,
            "messages": [
                ["role": "system", "content": system],
                ["role": "user", "content": user]
            ]
        ]
        let payload = try JSONSerialization.data(withJSONObject: body)
        let (data, _) = try await HTTP.withRetry(attempts: 4, baseDelay: 5) {
            try await HTTP.request("POST",
                                   "https://api.cohere.com/v2/chat",
                                   headers: ["Authorization": "Bearer \(KeychainStore.normalized(key))",
                                             "Content-Type": "application/json"],
                                   body: payload,
                                   timeout: 180)
        }
        let json = HTTP.json(from: data)
        if let err = json["error"] as? [String: Any] {
            // قد تعود الرسالة نصاً مباشراً أو كائناً
            if let msg = err["message"] as? String {
                throw APIError(status: 0, body: "Cohere: \(msg)")
            }
            if let msg = err["detail"] as? String {
                throw APIError(status: 0, body: "Cohere: \(msg)")
            }
            if let msg = json["message"] as? String {
                throw APIError(status: 0, body: "Cohere: \(msg)")
            }
            throw APIError(status: 0, body: "Cohere: خطأ غير معروف")
        }
        if let msg = json["message"] as? String, !msg.isEmpty {
            throw APIError(status: 0, body: "Cohere: \(msg)")
        }
        // v2: { "message": { "role": "assistant", "content": [ {"type":"text","text":"..."} ] } }
        guard let message = json["message"] as? [String: Any] else {
            throw APIError(status: 0, body: "استجابة Cohere غير متوقعة (لا توجد message)")
        }
        if let content = message["content"] as? [[String: Any]] {
            let text = content.compactMap { $0["text"] as? String }.joined()
            guard !text.isEmpty else {
                throw APIError(status: 0, body: "استجابة Cohere فارغة")
            }
            return text
        }
        // بعض الإصدارات/الموديلات قد تعيد نصاً مباشراً (v1 شكل توافق)
        if let text = message["content"] as? String, !text.isEmpty {
            return text
        }
        if let text = json["text"] as? String, !text.isEmpty {
            return text
        }
        throw APIError(status: 0, body: "استجابة Cohere فارغة")
    }
}

// MARK: - Lara Translate (محرك ترجمة متخصص)
// https://developers.laratranslate.com  ·  POST https://api.laratranslate.com/translate
// { "text": ["سطر", ...] (حتى 128)، "source": "en"، "target": "ar"، "context": "..." }
// الرد: { "translation": ["مترجم", ...] } أو نصاً واحداً إذا كان المدخل نصاً.
// الشريحة المجانية: 10,000 حرف شهرياً.

enum LaraTranslate {
    /// يترجم حتى 128 سطراً في طلب واحد مع سياق الفيديو.
    static func translate(texts: [String],
                          source: SubLang,
                          target: SubLang,
                          videoTitle: String) async throws -> [String] {
        guard let key = KeychainStore.get("lara") else {
            throw APIError(status: 401, body: "أدخل مفتاح Lara Translate من الإعدادات")
        }
        guard !texts.isEmpty else { return [] }
        // حد الواجهة: 128 عنصراً للطلب الواحد — نقسم احتياطاً لو تجاوزته الدفعة.
        if texts.count > 128 {
            let first = try await translate(texts: Array(texts.prefix(128)),
                                            source: source, target: target, videoTitle: videoTitle)
            let rest = try await translate(texts: Array(texts.dropFirst(128)),
                                           source: source, target: target, videoTitle: videoTitle)
            return first + rest
        }
        var body: [String: Any] = [
            "text": texts,
            "target": target.rawValue
        ]
        if source != .auto {
            body["source"] = source.rawValue
        }
        let context = "Subtitles of a video titled \"\(videoTitle)\". Translate each line as a short natural spoken subtitle line; keep the same count and order."
        body["context"] = context
        let payload = try JSONSerialization.data(withJSONObject: body)
        let (data, _) = try await HTTP.withRetry(attempts: 3, baseDelay: 4) {
            try await HTTP.request("POST",
                                   "https://api.laratranslate.com/translate",
                                   headers: ["Authorization": "Bearer \(KeychainStore.normalized(key))",
                                             "Content-Type": "application/json"],
                                   body: payload,
                                   timeout: 120)
        }
        let json = HTTP.json(from: data)
        if let detail = json["detail"] as? String {
            throw APIError(status: 0, body: "Lara: \(detail)")
        }
        if let detailArr = json["detail"] as? [Any],
           let first = detailArr.first as? [String: Any],
           let msg = first["msg"] as? String {
            throw APIError(status: 0, body: "Lara: \(msg)")
        }
        if let msg = json["message"] as? String {
            throw APIError(status: 0, body: "Lara: \(msg)")
        }
        var results: [String] = []
        if let translation = json["translation"] as? [String] {
            results = translation
        } else if let single = json["translation"] as? String {
            // لو أعادت نصاً واحداً لمدخل مصفوفة (غير متوقع)، نعامله كأول سطر.
            results = [single]
        } else if let blocks = json["translation"] as? [[String: Any]] {
            results = blocks.compactMap { $0["text"] as? String }
        }
        guard !results.isEmpty else {
            throw APIError(status: 0, body: "استجابة Lara فارغة أو غير متوقعة")
        }
        // نكمل الفراغات حتى يطابق طول المدخل
        while results.count < texts.count {
            results.append("")
        }
        return Array(results.prefix(texts.count))
    }
}

// MARK: - MyMemory (ترجمة بدون مفتاح)
// https://mymemory.translated.net/doc/spec.php  ·  GET /get?q=...&langpair=en|ar
// بدون مفتاح: 5,000 كلمة/يوم لكل IP (رفع إلى 50K مع بريد إلكتروني عبر &de=).
// حد الاستعلام: 500 بايت — مناسب لأسطر الترجمة القصيرة.

enum MyMemoryTranslate {
    /// يترجم الأسطر واحداً واحداً (لا تدعم الواجهة الدفعات). بطيء عمداً
    /// حتى لا نصطدم بحد الطلبات، لذلك يُستخدم كملاذ أخير فقط.
    static func translate(texts: [String],
                          source: SubLang,
                          target: SubLang) async throws -> [String] {
        guard !texts.isEmpty else { return [] }
        // MyMemory تتطلب لغة مصدر صريحة في langpair؛ عند «تلقائي» نخمّن من
        // الحروف العربية في النص وإلا نستخدم الإنجليزية (أغلب المحتوى).
        let resolvedSource: String
        if source != .auto {
            resolvedSource = source.rawValue
        } else {
            let arabicChars = texts.joined().filter { ("\u{0600}"..."\u{06FF}").contains($0) }.count
            let totalChars = texts.joined().count
            resolvedSource = totalChars > 0 && Double(arabicChars) / Double(totalChars) > 0.25 ? "ar" : "en"
        }
        let targetCode = target.rawValue

        var results: [String] = []
        for raw in texts {
            if Task.isCancelled { throw CancellationError() }
            let text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
            if text.isEmpty {
                results.append("")
                continue
            }
            guard let q = text.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) else {
                results.append("")
                continue
            }
            // حد 500 بايت للطلب — السطر الأطول يُعاد كما هو بدل إفشال الدفعة.
            if text.utf8.count > 480 {
                results.append(text)
                continue
            }
            let url = "https://api.mymemory.translated.net/get?q=\(q)&langpair=\(resolvedSource)|\(targetCode)"
            do {
                let (data, _) = try await HTTP.request("GET", url, timeout: 60)
                let json = HTTP.json(from: data)
                var translated = ""
                if let responseData = json["responseData"] as? [String: Any],
                   let t = responseData["translatedText"] as? String {
                    translated = t
                }
                // أحياناً تعيد رسالة خطأ نصية داخل translatedText
                let lower = translated.lowercased()
                if translated.isEmpty
                    || lower.contains("invalid")
                    || lower.contains("query limit")
                    || lower.contains("mymemory warning")
                    || lower.contains("please contact") {
                    // نحاول أول اقتراح بديل من matches إن وجد
                    if let matches = json["matches"] as? [[String: Any]],
                       let best = matches.first,
                       let t = best["translation"] as? String, !t.isEmpty {
                        translated = t
                    } else {
                        translated = ""
                    }
                }
                results.append(unescapeHTML(translated))
            } catch {
                // خطأ شبكة في سطر واحد لا يُسقط الدفعة كلها؛ نعيد فارغاً ليسقط
                // displayText للنص الأصلي.
                results.append("")
            }
            // مهلة صغيرة بين الطلبات احتراماً لحد الخدمة العامة.
            try? await Task.sleep(nanoseconds: 150_000_000)
        }
        return results
    }

    /// MyMemory تعيد كيانات HTML أحياناً (&#39; و&amp;) — نفكها للعرض الطبيعي.
    private static func unescapeHTML(_ value: String) -> String {
        value
            .replacingOccurrences(of: "&#39;", with: "'")
            .replacingOccurrences(of: "&quot;", with: "\"")
            .replacingOccurrences(of: "&amp;", with: "&")
            .replacingOccurrences(of: "&lt;", with: "<")
            .replacingOccurrences(of: "&gt;", with: ">")
    }
}
