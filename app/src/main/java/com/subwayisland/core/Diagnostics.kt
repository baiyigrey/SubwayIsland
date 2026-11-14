package com.subwayisland.core

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.subwayisland.BuildConfig

/**
 * 环境信息汇总。反馈时先看这一段，能省掉一半的来回确认。
 */
object Diagnostics {

    /** 展示用：设备、系统、协议能力、目标包、开关状态 */
    fun summary(ctx: Context): String = buildString {
        append("地铁岛 ").append(BuildConfig.VERSION_NAME).append('\n')
        append("设备: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
        append("系统: ").append(Build.VERSION.RELEASE)
            .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
        append("协议: ").append(HyperOsCompat.protocolVersion(ctx))
            .append(" · 超级岛: ").append(if (HyperOsCompat.supportsIsland()) "支持" else "不支持/未知")
            .append('\n')
        append("Root: ").append(if (LogReader.isRootProbablyAvailable()) "有 su" else "未检测到 su").append('\n')
        append("开关: 总=").append(Config.enabled)
            .append(" 系统侧=").append(Config.systemUiEnrich)
            .append(" 地图侧=").append(Config.inAppInject).append('\n')
        for (pkg in Config.MAP_PKGS) {
            append(Config.displayName(pkg)).append(": ").append(packageLine(ctx, pkg)).append('\n')
        }
        append("系统界面: ").append(packageLine(ctx, Config.PKG_SYSTEMUI))
    }

    /** 给日志页用的单行头，粘到剪贴板里便于对照 */
    fun header(ctx: Context): String = buildString {
        append("===== 地铁岛反馈 ").append(BuildConfig.VERSION_NAME).append(" =====\n")
        append(summary(ctx)).append('\n')
        append("----------------------------------------\n")
    }

    /**
     * 完整反馈文本：环境 + SystemUI 回报的状态 + App 本地日志 + 系统日志。
     *
     * 顺序是有意设计的：即使系统日志一条都读不到（su 未授权 / 日志路径变了），
     * 前半段仍然能回答「模块到底有没有加载、有没有命中注入点」。
     */
    fun feedback(ctx: Context, log: LogReader.Result?): String = buildString {
        append(header(ctx))

        append("\n--- 运行状态（SystemUI 回报）---\n")
        append(StatusStore.describe(ctx)).append('\n')

        val local = AppLog.read()
        append("\n--- 本机操作日志 ---\n")
        append(if (local.isBlank()) "(暂无)" else local).append('\n')

        append("\n--- 系统日志 ---\n")
        when {
            log == null -> append("(未读取)")
            log.ok -> append("来源: ").append(log.source).append('\n').append(log.text)
            else -> append(log.hint ?: "(空)")
        }
    }

    private fun packageLine(ctx: Context, pkg: String): String {
        return runCatching {
            val pm = ctx.packageManager
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pkg, 0)
            }
            val version = info.versionName ?: "?"
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode.toString()
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toString()
            }
            "$version ($code)"
        }.getOrElse { "未安装" }
    }
}
