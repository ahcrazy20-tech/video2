import UIKit
import SwiftUI

/// إدارة اتجاه الشاشة.
///
/// المشكلة التي يحلها هذا الملف: المشغّل كان يفرض الوضع الأفقي عبر
/// `UIDevice.current.setValue(...forKey:"orientation")` ولا يُعيده أبداً. هذا
/// الـ API مهجور منذ iOS 16 ويترك حالة الجهاز متعارضة مع الاتجاه الفعلي،
/// فتظهر شاشات لاحقة (مثل الإعدادات) مقلوبة رأساً على عقب أو معكوسة كمرآة
/// حتى يلفّ المستخدم الهاتف بيده.
///
/// الحل: مصدر واحد للحقيقة (`mask`) مع قفل عمودي لكل التطبيق، ويُسمح
/// بالأفقي فقط أثناء وجود المشغّل على الشاشة — عبر الـ API الحديث
/// `requestGeometryUpdate` الذي لا يترك تعارضاً في الحالة.
@MainActor
final class OrientationLock: NSObject {

    static let shared = OrientationLock()

    /// هل المشغّل معروض الآن؟ الأفقي مسموح فقط أثناء تشغيل فيديو.
    /// الكتابة داخلية (وليست خاصة) ليتمكن AppDelegate من فرض الرأسي عند الإقلاع.
    var playerVisible = false

    /// الاتجاهات المسموحة حالياً — يقرأها AppDelegate عند كل طلب دوران.
    /// `allButUpsideDown` يستثني الوضع المقلوب رأساً على عقب صراحةً، وهو الوضع
    /// الذي كان يشتكي منه المستخدم في شاشة الإعدادات.
    var mask: UIInterfaceOrientationMask {
        playerVisible ? .allButUpsideDown : .portrait
    }

    private override init() { super.init() }

    private var activeScene: UIWindowScene? {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        return scenes.first { $0.activationState == .foregroundActive } ?? scenes.first
    }

    /// يحدّث حالة القفل ثم يطلب من النظام إعادة تقييم الاتجاه فوراً.
    func setPlayerVisible(_ visible: Bool) {
        guard playerVisible != visible else { return }
        playerVisible = visible
        apply()
    }

    /// يعيد فرض القناع الحالي على النظام.
    func apply() {
        guard let scene = activeScene else { return }
        // يخبر النظام أن الجهات المسموحة تغيّرت؛ بدون هذه الخطوة يستمر في
        // قبول الدوران الحر لأن قناع Info.plist يشمل الأفقي.
        scene.keyWindow?.rootViewController?.setNeedsUpdateOfSupportedInterfaceOrientations()
        scene.requestGeometryUpdate(.iOS(interfaceOrientations: mask)) { _ in
            // الفشل هنا غير حرج: القناع يُحترم عند أي تدوير قادم على أي حال.
        }
    }

    /// تدوير يدوي داخل المشغّل بين الأفقي والرأسي.
    func toggleLandscape(currentlyLandscape: Bool) {
        guard let scene = activeScene else { return }
        let targetMask: UIInterfaceOrientationMask = currentlyLandscape ? .portrait : .landscape
        scene.keyWindow?.rootViewController?.setNeedsUpdateOfSupportedInterfaceOrientations()
        scene.requestGeometryUpdate(.iOS(interfaceOrientations: targetMask)) { _ in }
    }
}

// MARK: - ربط القفل بدورة حياة التطبيق

/// @MainActor لأن `OrientationLock` مُعلَّم به، ونداءات UIApplicationDelegate
/// تأتي على الخيط الرئيسي أصلاً.
@MainActor
final class AppDelegate: NSObject, UIApplicationDelegate {

    /// الاتجاهات المقبولة من النظام. بدون هذا التفويض يتجاهل iOS قيمة
    /// `OrientationLock.mask` ويعتمد Info.plist وحده (الذي يشمل الأفقي).
    func application(_ application: UIApplication,
                     supportedInterfaceOrientationsFor window: UIWindow?) -> UIInterfaceOrientationMask {
        // `playerVisible` يبدأ false، فالتطبيق يفتح عمودياً دائماً حتى لو أُغلق
        // سابقاً داخل المشغّل وهو أفقي.
        OrientationLock.shared.mask
    }
}

extension UIWindowScene {
    var keyWindow: UIWindow? {
        windows.first { $0.isKeyWindow } ?? windows.first
    }
}
