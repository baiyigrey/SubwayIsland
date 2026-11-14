package com.subwayisland.core

import android.app.Notification
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import com.subwayisland.R

/**
 * 把「参数 JSON + 图片 Bundle + 按钮 Bundle」作为一个整体产出。
 *
 * 三者必须保持一致：JSON 里写 `miui.focus.pic_subway`，`miui.focus.pics` 里就必须
 * 真有这个 key，否则岛上那块区域会空白甚至整条通知退化。把它们放在同一个函数里
 * 生成，是为了从结构上避免这种错配（dsha-island 的 IslandPayload 也是同一思路）。
 */
object IslandPayload {

    data class Result(
        val json: String,
        val pics: Bundle,
        val actions: Bundle?
    ) {
        val bytes: Int get() = json.toByteArray(Charsets.UTF_8).size
        val actionCount: Int get() = if (actions == null) 0 else actions.size()
    }

    /** 参数体积上限，超了系统会直接丢弃，宁可退化也不留一条坏通知 */
    private const val MAX_JSON_BYTES = 3_000

    fun of(n: Notification, state: NavState, protocol: Int): Result? {
        val specs = ArrayList<FocusParamBuilder.ActionSpec>(2)
        val actionBundle = Bundle()

        val open = buildOpenAction(n)
        if (open != null) {
            actionBundle.putParcelable(FocusParamBuilder.ACTION_OPEN, open)
            specs += FocusParamBuilder.ActionSpec(
                FocusParamBuilder.ACTION_OPEN, "打开", FocusParamBuilder.PIC_OPEN
            )
        }

        val stop = findStopAction(n)
        if (stop != null) {
            actionBundle.putParcelable(FocusParamBuilder.ACTION_STOP, stop)
            specs += FocusParamBuilder.ActionSpec(
                FocusParamBuilder.ACTION_STOP, "结束", FocusParamBuilder.PIC_STOP
            )
        }

        val json = runCatching {
            FocusParamBuilder.build(
                state,
                FocusParamBuilder.Options(
                    protocol = protocol,
                    actions = specs,
                    withHint = open != null
                )
            )
        }.getOrElse {
            XLog.e("PAY-01", "build_fail", it)
            return null
        }

        val bytes = json.toByteArray(Charsets.UTF_8).size
        if (bytes > MAX_JSON_BYTES) {
            XLog.e("PAY-02", "too_large|bytes=$bytes|limit=$MAX_JSON_BYTES")
            return null
        }

        return Result(json, buildPics(n, state), actionBundle.takeIf { !it.isEmpty })
    }

    /**
     * 图片 Bundle。
     * 优先用模块内置的线路图标（Icon.createWithResource 支持跨包引用，
     * 渲染端通过 PackageManager 解析），失败则回落到地图 App 自己的小图标。
     *
     * key 必须与 JSON 里的 `pic` 一致：地铁用 pic_subway，公交用 pic_bus，
     * 否则那个区域会空白，甚至整条岛退化。
     */
    private fun buildPics(n: Notification, state: NavState): Bundle {
        // 岛主体用模块图标；按钮沿用地图 App 自己的小图标，视觉上更像「打开地图」
        val islandIcon = moduleIcon(state.mode) ?: n.smallIcon
        val buttonIcon = n.smallIcon ?: islandIcon
        val bundle = Bundle()
        islandIcon?.let { bundle.putParcelable(FocusParamBuilder.picKey(state), it) }
        buttonIcon?.let {
            bundle.putParcelable(FocusParamBuilder.PIC_OPEN, it)
            bundle.putParcelable(FocusParamBuilder.PIC_STOP, it)
        }
        return bundle
    }

    private fun moduleIcon(mode: TransitMode): Icon? {
        if (!Config.useModuleIcon) return null
        val res = if (mode == TransitMode.BUS) R.drawable.ic_island_bus else R.drawable.ic_island_subway
        return runCatching {
            Icon.createWithResource(Config.MODULE_PKG, res)
        }.getOrNull()
    }

    /** 用通知的 contentIntent 造一个「打开地图」按钮 */
    private fun buildOpenAction(n: Notification): Notification.Action? {
        val pi = n.contentIntent ?: return null
        val icon = n.smallIcon ?: return null
        return runCatching {
            Notification.Action.Builder(icon, "打开", pi).build()
        }.getOrNull()
    }

    /** 复用地图 App 自己通知里的「结束导航」按钮，点了走它原来的逻辑 */
    private fun findStopAction(n: Notification): Notification.Action? {
        val actions = n.actions ?: return null
        val keywords = arrayOf("结束", "退出", "停止", "End", "Stop", "Exit")
        for (action in actions) {
            val title = action.title?.toString() ?: continue
            if (keywords.any { title.contains(it, ignoreCase = true) }) return action
        }
        return null
    }

    /**
     * 把参数挂到通知上。SystemUI 侧的富化与地图进程内的注入共用这一段，
     * 保证两条路径产出的通知完全一致。
     */
    /** @return 参数体积（字节）；<=0 表示未注入 */
    fun attach(n: Notification, state: NavState, protocol: Int): Int {
        val extras = n.extras ?: return 0
        val payload = of(n, state, protocol) ?: return 0

        extras.remove(FocusParamBuilder.KEY_PARAM)
        extras.remove(FocusParamBuilder.KEY_PICS)
        extras.remove(FocusParamBuilder.KEY_ACTIONS)

        extras.putString(FocusParamBuilder.KEY_PARAM, payload.json)
        if (!payload.pics.isEmpty) extras.putBundle(FocusParamBuilder.KEY_PICS, payload.pics)
        payload.actions?.let { extras.putBundle(FocusParamBuilder.KEY_ACTIONS, it) }

        // 保底胶囊：官方参数被系统拒绝时，AOSP 的 promoted ongoing 至少能在
        // 状态栏显示一行短文本（dsha-island 在无焦点权限时用的就是这条兜底）。
        // 注意 EXTRA_REQUEST_PROMOTED_ONGOING 是 @SystemApi，公开 SDK 里没有常量，
        // 只能写字面量。
        if (Config.promotedFallback) {
            extras.putBoolean("android.requestPromotedOngoing", true)
            extras.putString("android.shortCriticalText", state.headline())
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            extras.putBoolean(Notification.EXTRA_COLORIZED, true)
        }
        return payload.bytes
    }

    /** 清除注入痕迹，防止导航结束后岛上残留 */
    fun detach(n: Notification) {
        val extras = n.extras ?: return
        extras.remove(FocusParamBuilder.KEY_PARAM)
        extras.remove(FocusParamBuilder.KEY_PICS)
        extras.remove(FocusParamBuilder.KEY_ACTIONS)
        extras.remove("android.requestPromotedOngoing")
        extras.remove("android.shortCriticalText")
    }
}
