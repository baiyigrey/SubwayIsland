package com.subwayisland.core

import android.content.Context
import android.content.Intent
import android.os.SystemClock

/**
 * SystemUI 进程 → 模块 App 的运行状态回报。
 *
 * 为什么需要它：模块跑在别的进程里，App 自己既看不到对方的 ClassLoader，
 * 也不一定读得到 LSPosed 日志（su 授权、日志路径都可能出问题）。
 * 与其让用户「黑盒猜测有没有加载」，不如让 SystemUI 主动广播一句
 * 「我加载了 / 我命中了 Hook / 我刚注入了哪条通知」，App 存下来直接展示。
 *
 * 这条通道只传诊断字符串，不传通知内容，也不参与业务逻辑。
 *
 * 日志事件码：STA-01 状态已发出   STA-02 发送失败
 */
object StatusBridge {

    const val ACTION = "com.subwayisland.STATUS"

    /** App 主动探测：SystemUI 收到后按当前状态回一条 [ACTION] */
    const val PING_ACTION = "com.subwayisland.PING"

    const val EXTRA_STAGE = "stage"
    const val EXTRA_DETAIL = "detail"
    const val EXTRA_AT = "at"
    const val EXTRA_PROTO = "proto"

    /** 模块已进入 SystemUI 进程 */
    const val STAGE_LOADED = "loaded"

    /** 主注入点命中 */
    const val STAGE_HOOK_OK = "hook_ok"

    /** 主注入点缺失，只能走兜底 */
    const val STAGE_HOOK_MISS = "hook_miss"

    /** 刚把参数挂到某条通知上 */
    const val STAGE_INJECT = "inject"

    /** 自测通知也被成功注入 */
    const val STAGE_TEST = "test_inject"

    @Volatile
    private var app: Context? = null

    @Volatile
    private var lastSig: String = ""

    @Volatile
    private var lastAt: Long = 0L

    @Volatile
    private var lastStage: String? = null

    @Volatile
    private var lastDetail: String = ""

    @Volatile
    private var responderReady: Boolean = false

    /** Hook 装配早于 Application.onCreate，先把状态存着，等拿到 Context 再发 */
    @Volatile
    private var pendingStage: String? = null

    @Volatile
    private var pendingDetail: String = ""

    /** 由 SystemUI 的 Application.onCreate 调用一次 */
    fun attach(ctx: Context?) {
        if (ctx == null) return
        if (app == null) app = ctx.applicationContext ?: ctx
        val stage = pendingStage
        if (stage != null) {
            pendingStage = null
            report(stage, pendingDetail)
        }
    }

    fun report(stage: String, detail: String = "") {
        val ctx = app
        if (ctx == null) {
            pendingStage = stage
            pendingDetail = detail
            return
        }
        lastStage = stage
        lastDetail = detail

        val now = SystemClock.elapsedRealtime()
        val sig = "$stage|$detail"
        // Hook 点调用极频繁，同一状态 3 秒内只发一次，避免刷爆广播队列
        if (sig == lastSig && now - lastAt < 3_000L) return
        lastSig = sig
        lastAt = now
        send(ctx, stage, detail)
    }

    private fun send(ctx: Context, stage: String, detail: String) {
        runCatching {
            val i = Intent(ACTION).setPackage(Config.MODULE_PKG)
            i.putExtra(EXTRA_STAGE, stage)
            i.putExtra(EXTRA_DETAIL, detail.take(300))
            i.putExtra(EXTRA_AT, System.currentTimeMillis())
            i.putExtra(EXTRA_PROTO, HyperOsCompat.protocolVersion(ctx))
            ctx.sendBroadcast(i)
            XLog.d("STA-01", "report|$stage|$detail")
        }.onFailure { XLog.d("STA-02", "report_fail|${it.message}") }
    }

    /**
     * 在 SystemUI 里注册「被人问一句就答一句」的响应器。
     *
     * 为什么需要：App 可能在模块加载之后才被打开（甚至被强停过），
     * 一次性的广播早就丢了。有了回应机制，App 打开时主动 ping 一下，
     * 就能拿到「此刻」的真实状态，而不是历史残影。
     */
    fun registerResponder(ctx: Context?) {
        if (ctx == null || responderReady) return
        responderReady = true
        runCatching {
            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    val c = context ?: app ?: return
                    val stage = lastStage ?: STAGE_LOADED
                    send(c, stage, (lastDetail + "|by=ping").trimStart('|'))
                }
            }
            val filter = android.content.IntentFilter(PING_ACTION)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                ctx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                ctx.registerReceiver(receiver, filter)
            }
            XLog.d("STA-04", "responder|ok")
        }.onFailure { XLog.d("STA-05", "responder|fail|${it.message}") }
    }

    /** App 侧：主动问一次 SystemUI「你还在吗」 */
    fun ping(ctx: Context) {
        runCatching {
            ctx.sendBroadcast(Intent(PING_ACTION).setPackage(Config.PKG_SYSTEMUI))
        }
    }
}
