# blbl-android

一个第三方哔哩哔哩安卓 App，支持触摸、遥控，以及安卓5，适用于平板、TV、车机等设备。

## 二改说明

本仓库是基于 [cat3399/blbl](https://github.com/cat3399/blbl) **v0.1.29** 的二改版本，Fork 仓库：[github.com/xmbl4399/blbl](https://github.com/xmbl4399/blbl)。

以下**全量**列出相对原版 v0.1.29 的所有改动，并注明原因与必要性；纯风格/使用习惯类改动标注为「作者偏好」。

### 一、核心新增：「新番表」首页 tab（⭐主要二改）

> 原因：作者日常追番需要——按季度浏览新番、查看评分与放送进度，并一键直达 B 站搜索该番剧。

- **新增「新番表」tab**：首页 tab 末尾追加（key=`bangumi_calendar`），数据源为 [bangumi.tv](https://bgm.tv)（bgm.tv），设置页「主页显示页面」可开关
- **独立数据层**：`BangumiApi` 使用独立 OkHttpClient，**不带 B 站 UA/Referer/Origin 拦截头**（与 B 站风控体系隔离），App 启动时初始化
- **季度切换栏**：单排 scrollable TabLayout，**2006 冬 ~ 当前季（80+ 季）**，紧凑标签「26夏 / 26春 / 26冬…」；D-pad 方向键移动焦点不切换、OK/点击才加载
- **列表视图**：当季/历史季度统一为「统计 header + 番剧卡片网格」；header 只显示统计（如「68 部 · 放送 07-04 起」）
- **数据语义**：只拉季度首月（1/4/7/10）+ `cat=1` 仅 TV 番剧，排除剧场版/WEB/OVA——保证列表是真正的当季新番
- **卡片增强**：流派 tag（封面左上角）、评分徽章（封面右上角，≥7 分金色醒目）、已放送分集进度（封面左下角，如 8/12 集，由 `/v0/episodes` 按 airdate≤今天 计算）
- **点击直达搜索**：点卡片 → B 站站内搜索该番剧名 → 返回自动回到新番表并**恢复原卡片焦点**
- **缓存策略**：缓存原始 items JSON（当季 12h / 历史季度 30 天 / 进度每日）；封面走扩展后的 ImageLoader（非 B 站 CDN 独立加载 + 磁盘缓存）
- **修复**：季度合并跨季 bug（次月 +1 而非 +3）；进度缓存按季度键隔离（换季正确）；browse API limit 上限 100 分页拉全；header 跨列布局的 D-pad 焦点正确性（`topHeaderGroups`）

### 二、默认值与行为调整（作者偏好）

| 改动 | 说明 |
|---|---|
| 默认强调色 紫 → B站蓝提亮版（#00ADE8） | 作者偏好 |
| 「播放前打开详情页」默认 开 → 关 | 作者偏好：点卡片直接进入播放，少一步 |
| 启动全屏 默认 开 → 关 | 作者偏好：默认非全屏启动 |
| Gradle JVM 堆 -Xmx2g → -Xmx3g | 构建环境适配：本机 release（R8 混淆 + 资源压缩）内存峰值高，非功能改动 |

### 三、工程调整（必要性：独立发行渠道）

| 改动 | 说明 |
|---|---|
| 检查更新地址切换到本仓库 | CHANGELOG 与 APK 下载 URL 从 cat3399/blbl 改为 xmbl4399/blbl——二改版需要走自己的发行渠道（必要） |

### 四、曾实现后放弃（作者决策）

- **Bangumi token（NSFW/未公开条目）**：早期支持带 token 拉取更多条目，实测价值低，已整体移除（设置项、缓存、请求头全部清理）
- **星期分组视图**：早期按 bgm.tv 星期分组展示（周一~周日 header），数据源不稳定，统一改为单 header 统计列表

## 界面预览

**推荐页**
![推荐页](./example-pic/推荐页.png)

**分类页**
![分类页](./example-pic/分类页.png)

**动态页**
![动态页](./example-pic/动态页.png)

**直播页**
![直播页](./example-pic/直播页.png)

**我的页**
![我的页](./example-pic/我的页.png)

**搜索页**
![搜索页](./example-pic/搜索页.png)

**追番**
![追番](./example-pic/追番.png)

**新番表（⭐二改新增）**
![新番表](./example-pic/新番表.png)

**视频播放页**
![视频播放页](./example-pic/视频播放页.png)

## 功能概览

- 侧边栏导航：搜索 / 推荐 / 分类 / 动态 / 直播 / 我的
- 扫码登录入口
- 视频播放：Media3(ExoPlayer)，支持分辨率/编码/倍速/字幕/弹幕等设置
- 设置页：播放与弹幕偏好等

## 技术栈

- Kotlin + AndroidX + ViewBinding
- Media3(ExoPlayer)/[Ijkplayer](https://github.com/cat3399/ijkplayer)
- OkHttp
- Protobuf-lite
- Material / RecyclerView / ViewPager2

## 构建

环境要求：JDK 17，Android SDK（compileSdk 36）。

调试包：

```
./gradlew assembleDebug
```

发布包（已开启 R8 混淆 + 资源压缩）：

```
./gradlew assembleRelease
```

可选版本参数（本地或 CI）：

```
./gradlew assembleRelease -PversionName=0.1.1 -PversionCode=2
```

## 临时更新方案
**目前在代码中内置了国内环境可直接访问的直链,用于在测试阶段方便的覆盖更新,待后续稳定之后将会移除**,介意者请从release中下载action编译的安装包

## GitHub Actions

仓库包含两套手动触发的工作流：

- Android Debug：手动输入 `version_name`
- Android Release：同上，额外需要签名 Secrets

需要在仓库 Secrets 中配置：

- `RELEASE_KEYSTORE_BASE64`
- `RELEASE_STORE_PASSWORD`
- `RELEASE_KEY_ALIAS`
- `RELEASE_KEY_PASSWORD`

## 感谢

- https://github.com/SocialSisterYi/bilibili-API-collect B站API收集整理
- https://github.com/xiaye13579/BBLL 优秀的页面设计和操作逻辑，本项目绝大部分页面和操作逻辑都是抄袭BBLL🥰
- https://github.com/bggRGjQaUbCoE/PiliPlus 部分关键功能参考了Piliplus的逻辑
- https://github.com/debugly/ijkplayer 感谢debugly大佬移植的ijkplayer
- 开源第三方B站客户端
- 群友们的详细测试与反馈

## 免责声明

> 不得利用本项目进行任何非法活动。 不得干扰B站的正常运营。 不得传播恶意软件或病毒。 此外，为降低法律风险

1. 🚫禁止在官方平台（b站）及官方账号区域（如b站微博评论区）宣传本项目
2. 🚫禁止在微信公众号平台宣传本项目
3. 🚫禁止利用本项目牟利，本项目无任何盈利行为，第三方盈利与本项目无关

代码都是codex写的，如有问题请联系https://openai.com/ 😤
