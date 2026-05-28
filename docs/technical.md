# 技术文档：聚合热闻 Android

## 1. 当前实现

- 平台：Android 原生。
- 语言：Kotlin。
- 最低版本：Android 8.0，`minSdk 26`。
- 构建：Gradle Wrapper + Android Gradle Plugin 8.13.2。
- UI：原生 View 程序化布局，无 XML 页面依赖。
- 并发：Kotlin Coroutines。
- 本地存储：SharedPreferences。
- 网络：`HttpURLConnection`。
- XML 解析：Android `XmlPullParser`。
- JSON 解析：Android `org.json`。
- 播报：Android `TextToSpeech` + 可选远程 Speech API。

## 2. 主要文件

- `app/src/main/java/com/juhe/hotnews/MainActivity.kt`
  - UI 壳层、新闻列表、详情、来源管理、设置页。
  - `AppStore`：本地配置持久化。
  - `NewsRepository`：新闻源抓取与解析。
  - `AiClient`：锐评与远程语音接口。
- `app/src/main/AndroidManifest.xml`
  - 网络权限、应用入口、明文流量开关。
- `app/build.gradle.kts`
  - Android 与 Kotlin 构建配置。

`AppStore` 当前保存的数据：

- `sources`：新闻源配置。
- `news_cache`：最近 240 条新闻缓存。
- `source_diagnostics`：最近一次来源刷新/测试诊断结果。
- `keyword_filter`：最近一次关键词筛选。
- `feed_mode`：新闻列表视图模式，支持 `all`、`favorite`、`unread`。
- `scope_filter`：最近一次范围标签筛选，默认 `all`。
- `ai_*`：新闻锐评模型配置。
- `voice_*`：语音播报配置。

来源导出格式：

```json
{
  "version": 1,
  "app": "juhe-hotnews",
  "sources": [
    {
      "id": "source-id",
      "name": "来源名称",
      "url": "https://example.com/rss.xml",
      "type": "rss",
      "scope": "综合",
      "enabled": true
    }
  ]
}
```

## 3. 数据模型

`NewsSource`

- `id`：唯一标识。
- `name`：来源名称。
- `url`：抓取入口。
- `type`：来源解析器类型，支持 `rss`、`cctv_jsonp`、`baidu_hot`、`toutiao_hot`、`kr36_flash`、`thepaper_hot`、`v2ex_hot`、`sspai_home`。
- `scope`：范围标签。
- `enabled`：是否启用。

`NewsItem`

- `id`：基于 URL 或标题生成的 SHA-1。
- `title`：标题。
- `summary`：摘要。
- `url`：原文链接。
- `source`：来源名称。
- `scope`：范围标签。
- `publishedAt`：发布时间。
- `favorite`：本地收藏状态。
- `read`：本地已读状态。
- `script`：用于展示、锐评和播报的新闻稿文本。

`SourceDiagnostic`

- `sourceId`：来源 ID。
- `sourceName`：来源名称。
- `checkedAt`：检测时间。
- `success`：本次是否成功。
- `itemCount`：本次抓取条数。
- `message`：成功说明或失败原因。

`FetchResult`

- `items`：所有成功来源返回的新闻集合。
- `diagnostics`：每个来源对应的诊断记录。

## 4. 新闻抓取

当前支持九类解析器：

- `momoyu_hot`：解析摸摸鱼聚合热榜 JSON，读取返回的多个平台分区，将分区名称作为 `NewsItem.source`，用于首页平台分类。
- `cctv_jsonp`：解析央视网 JSONP，读取 `data.list` 中的 `title`、`brief`、`url`、`focus_date`。
- `rss`：解析 RSS/Atom 中的 `item` 或 `entry`，读取 `title`、`link`、`description/summary`、`pubDate/published/updated`。
- `baidu_hot`：解析百度热搜页面注入的 `s-data` JSON，兼容桌面端 `hotList` 与移动端 `tabTextList` 结构，读取 `word/query`、`desc`、`url/rawUrl`、`hotScore`。
- `toutiao_hot`：解析今日头条热榜 JSON，读取 `data[]` 中的 `Title`、`Url`、`HotValue`、`Label`。
- `kr36_flash`：解析 36氪快讯页面中的 `window.initialState`，兼容 `newsflashCatalogData` 与移动端 `newsflashList.flow` 结构，读取 `widgetTitle`、`widgetContent`、`sourceUrlRoute`、`publishTime`。
- `thepaper_hot`：解析澎湃新闻公开右侧栏 JSON，读取 `data.hotNews[]` 中的 `name`、`contId`、`nodeInfo.name`、`interactionNum`、`praiseTimes`、`pubTimeNew`。
- `v2ex_hot`：解析 V2EX 热门页 HTML，读取热门讨论标题、节点、回复数和话题链接。
- `sspai_home`：解析少数派首页 HTML，读取派早报、首页文章和“派友在看”标题。

默认来源模板版本：

- `source_template_version = 5`。
- 新安装会直接写入摸摸鱼聚合热榜默认来源。
- 旧安装首次启动时会移除历史默认源并写入最新默认来源；用户自定义来源保留。

刷新机制：

- 应用启动后立即刷新。
- 前台每 10 分钟刷新一次。
- 用户点击“刷新”手动刷新。
- 同一批结果按 `id` 去重，最多保留 240 条，避免范围筛选被单一来源占满。
- 刷新成功后写入 `news_cache`。
- 刷新失败且存在缓存时，展示缓存并在状态栏提示失败原因。
- 刷新成功后会将缓存中的收藏/已读状态合并回新抓取结果。
- 刷新使用 `NewsRepository.fetchWithDiagnostics()`，逐来源捕获成功/失败结果。
- 部分来源失败不会阻断其他来源展示；若全部来源未抓到新闻，则回退缓存并提示用户查看范围页诊断。
- 单源测试同样写入 `source_diagnostics`，方便在来源卡片上查看最近一次测试结果。

## 4.1 阅读增强

- 关键词筛选：在端侧对 `title`、`summary`、`source`、`scope` 做包含匹配。
- 全部/收藏/未读：在端侧基于 `favorite` 与 `read` 做列表过滤。
- 平台筛选：`renderPlatformFilter()` 从当前新闻的 `source` 字段收集平台名称，写入 `platform_filter` 后由 `visibleItems()` 统一过滤。
- 范围筛选：`renderScopeFilter()` 从当前新闻和来源配置中收集范围标签，写入 `scope_filter` 后由 `visibleItems()` 统一过滤。
- 可见批量操作：`renderVisibleBatchActions()` 基于 `visibleItems()` 展示当前可见统计，并调用 `updateVisibleReadState()` 批量写回 `read` 状态。
- 收藏/已读状态：随 `news_cache` 一起保存，刷新时按新闻 `id` 合并。
- 复制：使用 Android `ClipboardManager`。
- 分享：使用 `Intent.ACTION_SEND` 和系统分享面板。
- 打开原文：使用 `Intent.ACTION_VIEW` 打开 `NewsItem.url`。
- 标题清单：`visibleHeadlinesText()` 将当前可见结果格式化为可复制文本，最多列出前 80 条并提示剩余数量。

## 4.2 来源配置校验

保存自定义来源时执行：

- URL 非空。
- URL 以 `http://` 或 `https://` 开头。
- 类型必须为 `momoyu_hot`、`rss`、`cctv_jsonp`、`baidu_hot`、`toutiao_hot`、`kr36_flash`、`thepaper_hot`、`v2ex_hot`、`sspai_home` 之一。
- URL 不与已有来源重复。
- 单源测试：调用 `NewsRepository.fetch(listOf(source))`，在状态栏展示抓取数量或错误。
- 恢复默认：调用 `AppStore.resetDefaultSources()` 覆盖来源配置为内置默认值。
- 导出配置：调用 `AppStore.exportSourcesJson()` 生成带版本号的 JSON，并写入剪贴板。
- 导入配置：支持完整对象 `{ "sources": [...] }` 或纯数组 `[...]`；导入时复用来源校验，合并模式下按 `id` 或 `url` 覆盖旧配置，替换模式下直接保存导入列表。
- 来源诊断：范围页读取 `AppStore.sourceDiagnostics()`，按来源 ID 展示最近一次成功/失败、条数、时间和消息。

## 5. AI 锐评接口

默认使用 Xiaomi MiMo Token Plan 的 OpenAI-compatible Chat Completions。端侧默认 Endpoint 为 `https://token-plan-sgp.xiaomimimo.com`，默认模型为 `mimo-v2.5-pro`；API Key 由用户在设置页或测试设备私有配置中填入，不写入代码和文档。

请求格式：

```json
{
  "model": "mimo-v2.5-pro",
  "messages": [
    { "role": "system", "content": "用户配置的提示词" },
    { "role": "user", "content": "新闻稿文本" }
  ],
  "temperature": 0.7
}
```

返回读取：

```text
choices[0].message.content
```

Endpoint 支持两种填法：

- Token Plan Base URL，例如 `https://token-plan-sgp.xiaomimimo.com` 或 `https://token-plan-sgp.xiaomimimo.com/v1`；应用会自动补齐为 `/v1/chat/completions`。
- 完整 Chat Completions 地址，例如 `https://api.openai.com/v1/chat/completions`。
- 其他 OpenAI-compatible Base URL。

鉴权规则：

- 普通 OpenAI-compatible 服务默认使用 `Authorization: Bearer <API Key>`。
- Xiaomi MiMo Token Plan 域名或 `tp-` 开头的 API Key 使用 `api-key: <API Key>` 头。

设置页自检：

- `testAiSettings()` 读取当前表单配置，不要求先保存。
- 自检前校验 Endpoint、Model、API Key 和 Prompt。
- 自检调用 `AiClient.critique()` 发送短测试新闻稿，并在状态栏展示成功摘要或错误原因。

## 6. TTS 接口

本地模式：

- 使用 Android `TextToSpeech.speak`。
- 默认语言 `Locale.CHINA`。
- 通过 `UtteranceProgressListener` 串联新闻队列，实现连续播报。
- 连续播报使用当前筛选后的前 20 条新闻，逐条播报并写回已读状态。
- 停止播报会清空队列、停止本地 TTS 并释放远程音频播放器。
- 设置页本地 TTS 自检会调用 `TextToSpeech.isLanguageAvailable(Locale.CHINA)`，可用时播放一句测试语音。

默认语音模式为远程 MiMo Chat Audio；远程模型不可用、API Key 缺失或网络失败时自动回退 Android 本地 TTS。远程模式支持两类协议。

OpenAI-compatible Speech API：

```json
{
  "model": "tts-1",
  "input": "新闻稿文本",
  "voice": "alloy",
  "response_format": "mp3"
}
```

返回音频写入 `cacheDir`，随后用 `MediaPlayer` 播放。

Xiaomi MiMo TTS：

- Endpoint 可填 Token Plan Base URL，例如 `https://token-plan-sgp.xiaomimimo.com`。
- Model 可填 `mimo-v2.5-tts`。
- Voice 可填 `mimo_default`、`冰糖`、`茉莉`、`苏打`、`白桦`、`Mia`、`Chloe`、`Milo`、`Dean` 等内置音色。
- 请求走 `/v1/chat/completions`，目标朗读文本放在 `assistant` 消息中，`audio.format` 使用 `wav`。
- 响应读取 `choices[0].message.audio.data`，base64 解码后写入 `cacheDir` 并播放。

远程语音自检：

- `testVoiceSettings()` 读取当前表单配置，不要求先保存。
- 远程模式自检前校验 Endpoint、Model 和 Voice；API Key 缺失或远程请求失败时自动回退 Android 本地 TTS。
- 自检调用 `AiClient.speech()` 生成短测试音频，并复用 `playAudio()` 播放。
- 单条新闻和远程语音自检都遵循同一回退策略，避免远程 TTS 不可用时阻断播报。

## 7. 安全与生产化建议

当前 MVP 以快速验证为目标，生产化建议：

- 用 EncryptedSharedPreferences 或 Android Keystore 保存 API Key。
- 将抓取迁移到服务端，统一限频、重试、缓存、去重、审计。
- 引入 Room 保存新闻、来源、评论和语音缓存。
- 引入 WorkManager 做后台刷新，配合通知权限实现推送。
- 引入 Retrofit/OkHttp 提升网络层可测试性。
- 引入 Jetpack Compose 或 XML Design System 提升 UI 可维护性。
- 为新闻解析器添加单元测试和样本回归测试。
- 加入内容合规策略，限制模型输出未经验证的推断。

## 8. 构建验证

已验证命令：

```bash
./gradlew :app:assembleDebug
```

当前构建结果：通过。
