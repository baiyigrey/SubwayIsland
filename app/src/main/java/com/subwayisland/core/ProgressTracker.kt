package com.subwayisland.core

/**
 * 行程进度推算。
 *
 * 地图 App 的通知里通常只有「剩余 N 站」，没有「全程 M 站」。所以分两种情况：
 *  - 拿到全程（结构化 Hook / 文案里有「共 8 站」）：直接 `(M - N) / M`；
 *  - 只有剩余：记下本次行程观察到的最大剩余值当作近似全程，
 *    进度 = （最大剩余 - 当前剩余）/ 最大剩余。
 *    刚上车时是 0%，每过一站涨一点，方向是对的，也不会出现 100% 又回退。
 *
 * 每次「剩余站数变大」「换了线路」「导航结束」都视为新行程并重置，
 * 避免上一趟的进度污染下一趟。
 */
object ProgressTracker {

    private data class Session(
        val line: String?,
        var maxRemain: Int,
        var updatedAt: Long
    )

    private val sessions = HashMap<String, Session>()

    private const val IDLE_RESET_MS = 10 * 60_000L

    @Synchronized
    fun update(state: NavState): Int? {
        val pkg = state.pkg
        if (state.terminal) {
            sessions.remove(pkg)
            return 100
        }
        val remain = state.remainStations ?: return state.progressPercent
        val total = state.totalStations
        if (total != null && total > 1) {
            val remainClamped = remain.coerceIn(0, total)
            val percent = ((total - remainClamped) * 100) / total
            sessions[pkg] = Session(state.lineName, remainClamped, state.updatedAt)
            return percent.coerceIn(0, 99)
        }

        val now = state.updatedAt.takeIf { it > 0 } ?: System.currentTimeMillis()
        val prev = sessions[pkg]
        val stale = prev == null || now - prev.updatedAt > IDLE_RESET_MS
        if (stale || prev!!.line != state.lineName || remain > prev.maxRemain) {
            sessions[pkg] = Session(state.lineName, remain, now)
            return 0
        }
        prev.updatedAt = now
        prev.maxRemain = maxOf(prev.maxRemain, remain)
        val max = prev.maxRemain
        if (max <= 0) return null
        val passed = (max - remain).coerceAtLeast(0)
        return ((passed * 100) / max).coerceIn(0, 99)
    }

    @Synchronized
    fun reset(pkg: String) {
        sessions.remove(pkg)
    }
}
