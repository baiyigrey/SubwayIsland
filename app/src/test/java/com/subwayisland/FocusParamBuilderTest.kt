package com.subwayisland

import com.subwayisland.core.FocusParamBuilder
import com.subwayisland.core.NavState
import com.subwayisland.core.TransitMode
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 参数构造的回归测试。
 *
 * 超级岛对字段很挑剔、又没有官方校验工具，所以把「结构是否按预期落地」
 * 固化成测试：以后调布局只改 FocusParamBuilder，跑一遍测试就知道有没有写错 key。
 */
class FocusParamBuilderTest {

    private fun sample() = NavState(
        pkg = "com.autonavi.minimap",
        lineName = "1号线",
        nextStation = "人民广场",
        destination = "莘庄",
        remainStations = 3,
        etaMinutes = 12
    )

    @Test
    fun `普通导航生成合法 param_v2`() {
        val root = JSONObject(FocusParamBuilder.build(sample()))
        val p = root.getJSONObject("param_v2")

        assertEquals(1, p.getInt("protocol"))
        assertEquals("subway_navi", p.getString("business"))
        assertTrue(p.getBoolean("updatable"))
        assertFalse(p.getBoolean("enableFloat"))
        assertEquals("1号线 · 人民广场", p.getString("ticker"))
        assertEquals(FocusParamBuilder.PIC_SUBWAY, p.getString("tickerPic"))

        val island = p.getJSONObject("param_island")
        assertEquals(1, island.getInt("islandProperty"))
        assertEquals(3600, island.getInt("islandTimeout"))

        val big = island.getJSONObject("bigIslandArea")
        val left = big.getJSONObject("imageTextInfoLeft")
        assertEquals(1, left.getInt("type"))
        assertEquals("1号线", left.getJSONObject("textInfo").getString("title"))
        assertEquals("人民广场", left.getJSONObject("textInfo").getString("content"))
        assertEquals(FocusParamBuilder.PIC_SUBWAY, left.getJSONObject("picInfo").getString("pic"))

        val right = big.getJSONObject("textInfo")
        assertEquals("剩3站", right.getString("title"))
        assertEquals("12分", right.getString("content"))

        val small = island.getJSONObject("smallIslandArea")
        assertEquals(FocusParamBuilder.PIC_SUBWAY, small.getJSONObject("picInfo").getString("pic"))

        val base = p.getJSONObject("baseInfo")
        assertEquals("1号线", base.getString("title"))
        assertEquals("人民广场", base.getString("subTitle"))
        assertEquals("开往 莘庄", base.getString("content"))
        assertEquals("剩3站", base.getString("specialTitle"))
    }

    @Test
    fun `终态岛使用短超时且不自动展开`() {
        val root = JSONObject(
            FocusParamBuilder.build(sample().copy(terminal = true))
        )
        val p = root.getJSONObject("param_v2")

        assertFalse(p.getBoolean("islandFirstFloat"))
        assertEquals(8, p.getJSONObject("param_island").getInt("islandTimeout"))
        assertEquals("行程已结束", p.getString("ticker"))

        val base = p.getJSONObject("baseInfo")
        assertEquals("已到达", base.getString("title"))
        assertEquals("行程已结束", base.getString("content"))
    }

    @Test
    fun `协议低于3时不输出岛字段`() {
        val p = JSONObject(
            FocusParamBuilder.build(sample(), FocusParamBuilder.Options(protocol = 2))
        ).getJSONObject("param_v2")

        assertFalse(p.has("param_island"))
        // 焦点卡本身在 OS2 上仍然有效
        assertTrue(p.has("baseInfo"))
    }

    @Test
    fun `缺失线路时用兜底文案且不产生空字段`() {
        val state = NavState(pkg = "com.baidu.BaiduMap", nextStation = "静安寺")
        val big = JSONObject(FocusParamBuilder.build(state))
            .getJSONObject("param_v2")
            .getJSONObject("param_island")
            .getJSONObject("bigIslandArea")

        val text = big.getJSONObject("imageTextInfoLeft").getJSONObject("textInfo")
        assertEquals("地铁", text.getString("title"))
        assertEquals("静安寺", text.getString("content"))
        // 没有剩余站也没有 ETA 时，B 区应该整个不出现
        assertFalse(big.has("textInfo"))
    }

    @Test
    fun `有进度时 B 区换成进度文本组件`() {
        val state = sample().copy(totalStations = 8, progressPercent = 62)
        val big = JSONObject(FocusParamBuilder.build(state))
            .getJSONObject("param_v2")
            .getJSONObject("param_island")
            .getJSONObject("bigIslandArea")

        // 进度文本组件与普通文本组件互斥，不能同时出现
        assertFalse("有进度时不应再输出普通 textInfo", big.has("textInfo"))

        val progressText = big.getJSONObject("progressTextInfo")
        assertEquals(62, progressText.getJSONObject("progressInfo").getInt("progress"))

        val text = progressText.getJSONObject("textInfo")
        assertEquals("62%", text.getString("title"))
        assertEquals("3/8 站", text.getString("content"))
        assertEquals("约12分", text.getString("frontTitle"))
    }

    @Test
    fun `进度边界值被夹在 0 到 100`() {
        val over = JSONObject(FocusParamBuilder.build(sample().copy(progressPercent = 140)))
            .getJSONObject("param_v2")
            .getJSONObject("param_island")
            .getJSONObject("bigIslandArea")
            .getJSONObject("progressTextInfo")
        assertEquals(100, over.getJSONObject("progressInfo").getInt("progress"))

        val under = JSONObject(FocusParamBuilder.build(sample().copy(progressPercent = -5)))
            .getJSONObject("param_v2")
            .getJSONObject("param_island")
            .getJSONObject("bigIslandArea")
            .getJSONObject("progressTextInfo")
        assertEquals(0, under.getJSONObject("progressInfo").getInt("progress"))
    }

    @Test
    fun `公交使用公交车标与线路名`() {
        val state = sample().copy(mode = TransitMode.BUS, lineName = "123路", nextStation = "中山北路")
        val p = JSONObject(FocusParamBuilder.build(state)).getJSONObject("param_v2")
        val island = p.getJSONObject("param_island")
        val big = island.getJSONObject("bigIslandArea")

        assertEquals(FocusParamBuilder.PIC_BUS, p.getString("tickerPic"))
        assertEquals(FocusParamBuilder.PIC_BUS, p.getString("aodPic"))

        val left = big.getJSONObject("imageTextInfoLeft")
        assertEquals("123路", left.getJSONObject("textInfo").getString("title"))
        assertEquals("中山北路", left.getJSONObject("textInfo").getString("content"))
        assertEquals(FocusParamBuilder.PIC_BUS, left.getJSONObject("picInfo").getString("pic"))
        assertEquals(
            FocusParamBuilder.PIC_BUS,
            island.getJSONObject("smallIslandArea").getJSONObject("picInfo").getString("pic")
        )
    }

    @Test
    fun `到换乘站时 B 区让位给换乘提示`() {
        val state = sample().copy(
            totalStations = 8,
            progressPercent = 62,
            transferLine = "2号线",
            transferStation = "人民广场"
        )
        val p = JSONObject(FocusParamBuilder.build(state)).getJSONObject("param_v2")
        val big = p.getJSONObject("param_island").getJSONObject("bigIslandArea")

        // 换乘提示与进度、普通文本三者互斥
        assertFalse("换乘时不应再输出进度组件", big.has("progressTextInfo"))
        val tip = big.getJSONObject("textInfo")
        assertEquals("2号线", tip.getString("title"))
        assertEquals("下车换乘", tip.getString("content"))

        // 状态栏一行也要带上换乘
        assertEquals("人民广场 · 换乘2号线", p.getString("ticker"))
        assertEquals("开往 莘庄 · 换乘 2号线", p.getJSONObject("baseInfo").getString("content"))
    }

    @Test
    fun `未到换乘站时仍显示进度`() {
        val state = sample().copy(
            totalStations = 8,
            progressPercent = 62,
            transferLine = "2号线",
            transferStation = "徐家汇"
        )
        val p = JSONObject(FocusParamBuilder.build(state)).getJSONObject("param_v2")
        val big = p.getJSONObject("param_island").getJSONObject("bigIslandArea")

        assertTrue("没到换乘站时应保留进度", big.has("progressTextInfo"))
        assertFalse(big.has("textInfo"))
        // 焦点卡提前告知要换乘哪条线
        assertEquals("开往 莘庄 · 换乘 2号线", p.getJSONObject("baseInfo").getString("content"))
    }

    @Test
    fun `按钮与 actions 数组一一对应`() {
        val specs = listOf(
            FocusParamBuilder.ActionSpec(FocusParamBuilder.ACTION_OPEN, "打开", FocusParamBuilder.PIC_OPEN),
            FocusParamBuilder.ActionSpec(FocusParamBuilder.ACTION_STOP, "结束", FocusParamBuilder.PIC_STOP)
        )
        val p = JSONObject(
            FocusParamBuilder.build(sample(), FocusParamBuilder.Options(actions = specs))
        ).getJSONObject("param_v2")

        val actions = p.getJSONArray("actions")
        assertEquals(2, actions.length())
        assertEquals(FocusParamBuilder.ACTION_OPEN, actions.getJSONObject(0).getString("action"))
        assertEquals(FocusParamBuilder.ACTION_STOP, actions.getJSONObject(1).getString("action"))

        val hint = p.getJSONObject("hintInfo")
        assertEquals(2, hint.getInt("type"))
        assertEquals(
            FocusParamBuilder.ACTION_OPEN,
            hint.getJSONObject("actionInfo").getString("action")
        )
    }
}
