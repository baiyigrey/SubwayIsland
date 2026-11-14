package com.subwayisland

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.View.MeasureSpec
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.subwayisland.ui.MainScreen
import com.subwayisland.ui.StatusLevel
import com.subwayisland.ui.theme.MiuixTheme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 用 Robolectric 的原生图形模式把主界面真的画出来存成 PNG。
 *
 * 目的不是像素级回归，而是「敢把界面发出去」：布局炸了、字全白、卡片没圆角
 * 这类问题在这张图上肉眼就能看出来，不用等真机。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h915dp-xxhdpi")
class ScreenshotTest {

    private val env = """
        地铁岛 0.1.0
        设备: Xiaomi 2304FPN6DC
        系统: 17 (API 37)
        协议: 3 · 超级岛: 支持
        Root: 有 su
        开关: 总=true 系统侧=true 地图侧=false
        百度地图: 16.12.0 (1024)
        高德地图: 未安装
        系统界面: 17.03.260226.r
    """.trimIndent()

    private val status = """
        系统界面进程：已加载 · 注入点命中
        最近回报：11-14 20:03:11（刚刚）
        细节：class=MiuiBaseNotifUtil|proto=3
    """.trimIndent()

    private fun render(dark: Boolean, name: String, bus: Boolean = false) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            MiuixTheme(darkTheme = dark) {
                MainScreen(
                    version = "0.1.0",
                    initialBus = bus,
                    env = env,
                    status = status,
                    statusLevel = StatusLevel.Ok,
                    flags = mapOf(
                        "enabled" to true,
                        "systemui_enrich" to true,
                        "inapp_inject" to false,
                        "module_icon" to true,
                        "promoted_fallback" to true,
                        "nav_recon" to false
                    ),
                    onToggle = { _, _ -> },
                    onRefreshStatus = {},
                    onSelfTest = {},
                    onSelfTestBus = {},
                    onEndSelfTest = {},
                    onOpenLog = {},
                    onCopyEnv = {}
                )
            }
        }
        shadowOf(Looper.getMainLooper()).idle()

        val width = 1080
        val height = 2400
        val decor: View = activity.window.decorView
        decor.measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
        )
        decor.layout(0, 0, width, height)
        shadowOf(Looper.getMainLooper()).idle()

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(bitmap))
        val out = File("docs/$name")
        out.parentFile?.mkdirs()
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun `渲染浅色主界面`() = render(false, "screenshot-main-light.png")

    @Test
    fun `渲染深色主界面`() = render(true, "screenshot-main-dark.png")

    @Test
    fun `渲染公交预览`() = render(false, "screenshot-main-bus.png", bus = true)
}
