package com.subwayisland.map

import com.subwayisland.core.Config
import com.subwayisland.core.XLog
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

/**
 * 导航内部类「侦察器」——只读，不 Hook，默认关闭。
 *
 * 存在意义：通知正文能拿到线路、下一站，但拿不到「列车实时位置」「车厢拥挤度」
 * 「精确到秒的到站倒计时」。这些字段只存在于各家导航 SDK 的回调里，而它们的
 * 类名/方法名随版本变化，任何硬编码的 Hook 都会在下个版本失效。
 *
 * 所以这里的定位不是「实现 Hook」，而是把「候选类是否存在、里面有哪些
 * 跟导航相关的方法」打到 LSPosed 日志里，让需要精确定位的人在真机上一次性
 * 看清目标版本的真实结构，再自己往 [MapNotifyHook] 里加一条精确 Hook。
 *
 * 打开方式：模块内「调试」里勾选 nav_recon，然后重启地图 App。
 */
object NavRecon {

    /** 已知版本里出现过的候选类名，命中与否本身就有诊断价值 */
    private val CANDIDATES = listOf(
        // 百度地图（导航 SDK）
        "com.baidu.navisdk.adapter.BaiduNaviManagerFactory",
        "com.baidu.navisdk.adapter.BNRouteGuideManager",
        "com.baidu.navisdk.comapi.routeguide.RouteGuideParams",
        "com.baidu.navisdk.module.ugc.model.NaviInfo",
        "com.baidu.navisdk.adapter.struct.BNaviGuidanceInfo",
        // 高德地图（AMapNavi）
        "com.amap.api.navi.AMapNavi",
        "com.amap.api.navi.model.NaviInfo",
        "com.amap.api.navi.model.AMapNaviGuide",
        "com.autonavi.bundle.navi.internal.NaviServiceImpl",
        "com.autonavi.navi.NaviServiceImpl"
    )

    private val KEYWORDS = Regex(
        "(?i)(navi|guide|station|subway|transit|route|line|next|arrive|eta)"
    )

    fun installIfEnabled(lpparam: LoadPackageParam) {
        if (!Config.navRecon) return
        runCatching {
            XLog.i("RECON-01", "start|pkg=${lpparam.packageName}|candidates=${CANDIDATES.size}")
            var hits = 0
            for (name in CANDIDATES) {
                val clazz = XposedHelpers.findClassIfExists(name, lpparam.classLoader) ?: continue
                hits++
                XLog.i("RECON-02", "hit|$name")
                runCatching {
                    clazz.declaredMethods
                        .filter { KEYWORDS.containsMatchIn(it.name) && !it.isSynthetic }
                        .take(25)
                        .forEach { m ->
                            XLog.i("RECON-03", "method|${clazz.simpleName}#${m.name}|ret=${m.returnType.simpleName}|args=${m.parameterTypes.joinToString { it.simpleName }}")
                        }
                }.onFailure { XLog.d("RECON-04", "method_fail|${it.message}") }
            }
            XLog.i("RECON-05", "done|hits=$hits/${CANDIDATES.size}")
        }.onFailure { XLog.e("RECON-06", "fail", it) }
    }
}
