package com.subwayisland.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.subwayisland.R

/**
 * 自测出岛：不依赖百度/高德，直接发一条「模拟导航」通知，
 * 让 SystemUI 侧把整条链路（识别 → 构造参数 → 绕过权限 → 渲染）跑一遍。
 *
 * 这条路径存在的理由很实际：用户反馈「无法拉起地铁岛」时，
 * 如果设备上根本没装地图 App，那不管模块对不对都不会有岛——
 * 有了自测，就能把「模块坏了」和「还没有数据源」区分开。
 *
 * 提供两条样例：
 *  - 地铁：带换乘（下一站即换乘站），验证 B 区换乘提示；
 *  - 公交：带全程站数，验证公交车标、线路名与进度环。
 *
 * 自测通知带 [EXTRA_FLAG] 标记，SystemUI 侧只在看到这个标记时才接受
 * 来自本 App 包名的通知，不会影响真实应用的判定逻辑。
 */
object SelfTest {

    const val EXTRA_FLAG = "subwayisland.selftest"
    const val EXTRA_MODE = "subwayisland.mode"
    const val EXTRA_LINE = "subwayisland.line"
    const val EXTRA_NEXT = "subwayisland.next"
    const val EXTRA_DEST = "subwayisland.dest"
    const val EXTRA_REMAIN = "subwayisland.remain"
    const val EXTRA_ETA = "subwayisland.eta"
    const val EXTRA_TOTAL = "subwayisland.total"
    const val EXTRA_TRANSFER_LINE = "subwayisland.transferLine"
    const val EXTRA_TRANSFER_STATION = "subwayisland.transferStation"

    const val CHANNEL_ID = "subway_island_selftest"
    const val NOTIFICATION_ID = 20001
    const val BUS_NOTIFICATION_ID = 20002

    // 地铁样例：下一站就是换乘站，用来看换乘提示长什么样
    private const val SUBWAY_LINE = "1号线"
    private const val SUBWAY_NEXT = "人民广场"
    private const val SUBWAY_DEST = "莘庄"
    private const val SUBWAY_REMAIN = 3
    private const val SUBWAY_ETA = 12
    private const val SUBWAY_TOTAL = 8
    private const val SUBWAY_TRANSFER_LINE = "2号线"
    private const val SUBWAY_TRANSFER_STATION = "人民广场"

    // 公交样例：普通线路 + 全程站数，用来看公交车标与进度环
    private const val BUS_LINE = "123路"
    private const val BUS_NEXT = "中山北路"
    private const val BUS_DEST = "外滩"
    private const val BUS_REMAIN = 2
    private const val BUS_ETA = 8
    private const val BUS_TOTAL = 5

    fun buildNotification(ctx: Context): Notification = build(
        ctx,
        TransitMode.SUBWAY,
        SUBWAY_LINE,
        SUBWAY_NEXT,
        SUBWAY_DEST,
        SUBWAY_REMAIN,
        SUBWAY_TOTAL,
        SUBWAY_ETA,
        SUBWAY_TRANSFER_LINE,
        SUBWAY_TRANSFER_STATION
    )

    fun buildBusNotification(ctx: Context): Notification = build(
        ctx,
        TransitMode.BUS,
        BUS_LINE,
        BUS_NEXT,
        BUS_DEST,
        BUS_REMAIN,
        BUS_TOTAL,
        BUS_ETA,
        null,
        null
    )

    private fun build(
        ctx: Context,
        mode: TransitMode,
        line: String,
        next: String,
        dest: String,
        remain: Int,
        total: Int,
        eta: Int,
        transferLine: String?,
        transferStation: String?
    ): Notification {
        val icon = if (mode == TransitMode.BUS) R.drawable.ic_bus_glyph else R.drawable.ic_subway_glyph
        val open = PendingIntent.getActivity(
            ctx,
            0,
            Intent(Intent.ACTION_VIEW, Uri.parse("subwayisland://selftest"))
                .setClassName(ctx.packageName, "com.subwayisland.ui.MainActivity"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val body = buildString {
            append("开往").append(dest).append(" · ")
            append("剩").append(remain).append("/").append(total).append("站 · ")
            append(eta).append("分")
            if (transferLine != null) append(" · 换乘").append(transferLine)
        }

        return Notification.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(icon)
            .setContentTitle("$line · $next")
            .setContentText(body)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_NAVIGATION)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addExtras(android.os.Bundle().apply {
                putBoolean(EXTRA_FLAG, true)
                putString(EXTRA_MODE, mode.name)
                putString(EXTRA_LINE, line)
                putString(EXTRA_NEXT, next)
                putString(EXTRA_DEST, dest)
                putInt(EXTRA_REMAIN, remain)
                putInt(EXTRA_ETA, eta)
                putInt(EXTRA_TOTAL, total)
                putString(EXTRA_TRANSFER_LINE, transferLine)
                putString(EXTRA_TRANSFER_STATION, transferStation)
            })
            .build()
    }

    /** @return null 表示成功；否则是失败原因 */
    fun post(ctx: Context, bus: Boolean = false): String? {
        ensureChannel(ctx)
        val id = if (bus) BUS_NOTIFICATION_ID else NOTIFICATION_ID
        return runCatching {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(id, if (bus) buildBusNotification(ctx) else buildNotification(ctx))
            XLog.i("SELF-01", "post|ok|id=$id|mode=${if (bus) "bus" else "subway"}")
            null
        }.getOrElse { e ->
            XLog.e("SELF-02", "post|fail|${e.javaClass.simpleName}", e)
            "${e.javaClass.simpleName}: ${e.message}"
        }
    }

    fun cancel(ctx: Context) {
        runCatching {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(NOTIFICATION_ID)
            nm.cancel(BUS_NOTIFICATION_ID)
            XLog.i("SELF-03", "cancel|ok")
        }
    }

    fun ensureChannel(ctx: Context) {
        runCatching {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "地铁岛自测", NotificationManager.IMPORTANCE_DEFAULT)
                        .apply {
                            description = "调试用：模拟一次地铁 / 公交导航"
                            setShowBadge(false)
                            enableVibration(false)
                            setSound(null, null)
                        }
                )
            }
        }
    }

    /** SystemUI 侧读取：只有带标记的通知才认 */
    fun readState(n: Notification?): NavState? {
        val e = n?.extras ?: return null
        if (!e.getBoolean(EXTRA_FLAG, false)) return null
        val mode = runCatching { TransitMode.valueOf(e.getString(EXTRA_MODE) ?: "") }
            .getOrDefault(TransitMode.SUBWAY)
        return NavState(
            pkg = Config.MODULE_PKG,
            lineName = e.getString(EXTRA_LINE),
            nextStation = e.getString(EXTRA_NEXT),
            destination = e.getString(EXTRA_DEST),
            remainStations = e.getInt(EXTRA_REMAIN, -1).takeIf { it >= 0 },
            etaMinutes = e.getInt(EXTRA_ETA, -1).takeIf { it >= 0 },
            totalStations = e.getInt(EXTRA_TOTAL, -1).takeIf { it > 1 },
            mode = mode,
            transferLine = e.getString(EXTRA_TRANSFER_LINE),
            transferStation = e.getString(EXTRA_TRANSFER_STATION),
            source = "selftest"
        )
    }
}
