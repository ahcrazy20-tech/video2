import Foundation
import Network

// MARK: - وسيط الوسائط المحلي (Magic Stream Proxy)
//
// AVPlayer لا يستطيع إرسال ترويسات مخصّصة (Referer / User-Agent / Cookie) مع طلبات
// الشبكة، وكثير من الـ CDN ترفض الطلب بدونها. الوسيط هنا يستمع على منفذ محلي
// ويمرّر كل طلبات المشغّل إلى السيرفر البعيد بالترويسات نفسها، ويدعم Range كما هو،
// ويعيد كتابة قوائم HLS (بما فيها القوائم المتداخلة ومفاتيح التشفير) حتى تمرّ
// كل القطع من نفس الوسيط.
//
// لا يمسّ الوسيط مسار التحميل الأصلي إطلاقاً: هو للطرف فقط (التشغيل داخل التبويب).

final class MagicStreamProxy {
    static let shared = MagicStreamProxy()

    struct Target {
        var url: URL
        var headers: [String: String]
        /// إعادة كتابة قائمة التشغيل (m3u8) لتحويل كل الروابط الداخلية عبر الوسيط.
        var rewrite: Bool
    }

    private(set) var port: UInt16 = 0
    private var listener: NWListener?
    private var targets: [String: Target] = [:]
    private var counter = 0
    private let lock = NSLock()
    private let queue = DispatchQueue(label: "video2.magic.proxy", qos: .userInitiated)
    private static let ports: [UInt16] = [8770, 8771, 8772, 18770]

    /// جلسة للبث الممرّر: بدون كاش حتى لا تتلخّص طلبات AVPlayer النطاقية.
    private static let relayConfig: URLSessionConfiguration = {
        let cfg = URLSessionConfiguration.ephemeral
        cfg.requestCachePolicy = .reloadIgnoringLocalCacheData
        cfg.urlCache = nil
        cfg.timeoutIntervalForRequest = 24 * 3600
        cfg.timeoutIntervalForResource = 24 * 3600
        cfg.waitsForConnectivity = false
        cfg.shouldUseExtendedBackgroundIdleMode = true
        return cfg
    }()

    private var up: URLSession = {
        let cfg = URLSessionConfiguration.ephemeral
        cfg.requestCachePolicy = .reloadIgnoringLocalCacheData
        cfg.urlCache = nil
        cfg.timeoutIntervalForRequest = 20
        cfg.timeoutIntervalForResource = 24 * 3600
        cfg.waitsForConnectivity = false
        cfg.httpAdditionalHeaders = ["User-Agent": DownloadAuth.safariUA]
        return URLSession(configuration: cfg)
    }()

    // MARK: - واجهة الاستخدام

    /// يسجّل مصدراً بعيداً ويعيد رابطاً محلياً يعيد بثّه بالترويسات المطلوبة.
    func register(_ urlString: String, headers: [String: String]) -> String? {
        guard let url = Self.parse(urlString) else { return nil }
        let lower = urlString.lowercased()
        return register(url: url, headers: headers, rewrite: lower.contains("m3u8"))
    }

    private func register(url: URL, headers: [String: String], rewrite: Bool) -> String? {
        startIfNeeded()
        var p = port
        if p == 0 {
            // أول تسجيل قد يسبق انتهاء الربط بالمنفذ بدقائق من الثانية — ننتظر قليلاً
            for _ in 0..<20 {
                usleep(50_000)
                p = port
                if p > 0 { break }
            }
        }
        guard p > 0 else { return nil }
        lock.lock()
        counter += 1
        let token = String(counter, radix: 36)
        if targets.count > 20_000 { targets.removeAll(keepingCapacity: true) }
        targets[token] = Target(url: url, headers: headers, rewrite: rewrite)
        lock.unlock()
        return "http://127.0.0.1:\(p)/s/\(token)"
    }

    static func parse(_ s: String) -> URL? {
        if let u = URL(string: s.trimmingCharacters(in: .whitespacesAndNewlines)), u.scheme != nil { return u }
        let escaped = s.trimmingCharacters(in: .whitespacesAndNewlines)
            .addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed)
        return escaped.flatMap { URL(string: $0) }
    }

    private func startIfNeeded() {
        lock.lock()
        if listener != nil { lock.unlock(); return }
        lock.unlock()
        var bound: NWListener?
        var boundPort: UInt16 = 0
        for candidate in Self.ports {
            guard let l = try? NWListener(using: .tcp, on: NWEndpoint.Port(rawValue: candidate)!) else { continue }
            let sem = DispatchSemaphore(value: 0)
            var failed = false
            l.stateUpdateHandler = { state in
                switch state {
                case .ready: sem.signal()
                case .failed: failed = true; sem.signal()
                default: break
                }
            }
            l.newConnectionHandler = { [weak self] conn in self?.accept(conn) }
            l.start(queue: queue)
            _ = sem.wait(timeout: .now() + 2)
            if !failed {
                bound = l
                boundPort = candidate
                break
            }
            l.cancel()
        }
        lock.lock()
        if listener == nil {
            listener = bound
            port = boundPort
        }
        lock.unlock()
    }

    // MARK: - التعامل مع الطلبات

    private func accept(_ conn: NWConnection) {
        conn.start(queue: queue)
        receive(conn, buffer: Data())
    }

    private func receive(_ conn: NWConnection, buffer: Data) {
        conn.receive(minimumIncompleteLength: 1, maximumLength: 64 * 1024) { [weak self] data, _, isComplete, error in
            guard let self else { conn.cancel(); return }
            if error != nil { conn.cancel(); return }
            var buf = buffer
            if let data { buf.append(data) }
            if let range = buf.range(of: Data("\r\n\r\n".utf8)) {
                let head = String(data: buf.subdata(in: 0..<range.lowerBound), encoding: .isoLatin1) ?? ""
                self.handle(conn, head: head)
                return
            }
            if isComplete { conn.cancel(); return }
            if buf.count > 64 * 1024 { conn.cancel(); return }
            self.receive(conn, buffer: buf)
        }
    }

    private struct Request {
        var method = "GET"
        var token = ""
        var headers: [String: String] = [:]
    }

    private func parseHead(_ head: String) -> Request? {
        let lines = head.components(separatedBy: "\r\n")
        guard lines.count >= 1 else { return nil }
        let parts = lines[0].split(separator: " ")
        guard parts.count >= 2 else { return nil }
        var req = Request()
        req.method = String(parts[0]).uppercased()
        var path = String(parts[1])
        if let q = path.firstIndex(of: "?") { path = String(path[..<q]) }
        let comps = path.split(separator: "/")
        guard comps.count >= 2, comps[0] == "s" else { return nil }
        req.token = String(comps[1])
        for line in lines.dropFirst() {
            guard let idx = line.firstIndex(of: ":") else { continue }
            let k = line[..<idx].lowercased().trimmingCharacters(in: .whitespaces)
            let v = line[line.index(after: idx)...].trimmingCharacters(in: .whitespaces)
            if !k.isEmpty { req.headers[k] = String(v) }
        }
        return req
    }

    private func handle(_ conn: NWConnection, head: String) {
        guard let req = parseHead(head) else {
            fail(conn, 400, "bad request")
            return
        }
        lock.lock()
        let target = targets[req.token]
        lock.unlock()
        guard let target else {
            fail(conn, 404, "gone")
            return
        }
        if target.rewrite {
            fetchPlaylist(target: target, conn: conn)
            return
        }
        startRelay(target: target, request: req, conn: conn)
    }

    // MARK: قائمة HLS (إعادة كتابة)

    private func fetchPlaylist(target: Target, conn: NWConnection) {
        var req = URLRequest(url: target.url)
        req.httpMethod = "GET"
        req.cachePolicy = .reloadIgnoringLocalCacheData
        req.timeoutInterval = 20
        for (k, v) in target.headers { req.setValue(v, forHTTPHeaderField: k) }
        let task = up.dataTask(with: req) { [weak self] data, resp, _ in
            DispatchQueue.global(qos: .userInitiated).async {
                guard let self else { conn.cancel(); return }
                let http = resp as? HTTPURLResponse
                guard let data, (http?.statusCode ?? 0) < 400,
                      var text = String(data: data, encoding: .utf8) ?? String(data: data, encoding: .isoLatin1) else {
                    self.fail(conn, 502, "playlist failed")
                    return
                }
                text = self.rewritePlaylist(text, base: target.url, headers: target.headers)
                let body = Data(text.utf8)
                self.send(conn, status: 200, type: "application/vnd.apple.mpegurl", body: body, extra: nil)
            }
        }
        task.resume()
    }

    /// يحوّل كل مسار داخلي في القائمة إلى رابط عبر الوسيط (قوائم متداخلة، قطع، مفاتيح، init segments).
    private func rewritePlaylist(_ text: String, base: URL, headers: [String: String]) -> String {
        var out: [String] = []
        out.reserveCapacity(text.count / 40)
        for rawLine in text.components(separatedBy: .newlines) {
            let line = rawLine.trimmingCharacters(in: .whitespaces)
            if line.isEmpty {
                out.append("")
                continue
            }
            if line.hasPrefix("#") {
                if line.uppercased().contains("URI=\"") {
                    out.append(rewriteAttributes(line, base: base, headers: headers))
                } else {
                    out.append(rawLine)
                }
                continue
            }
            out.append(proxyLine(line, base: base, headers: headers) ?? line)
        }
        return out.joined(separator: "\n")
    }

    private func rewriteAttributes(_ line: String, base: URL, headers: [String: String]) -> String {
        // يستبدل كل URI="..." بالمسار نفسه بعد تحويله عبر الوسيط
        guard let re = try? NSRegularExpression(pattern: "URI=\"([^\"]+)\"") else { return line }
        let ns = line as NSString
        var result = line
        var edits: [(NSRange, String)] = []
        re.enumerateMatches(in: line, range: NSRange(location: 0, length: ns.length)) { m, _, _ in
            guard let m, m.numberOfRanges > 1 else { return }
            let value = ns.substring(with: m.range(at: 1))
            if let mapped = proxyLine(value, base: base, headers: headers) {
                edits.append((m.range(at: 1), mapped))
            }
        }
        for edit in edits.reversed() {
            result = result.replacingCharacters(in: Range(edit.0, in: result) ?? result.startIndex..<result.endIndex, with: edit.1)
        }
        return result
    }

    private func proxyLine(_ value: String, base: URL, headers: [String: String]) -> String? {
        let cleaned = value.trimmingCharacters(in: .whitespaces)
        guard !cleaned.isEmpty, !cleaned.hasPrefix("#") else { return nil }
        guard let abs = URL(string: cleaned, relativeTo: base)?.absoluteURL else { return nil }
        return register(url: abs, headers: headers, rewrite: abs.absoluteString.lowercased().contains("m3u8"))
    }

    // MARK: - البث الممرّر (progressive / segments)

    private func startRelay(target: Target, request: Request, conn: NWConnection) {
        var req = URLRequest(url: target.url)
        req.httpMethod = request.method == "HEAD" ? "HEAD" : "GET"
        req.cachePolicy = .reloadIgnoringLocalCacheData
        req.timeoutInterval = 24 * 3600
        req.setValue(DownloadAuth.safariUA, forHTTPHeaderField: "User-Agent")
        for (k, v) in target.headers { req.setValue(v, forHTTPHeaderField: k) }
        if let range = request.headers["range"] { req.setValue(range, forHTTPHeaderField: "Range") }

        let relay = Relay(conn: conn)
        let opQueue = OperationQueue()
        opQueue.maxConcurrentOperationCount = 1
        let session = URLSession(configuration: Self.relayConfig, delegate: relay, delegateQueue: opQueue)
        relay.session = session
        let task = session.dataTask(with: req)
        relay.task = task
        task.resume()
    }

    // MARK: - أدوات الإرسال

    private func send(_ conn: NWConnection, status: Int, type: String, body: Data, extra: String?) {
        var head = "HTTP/1.1 \(status) \(status == 200 ? "OK" : "ERROR")\r\n"
        head += "Content-Type: \(type)\r\n"
        head += "Content-Length: \(body.count)\r\n"
        if let extra { head += extra }
        head += "Accept-Ranges: bytes\r\nConnection: close\r\nAccess-Control-Allow-Origin: *\r\n\r\n"
        var packet = Data(head.utf8)
        packet.append(body)
        conn.send(content: packet, completion: .contentProcessed { _ in conn.cancel() })
    }

    private func fail(_ conn: NWConnection, _ status: Int, _ message: String) {
        send(conn, status: status, type: "text/plain", body: Data(message.utf8), extra: nil)
    }
}

// MARK: - وسيط تمرير البايتات مع تحكم في التدفق

private final class Relay: NSObject, URLSessionDataDelegate {
    private let conn: NWConnection
    var task: URLSessionDataTask?
    var session: URLSession?

    private var headSent = false
    private var supportsRange = false
    private var upstreamDone = false
    private var finished = false
    private var pending: [Data] = []
    private var pendingBytes = 0
    private var sending = false
    private var paused = false
    private let lock = NSLock()
    private let highWater = 6 * 1024 * 1024
    private let lowWater = 1024 * 1024

    init(conn: NWConnection) {
        self.conn = conn
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive response: URLResponse,
                    completionHandler: @escaping (URLSession.ResponseDisposition) -> Void) {
        guard let http = response as? HTTPURLResponse, http.statusCode < 400 else {
            completionHandler(.cancel)
            return
        }
        supportsRange = http.statusCode == 206
        sendHead(http)
        completionHandler(.allow)
    }

    private func sendHead(_ http: HTTPURLResponse) {
        lock.lock()
        if headSent { lock.unlock(); return }
        headSent = true
        lock.unlock()

        var head = "HTTP/1.1 \(http.statusCode) \(http.statusCode == 206 ? "Partial Content" : "OK")\r\n"
        let type = http.value(forHTTPHeaderField: "Content-Type") ?? "application/octet-stream"
        head += "Content-Type: \(type)\r\n"
        if let len = http.value(forHTTPHeaderField: "Content-Length") {
            head += "Content-Length: \(len)\r\n"
        }
        if supportsRange, let cr = http.value(forHTTPHeaderField: "Content-Range") {
            head += "Content-Range: \(cr)\r\n"
        }
        head += "Accept-Ranges: \(supportsRange ? "bytes" : "none")\r\n"
        head += "Connection: close\r\nAccess-Control-Allow-Origin: *\r\n\r\n"
        conn.send(content: Data(head.utf8), completion: .contentProcessed { [weak self] _ in
            self?.flush()
        })
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        lock.lock()
        pending.append(data)
        pendingBytes += data.count
        let shouldPause = pendingBytes > highWater && !paused
        if shouldPause { paused = true }
        lock.unlock()
        flush()
        if shouldPause { task?.suspend() }
    }

    /// يبثّ الشرائط المخزّنة واحدة تلو الأخرى، ويفتح تدفق السيرفر البعيد
    /// من جديد كلما فرغت الذاكرة المؤقتة (تجنّب استنزاف RAM على الملفات الكبيرة).
    private func flush() {
        lock.lock()
        if sending { lock.unlock(); return }
        if pending.isEmpty {
            let drainDone = upstreamDone
            let shouldResume = paused && pendingBytes < lowWater
            if shouldResume { paused = false }
            lock.unlock()
            if shouldResume { task?.resume() }
            if drainDone { finish() }
            return
        }
        guard headSent else {
            // رأس الاستجابة يجب أن يسبق أي بايت
            lock.unlock()
            return
        }
        sending = true
        let chunk = pending.removeFirst()
        pendingBytes -= chunk.count
        let shouldResume = paused && pendingBytes < lowWater
        if shouldResume { paused = false }
        lock.unlock()
        if shouldResume { task?.resume() }
        conn.send(content: chunk, completion: .contentProcessed { [weak self] _ in
            guard let self else { return }
            self.lock.lock()
            self.sending = false
            self.lock.unlock()
            self.flush()
        })
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        lock.lock()
        upstreamDone = true
        let empty = pending.isEmpty && !sending
        lock.unlock()
        if empty { finish() } else { flush() }
    }

    func urlSession(_ session: URLSession, didBecomeInvalidWithError error: Error?) {
        finish()
    }

    private func finish() {
        lock.lock()
        if finished { lock.unlock(); return }
        finished = true
        let wasPaused = paused
        let s = session
        session = nil
        let t = task
        task = nil
        lock.unlock()
        // مهمة معلّقة يجب إيقاظها قبل الإلغاء وإلا بقيت معلّقة
        if wasPaused { t?.resume() }
        s?.invalidateAndCancel()
        conn.cancel()
    }
}
