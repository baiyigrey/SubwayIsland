package com.subwayisland.map

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import com.subwayisland.core.Config
import com.subwayisland.core.HyperOsCompat
import com.subwayisland.core.IslandPayload
import com.subwayisland.core.NavBus
import com.subwayisland.core.NavParser
import com.subwayisland.core.XLog
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

/**
 * 地图进程侧：捕获导航通知，抽出状态并广播给 SystemUI。
 *
 * 为什么 Hook `NotificationManager.notify` 而不是百度/高德的内部导航类：
 *  - 这两个方法属于 framework，名字永远不会被混淆，也不随 App 版本变化；
 *  - 导航状态最终一定要落到一条常驻通知上，这是业务上绕不过去的稳定接口；
 *  - 内部导航类（`NaviInfo` / `GuideInfo` 之类）每个版本都可能改名、改签名，
 *    把模块绑在上面等于把可维护性交给地图团队。
 * 内部类的侦察能力保留在 [NavRecon] 里：默认关闭，只打日志不 Hook。
 *
 * 日志事件码：
 *   MAP-01 Hook 装配完成          MAP-02 解析到导航状态（去重后打）
 *   MAP-03 解析异常               MAP-04 未解析出导航（同 key 只记一次）
 *   MAP-07 地图侧注入成功
 */
object MapNotifyHook {

    fun install(lpparam: LoadPackageParam) {
        val pkg = lpparam.packageName
        hookNotify(pkg)
        hookStartForeground(pkg)
        NavRecon.installIfEnabled(lpparam)
        XLog.i("MAP-01", "hook|notify+foreground|pkg=$pkg|inApp=${Config.inAppInject}")
    }

    private fun hookNotify(pkg: String) {
        runCatching {
            XposedBridge.hookAllMethods(
                NotificationManager::class.java, "notify", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val n = param.args.firstOrNull { it is Notification } as? Notification ?: return
                        handle(pkg, n)
                    }
                }
            )
            XposedBridge.hookAllMethods(
                NotificationManager::class.java, "notifyAsPackage", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val n = param.args.firstOrNull { it is Notification } as? Notification ?: return
                        handle(pkg, n)
                    }
                }
            )
        }.onFailure { XLog.e("MAP-05", "hook|notify|fail|pkg=$pkg", it) }
    }

    /**
     * 导航通知多数是通过 startForeground 挂上去的，这条路径不经过
     * NotificationManager.notify，必须单独 Hook，否则会漏掉大部分导航场景。
     */
    private fun hookStartForeground(pkg: String) {
        runCatching {
            XposedBridge.hookAllMethods(Service::class.java, "startForeground", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val n = param.args.firstOrNull { it is Notification } as? Notification ?: return
                    handle(pkg, n)
                }
            })
        }.onFailure { XLog.e("MAP-06", "hook|startForeground|fail|pkg=$pkg", it) }
    }

    private var lastSignature: String = ""
    private var lastMissSignature: String = ""

    private fun handle(pkg: String, n: Notification) {
        try {
            val channelId = runCatching { n.channelId }.getOrNull().orEmpty()
            val state = NavParser.parse(pkg, n, source = "hook")
            if (state == null) {
                // 地图会频繁发普通通知，这里必须去重，否则日志没法看
                val miss = "$pkg|$channelId"
                if (miss != lastMissSignature) {
                    lastMissSignature = miss
                    XLog.d("MAP-04", "not_navi|pkg=$pkg|ch=$channelId")
                }
                return
            }

            // 同一状态不重复广播，避免每秒一次的 notify 把 SystemUI 刷爆
            val signature = listOf(
                state.lineName, state.nextStation, state.destination,
                state.remainStations, state.etaMinutes, state.etaText, state.terminal
            ).joinToString("|")
            if (signature != lastSignature) {
                lastSignature = signature
                XLog.i(
                    "MAP-02",
                    "state|pkg=$pkg|line=${state.lineName}|next=${state.nextStation}" +
                        "|dest=${state.destination}|remain=${state.remainStations}" +
                        "|eta=${state.etaText ?: state.etaMinutes}|terminal=${state.terminal}"
                )
            }

            if (state.isNavigating || state.terminal) {
                NavBus.publish(state)
            }

            // 备路径：SystemUI 侧 Hook 失效时，直接在地图进程里把参数挂上。
            // 默认关闭——两条路径同时开会产生重复注入。
            if (Config.inAppInject && state.isNavigating && HyperOsCompat.supportsIsland()) {
                val bytes = IslandPayload.attach(n, state, HyperOsCompat.protocolVersion())
                if (bytes > 0) XLog.i("MAP-07", "inapp_inject|ok|pkg=$pkg|bytes=$bytes")
            }
        } catch (t: Throwable) {
            XLog.e("MAP-03", "handle|fail|pkg=$pkg", t)
        }
    }
}
