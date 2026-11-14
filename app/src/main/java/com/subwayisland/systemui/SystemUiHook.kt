package com.subwayisland.systemui

import android.app.Application
import android.content.Context
import android.os.Build
import android.service.notification.StatusBarNotification
import com.subwayisland.core.Config
import com.subwayisland.core.HyperOsCompat
import com.subwayisland.core.StatusBridge
import com.subwayisland.core.XLog
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.Collections
import java.util.WeakHashMap

/**
 * SystemUI 侧 Hook 装配。
 *
 * 参考 dsha-island 的三处关键 Hook：
 *  1. `MiuiBaseNotifUtil.generateInnerNotifBean(StatusBarNotification)`——
 *     通知进入「焦点通知 / 超级岛」渲染管线的必经点，在这里改 extras 最省事；
 *  2. `MiuiNotificationListener.onNotificationPosted`——管线类名/包结构随版本漂移时的兜底；
 *  3. `PluginInstance$PluginFactory.createPluginContext`——HyperOS 把焦点通知逻辑
 *     挪进 SystemUI 插件 APK 后，主 ClassLoader 里找不到类，需要在插件 ClassLoader 上重装。
 *
 * HyperOS 4（API 37）上类名是否还叫这个没法离线确认，所以：
 *  - 主类名找不到时依次尝试候选包路径；
 *  - 无论命中与否，都把结果通过 [StatusBridge] 回报给 App，
 *    用户不用 root、不用 adb 就能看到「到底卡在哪一步」。
 *
 * 日志事件码：
 *   HOOK-01 主 Hook 命中     HOOK-02 主 Hook 类不存在（走兜底）
 *   HOOK-03 监听器 Hook      HOOK-04 缺失监听器
 *   HOOK-05 Application 钩子 HOOK-06 插件 ClassLoader 重装
 */
object SystemUiHook {

    /** HyperOS 4 可能换了包路径，按可能性从高到低尝试 */
    private val PIPELINE_CLASSES = arrayOf(
        "com.miui.systemui.notification.MiuiBaseNotifUtil",
        "miui.systemui.notification.MiuiBaseNotifUtil",
        "com.android.systemui.statusbar.notification.MiuiBaseNotifUtil",
        "com.miui.systemui.notification.MiuiNotifUtil"
    )

    private val installedLoaders = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<ClassLoader, Boolean>())
    )

    @Volatile
    private var appHookInstalled = false

    /** 管线 Hook 的结论已经回报过（命中或缺失），Application.onCreate 不再覆盖 */
    @Volatile
    private var pipelineReported = false

    /** 主类名缺失时，等拿到 Context 后扫一遍 SystemUI 的类名，好定位新版本改成了什么 */
    @Volatile
    private var reconNeeded = false

    fun install(cl: ClassLoader) {
        if (!installedLoaders.add(cl)) return

        hookPipeline(cl)
        hookNotificationListener(cl)
        hookApplicationCreate()
        FocusPermissionBypass.install(cl)
        hookPluginFactory(cl)
    }

    // ------------------------------------------------------------ 主路径

    private fun hookPipeline(cl: ClassLoader) {
        var found: Class<*>? = null
        var hitName: String? = null
        for (name in PIPELINE_CLASSES) {
            val c = XposedHelpers.findClassIfExists(name, cl)
            if (c != null) {
                found = c
                hitName = name
                break
            }
        }
        val target = found
        val className = hitName
        if (target == null || className == null) {
            pipelineReported = true
            XLog.i(
                "HOOK-02",
                "pipeline|missing|candidates=${PIPELINE_CLASSES.size}|loader=${cl.javaClass.simpleName}" +
                    "|api=${Build.VERSION.SDK_INT}"
            )
            // 小米管线类不在（HyperOS 4 可能改名/移入插件），退到 AOSP 标准回调，
            // 这条路在任意 Android 版本上都存在，是「至少不空转」的保底
            hookAospListenerFallback()
            reconNeeded = true
            StatusBridge.report(
                StatusBridge.STAGE_HOOK_MISS,
                "MiuiBaseNotifUtil 未找到|已启用 AOSP 回调兜底|api=${Build.VERSION.SDK_INT}"
            )
            return
        }

        var count = 0
        runCatching {
            XposedBridge.hookAllMethods(target, "generateInnerNotifBean", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val sbn = param.args.firstOrNull { it is StatusBarNotification } as? StatusBarNotification
                    NotificationEnricher.onSbn(sbn)
                }
            })
            count = target.declaredMethods.count { it.name == "generateInnerNotifBean" }
        }.onFailure { XLog.e("HOOK-01", "pipeline|hook_fail|$className", it) }

        pipelineReported = true
        XLog.i("HOOK-01", "pipeline|ok|class=$className|overloads=${count.coerceAtLeast(1)}")
        StatusBridge.report(
            StatusBridge.STAGE_HOOK_OK,
            "class=${className.substringAfterLast('.')}|proto=${HyperOsCompat.protocolVersion()}"
        )
    }

    // ------------------------------------------------------------ 兜底路径

    /**
     * AOSP 兜底：直接挂 `NotificationListenerService#onNotificationPosted`。
     *
     * 这条路径不依赖任何小米私有类名，代价是时机偏晚（通知已经进来，
     * 岛可能来不及展开），所以只作为 MIUI 管线缺失时的降级方案。
     */
    private fun hookAospListenerFallback() {
        runCatching {
            val cls = XposedHelpers.findClass(
                "android.service.notification.NotificationListenerService", null
            )
            XposedBridge.hookAllMethods(cls, "onNotificationPosted", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val sbn = param.args.firstOrNull { it is StatusBarNotification } as? StatusBarNotification
                    NotificationEnricher.onSbn(sbn)
                }
            })
            XLog.i("HOOK-07", "fallback|NotificationListenerService|ok")
        }.onFailure { XLog.e("HOOK-07", "fallback|fail", it) }
    }

    private fun hookNotificationListener(cl: ClassLoader) {
        val clazz = XposedHelpers.findClassIfExists(
            "com.android.systemui.statusbar.notification.MiuiNotificationListener", cl
        )
        if (clazz == null) {
            XLog.d("HOOK-04", "listener|MiuiNotificationListener|missing")
            return
        }
        XposedBridge.hookAllMethods(clazz, "onNotificationPosted", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val sbn = param.args.firstOrNull { it is StatusBarNotification } as? StatusBarNotification
                NotificationEnricher.onSbn(sbn)
            }
        })
        XLog.i("HOOK-03", "listener|MiuiNotificationListener|ok")
    }

    /** 拿到 SystemUI 的 Context：注册广播接收器 + 回报运行状态 */
    private fun hookApplicationCreate() {
        // Application 属于 boot classloader，同一个方法被 Hook 多次会产生重复回调
        if (appHookInstalled) return
        appHookInstalled = true
        runCatching {
            XposedHelpers.findAndHookMethod(
                Application::class.java, "onCreate", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val app = param.thisObject as? Application ?: return
                        Config.load(force = true)
                        NotificationEnricher.registerReceiverOn(app)

                        // attach 会把「已装载 / 注入点是否命中」补发出去
                        StatusBridge.attach(app)
                        StatusBridge.registerResponder(app)
                        if (reconNeeded) {
                            reconNeeded = false
                            reconSystemUi(app)
                        }
                        if (!pipelineReported) {
                            pipelineReported = true
                            StatusBridge.report(
                                StatusBridge.STAGE_LOADED,
                                "api=${Build.VERSION.SDK_INT}|proto=${HyperOsCompat.protocolVersion()}" +
                                    "|island=${HyperOsCompat.supportsIsland()}"
                            )
                        }
                    }
                }
            )
            XLog.i("HOOK-05", "application|onCreate|ok")
        }.onFailure { XLog.e("HOOK-05", "application|onCreate|fail", it) }
    }

    /**
     * 主类名找不到时，把 SystemUI 里名字带 Notif/Focus/Island 的类打出来。
     *
     * 这一步只在失败分支执行一次：不能上真机调试的情况下，
     * 用户贴一行 `HOOK-08` 就能告诉我新版本把类挪到哪去了，省掉一轮盲猜。
     */
    private fun reconSystemUi(ctx: Application) {
        runCatching {
            val src = ctx.applicationInfo?.sourceDir ?: return
            val dex = dalvik.system.DexFile(src)
            val hits = ArrayList<String>()
            try {
                val entries = dex.entries()
                while (entries.hasMoreElements()) {
                    val name = entries.nextElement()
                    val simple = name.substringAfterLast('.')
                    if (simple.contains("NotifUtil") || simple.contains("FocusNotif") ||
                        simple.contains("NotifFocus") || simple.contains("Island") ||
                        simple.contains("FocusManager")
                    ) {
                        hits += name
                        if (hits.size >= 30) break
                    }
                }
            } finally {
                runCatching { dex.close() }
            }
            XLog.i("HOOK-08", "recon|count=${hits.size}|" + hits.joinToString(","))
        }.onFailure { XLog.d("HOOK-08", "recon|fail|${it.message}") }
    }

    /** HyperOS 部分版本把焦点通知实现放在 SystemUI 插件里，需要在插件 ClassLoader 上重装 */
    private fun hookPluginFactory(cl: ClassLoader) {
        val factory = XposedHelpers.findClassIfExists(
            "com.android.systemui.shared.plugins.PluginInstance\$PluginFactory", cl
        ) ?: return
        runCatching {
            XposedBridge.hookAllMethods(factory, "createPluginContext", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val ctx = param.result as? Context ?: return
                    val pluginLoader = ctx.classLoader ?: return
                    XLog.d("HOOK-06", "plugin|reinstall|loader=${pluginLoader.javaClass.simpleName}")
                    runCatching { install(pluginLoader) }
                        .onFailure { XLog.e("HOOK-06", "plugin|install_fail", it) }
                }
            })
        }.onFailure { XLog.d("HOOK-06", "plugin|hook_fail|${it.message}") }
    }
}
