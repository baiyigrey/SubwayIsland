package com.subwayisland.core

import android.app.AndroidAppHelper
import android.content.Context
import android.os.Build
import android.provider.Settings
import java.util.Locale

/**
 * HyperOS 版本 / 协议能力探测。
 *
 * 依据小米官方《开发指南》第五节的查询接口：
 *  - persist.sys.feature.island      是否支持超级岛
 *  - notification_focus_protocol     1=OS1 焦点通知, 2=OS2 焦点通知, 3=OS3+ 超级岛
 *
 * 做成独立对象的目的是「协议适配收敛到一处」：HyperOS 4 的小版本若改协议，
 * 只需要动这个类和 FocusParamBuilder，不必翻遍 Hook 代码。
 */
object HyperOsCompat {

    @Volatile
    private var cachedProtocol: Int = -1

    @Volatile
    private var cachedIsland: Boolean? = null

    val isXiaomi: Boolean by lazy {
        val name = (Build.MANUFACTURER + " " + Build.BRAND).lowercase(Locale.ROOT)
        name.contains("xiaomi") || name.contains("redmi") || name.contains("poco")
    }

    /**
     * 协议版本。读不到时给 3：HyperOS 3/4 都是 3，而旧 OS 上模块本来也不该被启用。
     */
    fun protocolVersion(context: Context? = null): Int {
        val cached = cachedProtocol
        if (cached > 0) return cached
        var value = 0
        runCatching {
            // 模块 App 进程里没有 AndroidAppHelper，优先用调用方给的 Context
            val ctx = context ?: AndroidAppHelper.currentApplication()
            value = Settings.System.getInt(ctx.contentResolver, "notification_focus_protocol", 0)
        }
        val result = if (value in 1..9) value else 3
        cachedProtocol = result
        return result
    }

    /** 是否支持超级岛（不是所有 OS3 机型都开岛） */
    fun supportsIsland(): Boolean {
        cachedIsland?.let { return it }
        val prop = runCatching {
            val clazz = Class.forName("android.os.SystemProperties")
            val get = clazz.getDeclaredMethod("getBoolean", String::class.java, Boolean::class.javaPrimitiveType)
            get.invoke(null, "persist.sys.feature.island", false) as? Boolean
        }.getOrNull()
        val result = prop ?: (protocolVersion() >= 3)
        cachedIsland = result
        return result
    }

    /** 诊断信息，展示在模块主界面 */
    fun describe(): String = buildString {
        append("设备: ").append(Build.MANUFACTURER).append(' ').append(Build.BRAND).append('\n')
        append("系统: HyperOS/Android ").append(Build.VERSION.RELEASE)
            .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
        append("协议版本: ").append(protocolVersion()).append('\n')
        append("超级岛: ").append(if (supportsIsland()) "支持" else "不支持或未探测到")
    }
}
