# Packet Capture

**简体中文** | [English](README.en.md)

**首个版本：v1.0.0** · [版本记录](CHANGELOG.md)

基于 Kotlin 与 Jetpack Compose 开发的 Android 本地网络抓包工具，用于应用接口调试、HTTP/HTTPS 请求分析和网络问题排查。

通过 Android `VpnService` 采集所选应用的流量，无需 Root。流量转发、HTTPS 解密、数据存储和查看均在手机内完成，运行时无需电脑或外部抓包服务。HTTPS 解密适用于能够配置证书信任的自有测试应用。

**Android 8.0+ · 按应用抓包 · HTTP/1.1 / HTTP/2 · HAR / cURL 导出 · 本机 SOCKS5 代理**

## 主要功能

| 功能 | 说明 |
| --- | --- |
| 按应用抓包 | 按应用名称或包名搜索，选择一个或多个目标应用，查看实时上传、下载流量和请求数量。 |
| HTTP / HTTPS 分析 | 支持 HTTP/1.1 与 HTTP/2，查看 URL、请求方法、状态码、请求头、响应头、尾部字段、正文和耗时。 |
| 连接详情 | 展示连接地址、端口、传输协议、应用归属、TLS 版本、解密状态及失败原因；HTTP/2 请求保留流编号。 |
| 正文解析 | 支持 JSON 格式化与转义解码、URL 编码表单、multipart 字段，以及 gzip / deflate 解压；可切换解析视图与原文。 |
| Base64 与图片 | 二进制正文以 Base64 展示，可尝试识别图片并预览；支持将标准或 URL-safe Base64 解码为 UTF-8 文本。 |
| 搜索与筛选 | 按域名、路径等关键词搜索，并按请求方法、HTTP 状态码、应用包名或错误请求筛选。 |
| 历史会话 | 本地保存抓包会话，可搜索、查看、删除及导出；请求详情按需加载正文。 |
| 数据导出 | 支持会话 HAR 1.2、单条请求 cURL ZIP，以及当前正文视图的 UTF-8 文本导出。 |
| 代理转发 | 可将抓包后的 TCP、UDP 和 DNS 流量转交给本机 SOCKS5 代理。 |
| 界面与后台运行 | 支持浅色、深色和跟随系统主题；抓包通过前台服务运行，离开界面后可继续采集，并可从通知栏停止。 |

## 快速开始

1. 按照下方[从源码构建](#从源码构建)生成并安装 APK。
2. 打开应用，在“抓包”页选择目标应用。
3. 如需查看 HTTPS 明文，在“设置”中导出本设备 CA 证书，让目标测试应用信任该证书，并开启“HTTPS 解密”。
4. 点击“开始抓包”，接受 Android VPN 授权，再切换到目标应用发起网络请求。
5. 返回抓包列表查看请求，在详情页切换“概览”“请求”“响应”和“连接”；完成后点击“停止”或使用通知栏停止按钮。

目标应用、HTTPS 解密、透传域名和上游代理等抓包设置，在下一次开始抓包时生效。应用界面目前以中文为主。

### HTTPS 证书配置

每个安装实例生成独立 CA，仅导出公开证书。CA 私钥保存在应用私有目录，并由 Android Keystore 密钥加密保护。

<details>
<summary>在自有 Android 测试应用的 debug 构建中信任抓包 CA</summary>

将导出的证书复制到目标测试项目的 `app/src/debug/res/raw/capture_ca.cer`。

在 `app/src/debug/res/xml/debug_network_security.xml` 中添加：

```xml
<network-security-config>
    <debug-overrides>
        <trust-anchors>
            <certificates src="@raw/capture_ca" />
        </trust-anchors>
    </debug-overrides>
</network-security-config>
```

在该测试项目的 `app/src/debug/AndroidManifest.xml` 中引用：

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application android:networkSecurityConfig="@xml/debug_network_security" />
</manifest>
```

如果项目已有网络安全配置，请将 `debug-overrides` 合并到现有调试配置中。使用自定义 TrustManager 或证书固定的应用，需要在自身调试构建中另行配置证书信任。

</details>

应用不会自动更改其他应用的证书信任配置。清除抓包应用数据或重新安装导致 CA 重建后，需要重新导出并配置。无需解密的域名可加入“透传域名”，支持精确域名和 `*.example.com`；通配规则不包含根域 `example.com`。

### 通过本机 SOCKS5 代理转发

1. 启动手机上的 SOCKS5 代理服务。例如使用 v2rayNG 时，选择“仅代理”模式。
2. 在“设置 → 上游代理”开启“通过代理转发”，填写并保存实际监听端口。地址固定为 `127.0.0.1`，默认端口为 `10808`，支持无认证 SOCKS5。
3. 目标应用列表中排除抓包应用自身和代理应用，然后重新开始抓包。

流量路径为：目标应用 → 本应用 VPN / HTTP(S) 记录 → 本机 SOCKS5 代理 → 目标服务器。

代理需支持 `UDP ASSOCIATE`。启动时会检查代理可用性；转发失败不会自动回退直连。代理模式下 TCP、UDP（含 DNS）均通过代理，VPN DNS 使用 `1.1.1.1` 和 `8.8.8.8`。HTTP/3 / QUIC 仅转发并记录连接信息，不解密；ICMP 等 SOCKS5 不支持的 IP 协议不转发。

## 查看与导出

- **正文阅读**：可滚动查看全部已保存内容。JSON 阅读视图会解码转义字符，需要原始 JSON 时切换“原文”；复制和正文导出跟随当前文本视图。较大正文使用文本导出。
- **图片预览**：根据实际字节尝试识别系统支持的图片格式，无需依赖 `Content-Type`；多帧图片仅显示首帧，预览最长边不超过 2048 像素。图片模式下复制和导出的仍是 Base64 文本。
- **HAR 1.2**：导出会话中全部已记录的 HTTP 请求，保留重复头、尾部字段、连接 / 流编号及正文完整性信息，不受列表显示条数限制。
- **cURL ZIP**：包含 POSIX shell 命令 `request.sh` 及独立的请求体文件；解压后在同一目录执行。进行中或请求正文不完整的记录无法导出为可重放命令。
- **凭据处理**：HAR 和 cURL 默认隐藏 `Authorization`、`Proxy-Authorization`、`Cookie`、`Set-Cookie` 的值。URL、查询参数及正文保留原始业务数据，分享前需自行检查。

## 支持范围与限制

- HTTPS 解密要求目标应用信任抓包 CA；不提供证书固定绕过，也不支持上游双向 TLS 客户端证书认证。解密时仍校验上游服务器证书。
- 未启用解密、命中透传规则、没有 SNI 或使用 ECH 的 TLS 连接，仅透传并保留连接信息；其他非 HTTP 流量也不提供 HTTP 正文解析。
- 不支持 HTTP/3 解密、应用内请求重放或修改请求 / 响应。
- Android 同一用户下通常只能运行一个普通 VPN。与代理应用配合时，应让代理应用使用“仅代理”模式。
- Android 8 / 9 上无法可靠获取连接的应用归属，记录显示“未知”。
- 默认每份请求 / 响应正文最多保存 **5 MiB**，正文存储总额度为 **500 MiB**；解压后读取上限为 **5 MiB**。达到保存额度或记录队列上限时，网络继续转发，并提示数据未完整保存。
- 请求列表显示最近 **200** 条匹配记录，历史页最多显示最近 **100** 个匹配会话。

## 从源码构建

需要 **JDK 21**、**Android SDK 36**、**NDK 28.2.13676358** 和 **CMake 3.22.1**。项目使用 Gradle Wrapper **8.13**、Android Gradle Plugin **8.13.0** 和 Kotlin **2.0.21**。

1. 使用 Android Studio 打开项目，安装上述 SDK / NDK / CMake 组件，将 Gradle JDK 或 `JAVA_HOME` 设置为 JDK 21，并在本地 `local.properties` 配置 `sdk.dir`。
2. 在项目根目录执行构建。默认模块为 `:app`、`:core`、`:capture` 和 `:data`；若本地存在 `test-client/build.gradle`，会自动纳入该测试模块。

Windows（PowerShell）：

```powershell
.\gradlew.bat :app:assembleDebug
```

macOS / Linux：

```bash
bash ./gradlew :app:assembleDebug
```

APK 输出位置：`app/build/outputs/apk/debug/app-debug.apk`。最低支持 Android 8.0 / API 26，包含 `arm64-v8a`、`armeabi-v7a`、`x86_64` 和 `x86` ABI。发布 Release 安装包前需配置自己的签名。

## 项目结构

| 模块 | 职责 |
| --- | --- |
| `app/` | Jetpack Compose 界面、状态管理、正文查看与系统交互。 |
| `core/` | 抓包状态、领域模型、生命周期及模块间接口。 |
| `capture/` | VPN 前台服务、基于 zdtun 的原生转发、Netty HTTP/HTTP2 处理、TLS 与 SOCKS5 代理。 |
| `data/` | Room 数据库、DataStore 设置、正文文件存储、解析及 HAR / cURL 导出。 |

## 第三方组件

项目使用 zdtun、Netty、Conscrypt、Bouncy Castle、AndroidX 等组件，来源、版本与许可说明见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
