import Foundation

struct APIError: LocalizedError {
    let message: String
    var status: Int = 0
    var errorDescription: String? { message }
}

enum API {
    static let base = "https://176-124-220-95.sslip.io:8443"
    static let ws = base.replacingOccurrences(of: "https://", with: "wss://") + "/ws"
    static var token: String? = UserDefaults.standard.string(forKey: "token")

    static func abs(_ url: String?) -> URL? {
        guard let url else { return nil }
        if url.hasPrefix("http") { return URL(string: url) }
        return URL(string: base + url)
    }

    /// Voice messages recorded on the website are WebM/Opus, which iOS cannot play: the server converts them.
    static func playable(_ url: String) -> URL? {
        if url.hasPrefix("/uploads/"), url.hasSuffix(".webm") || url.hasSuffix(".ogg") {
            let name = (url as NSString).lastPathComponent
            let hex = (name as NSString).deletingPathExtension
            return URL(string: base + "/api/audio/\(hex).m4a")
        }
        return abs(url)
    }

    static let decoder = JSONDecoder()

    @discardableResult
    static func call(_ path: String, method: String = "GET", body: [String: Any?]? = nil) async throws -> [String: Any] {
        var req = URLRequest(url: URL(string: base + path)!)
        req.httpMethod = method
        req.timeoutInterval = 30
        if let token { req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
        if let body {
            req.setValue("application/json", forHTTPHeaderField: "Content-Type")
            req.httpBody = try JSONSerialization.data(withJSONObject: body.mapValues { $0 ?? NSNull() })
        }
        let data: Data, res: URLResponse
        do { (data, res) = try await URLSession.shared.data(for: req) } catch { throw APIError(message: "Нет соединения с сервером") }
        let obj = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
        let status = (res as? HTTPURLResponse)?.statusCode ?? 0
        if !(200..<300).contains(status) {
            if status == 401, token != nil, path != "/api/login" { await MainActor.run { Store.shared.sessionExpired() } }
            throw APIError(message: obj["error"] as? String ?? "Ошибка \(status)", status: status)
        }
        return obj
    }

    /// Re-encodes a JSON fragment from `call` into a Codable model.
    static func decode<T: Decodable>(_ type: T.Type, _ any: Any?) throws -> T {
        guard let any, !(any is NSNull) else { throw APIError(message: "Пустой ответ") }
        let data = try JSONSerialization.data(withJSONObject: any)
        return try decoder.decode(T.self, from: data)
    }

    struct Uploaded { let url: String; let size: Int64; let ext: String? }

    static func upload(file: URL, kind: String, name: String) async throws -> Uploaded {
        var comps = URLComponents(string: base + "/api/upload")!
        comps.queryItems = [URLQueryItem(name: "kind", value: kind), URLQueryItem(name: "name", value: name)]
        var req = URLRequest(url: comps.url!)
        req.httpMethod = "POST"
        req.timeoutInterval = 300
        req.setValue("application/octet-stream", forHTTPHeaderField: "Content-Type")
        if let token { req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
        let data: Data, res: URLResponse
        do { (data, res) = try await URLSession.shared.upload(for: req, fromFile: file) } catch { throw APIError(message: "Нет соединения") }
        let obj = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
        let status = (res as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(status), let url = obj["url"] as? String else {
            throw APIError(message: obj["error"] as? String ?? "Ошибка \(status)", status: status)
        }
        return Uploaded(url: url, size: (obj["size"] as? NSNumber)?.int64Value ?? 0, ext: obj["ext"] as? String)
    }
}
