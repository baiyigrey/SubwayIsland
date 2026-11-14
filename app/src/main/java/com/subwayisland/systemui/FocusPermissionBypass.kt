package com.subwayisland.systemui

import android.os.SystemClock
import com.subwayisland.core.Config
import com.subwayisland.core.XLog
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.concurrent.atomic.AtomicLong

/**
 * 放开「焦点通知」的权限校验与签名校验。
 *
 * 背景：第三方 App 想在 HyperOS 上把通知渲染成超级岛，除了要带
 * `miui.focus.param`，SystemUI 还会检查两件事：
 *   1. 该应用是否开启了「焦点通知」权限（NotificationSettingsManager#canShowFocus / canCustomFocus）；
 *   2. 该通知是否来自小米白名单签名（focus.SignatureChecker#checkSignatures）。
 * 百度/高德都不可能满足第 2 条，所以必须在这里放行，否则参数写了也白写。
 *
 * 安全边界：只对目标包名 + 注入后 3 秒窗口生效，不做全局解锁。
 *
 * 日志事件码：
 *   PERM-01 Hook 成功（带方法数）   PERM-02 类不存在
 *   PERM-03 放行（按包名/时间窗）   PERM-04 拒绝
 */
object FocusPermissionBypass {

    private const val ARM_WINDOW_MS = 3_000L

    private val armedAt = AtomicLong(0L)

    @Volatile
    private var lastDecision = ""

    /** 刚给某条通知注入过参数，短时间内放行校验 */
    fun arm() {
        armedAt.set(SystemClock.elapsedRealtime())
    }

    private fun recentlyArmed(): Boolean {
        val at = armedAt.get()
        return at != 0L && SystemClock.elapsedRealtime() - at <= ARM_WINDOW_MS
    }

    fun install(cl: ClassLoader) {
        hookBoolean(
            cl,
            "miui.systemui.notification.NotificationSettingsManager",
            "canShowFocus", "canCustomFocus"
        )
        hookBoolean(
            cl,
            "miui.systemui.notification.focus.SignatureChecker",
            "checkSignatures"
        )
    }

    private fun hookBoolean(cl: ClassLoader, className: String, vararg methodNames: String) {
        val clazz = XposedHelpers.findClassIfExists(className, cl)
        if (clazz == null) {
            XLog.d("PERM-02", "missing|$className")
            return
        }
        var count = 0
        for (name in methodNames) {
            runCatching {
                XposedBridge.hookAllMethods(clazz, name, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val pkg = param.args.firstOrNull { it is String } as? String
                        // 三种放行：目标地图包名；自测通知（本 App）且刚注入过；拿不到包名但在注入窗口内
                        val byPkg = pkg != null && Config.isTargetMap(pkg)
                        val bySelf = pkg != null && Config.isSelf(pkg) && recentlyArmed()
                        val byWindow = pkg == null && recentlyArmed()
                        if (!byPkg && !bySelf && !byWindow) {
                            logDecision("PERM-04", "deny|$className#$name|pkg=$pkg")
                            return
                        }
                        val returnType = (param.method as? java.lang.reflect.Method)?.returnType
                        when (returnType) {
                            java.lang.Boolean.TYPE -> param.result = true
                            // PackageManager 签名校验：0 == SIGNATURE_MATCH
                            java.lang.Integer.TYPE -> param.result = 0
                        }
                        logDecision(
                            "PERM-03",
                            "allow|$className#$name|pkg=$pkg|byPkg=$byPkg|bySelf=$bySelf|byWindow=$byWindow"
                        )
                    }
                })
                count++
            }.onFailure { XLog.d("PERM-02", "hook_fail|$className#$name|${it.message}") }
        }
        if (count > 0) XLog.i("PERM-01", "hook|$className|methods=$count")
    }

    /** 这个类会被高频调用，日志做去重，避免把 LSPosed 日志刷爆 */
    private fun logDecision(code: String, detail: String) {
        if (!XLog.verbose) return
        if (detail == lastDecision) return
        lastDecision = detail
        XLog.d(code, detail)
    }
}
