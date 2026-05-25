# 聚合热闻 Android App

一款面向中文用户的热门新闻实时汇总 Android 原型。当前版本支持：

- 从国内知名媒体公开源抓取新闻稿并以文字形式展示。
- 默认范围：央视网综合、央视网国内、央视网国际、中国日报 China RSS。
- 用户自定义抓取范围 CRUD，支持 `cctv_jsonp` 与 `rss` 两类源。
- 抓取范围支持 JSON 导入/导出，可备份、迁移或批量配置媒体源。
- 抓取范围页展示最近一次来源诊断，包括成功/失败、抓取条数、检测时间和失败原因。
- 热点页按标题关键词聚合高频话题，展示热度、来源数和代表标题。
- 新闻、热点、简报和日报支持按范围标签筛选，如综合、国内、国际等。
- 新闻页支持对当前可见结果批量标已读/未读，并复制当前可见标题清单。
- 简报页可生成文字简报，并支持复制、分享和播报。
- 日报稿可自动整合概览、热点、重点新闻和稍后关注，支持复制、分享和播报。
- 日报稿支持本地归档，历史日报可复制、分享、播报或删除。
- 默认接入 Xiaomi MiMo Token Plan 兼容接口生成新闻锐评，也支持用户替换为其他 OpenAI-compatible Chat Completions 服务。
- 默认使用 MiMo Chat Audio 远程语音播报；远程不可用或未配置 API Key 时自动回退 Android 本地 TTS。
- 设置页支持锐评接口和语音接口自检，便于确认 Endpoint、Model、API Key 和本地 TTS 是否可用。
- 前台每 10 分钟自动刷新一次，用户也可以手动刷新。

## 运行

```bash
./gradlew :app:assembleDebug
```

APK 输出：

```text
app/build/outputs/apk/debug/app-debug.apk
```

安装到已连接设备：

```bash
~/Library/Android/sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 文档

- [需求文档](docs/requirements.md)
- [业务文档](docs/business.md)
- [技术文档](docs/technical.md)

## 注意

默认源使用公开可访问数据，仅适合作为原型和内部验证。正式商用前建议接入媒体授权 API、自建采集服务或合规 RSS 服务，并补充内容版权、缓存、风控与审计能力。
