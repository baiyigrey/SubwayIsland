package com.subwayisland.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.subwayisland.BuildConfig
import com.subwayisland.core.AppLog
import com.subwayisland.core.Config
import com.subwayisland.core.Diagnostics
import com.subwayisland.core.SelfTest
import com.subwayisland.core.StatusBridge
import com.subwayisland.core.StatusStore
import com.subwayisland.core.XLog
import com.subwayisland.ui.theme.MiuixTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 主界面：Compose + MIUIX 视觉规范（见 ui/theme/MiuixTheme.kt）。
 *
 * 数据都在这里注入，Composable 只负责画，方便以后换皮或加页面。
 */
class MainActivity : ComponentActivity() {

    private lateinit var prefs: SharedPreferences

    /** 权限弹窗是异步的，回来时要知道刚才点的是地铁还是公交 */
    private var pendingBus = false

    private val notifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) postSelfTest(pendingBus) else toast("需要通知权限才能自测")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(Config.PREFS_NAME, MODE_PRIVATE)
        Config.syncFrom(prefs)
        AppLog.install(this)
        XLog.i("UI-01", "main|open|v=${BuildConfig.VERSION_NAME}")

        val activity = this
        setContent {
            MiuixTheme {
                var env by remember { mutableStateOf("正在检测…") }
                var status by remember { mutableStateOf(StatusStore.describe(activity)) }
                var flags by remember { mutableStateOf(loadFlags()) }
                var statusNonce by remember { mutableStateOf(0) }
                val scope = rememberCoroutineScope()

                // 环境探测会跑包管理和 su 探测，不能在主线程做
                LaunchedEffect(Unit) {
                    env = withContext(Dispatchers.IO) { Diagnostics.summary(activity) }
                }
                // 打开页面主动问一次 SystemUI：历史广播可能早就丢了
                LaunchedEffect(Unit) {
                    StatusBridge.ping(activity)
                    delay(600)
                    status = StatusStore.describe(activity)
                }
                LaunchedEffect(statusNonce) {
                    if (statusNonce > 0) {
                        delay(1200)
                        StatusBridge.ping(activity)
                        status = StatusStore.describe(activity)
                    }
                }

                val snapshot = remember(status) { StatusStore.load(activity) }
                val level = when {
                    snapshot == null -> StatusLevel.Unknown
                    snapshot.stage == StatusBridge.STAGE_HOOK_MISS -> StatusLevel.Warn
                    else -> StatusLevel.Ok
                }

                MainScreen(
                    version = BuildConfig.VERSION_NAME,
                    env = env,
                    status = status,
                    statusLevel = level,
                    flags = flags,
                    onToggle = { key, value ->
                        prefs.edit().putBoolean(key, value).apply()
                        Config.syncFrom(prefs)
                        flags = flags + (key to value)
                        XLog.i("UI-02", "switch|$key=$value")
                    },
                    onRefreshStatus = {
                        StatusBridge.ping(activity)
                        scope.launch {
                            delay(600)
                            status = StatusStore.describe(activity)
                        }
                    },
                    onSelfTest = {
                        requestSelfTest(bus = false)
                        statusNonce++
                    },
                    onSelfTestBus = {
                        requestSelfTest(bus = true)
                        statusNonce++
                    },
                    onEndSelfTest = {
                        SelfTest.cancel(activity)
                        toast("已取消自测通知")
                        statusNonce++
                    },
                    onOpenLog = { startActivity(Intent(activity, LogActivity::class.java)) },
                    onCopyEnv = {
                        copy(Diagnostics.header(activity))
                        toast("环境信息已复制")
                    }
                )
            }
        }
    }

    private fun loadFlags(): Map<String, Boolean> = SETTINGS.associate { item ->
        item.key to prefs.getBoolean(item.key, SETTING_DEFAULTS[item.key] ?: false)
    }

    private fun requestSelfTest(bus: Boolean) {
        pendingBus = bus
        val needPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needPermission) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        postSelfTest(bus)
    }

    private fun postSelfTest(bus: Boolean = false) {
        val error = SelfTest.post(this, bus)
        toast(
            when {
                error != null -> "发送失败：$error"
                bus -> "已发出公交模拟导航，注意状态栏与岛"
                else -> "已发出地铁模拟导航，注意状态栏与岛"
            }
        )
    }

    private fun copy(text: String) {
        val clip = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clip.setPrimaryClip(ClipData.newPlainText("SubwayIsland", text))
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }
}
