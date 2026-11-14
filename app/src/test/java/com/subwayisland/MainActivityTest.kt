package com.subwayisland

import android.view.ViewGroup
import android.widget.TextView
import com.subwayisland.ui.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 0.1.0 的界面是空白的——onCreate 里构建了视图却忘了 setContentView。
 * 换成 Compose 之后这个坑依然存在（setContent 漏了同样是白屏），
 * 所以这条测试留着：Activity 起来后内容视图必须有子视图，且不是异常兜底页。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainActivityTest {

    @Test
    fun `主界面有内容而不是白屏`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        assertTrue("内容视图为空，界面是白屏", content.childCount > 0)
        val root = content.getChildAt(0)
        assertTrue("不应回落到异常页，实际是 ${root.javaClass.name}", root !is TextView)
    }
}
