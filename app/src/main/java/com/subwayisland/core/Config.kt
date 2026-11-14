package com.subwayisland.core

import android.content.SharedPreferences
import de.robv.android.xposed.XSharedPreferences

/**
 * 模块配置。
 *
 * 采用 XSharedPreferences 读取 Module App 的私有 prefs：
 *  - 写入：MainActivity 用普通 getSharedPreferences 写；
 *  - 读取：被 Hook 的进程（SystemUI / 地图）通过 XSharedPreferences 读。
 *
 * 注意：LSPosed 对 XSharedPreferences 的支持是有条件的（模块进程需要能读到
 * 目标文件）。因此这里所有读取都做了兜底——读不到就使用「全部开启」的默认值，
 * 保证模块在配置读取失败时仍然可用，只是无法在线切换开关。
 */
object Config {

    const val PREFS_NAME = "subway_island"
    const val MODULE_PKG = "com.subwayisland"

    const val PKG_SYSTEMUI = "com.android.systemui"
    const val PKG_BAIDU = "com.baidu.BaiduMap"
    const val PKG_AMAP = "com.autonavi.minimap"
    const val PKG_TENCENT = "com.tencent.map"

    /** 三个地图的作用域包名，Diagnostics 与作用域数组都按这个顺序展示 */
    val MAP_PKGS = listOf(PKG_BAIDU, PKG_AMAP, PKG_TENCENT)

    fun displayName(pkg: String): String = when (pkg) {
        PKG_BAIDU -> "百度地图"
        PKG_AMAP -> "高德地图"
        PKG_TENCENT -> "腾讯地图"
        PKG_SYSTEMUI -> "系统界面"
        else -> pkg
    }

    private const val KEY_ENABLED = "enabled"
    private const val KEY_SYSTEMUI_ENRICH = "systemui_enrich"
    private const val KEY_INAPP_INJECT = "inapp_inject"
    private const val KEY_MODULE_ICON = "module_icon"
    private const val KEY_PROMOTED_FALLBACK = "promoted_fallback"
    private const val KEY_RECON = "nav_recon"
    private const val KEY_VERBOSE = "verbose"

    /** 模块 APK 路径，initZygote 时由框架注入，仅用于诊断展示。 */
    @Volatile
    var modulePath: String = ""

    @Volatile
    var enabled: Boolean = true
        private set

    /** 主路径：在 SystemUI 进程富化地图通知（dsha-island 已验证的路径）。 */
    @Volatile
    var systemUiEnrich: Boolean = true
        private set

    /** 备路径：在地图进程内直接给通知挂参数（SystemUI Hook 失效时兜底）。 */
    @Volatile
    var inAppInject: Boolean = false
        private set

    /** 岛屿图标使用模块内置地铁图标，而不是地图 App 的小图标。 */
    @Volatile
    var useModuleIcon: Boolean = true
        private set

    /** 官方参数被系统拒绝时，退化为 AOSP promoted ongoing（胶囊）。 */
    @Volatile
    var promotedFallback: Boolean = true
        private set

    /** 调试：侦察地图 App 内导航相关类名（只打日志，不 Hook）。 */
    @Volatile
    var navRecon: Boolean = false
        private set

    @Volatile
    private var lastLoadAt: Long = 0L

    fun load(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastLoadAt < 5_000L) return
        lastLoadAt = now
        runCatching {
            val prefs = XSharedPreferences(MODULE_PKG, PREFS_NAME)
            runCatching { prefs.makeWorldReadable() }
            prefs.reload()
            apply(prefs)
            XLog.d("CFG-01", "loaded|enabled=$enabled|enrich=$systemUiEnrich|inApp=$inAppInject")
        }.onFailure {
            XLog.d("CFG-02", "defaults|${it.message}")
        }
    }

    private fun apply(prefs: SharedPreferences) {
        enabled = prefs.getBoolean(KEY_ENABLED, true)
        systemUiEnrich = prefs.getBoolean(KEY_SYSTEMUI_ENRICH, true)
        inAppInject = prefs.getBoolean(KEY_INAPP_INJECT, false)
        useModuleIcon = prefs.getBoolean(KEY_MODULE_ICON, true)
        promotedFallback = prefs.getBoolean(KEY_PROMOTED_FALLBACK, true)
        navRecon = prefs.getBoolean(KEY_RECON, false)
        XLog.verbose = prefs.getBoolean(KEY_VERBOSE, true)
    }

    /** 模块 App 进程内直接读自己的 prefs，保证界面上显示的开关是真实值 */
    fun syncFrom(prefs: SharedPreferences) {
        runCatching { apply(prefs) }
        lastLoadAt = System.currentTimeMillis()
    }

    fun isTargetMap(pkg: String?): Boolean = pkg != null && pkg in MAP_PKGS

    /** 自测通知来自模块自己，只在带自测标记时才进入富化流程 */
    fun isSelf(pkg: String?): Boolean = pkg == MODULE_PKG

    /** 供 MainActivity 使用的 key 常量，避免两处硬编码漂移。 */
    object Keys {
        const val ENABLED = KEY_ENABLED
        const val SYSTEMUI_ENRICH = KEY_SYSTEMUI_ENRICH
        const val INAPP_INJECT = KEY_INAPP_INJECT
        const val MODULE_ICON = KEY_MODULE_ICON
        const val PROMOTED_FALLBACK = KEY_PROMOTED_FALLBACK
        const val RECON = KEY_RECON
        const val VERBOSE = KEY_VERBOSE
    }
}
