package com.subwayisland.core

/** 出行方式：地铁与公交共用一套状态模型，只在图标、文案与线路识别上区分 */
enum class TransitMode {
    SUBWAY, BUS;

    val label: String get() = if (this == BUS) "公交" else "地铁"
}

/**
 * 导航状态（跨进程传递的最小数据模型）。
 *
 * 字段全部用可序列化到 Bundle 的基础类型，不走 Parcelable：
 * 地图进程 → SystemUI 的广播通道上放 Parcelable 容易踩 ClassLoader 的坑。
 */
data class NavState(
    /** 来源包名：com.baidu.BaiduMap / com.autonavi.minimap / com.tencent.map */
    val pkg: String,
    /** 线路名，如「1号线」「10号线」「APM线」「123路」「特8路」 */
    val lineName: String? = null,
    /** 下一站，如「人民广场」 */
    val nextStation: String? = null,
    /** 终点站 / 开往方向，如「莘庄」 */
    val destination: String? = null,
    /** 剩余站数（可选） */
    val remainStations: Int? = null,
    /** 预计到达剩余分钟（可选） */
    val etaMinutes: Int? = null,
    /** 原始的 ETA 文案，如「18:42 到达」（可选，优先于 etaMinutes 展示） */
    val etaText: String? = null,
    /** 全程站数（可选，能从「共8站」这类文案或结构化 Hook 拿到） */
    val totalStations: Int? = null,
    /** 行程进度 0..100（由 ProgressTracker 推出来，见该类注释） */
    val progressPercent: Int? = null,
    /** 地铁 / 公交。只影响图标与兜底文案，数据字段完全共用 */
    val mode: TransitMode = TransitMode.SUBWAY,
    /** 需要换乘的线路，如「2号线」「71路」 */
    val transferLine: String? = null,
    /** 换乘站，如「人民广场」 */
    val transferStation: String? = null,
    /** 导航是否已结束（到站 / 用户退出导航） */
    val terminal: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
    /** 数据来源，仅用于日志：notif / broadcast / hook */
    val source: String = "notif"
) {

    val isNavigating: Boolean
        get() = !terminal && (lineName != null || nextStation != null)

    /** 已经到（或正停在）换乘站：此时 B 区优先显示换乘提示，而不是进度 */
    val atTransfer: Boolean
        get() = !terminal && transferLine != null && transferStation != null &&
            nextStation != null && nextStation.contains(transferStation)

    /** 岛上主标题，「1号线 · 人民广场」 */
    fun headline(): String = when {
        lineName != null && nextStation != null -> "$lineName · $nextStation"
        lineName != null -> lineName
        nextStation != null -> nextStation
        else -> "${mode.label}导航"
    }

    /** 状态栏 / 息屏一行文案。只取最关键的「线路 · 下一站」，太长会被系统裁掉 */
    fun ticker(): String = when {
        terminal -> "行程已结束"
        atTransfer -> "$nextStation · 换乘$transferLine"
        else -> headline()
    }

    fun remainText(): String? = remainStations?.let { "剩${it}站" }

    /** 「3/8 站」这种带全程的说法，没有全程就退回「剩3站」 */
    fun remainWithTotal(): String? = when {
        remainStations != null && totalStations != null -> "$remainStations/$totalStations 站"
        remainStations != null -> "剩${remainStations}站"
        else -> null
    }

    fun etaShort(): String? = etaMinutes?.let { "${it}分" } ?: etaText

    /** 「换乘 2号线」 */
    fun transferText(): String? = transferLine?.let { "换乘 $it" }

    /**
     * 用 [fallback]（一般是通知正文解析结果）补齐本对象缺失的字段。
     * 结构化 Hook 拿到的数据优先级更高，解析结果只做兜底。
     */
    fun fillFrom(fallback: NavState?): NavState {
        if (fallback == null) return this
        return copy(
            lineName = lineName ?: fallback.lineName,
            nextStation = nextStation ?: fallback.nextStation,
            destination = destination ?: fallback.destination,
            remainStations = remainStations ?: fallback.remainStations,
            etaMinutes = etaMinutes ?: fallback.etaMinutes,
            etaText = etaText ?: fallback.etaText,
            totalStations = totalStations ?: fallback.totalStations,
            transferLine = transferLine ?: fallback.transferLine,
            transferStation = transferStation ?: fallback.transferStation,
            mode = if (lineName == null) fallback.mode else mode,
            terminal = terminal || fallback.terminal
        )
    }
}
