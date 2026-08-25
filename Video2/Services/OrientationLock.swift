import UIKit
import SwiftUI

/// إدارة اتجاه الشاشة بدون قفل التطبيق على الوضع الأفقي أو العمودي.
///
/// يعلن التطبيق الاتجاهات المعتادة للآيفون (عمودي وأفقي يمين/يسار)، لذلك
/// يتبع دوران الجهاز تلقائياً في كل الشاشات. الدالة الوحيدة هنا هي زر التدوير
/// الاختياري داخل المشغّل، وتطلب تغييراً لحظياً للاتجاه من دون تغيير الاتجاهات
/// التي يسمح بها التطبيق.
@MainActor
final class OrientationLock: NSObject {

    static let shared = OrientationLock()

    /// الاتجاهات التي يسمح بها التطبيق دائماً. الوضع المقلوب رأساً على عقب
    /// مستبعد لأنه ليس اتجاهاً طبيعياً للاستخدام في الآيفون.
    var mask: UIInterfaceOrientationMask { .allButUpsideDown }

    private override init() { super.init() }

    private var activeScene: UIWindowScene? {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        return scenes.first { $0.activationState == .foregroundActive } ?? scenes.first
    }

    /// تدوير اختياري داخل المشغّل بين الأفقي والرأسي.
    /// لا يثبت التطبيق على الاتجاه المطلوب؛ بعد الطلب يظل الدوران التلقائي
    /// متاحاً لأن `mask` لا يتغير.
    func toggleLandscape(currentlyLandscape: Bool) {
        guard let scene = activeScene else { return }
        let targetMask: UIInterfaceOrientationMask = currentlyLandscape ? .portrait : .landscape
        scene.keyWindow?.rootViewController?.setNeedsUpdateOfSupportedInterfaceOrientations()
        scene.requestGeometryUpdate(.iOS(interfaceOrientations: targetMask)) { _ in }
    }
}

// MARK: - ربط الاتجاهات بدورة حياة التطبيق

@MainActor
final class AppDelegate: NSObject, UIApplicationDelegate {

    /// الاتجاهات المعتادة متاحة في التطبيق كله؛ لا يتم فرض الوضع الأفقي عند
    /// فتح المشغّل ولا يتم قفل بقية الشاشات على الوضع العمودي.
    func application(_ application: UIApplication,
                     supportedInterfaceOrientationsFor window: UIWindow?) -> UIInterfaceOrientationMask {
        OrientationLock.shared.mask
    }
}

extension UIWindowScene {
    var keyWindow: UIWindow? {
        windows.first { $0.isKeyWindow } ?? windows.first
    }
}
