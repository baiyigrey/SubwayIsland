# `miui.focus.param` 结构速查（HyperOS 3/4）

本文件是 `FocusParamBuilder.kt` 的「设计依据」，也是改布局时的第一手参考。
字段定义来自小米开放平台《开发指南》（`pId=2131`）与《小米超级岛模板库》2026-01 版，
字段组合的可用性参考了 [dsha-island](https://github.com/Ralsei1337/dsha-island) 的真机验证结果。

> 结论先行：**这是非公开接口**，文档只覆盖「合法输入」，不保证非法输入会被容错。
> 少一个必传字段的后果通常是「该区域空白」，多一个不认识的 key 的后果通常是「整条通知退化」。

---

## 1. 挂载位置

参数是**通知 extras 的三个 String / Bundle**，不是独立通知：

```kotlin
notification.extras.putString("miui.focus.param", json)          // 主体，JSON 字符串
notification.extras.putBundle("miui.focus.pics", picsBundle)     // 自定义图标：key -> Icon
notification.extras.putBundle("miui.focus.actions", actionBundle)// 按钮：key -> Notification.Action
```

然后照常 `NotificationManager.notify()`。系统解析到 `miui.focus.param` 后按岛样式渲染。

三个 Bundle 的 key 必须**跨文件对齐**：JSON 里写 `"pic": "miui.focus.pic_subway"`，
`miui.focus.pics` 里就必须存在名为 `miui.focus.pic_subway` 的 `Icon`，否则那块区域是空的。

---

## 2. `param_v2` 顶层

| 字段 | 类型 | 必填 | 本项目取值 | 说明 |
|---|---|---|---|---|
| `protocol` | int | 是 | `1` | 协议版本，固定 1 |
| `business` | String | 是 | `subway_navi` | 运营场景，官方用于数据统计 |
| `updatable` | boolean | 否 | `true` | 允许同 id 通知持续更新同一条岛 |
| `enableFloat` | boolean | 否 | `false` | 更新时**不**自动展开，避免每次到站弹大岛 |
| `islandFirstFloat` | boolean | 否 | `true`（终态 `false`） | 首次出现自动展开 |
| `filterWhenNoPermission` | boolean | 否 | `false` | 无焦点权限时退化为普通通知而非丢弃 |
| `islandTimeout` | int(s) | 否 | 见 `param_island` | 岛自动消失时间 |
| `ticker` / `tickerPic` | String | 否 | 线路 · 下一站 | OS2 状态栏焦点文案与图标 |
| `aodTitle` / `aodPic` | String | 否 | 同上 | 息屏显示 |
| `param_island` | Object | 是 | 见下 | 岛（摘要态）数据 |
| `baseInfo` | Object | 是 | 见下 | 焦点通知（展开态/通知栏）文本 |
| `hintInfo` | Object | 否 | 见下 | 焦点卡上的按钮行 |
| `actions` | Array | 否 | 见下 | 大岛按钮 |

---

## 3. `param_island`

| 字段 | 类型 | 说明 |
|---|---|---|
| `islandProperty` | int | `1` 信息展示为主 / `2` 操作为主。本项目恒为 1 |
| `islandTimeout` | int(s) | 岛自动消失时间：进行中 `3600`，终态 `8` |
| `islandOrder` | boolean | 更新时是否更新岛排序 |
| `highlightColor` | String | 强调色，`#RRGGBB` |
| `bigIslandArea` | Object | 大岛（展开态）内容 |
| `smallIslandArea` | Object | 小岛（摘要态）内容 |

### 3.1 `bigIslandArea`：A 区 + B 区

大岛以摄像头为界分 A / B 两区，模板库共列出 9 种组合。本项目用**组合 2**：

- A 区：`imageTextInfoLeft`（图文组件 1）—— 图标 + 大字 + 后置小字
- B 区：`textInfo`（文本组件）—— 大字 + 后置小字

```json
"bigIslandArea": {
  "imageTextInfoLeft": {
    "type": 1,
    "picInfo": { "type": 1, "pic": "miui.focus.pic_subway" },
    "textInfo": { "title": "1号线", "content": "人民广场", "showHighlightColor": true }
  },
  "textInfo": { "title": "剩3站", "content": "12分" }
}
```

约束（来自模板库）：

- A 区「图标」与「正文大字」二选一必传，本项目两个都给；
- 大字建议 **≤ 4 个汉字**，超长会降级成小字，再超会被裁切（无滚动）；
- `picInfo.type` 固定 `1` 表示静态图标；`pic` 为 `miui.focus.pics` 中的 key；
- `narrowFont` 仅对数字/字母生效。

### 3.2 `smallIslandArea`

```json
"smallIslandArea": { "picInfo": { "type": 1, "pic": "miui.focus.pic_subway" } }
```

只放图标。不传时系统回落顺序：小岛图标 → 大岛 A 区左侧图标 → 应用图标。

### 3.3 进度类组件：用 `progressTextInfo`，不用 `progressInfo`

B 区有两种进度类组件，本项目只用其中一种：

| 组件 | 结构 | 本项目 |
|---|---|---|
| `progressTextInfo` | 环形进度 + 文字（百分比 / ETA / 站数） | **使用**，属模板库第 5 套组合 |
| `progressInfo` | 纯进度条，需要 `picForward` / `picMiddle` / `picEnd` 等多个图片 key | 不用：与 A 区图文组件的组合不在模板库 9 套之内 |

`progressInfo` 的字段形如：

```json
"progressInfo": { "progress": 40, "colorProgress": "#FF8514", "colorProgressEnd": "#FF8514" }
```

想试的话，把它塞进 `bigIslandArea` 并删掉 `textInfo`，同时在 `miui.focus.pics` 里补齐
上述 key，真机确认渲染正常后再依赖——这套组合没有官方样例，出错时通常表现为该区域空白。

---

## 4. `baseInfo`（焦点通知 / 通知栏）

| 字段 | 必填 | 本项目含义 |
|---|---|---|
| `type` | 是 | `1`（文本组件 1：title + subTitle 一行，content 一行） |
| `title` | 是 | 线路名，如「1号线」 |
| `subTitle` | 否 | 下一站 |
| `content` | 是 | 「开往 莘庄」 |
| `extraTitle` | 否 | ETA 文案 |
| `specialTitle` | 否 | 「剩3站」（强调标签） |
| `showDivider` / `showContentDivider` | 否 | 主文本之间 / 主文本与补充文本之间的分割符 |

终态时改为 `title=已到达`、`content=行程已结束`。

---

## 5. `hintInfo` + `actions`（按钮）

按钮有两条互斥路径，**同时给也对**：`action` 指向 `miui.focus.actions` 里的 `Notification.Action`
（复用原 App 的 `PendingIntent`），`actionIcon` / `actionTitle` 提供兜底渲染。

```json
"hintInfo": {
  "type": 2,
  "title": "1号线 · 人民广场",
  "content": "查看地图",
  "actionInfo": {
    "action": "miui.focus.action_open",
    "actionIcon": "miui.focus.pic_open",
    "actionTitle": "打开",
    "actionIntentType": 0
  }
},
"actions": [
  { "action": "miui.focus.action_open", "actionIcon": "miui.focus.pic_open",
    "actionIconDark": "miui.focus.pic_open", "actionIntentType": 0, "type": 0 }
]
```

`type`：`0` 普通按钮 / `1` 进度按钮 / `2` 文字按钮。
`actionIntentType`：`0` 走 `miui.focus.actions` 中的 PendingIntent，`1` URL，`2` 广播，`3` 服务。

对应的 extras：

```kotlin
val pics = Bundle().apply {
    putParcelable("miui.focus.pic_subway", Icon.createWithResource("com.subwayisland", R.drawable.ic_island_subway))
    putParcelable("miui.focus.pic_open", icon)
}
val actions = Bundle().apply {
    putParcelable("miui.focus.action_open", notification.actions[0])   // 或自建 Notification.Action
}
```

---

## 6. 完整样例（本项目真实输出）

输入：`NavState(1号线, 人民广场, 莘庄, 剩3站, 12分)` + 一个「打开」按钮。

```json
{
  "param_v2": {
    "protocol": 1,
    "business": "subway_navi",
    "updatable": true,
    "enableFloat": false,
    "islandFirstFloat": true,
    "filterWhenNoPermission": false,
    "ticker": "1号线 · 人民广场",
    "tickerPic": "miui.focus.pic_subway",
    "aodTitle": "1号线 · 人民广场",
    "aodPic": "miui.focus.pic_subway",
    "param_island": {
      "islandProperty": 1,
      "islandTimeout": 3600,
      "islandOrder": true,
      "highlightColor": "#1A73E8",
      "bigIslandArea": {
        "imageTextInfoLeft": {
          "type": 1,
          "picInfo": { "type": 1, "pic": "miui.focus.pic_subway" },
          "textInfo": {
            "title": "1号线",
            "content": "人民广场",
            "showHighlightColor": true,
            "narrowFont": false
          }
        },
        "textInfo": {
          "title": "剩3站",
          "content": "12分",
          "showHighlightColor": false,
          "narrowFont": false
        }
      },
      "smallIslandArea": {
        "picInfo": { "type": 1, "pic": "miui.focus.pic_subway" }
      }
    },
    "baseInfo": {
      "type": 1,
      "title": "1号线",
      "subTitle": "人民广场",
      "content": "开往 莘庄",
      "specialTitle": "剩3站",
      "showDivider": true,
      "showContentDivider": true
    },
    "hintInfo": {
      "type": 2,
      "title": "1号线 · 人民广场",
      "content": "查看地图",
      "actionInfo": {
        "action": "miui.focus.action_open",
        "actionIcon": "miui.focus.pic_open",
        "actionTitle": "打开",
        "actionIntentType": 0
      }
    },
    "actions": [
      {
        "action": "miui.focus.action_open",
        "actionIcon": "miui.focus.pic_open",
        "actionIconDark": "miui.focus.pic_open",
        "actionIntentType": 0,
        "type": 0
      }
    ]
  }
}
```

体积约 1.1 KB。代码里对 JSON 做了 3 KB 上限保护（`IslandPayload.MAX_JSON_BYTES`），
超限直接放弃注入而不是发一条坏通知。

---

## 6.5 进度文本组件（progressTextInfo）

大岛 B 区一共有 9 种组件可选，本项目用的是模板库第 5 套：
**A 区图文组件1 + B 区进度文本组件**。它在海外版/国内版都是文档里明确列出的组合，
比直接塞 `progressInfo`（需要 5 个系统图标资源）稳。

### 结构

```json
"bigIslandArea": {
  "imageTextInfoLeft": {
    "type": 1,
    "picInfo": { "type": 1, "pic": "miui.focus.pic_subway" },
    "textInfo": { "title": "1号线", "content": "人民广场", "showHighlightColor": true }
  },
  "progressTextInfo": {
    "progressInfo": {
      "progress": 62,
      "colorReach": "#FF3482FF",
      "colorUnReach": "#223482FF"
    },
    "textInfo": {
      "frontTitle": "约12分",
      "title": "62%",
      "content": "3/8 站",
      "narrowFont": true,
      "showHighlightColor": true
    }
  }
}
```

要点：

| 字段 | 约束 |
|---|---|
| `progress` | 0–100 整数，必传；越界会被系统按边界处理，本项目已提前夹取 |
| `colorReach` / `colorUnReach` | ARGB，可空（空则用系统主色） |
| `textInfo.title` | 大字，建议 ≤ 4 个汉字；百分比数字配 `narrowFont=true` 更紧凑 |
| `textInfo.frontTitle` / `content` | 前后小字，**最多两段**，再多会被裁切 |
| 与 `textInfo` 的关系 | B 区只能二选一：有进度就不要再传普通 `textInfo` |

### 进度从哪来

`ProgressTracker` 有两条路径：

```kotlin
// 1) 有全程：精确
percent = (total - remain) * 100 / total

// 2) 只有剩余：用本次行程观察到的最大剩余当分母
percent = (maxRemain - remain) * 100 / maxRemain
```

第 2 条是给「剩余 3 站」这种常见文案准备的：刚上车 0%，每过一站上涨，
换线路 / 剩余变大 / 导航结束都会重置。拿不到剩余站数时不猜，B 区退回普通文本组件。

> 这部分是纯逻辑，`ProgressTrackerTest` 覆盖了比例、重置、越界与空数据六种情况。

### 6.6 公交与换乘的差异

公交与地铁的参数结构完全相同，只有图标与兜底文案不同：

| 位置 | 地铁 | 公交 |
|---|---|---|
| `tickerPic` / `aodPic` | `miui.focus.pic_subway` | `miui.focus.pic_bus` |
| `imageTextInfoLeft.picInfo.pic` | 同上 | 同上 |
| `smallIslandArea.picInfo.pic` | 同上 | 同上 |
| A 区 `title` 的兜底 | 「地铁」 | 「公交」 |

`miui.focus.pics` 里必须放**同一个 key**，否则那块区域空白——这也是 `IslandPayload`
把「JSON 里的 key」和「Bundle 里的 key」放在同一个函数里生成的原因。

换乘不改结构，只改 B 区内容：当下一站就是换乘站时，B 区从 `progressTextInfo`
或普通 `textInfo` 换成一条换乘提示：

```json
"textInfo": { "title": "2号线", "content": "下车换乘", "showHighlightColor": true }
```

三种 B 区形态互斥，同一时间只能出现一个。同时：

- `ticker` 变成「人民广场 · 换乘2号线」；
- `baseInfo.content` 变成「开往 莘庄 · 换乘 2号线」；
- 换乘站之后线路名变化，进度重新从 0 开始。

## 7. 适配指南

| 想改什么 | 改哪里 | 注意 |
|---|---|---|
| 文案（线路/下一站的排版） | `FocusParamBuilder.buildBigArea` / `buildBaseInfo` | A 区大字 ≤ 4 汉字 |
| 换图标 | `IslandPayload.buildPics` + `R.drawable.ic_island_subway` | key 必须与 JSON 的 `pic` 一致 |
| 加按钮 | `IslandPayload.of` 里的 `ActionSpec` 列表 + 对应 `miui.focus.actions` | `action` 字符串必须两处一致 |
| 加/改进度 | `buildBigArea` → `progressTextInfo` | 用模板库第 5 套组合，见下节 |
| 关闭大岛只留胶囊 | `Options(protocol = 2)` | 等于退回 OS2 焦点通知 |
| 适配新协议 | `HyperOsCompat.protocolVersion()` + 本文件 | 新版本先看 `notification_focus_protocol` |

### 版本探测（官方接口）

```kotlin
// 是否支持超级岛
SystemProperties.getBoolean("persist.sys.feature.island", false)

// 1=OS1 焦点通知, 2=OS2 焦点通知, 3=OS3+ 超级岛
Settings.System.getInt(cr, "notification_focus_protocol", 0)
```

### 踩坑清单

1. **只写 `miui.focus.param` 不够**：第三方应用还要过 `canShowFocus` / `checkSignatures`
   两道校验，否则参数会被静默丢弃 —— 见 `FocusPermissionBypass.kt`。
2. **JSON 里出现的每个 `miui.focus.pic_*` 都必须在 `miui.focus.pics` 里有同名 Icon**。
3. **不要在更新时设 `enableFloat=true`**：地铁每站都会刷一次通知，会变成连续弹岛。
4. **终态要短超时**：靠 `islandTimeout=8` 比靠「发一条 cancel」可靠，后者容易把用户
   正在看的大岛直接抽走。
5. `param_v2` 的字段顺序无关紧要，`org.json` 输出是无序 Map，系统按 key 取值。
