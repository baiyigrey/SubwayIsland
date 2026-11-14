package com.subwayisland.core

import android.app.AndroidAppHelper
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import java.util.Collections

/**
 * 地图进程 → SystemUI 进程的状态通道。
 *
 * 为什么需要它：SystemUI 侧只能看到「通知里那几行字」，拿不到剩余站数、
 * 线路编号这类结构化信息；而真正能拿到这些信息的 Hook 跑在地图进程里。
 * 两个进程之间最轻量的通道就是一条显式广播（不依赖文件、不依赖 provider、
 * 不需要额外权限）。
 *
 * 设计上参考 dsha-island 的思路：数据源与渲染端解耦，渲染端只消费
 * 「已经归一化好的状态」，换数据源（通知解析 / 内部 Hook）不用改渲染代码。
 * dsha 用的是 127.0.0.1 上的 socket，这里换成广播——少一个端口、少一份权限。
 */
object NavBus {

    const val ACTION_NAV_STATE = "com.subwayisland.action.NAV_STATE"

    private const val EXTRA_PKG = "pkg"
    private const val EXTRA_LINE = "line"
    private const val EXTRA_NEXT = "next"
    private const val EXTRA_DEST = "dest"
    private const val EXTRA_REMAIN = "remain"
    private const val EXTRA_ETA_MIN = "etaMin"
    private const val EXTRA_ETA_TEXT = "etaText"
    private const val EXTRA_TOTAL = "total"
    private const val EXTRA_MODE = "mode"
    private const val EXTRA_TRANSFER_LINE = "transferLine"
    private const val EXTRA_TRANSFER_STATION = "transferStation"
    private const val EXTRA_TERMINAL = "terminal"
    private const val EXTRA_AT = "at"

    /** 结构化状态的有效期：超过这个时间就认为地图进程没在更新，退回通知解析 */
    private const val TTL_MS = 20_000L

    private val cache = Collections.synchronizedMap(HashMap<String, NavState>())

    @Volatile
    private var receiverRegistered = false

    // ---------------------------------------------------------------- 发送端

    /**
     * 在地图进程内调用。用 AndroidAppHelper 取当前进程的 Application，
     * 避免为了一个 Context 去 Hook Activity。
     */
    fun publish(state: NavState) {
        val ctx: Context = runCatching { AndroidAppHelper.currentApplication() }.getOrNull() ?: return
        put(state)
        runCatching {
            val intent = Intent(ACTION_NAV_STATE).apply {
                // 显式指定包名，避免变成隐式广播被系统拦掉（Android 8+ 后台限制）
                setPackage(Config.PKG_SYSTEMUI)
                putExtra(EXTRA_PKG, state.pkg)
                putExtra(EXTRA_LINE, state.lineName)
                putExtra(EXTRA_NEXT, state.nextStation)
                putExtra(EXTRA_DEST, state.destination)
                putExtra(EXTRA_REMAIN, state.remainStations ?: -1)
                putExtra(EXTRA_ETA_MIN, state.etaMinutes ?: -1)
                putExtra(EXTRA_ETA_TEXT, state.etaText)
                putExtra(EXTRA_TOTAL, state.totalStations ?: -1)
                putExtra(EXTRA_MODE, state.mode.name)
                putExtra(EXTRA_TRANSFER_LINE, state.transferLine)
                putExtra(EXTRA_TRANSFER_STATION, state.transferStation)
                putExtra(EXTRA_TERMINAL, state.terminal)
                putExtra(EXTRA_AT, state.updatedAt)
            }
            ctx.sendBroadcast(intent)
        }.onFailure { XLog.d("BUS-04", "publish|fail|${it.message}") }
    }

    // ---------------------------------------------------------------- 接收端

    fun registerReceiver(ctx: Context) {
        if (receiverRegistered) return
        receiverRegistered = true
        val filter = IntentFilter(ACTION_NAV_STATE)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // 动态注册的接收器需要显式声明导出标记，否则收不到其他应用的广播
                ctx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                ctx.registerReceiver(receiver, filter)
            }
            XLog.i("BUS-01", "receiver|registered")
        }.onFailure {
            receiverRegistered = false
            XLog.e("BUS-02", "receiver|fail", it)
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val i = intent ?: return
            if (i.action != ACTION_NAV_STATE) return
            runCatching {
                val pkg = i.getStringExtra(EXTRA_PKG) ?: return
                // 只接受白名单包名：这是纯 UI 能力，但仍然避免任意应用伪造岛内容
                if (!Config.isTargetMap(pkg)) return
                val state = NavState(
                    pkg = pkg,
                    lineName = i.getStringExtra(EXTRA_LINE)?.takeIf { it.isNotBlank() },
                    nextStation = i.getStringExtra(EXTRA_NEXT)?.takeIf { it.isNotBlank() },
                    destination = i.getStringExtra(EXTRA_DEST)?.takeIf { it.isNotBlank() },
                    remainStations = i.getIntExtra(EXTRA_REMAIN, -1).takeIf { it >= 0 },
                    etaMinutes = i.getIntExtra(EXTRA_ETA_MIN, -1).takeIf { it >= 0 },
                    etaText = i.getStringExtra(EXTRA_ETA_TEXT)?.takeIf { it.isNotBlank() },
                    totalStations = i.getIntExtra(EXTRA_TOTAL, -1).takeIf { it >= 0 },
                    mode = runCatching { TransitMode.valueOf(i.getStringExtra(EXTRA_MODE) ?: "") }
                        .getOrDefault(TransitMode.SUBWAY),
                    transferLine = i.getStringExtra(EXTRA_TRANSFER_LINE)?.takeIf { it.isNotBlank() },
                    transferStation = i.getStringExtra(EXTRA_TRANSFER_STATION)?.takeIf { it.isNotBlank() },
                    terminal = i.getBooleanExtra(EXTRA_TERMINAL, false),
                    updatedAt = i.getLongExtra(EXTRA_AT, System.currentTimeMillis()),
                    source = "broadcast"
                )
                put(state)
                XLog.d(
                    "BUS-03",
                    "recv|${state.pkg}|mode=${state.mode}|line=${state.lineName}|next=${state.nextStation}" +
                        "|remain=${state.remainStations}|transfer=${state.transferLine}"
                )
            }.onFailure { XLog.e("BUS-05", "recv|fail", it) }
        }
    }

    // ---------------------------------------------------------------- 读取

    fun put(state: NavState) {
        cache[state.pkg] = state
    }

    /** 取结构化状态；过期或不存在返回 null，调用方据此退回通知解析。 */
    fun peek(pkg: String): NavState? {
        val s = cache[pkg] ?: return null
        if (System.currentTimeMillis() - s.updatedAt > TTL_MS) {
            cache.remove(pkg)
            return null
        }
        return s
    }

    fun clear(pkg: String) {
        cache.remove(pkg)
    }
}
