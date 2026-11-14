package com.subwayisland.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * 把 [NavState] 构造成 HyperOS 官方 `miui.focus.param`（`param_v2`）。
 *
 * 字段来源与依据：
 *  - 小米开放平台《开发指南》（dev.mi.com/xiaomihyperos/documentation/detail?pId=2131）
 *    的 `param_v2` / `param_island` / `bigIslandArea` / `smallIslandArea` / `baseInfo` 定义；
 *  - 《小米超级岛模板库》里「大岛 A 区 = 图文组件1(imageTextInfoLeft) + B 区 = 文本组件(textInfo)」
 *    这一组合（模板 2），也是 dsha-island 在真机上验证过的组合方式。
 *
 * 有意保持「纯函数 + 无状态」：协议一变，只改这个文件即可，
 * 这也是 dsha-island 把参数构造独立成 IslandPayload 的原因。
 */
object FocusParamBuilder {

    // ---- extras 键名（官方约定，不可改）----
    const val KEY_PARAM = "miui.focus.param"
    const val KEY_PICS = "miui.focus.pics"
    const val KEY_ACTIONS = "miui.focus.actions"

    // ---- 图片 / 按钮的资源 key，需与 miui.focus.pics / miui.focus.actions 中的键一致 ----
    const val PIC_SUBWAY = "miui.focus.pic_subway"
    const val PIC_BUS = "miui.focus.pic_bus"
    const val PIC_OPEN = "miui.focus.pic_open"
    const val PIC_STOP = "miui.focus.pic_stop"

    const val ACTION_OPEN = "miui.focus.action_open"
    const val ACTION_STOP = "miui.focus.action_stop"

    /** 进度条颜色，与 App 主题的 MIUI 蓝保持一致 */
    private const val COLOR_PROGRESS = "#FF3482FF"
    private const val COLOR_PROGRESS_TRACK = "#223482FF"

    /** 大岛上的按钮描述，调用方按它往 miui.focus.actions 里塞 Notification.Action */
    data class ActionSpec(
        val key: String,
        val title: String,
        val iconKey: String,
        /** 0=普通按钮 1=进度按钮 2=文字按钮 */
        val type: Int = 0,
        /** 0=跟随 miui.focus.actions 中的 PendingIntent */
        val intentType: Int = 0
    )

    data class Options(
        /** HyperOS 协议版本：>=3 才输出 param_island */
        val protocol: Int = 3,
        val actions: List<ActionSpec> = emptyList(),
        /** 首次出现是否自动展开为大岛 */
        val firstFloat: Boolean = true,
        /** 是否附带 hintInfo（焦点卡上的按钮行） */
        val withHint: Boolean = true
    )

    fun build(state: NavState, opt: Options = Options()): String {
        val param = JSONObject()
        param.put("protocol", 1)
        // 运营场景，官方用于数据统计；保持自解释的字符串即可
        param.put("business", "subway_navi")
        param.put("updatable", true)
        // 更新时不自动展开，避免每次到站都弹一次大岛打断用户
        param.put("enableFloat", false)
        param.put("islandFirstFloat", opt.firstFloat && !state.terminal)
        // 焦点通知权限被关时不要把整条通知过滤掉，至少还能在通知栏看到
        param.put("filterWhenNoPermission", false)

        param.put("ticker", state.ticker())
        param.put("tickerPic", picKey(state))
        param.put("aodTitle", state.ticker())
        param.put("aodPic", picKey(state))

        if (opt.protocol >= 3) {
            param.put("param_island", buildIsland(state))
        }

        param.put("baseInfo", buildBaseInfo(state))

        if (opt.withHint) {
            buildHint(state, opt)?.let { param.put("hintInfo", it) }
        }

        if (opt.actions.isNotEmpty()) {
            val arr = JSONArray()
            opt.actions.forEach { spec ->
                arr.put(
                    JSONObject()
                        .put("action", spec.key)
                        .put("actionIcon", spec.iconKey)
                        .put("actionIconDark", spec.iconKey)
                        .put("actionIntentType", spec.intentType)
                        .put("type", spec.type)
                )
            }
            param.put("actions", arr)
        }

        return JSONObject().put("param_v2", param).toString()
    }

    // ------------------------------------------------------------------ 岛内容

    private fun buildIsland(state: NavState): JSONObject {
        val island = JSONObject()
        // 1=信息展示为主 2=操作为主；纯展示场景固定 1
        island.put("islandProperty", 1)
        // 岛自动消失时间（秒）：进行中 1 小时，结束后 8 秒
        island.put("islandTimeout", if (state.terminal) 8 else 3600)
        island.put("highlightColor", "#1A73E8")
        island.put("islandOrder", true)

        island.put("bigIslandArea", buildBigArea(state))
        island.put("smallIslandArea", buildSmallArea(state))
        return island
    }

    /**
     * 大岛：A 区图文组件1（图标 + 线路名 + 下一站），B 区文本组件（剩余站 / ETA）。
     * A 区限制：大字建议不超过 4 个汉字，所以线路名放 title，「人民广场」放 content 小字。
     */
    private fun buildBigArea(state: NavState): JSONObject {
        val big = JSONObject()

        val leftText = JSONObject()
        // 没有线路名时用「地铁 / 公交」兜底，比空白更让人安心
        leftText.put("title", state.lineName ?: state.mode.label)
        state.nextStation?.let { leftText.put("content", it) }
        leftText.put("showHighlightColor", true)
        leftText.put("narrowFont", false)

        big.put(
            "imageTextInfoLeft",
            JSONObject()
                .put("type", 1)
                .put("picInfo", pic(state))
                .put("textInfo", leftText)
        )

        // B 区有三种形态，优先级从上到下：
        //  1. 已到换乘站 → 直接提示换乘线路，这时候「换乘」比「进度」重要
        //  2. 能推出进度 → 进度文本组件（模板库第 5 套「图文组件1 + 进度文本组件」）
        //  3. 推不出（比如只有「下一站：人民广场」）→ 普通文本组件
        val percent = state.progressPercent
        if (state.atTransfer && state.transferLine != null) {
            // 左侧 A 区已经写了「下一站」，这里只写换乘线路与动作，避免两行都重复站名
            val tip = JSONObject()
                .put("title", state.transferLine)
                .put("content", "下车换乘")
                .put("showHighlightColor", true)
                .put("narrowFont", false)
            big.put("textInfo", tip)
        } else if (percent != null) {
            big.put("progressTextInfo", buildProgressText(state, percent))
        } else {
            val remain = state.remainText()
            val eta = state.etaShort()
            val bTitle = remain ?: eta
            if (bTitle != null) {
                val rightText = JSONObject()
                rightText.put("title", bTitle)
                if (remain != null && eta != null) rightText.put("content", eta)
                rightText.put("showHighlightColor", false)
                rightText.put("narrowFont", false)
                big.put("textInfo", rightText)
            }
        }

        return big
    }

    /**
     * 进度文本组件：环形进度 + 「62%」大字 + 「约12分 / 3/8 站」小字。
     *
     * 字段依据《超级岛模板库》「进度文本组件 progressTextInfo」：
     * progressInfo 必传 progress，颜色可选；textInfo 里 title 是大字，
     * frontTitle / content 是前后小字，最多两段，所以这里严格只放两段。
     */
    private fun buildProgressText(state: NavState, percent: Int): JSONObject {
        val progress = JSONObject()
            .put("progress", percent.coerceIn(0, 100))
            .put("colorReach", COLOR_PROGRESS)
            .put("colorUnReach", COLOR_PROGRESS_TRACK)

        val text = JSONObject().put("title", "$percent%")
        state.etaShort()?.let { text.put("frontTitle", if (it.endsWith("分")) "约$it" else it) }
        state.remainWithTotal()?.let { text.put("content", it) }
        text.put("showHighlightColor", true)
        // 百分比是数字，窄体更紧凑，也符合 MIUI 的排版习惯
        text.put("narrowFont", true)

        return JSONObject().put("progressInfo", progress).put("textInfo", text)
    }

    /** 小岛只放图标；系统会优先取小岛图标，不传则回落到 A 区左侧图标 */
    private fun buildSmallArea(state: NavState): JSONObject =
        JSONObject().put("picInfo", pic(state))

    // ------------------------------------------------------------------ 焦点卡

    /**
     * 焦点通知（下拉通知栏 / 锁屏）用的文本组件。
     * type=1 布局：title + subTitle 一行（带分隔符），content 一行补充说明。
     */
    private fun buildBaseInfo(state: NavState): JSONObject {
        val base = JSONObject()
        base.put("type", 1)

        if (state.terminal) {
            base.put("title", "已到达")
            state.nextStation?.let { base.put("subTitle", it) }
            base.put("content", "行程已结束")
            base.put("showDivider", true)
            base.put("showContentDivider", true)
            return base
        }

        base.put("title", state.lineName ?: "${state.mode.label}导航")
        (state.nextStation ?: state.destination)?.let { base.put("subTitle", it) }
        base.put("content", when {
            state.destination != null && state.transferLine != null ->
                "开往 ${state.destination} · 换乘 ${state.transferLine}"
            state.destination != null -> "开往 ${state.destination}"
            state.transferLine != null -> "换乘 ${state.transferLine}"
            else -> "${state.mode.label}导航中"
        })
        state.etaText?.let { base.put("extraTitle", it) }
        state.remainText()?.let { base.put("specialTitle", it) }
        base.put("showDivider", true)
        base.put("showContentDivider", true)
        return base
    }

    /**
     * 按钮行（按钮组件2）。dsha-island 用一个「控制」按钮做过真机验证，
     * 这里复刻它的最小字段集：type/title/content/actionInfo。
     */
    private fun buildHint(state: NavState, opt: Options): JSONObject? {
        val spec = opt.actions.firstOrNull() ?: return null
        val hint = JSONObject()
        hint.put("type", 2)
        hint.put("title", if (state.terminal) "行程结束" else state.headline())
        hint.put("content", when {
            state.terminal -> "查看记录"
            state.atTransfer -> "下车换乘"
            else -> "查看地图"
        })
        hint.put(
            "actionInfo",
            JSONObject()
                .put("action", spec.key)
                .put("actionIcon", spec.iconKey)
                .put("actionTitle", spec.title)
                .put("actionIntentType", spec.intentType)
        )
        return hint
    }

    /** 岛主体图标：地铁用列车，公交用巴士。pics Bundle 必须用同一个 key，否则该区域空白 */
    fun picKey(state: NavState): String =
        if (state.mode == TransitMode.BUS) PIC_BUS else PIC_SUBWAY

    private fun pic(state: NavState): JSONObject =
        JSONObject().put("type", 1).put("pic", picKey(state))
}
