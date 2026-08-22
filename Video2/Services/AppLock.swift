import Foundation
import Combine

@MainActor
final class AppLock: ObservableObject {
    static let account = "app-password"
    @Published private(set) var isLocked: Bool
    @Published private(set) var hasPassword: Bool

    init() {
        let exists = KeychainStore.has(Self.account)
        hasPassword = exists
        isLocked = exists
    }

    func unlock(_ password: String) -> Bool {
        guard let saved = KeychainStore.get(Self.account), saved == password else { return false }
        isLocked = false
        return true
    }

    @discardableResult
    func setPassword(_ password: String) -> Bool {
        let value = password.trimmingCharacters(in: .whitespacesAndNewlines)
        guard value.count >= 4 else { return false }
        let ok = KeychainStore.set(value, for: Self.account)
        if ok { hasPassword = true; isLocked = false }
        return ok
    }

    func removePassword() {
        KeychainStore.delete(Self.account)
        hasPassword = false
        isLocked = false
    }

    func lock() { if hasPassword { isLocked = true } }
}
