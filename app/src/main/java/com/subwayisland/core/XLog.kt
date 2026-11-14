package com.subwayisland.core

import android.util.Log
import com.subwayisland.BuildConfig
import de.robv.android.xposed.XposedBridge

/**
 * 统一日志出口，格式固定为一行、竖线分隔、可直接 grep：
 *
 * ```
 * SubwayIsland|0.1.0|I|ENR-03|inject|ok|key=...|line=1号线|next=人民广场|src=broadcast|bytes=1120
 * ```
 *
 * 为什么要设计成「版本 + 级别 + 事件码 + 键值对」：
 * 用户反馈时只要把几行日志贴过来，就能直接定位到哪一步断了，不用反复来回问。
 * 事件码前缀含义：
 *   INIT/HOOK  模块装载与 Hook 是否命中
 *   PERM       焦点通知权限/签名放行
 *   ENR        通知富化（判定 → 注入）
 *   MAP        地图进程采集
 *   BUS        进程间广播通道
 *   PAY        参数构造
 *   CFG        配置读取
 *   SELF       自测出岛
 *   STA        运行状态回报
 *   RECON      类名侦察
 *
 * 三路输出：
 *  - XposedBridge.log → LSPosed 管理器日志页；
 *  - android.util.Log → logcat；
 *  - App 自身进程额外落盘（见 [AppLog]），su 读不到系统日志时仍可反馈。
 */
object XLog {

    const val TAG = "SubwayIsland"
    val VERSION: String = BuildConfig.VERSION_NAME

    /** 调试开关；关闭后只保留 I / E */
    @Volatile
    var verbose: Boolean = true

    /** 模块 App 自身进程里没有 XposedBridge，用它判断是否需要走 LSPosed 日志 */
    private val hasXposed: Boolean by lazy {
        runCatching { Class.forName("de.robv.android.xposed.XposedBridge"); true }.getOrDefault(false)
    }

    fun i(code: String, detail: String = "") = emit('I', code, detail, null)

    fun d(code: String, detail: String = "") {
        if (verbose) emit('D', code, detail, null)
    }

    fun e(code: String, detail: String, t: Throwable? = null) = emit('E', code, detail, t)

    private fun emit(level: Char, code: String, detail: String, t: Throwable?) {
        val line = buildString {
            append(TAG).append('|').append(VERSION).append('|').append(level).append('|')
            append(code)
            if (detail.isNotEmpty()) append('|').append(detail.replace('\n', ' '))
        }
        if (hasXposed) {
            runCatching { XposedBridge.log(line) }
        }
        runCatching {
            when (level) {
                'E' -> Log.e(TAG, line, t)
                'D' -> Log.d(TAG, line)
                else -> Log.i(TAG, line)
            }
        }
        // App 进程落盘（其它进程里 file 为空，no-op）
        AppLog.append("${now()} $line")
        if (t != null && hasXposed) {
            runCatching { XposedBridge.log(t) }
        }
    }

    private fun now(): String =
        java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
}
