package com.subwayisland

import android.app.Notification
import com.subwayisland.core.SelfTest
import com.subwayisland.core.TransitMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 自测通知是「不装地图也能验证链路」的唯一入口，
 * 两侧（App 发 / SystemUI 读）靠 extras 里的字段名对接，改错一个字母就整条断掉。
 * 这条测试锁死这个契约。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SelfTestTest {

    @Test
    fun `自测通知可被解析成导航状态`() {
        val n = SelfTest.buildNotification(RuntimeEnvironment.getApplication())
        assertEquals(true, n.extras.getBoolean(SelfTest.EXTRA_FLAG, false))

        val state = SelfTest.readState(n)
        assertNotNull("带标记的通知必须能解析出 NavState", state)
        assertEquals("1号线", state!!.lineName)
        assertEquals("人民广场", state.nextStation)
        assertEquals("莘庄", state.destination)
        assertEquals(3, state.remainStations)
        assertEquals(12, state.etaMinutes)
        assertEquals("selftest", state.source)
        assertTrue("必须被判为导航中，否则富化器会跳过", state.isNavigating)
    }

    @Test
    fun `公交自测通知解析为公交且无换乘`() {
        val n = SelfTest.buildBusNotification(RuntimeEnvironment.getApplication())
        val state = SelfTest.readState(n)

        assertNotNull("带标记的公交通知必须能解析出 NavState", state)
        assertEquals(TransitMode.BUS, state!!.mode)
        assertEquals("123路", state.lineName)
        assertEquals("中山北路", state.nextStation)
        assertEquals(2, state.remainStations)
        assertEquals(5, state.totalStations)
        assertNull("公交样例不应带换乘", state.transferLine)
    }

    @Test
    fun `没有标记的普通通知不会被当成自测`() {
        val ctx = RuntimeEnvironment.getApplication()
        val plain = Notification.Builder(ctx, SelfTest.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_island_subway)
            .setContentTitle("普通通知")
            .build()
        assertNull(SelfTest.readState(plain))
    }
}
