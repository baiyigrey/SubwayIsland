package com.subwayisland.core

import android.content.Context
import java.io.File

/**
 * 模块 App 自己进程内的落盘日志。
 *
 * 为什么要单独一份：系统日志（LSPosed / logcat）需要 root 才能读，
 * 而「点自测按钮 → 通知有没有发出去 → 界面这边做了什么」这些都在 App 进程里，
 * 不该因为 su 授权失败就一起丢掉。这份日志永远读得到，是反馈的保底证据。
 *
 * 只在 App 进程启用（[install] 由 MainActivity 调用）：
 * SystemUI / 地图进程里 file 为 null，直接 no-op，不会污染别人的目录。
 */
object AppLog {

    private const val FILE_NAME = "app.log"
    private const val MAX_BYTES = 192 * 1024

    @Volatile
    private var file: File? = null

    private val lock = Any()

    fun install(ctx: Context) {
        if (file != null) return
        file = File(ctx.filesDir, FILE_NAME)
    }

    fun append(line: String) {
        val f = file ?: return
        synchronized(lock) {
            runCatching {
                if (f.length() > MAX_BYTES) {
                    // 超过上限就只留后半段，避免文件无限长大
                    val keep = f.readLines().takeLast(400)
                    f.writeText(keep.joinToString("\n") + "\n")
                }
                f.appendText(line + "\n")
            }
        }
    }

    fun read(maxLines: Int = 300): String {
        val f = file ?: return ""
        return runCatching {
            val lines = f.readLines()
            if (lines.size <= maxLines) lines.joinToString("\n")
            else lines.takeLast(maxLines).joinToString("\n")
        }.getOrDefault("")
    }

    fun clear() {
        synchronized(lock) { runCatching { file?.writeText("") } }
    }

    fun sizeBytes(): Long = runCatching { file?.length() ?: 0L }.getOrDefault(0L)
}
