package com.subwayisland

import com.subwayisland.core.NavState
import com.subwayisland.core.ProgressTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 进度推算的两条路径：
 *  - 知道全程：直接按比例算；
 *  - 只知道剩余：用本次行程观察到的最大剩余当近似全程。
 * 地铁场景里最常见的是后者（「剩余 3 站」），所以要保证它单调、不越界、换行程会重置。
 */
class ProgressTrackerTest {

    private fun state(remain: Int?, total: Int? = null, line: String = "1号线", terminal: Boolean = false) =
        NavState(
            pkg = "test",
            lineName = line,
            nextStation = "人民广场",
            remainStations = remain,
            totalStations = total,
            terminal = terminal
        )

    @Test
    fun `知道全程时按比例计算`() {
        ProgressTracker.reset("test")
        assertEquals(62, ProgressTracker.update(state(remain = 3, total = 8)))
        assertEquals(0, ProgressTracker.update(state(remain = 8, total = 8)))
    }

    @Test
    fun `只有剩余站时随站数递减而递增`() {
        ProgressTracker.reset("test")
        val first = ProgressTracker.update(state(remain = 6))
        val second = ProgressTracker.update(state(remain = 4))
        val third = ProgressTracker.update(state(remain = 2))

        assertEquals(0, first)
        assertEquals(33, second)
        assertEquals(66, third)
    }

    @Test
    fun `剩余站数变大视为新行程并重置`() {
        ProgressTracker.reset("test")
        ProgressTracker.update(state(remain = 6))
        assertEquals(66, ProgressTracker.update(state(remain = 2)))
        // 换乘 / 重新开始导航：剩余突然变多
        assertEquals(0, ProgressTracker.update(state(remain = 9)))
    }

    @Test
    fun `换线路重置进度`() {
        ProgressTracker.reset("test")
        ProgressTracker.update(state(remain = 6))
        assertEquals(66, ProgressTracker.update(state(remain = 2)))
        assertEquals(0, ProgressTracker.update(state(remain = 5, line = "2号线")))
    }

    @Test
    fun `导航结束给满分并可再次开始`() {
        ProgressTracker.reset("test")
        ProgressTracker.update(state(remain = 6))
        assertEquals(100, ProgressTracker.update(state(remain = 0, terminal = true)))
        assertEquals(0, ProgressTracker.update(state(remain = 5)))
    }

    @Test
    fun `没有剩余站时不猜进度`() {
        ProgressTracker.reset("test")
        assertNull(ProgressTracker.update(state(remain = null)))
    }
}
