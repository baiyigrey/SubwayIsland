package com.subwayisland.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.subwayisland.core.AppLog
import com.subwayisland.core.Diagnostics
import com.subwayisland.core.LogReader
import com.subwayisland.core.StatusBridge
import com.subwayisland.core.StatusStore
import com.subwayisland.ui.theme.MiuixTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 日志页：环境 + 运行状态 + 本机日志 + 系统日志，一键复制。
 */
class LogActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.install(this)

        val activity = this
        setContent {
            MiuixTheme {
                var state by remember { mutableStateOf(LogUiState()) }
                var lastResult by remember { mutableStateOf<LogReader.Result?>(null) }
                val scope = rememberCoroutineScope()

                val reload: () -> Unit = {
                    state = state.copy(loading = true)
                    scope.launch {
                        StatusBridge.ping(activity)
                        val result = withContext(Dispatchers.IO) {
                            runCatching { LogReader.read() }.getOrElse {
                                LogReader.Result("", "error", "读取异常：${it.message}")
                            }
                        }
                        lastResult = result
                        state = LogUiState(
                            loading = false,
                            statusLine = if (result.ok) {
                                val lines = result.text.count { it == '\n' } + 1
                                "来源=${sourceName(result.source)} · $lines 行"
                            } else {
                                "系统日志未取到"
                            },
                            report = StatusStore.describe(activity),
                            logText = if (result.ok) result.text else result.hint.orEmpty()
                        )
                    }
                }

                LaunchedEffect(Unit) { reload() }

                LogScreen(
                    state = state,
                    onRefresh = reload,
                    onCopy = {
                        val text = Diagnostics.feedback(activity, lastResult)
                        val clip = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clip.setPrimaryClip(ClipData.newPlainText("SubwayIsland feedback", text))
                        Toast.makeText(
                            activity,
                            "已复制 ${text.count { it == '\n' } + 1} 行（含环境与运行状态）",
                            Toast.LENGTH_LONG
                        ).show()
                    },
                    onBack = { finish() }
                )
            }
        }
    }

    private fun sourceName(source: String): String = when (source) {
        "lspd" -> "LSPosed 日志"
        "logcat" -> "logcat"
        else -> source
    }
}
