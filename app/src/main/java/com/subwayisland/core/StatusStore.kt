package com.subwayisland.core

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 把 [StatusBridge] 推来的状态落到 App 私有 prefs，供主界面与反馈文本展示。
 *
 * 存 prefs 而不是内存：用户往往是「出问题 → 强停 SystemUI → 再打开 App」，
 * 内存态活不过这个流程，而 prefs 能留下「最后一次」的痕证。
 */
object StatusStore {

    private const val PREFS = "status"

    private const val K_STAGE = "stage"
    private const val K_DETAIL = "detail"
    private const val K_AT = "at"
    private const val K_PROTO = "proto"

    data class Snapshot(
        val stage: String,
        val detail: String,
        val at: Long,
        val proto: Int
    )

    fun save(ctx: Context, stage: String, detail: String, at: Long, proto: Int) {
        runCatching {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(K_STAGE, stage)
                .putString(K_DETAIL, detail)
                .putLong(K_AT, at)
                .putInt(K_PROTO, proto)
                .apply()
        }
    }

    fun load(ctx: Context): Snapshot? {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stage = p.getString(K_STAGE, null) ?: return null
        return Snapshot(
            stage = stage,
            detail = p.getString(K_DETAIL, "").orEmpty(),
            at = p.getLong(K_AT, 0L),
            proto = p.getInt(K_PROTO, 0)
        )
    }

    fun clear(ctx: Context) {
        runCatching { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply() }
    }

    /** 主界面 / 反馈文本用的一段人话 */
    fun describe(ctx: Context): String {
        val s = load(ctx) ?: return "系统界面进程：未收到任何回报\n" +
            "说明模块还没在 SystemUI 里跑起来（LSPosed 总开关 / 作用域 / 重启）"

        val stageText = when (s.stage) {
            StatusBridge.STAGE_LOADED -> "已加载"
            StatusBridge.STAGE_HOOK_OK -> "已加载 · 注入点命中"
            StatusBridge.STAGE_HOOK_MISS -> "已加载 · 注入点未命中（走兜底）"
            StatusBridge.STAGE_INJECT -> "已加载 · 注入过地图通知"
            StatusBridge.STAGE_TEST -> "已加载 · 自测注入成功"
            else -> s.stage
        }
        val ago = if (s.at <= 0L) "时间未知" else "${stamp(s.at)}（${ago(s.at)}）"
        return "系统界面进程：$stageText\n最近回报：$ago" +
            (if (s.detail.isBlank()) "" else "\n细节：${s.detail}")
    }

    private fun stamp(at: Long): String =
        SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(at))

    private fun ago(at: Long): String {
        val diff = System.currentTimeMillis() - at
        if (diff < 0) return "刚刚"
        val min = diff / 60_000
        return when {
            min < 1 -> "刚刚"
            min < 60 -> "${min} 分钟前"
            min < 60 * 24 -> "${min / 60} 小时前"
            else -> "${min / (60 * 24)} 天前"
        }
    }
}
