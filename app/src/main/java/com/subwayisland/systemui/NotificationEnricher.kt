package com.subwayisland.systemui

import android.app.AndroidAppHelper
import android.app.Application
import android.app.Notification
import android.content.Context
import android.service.notification.StatusBarNotification
import com.subwayisland.core.Config
import com.subwayisland.core.HyperOsCompat
import com.subwayisland.core.IslandPayload
import com.subwayisland.core.NavBus
import com.subwayisland.core.NavParser
import com.subwayisland.core.NavState
import com.subwayisland.core.ProgressTracker
import com.subwayisland.core.SelfTest
import com.subwayisland.core.StatusBridge
import com.subwayisland.core.XLog
import java.util.Collections
import java.util.HashSet
import java.util.WeakHashMap

/**
 * SystemUI 进程里的「富化器」：判断这条通知是不是地铁导航，
 * 是的话补上 `miui.focus.param` / `miui.focus.pics` / `miui.focus.actions`。
 *
 * 数据来源优先级：
 *   1. 地图进程通过广播推来的结构化状态（含剩余站数、线路号）；
 *   2. 通知正文解析（保底，不依赖任何混淆类名）；
 *   3. 自测通知（来自模块 App，带标记，字段直接写在 extras 里）。
 * 都没有就认为「不是导航」，并从通知上摘掉参数，避免岛残留。
 *
 * 日志事件码（反馈时重点看 ENR-03）：
 *   ENR-01 开始处理某条通知        ENR-02 判定为「非导航」，已跳过
 *   ENR-03 注入成功（带完整字段）  ENR-04 注入失败
 *   ENR-05 注入终态岛              ENR-06 摘除残留参数
 *   ENR-07 不支持超级岛            ENR-08 异常
 */
object NotificationEnricher {

    /** 当前已经注入过参数的 notification key */
    private val activeKeys = Collections.synchronizedSet(HashSet<String>())

    /** 已经发过「已到达」终态的 key，之后不再重复注入 */
    private val doneKeys = Collections.synchronizedSet(HashSet<String>())

    @Volatile
    private var busReady = false

    @Volatile
    private var lastLogged = ""

    @Volatile
    private var lastSkipLogged = ""

    fun onSbn(sbn: StatusBarNotification?) {
        val s = sbn ?: return
        val pkg = s.packageName ?: return
        val n = s.notification ?: return

        // 自测通知：包名是本 App，但必须带标记，避免误伤自己的普通通知
        val selfTest = Config.isSelf(pkg) && n.extras?.getBoolean(SelfTest.EXTRA_FLAG) == true
        if (!selfTest && !Config.isTargetMap(pkg)) return
        if (!Config.systemUiEnrich) return

        ensureBus()

        val key = s.key ?: "$pkg:?"
        try {
            val raw: NavState? = if (selfTest) {
                SelfTest.readState(n)
            } else {
                val parsed = runCatching { NavParser.parse(pkg, n) }.getOrNull()
                val pushed = NavBus.peek(pkg)
                when {
                    pushed != null && parsed != null -> pushed.fillFrom(parsed)
                    pushed != null -> pushed
                    else -> parsed
                }
            }
            // 换乘站有时只写在「换乘」前，没带「站」字：临近换乘时用下一站补齐
            val state: NavState? = raw?.let { NavParser.fillTransferStation(it) }

            if (state == null || !state.isNavigating) {
                logSkip(pkg, key, s, state)
                handleInactive(key, n, state)
                return
            }
            if (!HyperOsCompat.supportsIsland()) {
                XLog.d("ENR-07", "skip|no_island|pkg=$pkg")
                return
            }

            // 进度：能从「共 N 站 / 剩余 M 站」直接算就直接算，否则用本次行程的观察值近似
            val progress = ProgressTracker.update(state)
            val enriched = if (progress != null && progress != state.progressPercent) {
                state.copy(progressPercent = progress)
            } else state
            val bytes = IslandPayload.attach(n, enriched, HyperOsCompat.protocolVersion())
            if (bytes > 0) {
                activeKeys.add(key)
                doneKeys.remove(key)
                // 只有刚注入的短时间窗口内，才放行焦点通知的签名/权限校验
                FocusPermissionBypass.arm()
                val detail = "pkg=$pkg|key=$key|mode=${state.mode}|line=${state.lineName}" +
                    "|next=${state.nextStation}|dest=${state.destination}|remain=${state.remainStations}" +
                    "|total=${state.totalStations}|pct=${progress}" +
                    "|transfer=${state.transferLine}@${state.transferStation}" +
                    "|eta=${state.etaText ?: state.etaMinutes}" +
                    "|src=${state.source}|proto=${HyperOsCompat.protocolVersion()}|bytes=$bytes"
                logOnce("inject|ok|$detail")
                StatusBridge.report(
                    if (selfTest) StatusBridge.STAGE_TEST else StatusBridge.STAGE_INJECT,
                    "line=${state.lineName}|next=${state.nextStation}|bytes=$bytes|key=$key"
                )
            } else {
                XLog.e("ENR-04", "inject|fail|pkg=$pkg|key=$key|ch=${runCatching { n.channelId }.getOrNull()}")
            }
        } catch (t: Throwable) {
            XLog.e("ENR-08", "exception|pkg=$pkg|key=$key", t)
        }
    }

    private fun handleInactive(key: String, n: Notification, state: NavState?) {
        val terminal = state?.terminal == true
        if (terminal && doneKeys.add(key)) {
            // 到站/退出导航：给一个 8 秒后自动消失的终态岛，比直接抽走更不容易被用户错过
            val bytes = runCatching {
                IslandPayload.attach(n, state!!, HyperOsCompat.protocolVersion())
            }.getOrDefault(0)
            if (bytes > 0) {
                activeKeys.add(key)
                FocusPermissionBypass.arm()
                logOnce("terminal|key=$key|next=${state?.nextStation}|bytes=$bytes")
            }
            return
        }
        if (activeKeys.remove(key)) {
            runCatching { IslandPayload.detach(n) }
            XLog.d("ENR-06", "clear|key=$key")
        }
    }

    /**
     * 判定失败时的日志要克制：地图 App 会频繁发普通通知，
     * 每条都打会把关键行淹没。同一条 key 只记一次。
     */
    private fun logSkip(pkg: String, key: String, s: StatusBarNotification, state: NavState?) {
        if (!XLog.verbose) return
        val sig = "$pkg|$key|${state?.terminal}"
        if (sig == lastSkipLogged) return
        lastSkipLogged = sig
        val n = s.notification
        val ch = runCatching { n?.channelId }.getOrNull()
        val text = runCatching {
            n?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        }.getOrNull()
        val title = runCatching {
            n?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        }.getOrNull()
        XLog.d(
            "ENR-02",
            "skip|not_navi|pkg=$pkg|key=$key|ch=$ch|title=${title?.take(30)}|text=${text?.take(60)}"
        )
    }

    /**
     * 懒注册广播接收器。
     * 正常情况下 SystemUiHook 会在 Application.onCreate 里注册；这里作为兜底，
     * 因为部分 ROM 的 SystemUI 进程不走标准 Application 生命周期。
     */
    private fun ensureBus() {
        if (busReady) return
        val ctx = runCatching { AndroidAppHelper.currentApplication() }.getOrNull() ?: return
        NavBus.registerReceiver(ctx)
        StatusBridge.attach(ctx)
        StatusBridge.registerResponder(ctx)
        busReady = true
    }

    fun registerReceiverOn(app: Application) {
        runCatching {
            val ctx = app.applicationContext ?: app as Context
            NavBus.registerReceiver(ctx)
            StatusBridge.attach(ctx)
            StatusBridge.registerResponder(ctx)
            busReady = true
        }.onFailure { XLog.e("BUS-02", "receiver|fail", it) }
    }

    private fun logOnce(detail: String) {
        if (detail != lastLogged) {
            lastLogged = detail
            XLog.i("ENR-03", detail)
        }
    }
}
