package com.subwayisland

import android.os.Build
import com.subwayisland.core.Config
import com.subwayisland.core.HyperOsCompat
import com.subwayisland.core.XLog
import com.subwayisland.map.MapNotifyHook
import com.subwayisland.systemui.SystemUiHook
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

/**
 * 模块入口，由 `assets/xposed_init` 声明。
 *
 * 作用域三个进程，各自职责单一：
 *  - com.android.systemui    读通知 → 补 miui.focus.param → 交给系统渲染超级岛
 *  - com.baidu.BaiduMap      采集导航状态 → 广播给 SystemUI
 *  - com.autonavi.minimap    同上
 *
 * 只要 SystemUI 那一侧工作，模块就能跑起来；地图侧只是让数据更准。
 * 这样即使某天百度/高德改了实现，最坏也只是「退回通知正文解析」。
 *
 * 日志事件码：
 *   INIT-01 SystemUI 装载            INIT-02 模块被关闭
 *   INIT-03 非小米设备，跳过          INIT-04 SystemUI Hook 安装失败
 *   INIT-05 地图侧装载               INIT-06 地图 Hook 安装失败
 */
class XposedInit : IXposedHookLoadPackage, IXposedHookZygoteInit {

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        Config.modulePath = startupParam.modulePath ?: ""
    }

    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        Config.load()

        when (lpparam.packageName) {
            Config.PKG_SYSTEMUI -> installSystemUi(lpparam)
            Config.PKG_BAIDU, Config.PKG_AMAP, Config.PKG_TENCENT -> installMap(lpparam)
            else -> return
        }
    }

    private fun installSystemUi(lpparam: LoadPackageParam) {
        if (!Config.enabled) {
            XLog.i("INIT-02", "skip|disabled")
            return
        }
        if (!HyperOsCompat.isXiaomi) {
            XLog.i("INIT-03", "skip|not_xiaomi|brand=${Build.BRAND}|manufacturer=${Build.MANUFACTURER}")
            return
        }
        XLog.i(
            "INIT-01",
            "systemui|loaded|api=${Build.VERSION.SDK_INT}|sdk=${Build.VERSION.RELEASE}" +
                "|proto=${HyperOsCompat.protocolVersion()}|island=${HyperOsCompat.supportsIsland()}" +
                "|enrich=${Config.systemUiEnrich}|inApp=${Config.inAppInject}"
        )
        runCatching { SystemUiHook.install(lpparam.classLoader) }
            .onFailure { XLog.e("INIT-04", "systemui|hook_fail", it) }
    }

    private fun installMap(lpparam: LoadPackageParam) {
        if (!Config.enabled) return
        runCatching { MapNotifyHook.install(lpparam) }
            .onFailure { XLog.e("INIT-06", "map|hook_fail|pkg=${lpparam.packageName}", it) }
    }
}
