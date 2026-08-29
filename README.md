# blbl-android

一个第三方哔哩哔哩安卓 App，支持触摸、遥控，以及安卓5，适用于平板、TV、车机等设备。

## 二改说明

本仓库是基于 [cat3399/blbl](https://github.com/cat3399/blbl) **v0.1.29** 的二改版本，Fork 仓库：[github.com/xmbl4399/blbl](https://github.com/xmbl4399/blbl)。

以下**全量**列出相对原版 v0.1.29 的所有改动；纯风格/使用习惯类改动标注为「作者偏好」。

### 一、核心改动：扩充「新番表」为 7 个分类 tab（⭐主要二改）

> 原因：原版只有推荐/热门/番剧/影视等通用 B 站首页 tab，没有按年流式浏览番剧的入口。日常追番需要——按年浏览、查看评分与流派、一键直达 B 站搜索该番剧。

- **主页 tab 结构**：推荐 / 热门 / **TV动画** / **非TV动画** / **电影** / **日剧** / **韩剧** / **欧美剧** / **华语剧**（顶部 scrollable；番剧/影视默认隐藏可勾回）
- **数据源**：[bangumi.tv](https://bgm.tv)（bgm.tv），独立 OkHttpClient（与 B 站风控隔离），App 启动时初始化
- **年份流式加载**：年份栏 2026→2006，从当前月往前逐月追加（不再用季度栏）
- **TV动画 = cat=1**（纯 TV 番剧）；**非TV动画 = cat=5(WEB) ∪ cat=2(OVA) ∪ cat=3(剧场版)** 合并去重
- **三次元 tab**：日剧 / 电影（cat=6）；欧美剧（cat=6, meta_tag=美国/英国…）；华语剧（cat=6, meta_tag=中国/香港/台湾…）；韩剧（cat=6001 电视剧 ∩ meta_tags「韩国」）
- **排序**：全页统一**评分降序**（score 降序，无评分按日期垫底）—— bgm `sort=rank` 服务端截断（实测 2026-08 cat=3：无 sort=11 条，带 sort=rank 只回 3 条），弃用
- **零二次请求**：不拉已播集数（早期 `/v0/episodes` 已全删），卡片只显示总话数
- **卡片**：流派 tag（动画向 47 词白名单，三次元不显示）/ 评分徽章（≥7 金色）/ 总话数
- **封面精度**：按设置选 bangumi images 变体（small=200px / medium=common400 / large=800px），缓存存原始 JSON
- **点击直达搜索**：点卡片 → B 站站内搜索该番剧名（优先 name_cn 否则 name），返回后恢复原卡片焦点
- **长按复制源标题**：复制完整原始 name（不受标题截断影响）
- **缓存**：当年 12h / 历史年份 30 天（节省 12 倍请求）；OkHttp 超时 60s
- **下拉刷新**：强制重新拉取当月；后续月份静默追加
- **空态提示**：「暂无数据，下拉刷新重试」

### 二、隐藏无评分影视开关（⭐体验优化）

- 设置-页面设置-我的页显示页面下方，**默认开启**
- 开启后**全局生效**（含动画页），自动清空全部页面缓存
- 关闭则保留无评分条目（仍按日期垫底）

### 三、Android 15 UI 适配

- targetSdk=36 强制 edge-to-edge，**非全屏时状态栏/导航栏不再覆盖内容**（BaseActivity 手动 insets padding）

### 四、默认值与行为调整（作者偏好）

| 改动 | 说明 |
|---|---|
| 默认强调色 紫 → B站蓝提亮版（#00ADE8） | 作者偏好 |
| 「播放前打开详情页」默认 开 → 关 | 作者偏好：点卡片直接进入播放，少一步 |
| 启动全屏 默认 开 → 关 | 作者偏好：默认非全屏启动 |
| 卡片标题截断（name_cn 空 + name 含 " - "） | 显示层只展示主标题（复制/搜索仍用完整源标题） |
| Gradle JVM 堆 -Xmx2g → -Xmx3g | 构建环境适配：本机 release（R8 + 资源压缩）内存峰值高 |

### 五、工程调整（独立发行渠道）

| 改动 | 说明 |
|---|---|
| 检查更新地址切到本仓库 | CHANGELOG + APK 下载 URL → xmbl4399/blbl——二改版需要走自己的发行渠道（必要） |
| 下载镜像 fallback | 直连失败自动降级 ghproxy.net / gh-proxy.com（国内网络兼容） |
| 版本号对齐源项目 | 源项目 v0.1.29，二改发 v0.1.29.1（versionCode=1291），迭代号后缀避免版本错乱 |

### 六、曾实现后放弃（作者决策）

- **已播集数进度**：早期 `/v0/episodes` 按 airdate≤今天 计算「8/12 集」——全删（零二次请求，只显示总话数）
- **季度动画/季度栏**：早期 80+ 季度切换栏（26夏/26春/…/06冬），改用**年份流式**更直观、季度栏占空间且切换慢
- **三次元 tag 白名单**：日剧/电影早期共用动画 47 词白名单常为空，回归**仅动画显示 tag**、三次元不显示
- **Bangumi token（NSFW/未公开条目）**：早期支持带 token 拉取更多条目，实测价值低，已整体移除（设置项、缓存、请求头全部清理）

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

**TV动画（⭐二改，含主页 9 tab 全貌）**
![TV动画](./example-pic/TV动画.png)

**日剧（⭐二改）**
![日剧](./example-pic/日剧.png)

**欧美剧（⭐二改）**
![欧美剧](./example-pic/欧美剧.png)

**视频播放页**
![视频播放页](./example-pic/视频播放页.png)

## 功能概览

- 侧边栏导航：搜索 / 推荐 / 分类 / 动态 / 直播 / 我的
- **首页 9 tab**：推荐 / 热门 / TV动画 / 非TV动画 / 电影 / 日剧 / 韩剧 / 欧美剧 / 华语剧（番剧/影视默认隐藏可勾回）
- 扫码登录入口
- 视频播放：Media3(ExoPlayer)，支持分辨率/编码/倍速/字幕/弹幕等设置
- 设置页：播放与弹幕偏好、主页显示页面、**隐藏无评分影视**等

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
