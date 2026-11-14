package com.subwayisland.core

import android.app.Notification

/**
 * 从通知正文里反推地铁 / 公交导航状态。
 *
 * 这是整个模块的「保底数据源」，也是不依赖任何混淆类名的那一半：
 * 百度 / 高德 / 腾讯在导航时常驻一条前台服务通知，标题和正文形如
 *   「1号线 · 开往莘庄方向」 / 「下一站：人民广场，剩余 3 站，约 12 分钟」
 *   「123路 · 开往中山北路」 / 「下一站：中山北路，还有 2 站」
 * 我们只做文本解析，不碰它们的内部导航类。
 *
 * 解析策略刻意保守：必须命中「下一站 / 开往 / N号线 / N路 / 剩余N站」中的至少一项，
 * 且必须能拿到线路或下一站，否则认为「这不是导航通知」，不注入参数——
 * 避免把地图的普通推送（如「附近有新开餐厅」）送上岛。
 *
 * 换乘信息单独处理：先取出「换乘 2号线」这类片段，再从剩下的文本里识别当前线路。
 * 否则公交 123 路换乘地铁 2 号线时，当前线路会被误判成 2 号线。
 */
object NavParser {

    // ---------------------------------------------------------------- 线路

    /** 地铁：1号线、11 号线、APM线、磁浮线 */
    private val SUBWAY_LINE = Regex(
        "([0-9]{1,2}|[一二三四五六七八九十]{1,3}|APM|apm|磁浮)\\s*号线"
    )

    /**
     * 公交：123路、特8路、快1路、专167路、夜27路、B1路、BRT2路、T3路。
     * 要求「数字在前、路在后」，所以不会把「南京路」这类地名当成线路。
     */
    private val BUS_LINE = Regex(
        "((?:特|快|专|夜|B|BRT|K|T)?\\s*[0-9]{1,4})\\s*路"
    )

    // ---------------------------------------------------------------- 换乘

    /** 「换乘 2号线」 */
    private val TRANSFER_SUBWAY = Regex(
        "换乘\\s*(?:地铁)?\\s*([0-9]{1,2}|[一二三四五六七八九十]{1,3}|APM|apm|磁浮)\\s*号线"
    )

    /** 「换乘 71路」 */
    private val TRANSFER_BUS = Regex(
        "换乘\\s*((?:特|快|专|夜|B|BRT|K|T)?\\s*[0-9]{1,4})\\s*路"
    )

    /** 「在中山北路站换乘」「人民广场站换乘」——必须带「站」字，避免把「下一站换乘」当站名 */
    private val TRANSFER_STATION = Regex(
        "(?:在|到|至)?\\s*([^\\s，,。;；|｜、]{2,12}?)站\\s*换乘"
    )

    /** 换乘片段本身，用于从主文本里剥离，防止污染当前线路识别 */
    private val TRANSFER_SEGMENT = Regex("换乘\\s*[^\\s，,。;；|｜、]{1,12}")

    // ---------------------------------------------------------------- 站名与时间

    private val NEXT_STATION = Regex(
        "(?:下一站|下站|即将到达|即将进站|即将到站)[：:，,\\s]*([^\\s，,。;；|｜、]{1,12})"
    )

    private val DESTINATION = Regex(
        "(?:开往|往|去往)[：:，,\\s]*([^\\s，,。;；|｜、]{1,12}?)(?:方向)?(?:[\\s，,。;；|｜]|$)"
    )

    private val REMAIN = Regex("(?:剩余|还有|余)\\s*([0-9]{1,2})\\s*站")

    /** 全程站数：共 8 站 / 全程8站 / 8站中的第3站 / 3/8 站 */
    private val TOTAL = Regex("(?:共|全程|一共|计)\\s*([0-9]{1,2})\\s*站")
    private val TOTAL_RATIO = Regex("([0-9]{1,2})\\s*/\\s*([0-9]{1,2})\\s*站")

    private val ETA_MINUTES = Regex(
        "(?:约|预计|还需|还需约)?\\s*([0-9]{1,3})\\s*分钟(?:后)?(?:到达|抵达)?"
    )

    private val ETA_CLOCK = Regex("([0-9]{1,2}:[0-9]{2})\\s*(?:到达|抵达)")

    private val TERMINAL_WORDS = listOf(
        "已到达", "已抵达", "导航结束", "行程结束", "已结束", "已退出导航", "退出导航", "已停止导航"
    )

    /** 导航类通知频道关键字（各家 ROM / 版本不完全一致，尽量放宽） */
    private val NAV_CHANNEL_HINTS = listOf(
        "navi", "navigation", "guide", "gps", "daohang", "navigate"
    )

    /** 正文中出现的导航关键字，用于提高判定置信度 */
    private val NAV_TEXT_HINTS = listOf(
        "导航", "下一站", "开往", "号线", "地铁", "公交", "巴士", "换乘", "剩余", "到达", "乘车", "步行"
    )

    fun parse(pkg: String, n: Notification, source: String = "notif"): NavState? {
        val extras = n.extras ?: return null
        val chunks = ArrayList<String>(8)
        for (key in arrayOf(
            Notification.EXTRA_TITLE,
            Notification.EXTRA_TITLE_BIG,
            Notification.EXTRA_TEXT,
            Notification.EXTRA_BIG_TEXT,
            Notification.EXTRA_SUB_TEXT,
            Notification.EXTRA_INFO_TEXT,
            Notification.EXTRA_SUMMARY_TEXT
        )) {
            val v = extras.getCharSequence(key)?.toString()
            if (!v.isNullOrBlank()) chunks.add(v)
        }
        extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.forEach {
            val v = it?.toString()
            if (!v.isNullOrBlank()) chunks.add(v)
        }
        if (chunks.isEmpty()) return null

        val text = chunks.joinToString(" | ")

        // 换乘字段先取，再从主文本里剥掉换乘片段，避免把换乘线路当成当前线路
        val transferSubway = TRANSFER_SUBWAY.find(text)?.groupValues?.get(1)
            ?.let { normalizeSubway(it) }
        val transferBus = TRANSFER_BUS.find(text)?.groupValues?.get(1)
            ?.let { normalizeBus(it) }
        val transferStation = TRANSFER_STATION.find(text)?.groupValues?.get(1)?.trim()

        val mainText = text.replace(TRANSFER_SEGMENT, " ")

        val subwayLine = SUBWAY_LINE.find(mainText)?.groupValues?.get(1)
            ?.let { normalizeSubway(it) }
        val busLine = BUS_LINE.find(mainText)?.groupValues?.get(1)
            ?.let { normalizeBus(it) }
        val mode = when {
            subwayLine != null -> TransitMode.SUBWAY
            busLine != null -> TransitMode.BUS
            mainText.contains("公交") || mainText.contains("巴士") -> TransitMode.BUS
            else -> TransitMode.SUBWAY
        }
        val lineName = subwayLine ?: busLine

        val terminal = TERMINAL_WORDS.any { text.contains(it) }
        val nextStation = NEXT_STATION.find(mainText)?.groupValues?.get(1)?.trim()?.trimEnd('。')
        val destination = DESTINATION.find(mainText)?.groupValues?.get(1)?.trim()
        val remain = REMAIN.find(mainText)?.groupValues?.get(1)?.toIntOrNull()
        val ratio = TOTAL_RATIO.find(mainText)
        val total = TOTAL.find(mainText)?.groupValues?.get(1)?.toIntOrNull()
            ?: ratio?.groupValues?.get(2)?.toIntOrNull()?.takeIf { it in 2..60 }
        val etaText = ETA_CLOCK.find(mainText)?.groupValues?.get(1)?.let { "$it 到达" }
        val etaMinutes = if (etaText == null) {
            ETA_MINUTES.find(mainText)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1..300 }
        } else null

        if (!terminal && lineName == null && nextStation == null) return null

        val channelId = runCatching { n.channelId }.getOrNull().orEmpty()
        val channelHit = NAV_CHANNEL_HINTS.any { channelId.contains(it, ignoreCase = true) }
        val textHit = NAV_TEXT_HINTS.count { text.contains(it) }
        val strongHit = lineName != null || nextStation != null || terminal
        if (!channelHit && textHit < 2 && !strongHit) return null

        // 换乘线路：当前是公交就记地铁，当前是地铁就记公交
        val transferLine = when (mode) {
            TransitMode.BUS -> transferSubway ?: transferBus
            TransitMode.SUBWAY -> transferBus ?: transferSubway
        }

        return NavState(
            pkg = pkg,
            lineName = lineName,
            nextStation = nextStation,
            destination = destination,
            remainStations = remain ?: ratio?.groupValues?.get(1)?.toIntOrNull(),
            totalStations = total,
            etaMinutes = etaMinutes,
            etaText = etaText,
            mode = mode,
            transferLine = transferLine,
            transferStation = transferStation,
            terminal = terminal,
            source = source
        )
    }

    /**
     * 换乘站名有时只写在「换乘」前，没有「站」字：这时用下一站兜底。
     *
     * 只在「剩余 0–1 站」时补，宁可少提示也不早提示：
     * 一旦被判定为「已到换乘站」，B 区的进度会被换乘提示顶掉，
     * 还剩七八站就提醒下车反而添乱。
     */
    fun fillTransferStation(state: NavState): NavState {
        if (state.transferLine == null || state.transferStation != null) return state
        val next = state.nextStation ?: return state
        val remain = state.remainStations ?: return state
        return if (remain <= 1) state.copy(transferStation = next) else state
    }

    private fun normalizeSubway(raw: String): String = when {
        raw.equals("apm", true) -> "APM线"
        raw.endsWith("号线") -> raw
        else -> "${raw}号线"
    }

    private fun normalizeBus(raw: String): String {
        val compact = raw.replace(" ", "")
        return if (compact.endsWith("路")) compact else "${compact}路"
    }
}
