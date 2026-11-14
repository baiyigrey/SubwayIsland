package com.subwayisland

import android.app.Notification
import com.subwayisland.core.NavParser
import com.subwayisland.core.TransitMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 通知文案解析是整条链路的入口，也是最容易受地图改版影响的部分。
 * 这些样例取自三家地图常见的导航文案形态，重点锁两件事：
 *  - 公交线路（123路 / 特8路）必须认成公交，而不是被当成地铁；
 *  - 公交换乘地铁时，当前线路仍是公交，换乘线路单独记录。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NavParserTest {

    private val ctx = RuntimeEnvironment.getApplication()

    private fun notification(title: String, text: String, channel: String = "navigation") =
        Notification.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_subway_glyph)
            .setContentTitle(title)
            .setContentText(text)
            .build()

    @Test
    fun `识别地铁线路与下一站`() {
        val state = NavParser.parse(
            "com.autonavi.minimap",
            notification("1号线 · 开往莘庄", "下一站：人民广场，剩余3站，约12分钟")
        )

        assertEquals(TransitMode.SUBWAY, state!!.mode)
        assertEquals("1号线", state.lineName)
        assertEquals("人民广场", state.nextStation)
        assertEquals("莘庄", state.destination)
        assertEquals(3, state.remainStations)
        assertEquals(12, state.etaMinutes)
    }

    @Test
    fun `识别公交线路与下一站`() {
        val state = NavParser.parse(
            "com.baidu.BaiduMap",
            notification("123路 · 开往外滩", "下一站：中山北路，还有2站，约8分钟")
        )

        assertEquals(TransitMode.BUS, state!!.mode)
        assertEquals("123路", state.lineName)
        assertEquals("中山北路", state.nextStation)
        assertEquals("外滩", state.destination)
        assertEquals(2, state.remainStations)
    }

    @Test
    fun `公交线路支持特快专夜等前缀`() {
        val state = NavParser.parse(
            "com.baidu.BaiduMap",
            notification("特8路 · 开往北京西站", "下一站：六里桥，剩余5站")
        )
        assertEquals(TransitMode.BUS, state!!.mode)
        assertEquals("特8路", state.lineName)
    }

    @Test
    fun `公交换乘地铁不会把当前线路认错`() {
        val state = NavParser.parse(
            "com.autonavi.minimap",
            notification("123路 · 中山北路", "下一站：中山北路，还有2站 · 换乘2号线")
        )

        assertEquals(TransitMode.BUS, state!!.mode)
        assertEquals("当前线路应仍是 123路", "123路", state.lineName)
        assertEquals("换乘线路单独记录", "2号线", state.transferLine)
    }

    @Test
    fun `解析换乘站与换乘线路`() {
        val state = NavParser.parse(
            "com.autonavi.minimap",
            notification(
                "1号线 · 人民广场",
                "下一站：人民广场，在人民广场站换乘2号线，剩余1站"
            )
        )

        assertEquals("1号线", state!!.lineName)
        assertEquals("人民广场", state.nextStation)
        assertEquals("2号线", state.transferLine)
        assertEquals("人民广场", state.transferStation)
        assertTrue("下一站就是换乘站，应判定为已到换乘站", state.atTransfer)
    }

    @Test
    fun `普通推送不会被当成导航`() {
        val state = NavParser.parse(
            "com.baidu.BaiduMap",
            notification("附近新开了家餐厅", "走 300 米就到，人气很高")
        )
        assertNull(state)
    }
}
