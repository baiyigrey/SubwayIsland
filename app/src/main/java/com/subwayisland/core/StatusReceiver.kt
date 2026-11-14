package com.subwayisland.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process

/**
 * 接收 SystemUI 进程回报的运行状态。
 *
 * 安全：这条广播只是诊断文本，不参与业务，但仍做一次来源校验——
 * Android 14+ 能拿到发送方 uid，只接受系统（1000）和本 App 自己；
 * 更老的系统上退化为不校验（代价只是有人能伪造一行状态文字，无实际影响）。
 */
class StatusReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != StatusBridge.ACTION) return
        if (!trusted()) return

        val stage = intent.getStringExtra(StatusBridge.EXTRA_STAGE) ?: return
        val detail = intent.getStringExtra(StatusBridge.EXTRA_DETAIL).orEmpty()
        StatusStore.save(
            ctx = context,
            stage = stage,
            detail = detail,
            at = intent.getLongExtra(StatusBridge.EXTRA_AT, System.currentTimeMillis()),
            proto = intent.getIntExtra(StatusBridge.EXTRA_PROTO, 0)
        )
        XLog.i("STA-03", "recv|$stage|$detail")
    }

    private fun trusted(): Boolean {
        if (Build.VERSION.SDK_INT < 34) return true
        val uid = runCatching { sentFromUid }.getOrDefault(-1)
        if (uid < 0) return true
        return uid == Process.SYSTEM_UID || uid == Process.myUid()
    }
}
