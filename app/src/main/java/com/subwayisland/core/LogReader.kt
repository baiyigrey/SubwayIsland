package com.subwayisland.core

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 把系统日志读出来，给用户一个「点一下就能复制」的反馈路径。
 *
 * 模块跑在 SystemUI / 地图进程里，写不进本 App 的私有目录（跨 UID），
 * 所以只能反向读：
 *   1. 优先读 LSPosed 落盘的日志（/data/adb/lspd/log/ 下的文件）；
 *   2. 读不到就退回 `logcat -d | grep SubwayIsland`；
 *   3. 都不通时返回**具体原因**，而不是一个空页面。
 *
 * 0.1.1 的两个坑，这里都修了：
 *   - `ProcessBuilder("su")` 依赖 PATH，KernelSU / 部分 Magisk 环境下根本找不到 su，
 *     于是「有 su」却「读不到日志」。现在按绝对路径逐个探测；
 *   - 不弹授权、不报错，直接给空结果。现在先跑 `id` 探一次，
 *     未授权会明确告诉用户去授权弹窗点允许。
 */
object LogReader {

    data class Result(
        val text: String,
        val source: String,
        val hint: String? = null
    ) {
        val ok: Boolean get() = text.isNotBlank()
    }

    private val SU_CANDIDATES = listOf(
        "/system/bin/su",
        "/debug_ramdisk/su",
        "/data/adb/ksu/bin/su",
        "/sbin/su",
        "/system/xbin/su",
        "su"
    )

    private const val MAX_LINES = 600

    private data class SuResult(val out: String, val code: Int)

    fun read(): Result {
        val su = pickSu() ?: return Result(
            text = "",
            source = "none",
            hint = "找不到可用的 su（试过 ${SU_CANDIDATES.size} 个常见路径）。\n\n" +
                "可以在 LSPosed 管理器 → 日志 里手动搜索 SubwayIsland；\n" +
                "或者把「系统界面」自检结果贴给我——那条通道不需要 root。"
        )

        // 先探授权：Magisk/KernelSU 首次会弹窗，用户没点之前所有命令都会失败
        val probe = runSu(su, "id", 20_000)
        when {
            probe.code == -1 -> return Result(
                text = "", source = "none",
                hint = "su 存在但无法执行：$su\n\n" +
                    "可能被系统安全策略拦截，或该路径对普通应用不可执行。"
            )
            probe.code == -2 -> return Result(
                text = "", source = "none",
                hint = "等待 su 授权超时。\n\n" +
                    "请留意屏幕上的 Magisk / KernelSU 授权弹窗，给「地铁岛」点允许后再刷新。"
            )
            !probe.out.contains("uid=0") -> return Result(
                text = "", source = "none",
                hint = "su 没有拿到 root 权限（返回码 ${probe.code}）。\n\n" +
                    "请到 Magisk / KernelSU 里给「地铁岛」授予 root，然后点「刷新」。\n" +
                    "返回内容：${probe.out.trim().take(120).ifBlank { "(空)" }}"
            )
        }

        val raw = runSu(su, collectScript(), 30_000)
        if (raw.code != 0 && raw.out.isBlank()) {
            return Result(
                text = "", source = "none",
                hint = "已拿到 root，但读取日志的命令执行失败（返回码 ${raw.code}）。"
            )
        }

        var source = "none"
        var sawFile = false
        var hasLogDir = false
        val lines = ArrayList<String>(MAX_LINES)

        for (line in raw.out.lineSequence()) {
            when {
                line.startsWith("@@LS:") -> {
                    hasLogDir = line.removePrefix("@@LS:").trim() == "ok"
                }
                line.startsWith("@@FILE:") -> sawFile = true
                line.startsWith("@@") -> Unit
                line.contains("SubwayIsland") -> lines.add(line.trim())
            }
        }

        if (lines.isEmpty()) {
            val why = when {
                !hasLogDir -> "没找到 /data/adb/lspd/log 目录。\n" +
                    "可能是 LSPosed 版本把日志放到了别处，或模块日志功能被关闭。"
                sawFile -> "日志文件存在，但里面没有 SubwayIsland 的记录。\n" +
                    "说明模块还没有被任何作用域进程加载：\n" +
                    "1) LSPosed 里确认「启用模块」总开关已打开（不是只勾作用域）；\n" +
                    "2) 作用域至少勾选 com.android.systemui；\n" +
                    "3) 强制停止「系统界面」或重启手机后再看。"
                else -> "日志目录存在但没有 .log 文件，logcat 里也没有匹配项。"
            }
            return Result(text = "", source = "none", hint = why)
        }

        if (sawFile) source = "lspd"
        else if (raw.out.contains("@@LOGCAT")) source = "logcat"

        return Result(text = lines.joinToString("\n"), source = source)
    }

    /** 依次找一个「能跑起来」的 su，尽可能少触发无关的授权弹窗 */
    private fun pickSu(): String? {
        for (candidate in SU_CANDIDATES) {
            if (candidate != "su" && !runCatching { File(candidate).exists() }.getOrDefault(false)) continue
            val r = runSu(candidate, "id", 20_000)
            // -1 = 进程起不来（不存在/不可执行），换下一个；其余情况说明 su 本身可用
            if (r.code != -1) {
                XLog.d("LOG-01", "su|$candidate|code=${r.code}|root=${r.out.contains("uid=0")}")
                return candidate
            }
        }
        return null
    }

    private fun collectScript(): String = """
        echo '@@LS:'$(ls -1 /data/adb/lspd/log >/dev/null 2>&1 && echo ok || echo no)
        for f in /data/adb/lspd/log/*.log /data/adb/lspd/log/*/*.log; do
          if [ -f "${'$'}f" ]; then
            echo "@@FILE:${'$'}f"
            tail -n 5000 "${'$'}f" 2>/dev/null | grep -a SubwayIsland | tail -n $MAX_LINES
          fi
        done
        echo '@@LOGCAT'
        logcat -d -v time -t 6000 2>/dev/null | grep -a SubwayIsland | tail -n $MAX_LINES
    """.trimIndent()

    /** 返回 -1 = 起不来，-2 = 超时，其余为退出码；在后台线程调用 */
    private fun runSu(su: String, script: String, timeoutMs: Long): SuResult {
        return runCatching {
            val process = ProcessBuilder(su, "-c", script)
                .redirectErrorStream(true)
                .start()
            val out = process.inputStream.bufferedReader().use { it.readText() }
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                process.destroy()
                return SuResult(out, -2)
            }
            SuResult(out, process.exitValue())
        }.getOrElse { SuResult("", -1) }
    }

    /** 是否有可用的 su（仅做粗判，不弹授权） */
    fun isRootProbablyAvailable(): Boolean {
        val direct = listOf(
            "/system/bin/su", "/system/xbin/su", "/sbin/su",
            "/data/adb/ksu/bin/su", "/debug_ramdisk/su"
        ).any { runCatching { File(it).exists() }.getOrDefault(false) }
        if (direct) return true
        // 部分设备上 su 只在 PATH 里（比如 Magisk 的 tmpfs 挂载）
        return runCatching {
            val p = ProcessBuilder("sh", "-c", "command -v su").redirectErrorStream(true).start()
            val text = p.inputStream.bufferedReader().use { it.readText() }
            p.waitFor(3, TimeUnit.SECONDS)
            text.trim().isNotEmpty()
        }.getOrDefault(false)
    }
}
