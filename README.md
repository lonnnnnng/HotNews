# 聚合热闻 Android App

一款面向中文用户的热门新闻实时汇总 Android App。当前版本支持：

- 从国内热门站点和公开源抓取新闻稿，并以文字形式展示。
- 默认范围：摸摸鱼聚合热榜、百度热搜、今日头条热榜、36氪快讯、IT之家、澎湃热点、V2EX 热门、少数派首页。
- 用户自定义抓取范围 CRUD，支持 `cctv_jsonp` 与 `rss` 两类源。
- 内置扩展源类型：`momoyu_hot`、`toutiao_hot`、`baidu_hot`、`kr36_flash`、`thepaper_hot`、`v2ex_hot`、`sspai_home`。
- 抓取范围支持 JSON 导入/导出，可备份、迁移或批量配置媒体源。
- 设置页提供抓取范围入口，范围页展示最近一次来源诊断，包括成功/失败、抓取条数、检测时间和失败原因。
- 新闻页支持下拉刷新、关键词搜索、范围标签筛选、当前展开阅读和已读/未读标记。
- 简报和日报基于当前新闻列表、筛选词和抓取范围自动生成，并支持复制、分享和播报。
- 新闻页支持对当前可见结果批量标已读/未读，并复制当前可见标题清单。
- 日报稿支持本地归档，历史日报可复制、分享、播报或删除。
- 默认接入 Xiaomi MiMo Token Plan 兼容接口生成新闻锐评，也支持用户替换为其他 OpenAI-compatible Chat Completions 服务。
- 默认使用 MiMo Chat Audio 远程语音播报；远程不可用或未配置 API Key 时自动回退 Android 本地 TTS。
- 设置页支持锐评接口和语音接口自检，便于确认 Endpoint、Model、API Key 和本地 TTS 是否可用。
- 设置页支持检测 GitHub Releases 新版本，发现新版后可查看更新说明、下载 APK、显示下载进度并调起系统安装器。
- 前台每 10 分钟自动刷新一次，用户也可以手动刷新或下拉刷新。

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

指定版本号打包：

```bash
./gradlew :app:assembleDebug --stacktrace -PVERSION_NAME=0.1.5 -PVERSION_CODE=6
```

## GitHub Actions 打包

仓库内置流水线：[Android APK](.github/workflows/android-apk.yml)。

- 推送 `main` 或发起 PR 时构建 debug APK，并上传为 workflow artifact。
- 推送 `v*` tag 时构建 debug APK；如果仓库 Secrets 配置了发布签名，则额外构建签名 release APK。
- tag 版本会写入 `VERSION_NAME`，例如 `v0.2.0` 会生成 `versionName=0.2.0`。
- `VERSION_CODE` 默认使用 UTC 小时时间戳，保证流水线构建产物可升级。
- tag 构建完成后会创建或更新 GitHub Release。存在发布签名时上传 `HotNews-vX.Y.Z.apk`；未配置签名时上传 `HotNews-vX.Y.Z-debug.apk`，用于当前 public 仓库的测试发布和应用内更新验证。

发布正式可升级 APK 前，建议在 GitHub 仓库 Secrets 配置同一套长期稳定签名证书：

```text
HOTNEWS_KEYSTORE_BASE64
HOTNEWS_STORE_PASSWORD
HOTNEWS_KEY_ALIAS
HOTNEWS_KEY_PASSWORD
```

注意：Android 覆盖安装要求新旧 APK 使用同一个签名证书。debug APK 适合测试发布；面向真实用户发布时应使用稳定 release 签名，不要更换正式签名证书，否则已安装用户无法直接升级。

## 应用内更新

设置页的「检测更新」会请求：

```text
https://api.github.com/repos/lonnnnnng/HotNews/releases/latest
```

更新逻辑：

- 读取 latest Release 的 `tag_name` 作为新版本号。
- 与当前 `BuildConfig.VERSION_NAME` 做语义化版本比较。
- 如果 latest Release 中存在 `.apk` 资产且版本更高，展示当前版本、新版本和 Release 更新说明。
- 点击下载后显示进度条，下载完成后通过 `FileProvider` 调起 Android 系统安装器。
- 如果没有新版，提示已是最新版本。

本地验证在线升级时，应使用同一签名链路。例如当前模拟器安装的是 debug 版 `0.1.4`，则测试 Release 中的新版 APK 也应使用本机 debug 签名：

```bash
./gradlew :app:assembleDebug --stacktrace -PVERSION_NAME=0.1.5 -PVERSION_CODE=6
gh release create v0.1.5 app/build/outputs/apk/debug/app-debug.apk#HotNews-v0.1.5-debug.apk \
  --title "HotNews v0.1.5" \
  --notes "测试在线更新流程：设置页检测更新、下载进度、下载完成后调起安装器。"
```

## 文档

- [需求文档](docs/requirements.md)
- [业务文档](docs/business.md)
- [技术文档](docs/technical.md)

## 注意

默认源使用公开可访问数据，仅适合作为原型和内部验证。正式商用前建议接入媒体授权 API、自建采集服务或合规 RSS 服务，并补充内容版权、缓存、风控与审计能力。
