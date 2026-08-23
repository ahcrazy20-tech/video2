import Foundation
import Security

/// تخزين مفاتيح API بأمان في Keychain بدلاً من ملفات عادية.
enum KeychainStore {
    private static let service = "com.ahcrazy.video2.apikeys"

    /// ينظّف النص المنسوخ من لوحات التحكم. بعض المستخدمين ينسخون
    /// `Bearer sk-...` أو المفتاح بين علامات اقتباس، وهو ما كان ينتج 401.
    static func normalized(_ value: String) -> String {
        var result = value.trimmingCharacters(in: .whitespacesAndNewlines)
        if result.count >= 2,
           (result.hasPrefix("\"") && result.hasSuffix("\"") ||
            result.hasPrefix("'") && result.hasSuffix("'")) {
            result.removeFirst()
            result.removeLast()
            result = result.trimmingCharacters(in: .whitespacesAndNewlines)
        }
        if result.lowercased().hasPrefix("bearer ") {
            result = String(result.dropFirst(7)).trimmingCharacters(in: .whitespacesAndNewlines)
        }
        return result
    }

    @discardableResult
    static func set(_ value: String, for account: String) -> Bool {
        let clean = normalized(value)
        guard !clean.isEmpty else { return false }
        let data = Data(clean.utf8)
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account
        ]
        SecItemDelete(query as CFDictionary)
        var attrs = query
        attrs[kSecValueData as String] = data
        attrs[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
        return SecItemAdd(attrs as CFDictionary, nil) == errSecSuccess
    }

    static func get(_ account: String) -> String? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]
        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess,
              let data = item as? Data,
              let stored = String(data: data, encoding: .utf8) else { return nil }
        let clean = normalized(stored)
        return clean.isEmpty ? nil : clean
    }

    static func has(_ account: String) -> Bool {
        get(account) != nil
    }

    @discardableResult
    static func delete(_ account: String) -> Bool {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account
        ]
        return SecItemDelete(query as CFDictionary) == errSecSuccess
    }
}
