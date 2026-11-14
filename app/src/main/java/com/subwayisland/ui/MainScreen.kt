package com.subwayisland.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.subwayisland.R
import com.subwayisland.core.Config
import com.subwayisland.ui.theme.MiuixPalette

/** 状态灯：绿=正常，橙=能用但有落差，灰=没拿到 */
enum class StatusLevel { Ok, Warn, Unknown }

data class SettingItem(
    val key: String,
    val title: String,
    val desc: String
)

val SETTINGS = listOf(
    SettingItem(Config.Keys.ENABLED, "启用模块", "总开关，改动后需重启作用域进程"),
    SettingItem(Config.Keys.SYSTEMUI_ENRICH, "系统侧注入", "推荐路径：在系统界面进程里补参数"),
    SettingItem(Config.Keys.INAPP_INJECT, "地图侧注入", "备路径：系统侧 Hook 失效时再用"),
    SettingItem(Config.Keys.MODULE_ICON, "使用模块图标", "岛上显示线路图标，关闭则用地图小图标"),
    SettingItem(Config.Keys.PROMOTED_FALLBACK, "保底胶囊", "官方参数被拒时退化为状态栏单行文本"),
    SettingItem(Config.Keys.RECON, "导航类名侦察", "调试：扫描地图内导航类并打日志")
)

val SETTING_DEFAULTS = mapOf(
    Config.Keys.ENABLED to true,
    Config.Keys.SYSTEMUI_ENRICH to true,
    Config.Keys.INAPP_INJECT to false,
    Config.Keys.MODULE_ICON to true,
    Config.Keys.PROMOTED_FALLBACK to true,
    Config.Keys.RECON to false
)

@Composable
fun MainScreen(
    version: String,
    env: String,
    status: String,
    statusLevel: StatusLevel,
    flags: Map<String, Boolean>,
    onToggle: (String, Boolean) -> Unit,
    onRefreshStatus: () -> Unit,
    onSelfTest: () -> Unit,
    onSelfTestBus: () -> Unit,
    onEndSelfTest: () -> Unit,
    onOpenLog: () -> Unit,
    onCopyEnv: () -> Unit,
    initialBus: Boolean = false
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp)
            .padding(top = 28.dp, bottom = 40.dp)
    ) {
        Header(version)
        IslandPreview(initialBus = initialBus)
        SectionTitle("运行状态")
        StatusCard(status, statusLevel, onRefreshStatus)
        SectionTitle("自测出岛")
        SelfTestCard(onSelfTest, onSelfTestBus, onEndSelfTest)
        SectionTitle("日志与反馈")
        LogCard(onOpenLog, onCopyEnv)
        SectionTitle("开关")
        SettingsCard(flags, onToggle)
        SectionTitle("环境")
        EnvCard(env)
        SectionTitle("使用步骤")
        StepsCard()
        Footer(version)
    }
}

@Composable
private fun Header(version: String) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            "地铁岛",
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "v$version",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 5.dp)
        )
    }
    Text(
        "把地铁与公交导航送上澎湃超级岛",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp)
    )
}

/**
 * 岛上长什么样。这里用同一套数据画一个近似预览，既是说明也是占位：
 * 用户在没装地图之前也能看到「线路 · 下一站 + 环形进度」的最终效果。
 */
/**
 * 岛上长什么样。用同一套数据画一个近似预览，既能说明效果，也是占位：
 * 用户在没装地图之前也能看到「线路 · 下一站 + 环形进度」的最终样子。
 *
 * 顶部两个 chip 切换地铁 / 公交，两种出行方式共用一套版式，只有图标与文案不同。
 */
@Composable
private fun IslandPreview(percent: Int = 62, initialBus: Boolean = false) {
    var bus by remember { mutableStateOf(initialBus) }

    Row(Modifier.padding(top = 18.dp)) {
        ModeChip("地铁", !bus) { bus = false }
        Spacer(Modifier.width(8.dp))
        ModeChip("公交", bus) { bus = true }
    }
    Spacer(Modifier.height(10.dp))

    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(26.dp))
            .background(
                Brush.verticalGradient(
                    listOf(MiuixPalette.IslandTop, MiuixPalette.IslandBottom)
                )
            )
            .padding(horizontal = 16.dp, vertical = 15.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MiuixPalette.Blue.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painterResource(if (bus) R.drawable.ic_bus_glyph else R.drawable.ic_subway_glyph),
                    contentDescription = null,
                    tint = MiuixPalette.BlueLight,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (bus) "123路 · 中山北路" else "1号线 · 人民广场",
                    color = MiuixPalette.IslandText,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    if (bus) "开往外滩 · 剩2/5站" else "开往莘庄 · 换乘2号线",
                    color = MiuixPalette.IslandSubText,
                    fontSize = 12.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(12.dp))
            ProgressRing(percent)
        }
    }
    Text(
        if (bus) {
            "预览：公交车标 + 线路名，右侧环形进度是全程完成度"
        } else {
            "预览：下一站即换乘站时，右侧改为换乘提示"
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 6.dp, top = 8.dp)
    )
}

@Composable
private fun ModeChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = if (selected) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
            )
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 6.dp)
    )
}

@Composable
private fun ProgressRing(percent: Int?) {
    Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 4.dp.toPx()
            inset(stroke / 2) {
                drawArc(
                    color = Color(0xFF2E323B),
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    style = Stroke(stroke, cap = StrokeCap.Round),
                    topLeft = Offset.Zero,
                    size = Size(size.width, size.height)
                )
                if (percent != null) {
                    drawArc(
                        color = MiuixPalette.BlueLight,
                        startAngle = -90f,
                        sweepAngle = 360f * percent.coerceIn(0, 100) / 100f,
                        useCenter = false,
                        style = Stroke(stroke, cap = StrokeCap.Round),
                        topLeft = Offset.Zero,
                        size = Size(size.width, size.height)
                    )
                }
            }
        }
        Text(
            if (percent != null) "$percent%" else "--",
            color = MiuixPalette.IslandText,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 8.dp, top = 24.dp, bottom = 8.dp)
    )
}

@Composable
private fun MiuixCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface),
        content = content
    )
}

@Composable
private fun StatusCard(status: String, level: StatusLevel, onRefresh: () -> Unit) {
    MiuixCard {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val color = when (level) {
                    StatusLevel.Ok -> MiuixPalette.Green
                    StatusLevel.Warn -> MiuixPalette.Orange
                    StatusLevel.Unknown -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(color)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    when (level) {
                        StatusLevel.Ok -> "链路正常"
                        StatusLevel.Warn -> "已加载，但有落差"
                        StatusLevel.Unknown -> "尚未收到回报"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "刷新",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onRefresh() }
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
            Spacer(Modifier.height(10.dp))
            SelectionContainer {
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 19.sp
                )
            }
        }
    }
}

@Composable
private fun SelfTestCard(onSelfTest: () -> Unit, onSelfTestBus: () -> Unit, onEnd: () -> Unit) {
    MiuixCard {
        Column(Modifier.padding(16.dp)) {
            Text(
                "不需要地图 App。点一下会发一条模拟导航，系统界面把它变成岛。" +
                    "地铁样例带换乘，公交样例带全程进度——两种形态都能单独验证。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 19.sp
            )
            Spacer(Modifier.height(14.dp))
            Row {
                Button(
                    onClick = onSelfTest,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(42.dp)
                ) {
                    Text("自测地铁", fontSize = 14.sp)
                }
                Spacer(Modifier.width(10.dp))
                Button(
                    onClick = onSelfTestBus,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(42.dp)
                ) {
                    Text("自测公交", fontSize = 14.sp)
                }
            }
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = onEnd,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurface
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(42.dp)
            ) {
                Text("结束自测", fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun LogCard(onOpenLog: () -> Unit, onCopyEnv: () -> Unit) {
    MiuixCard {
        Column(Modifier.padding(16.dp)) {
            Text(
                "日志页会自动带上环境、运行状态与系统日志，复制后整段发即可。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 19.sp
            )
            Spacer(Modifier.height(14.dp))
            Row {
                Button(
                    onClick = onOpenLog,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.height(42.dp)
                ) {
                    Text("查看日志", fontSize = 14.sp)
                }
                Spacer(Modifier.width(10.dp))
                Button(
                    onClick = onCopyEnv,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    ),
                    modifier = Modifier.height(42.dp)
                ) {
                    Text("复制环境", fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable
private fun SettingsCard(flags: Map<String, Boolean>, onToggle: (String, Boolean) -> Unit) {
    MiuixCard {
        SETTINGS.forEachIndexed { index, item ->
            if (index > 0) {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    thickness = 0.7.dp,
                    modifier = Modifier.padding(start = 16.dp)
                )
            }
            val checked = flags[item.key] ?: false
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onToggle(item.key, !checked) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        item.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        item.desc,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = checked,
                    onCheckedChange = { onToggle(item.key, it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                        checkedBorderColor = Color.Transparent,
                        uncheckedThumbColor = Color.White,
                        uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                        uncheckedBorderColor = Color.Transparent
                    )
                )
            }
        }
    }
}

@Composable
private fun EnvCard(env: String) {
    MiuixCard {
        SelectionContainer {
            Text(
                env,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 19.sp,
                modifier = Modifier.padding(16.dp)
            )
        }
    }
}

@Composable
private fun StepsCard() {
    val steps = listOf(
        "先用「自测地铁 / 公交」确认模块本身可用",
        "LSPosed 启用模块（总开关），作用域勾 com.android.systemui",
        "地图 App 按需勾选：百度 / 高德 / 腾讯",
        "强制停止「系统界面」，或直接重启手机",
        "打开地图，开始一次地铁或公交路线导航"
    )
    MiuixCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            steps.forEachIndexed { index, text ->
                Row {
                    Text(
                        "${index + 1}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                            .padding(top = 3.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        lineHeight = 18.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun Footer(version: String) {
    Text(
        "v$version · MIT License · 仅用于个人学习，请勿用于商业用途",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        modifier = Modifier.padding(start = 8.dp, top = 28.dp)
    )
}
