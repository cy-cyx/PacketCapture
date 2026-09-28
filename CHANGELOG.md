# Changelog

## v1.0.1 — 2026-09-28

### 中文

- 更新应用图标，提供青蓝色数据包设计及圆形、自适应和系统主题单色版本。
- 设置页新增清空抓包存储，确认后删除全部会话、连接、请求及正文，并释放数据库空间；抓包期间禁止清空。
- 导出文件名包含应用名称和导出时间，HAR、cURL 及请求 / 响应文本默认保留原始凭据和头字段。
- 请求详情使用独立页面，返回首页时保留列表位置与筛选状态，并移除首页重复的设置入口。
- 请求 / 响应 ZIP 包含请求文本、响应文本和可独立执行的完整 cURL 命令，支持内嵌文本及二进制请求体。

### English

- Replace the app icon with a teal packet-capture design, including round, adaptive, and monochrome themed icons.
- Add confirmed storage cleanup for all sessions, connections, exchanges, and bodies, reclaiming database space; block cleanup while capturing.
- Include app names and export timestamps in filenames, and preserve original credentials and headers in HAR, cURL, and request / response text by default.
- Open request details in a separate screen while preserving the home list position and filters, and remove the duplicate settings shortcut.
- Export request / response ZIP bundles with request text, response text, and a self-contained cURL command supporting embedded text and binary bodies.

## v1.0.0 — 2026-09-24

首个版本 / Initial version.

### 中文

- 通过 Android VPN 按应用抓包，无需 Root，支持 HTTP/1.1 与 HTTP/2 请求分析。
- 支持配置 CA 信任后的 HTTPS 解密、域名透传和本机 SOCKS5 代理转发。
- 提供 JSON、表单、压缩正文、Base64 解析与图片预览。
- 支持历史会话、请求筛选、HAR 1.2、cURL ZIP 和正文文本导出。
- 提供中英文 README、源码构建说明及第三方组件声明。

### English

- Capture selected apps through Android VPN without root, with HTTP/1.1 and HTTP/2 inspection.
- Decrypt HTTPS when the target app trusts the capture CA; support domain passthrough and local SOCKS5 forwarding.
- Inspect JSON, forms, compressed bodies, Base64, and image previews.
- Browse session history, filter requests, and export HAR 1.2, cURL ZIP bundles, or body text.
- Include Chinese and English READMEs, source build instructions, and third-party notices.
