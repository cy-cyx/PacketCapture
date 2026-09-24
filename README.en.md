# Packet Capture

[简体中文](README.md) | **English**

**Initial version: v1.0.0** · [Changelog](CHANGELOG.md)

A local network capture tool for Android, built with Kotlin and Jetpack Compose for API debugging, HTTP/HTTPS inspection, and network troubleshooting.

Packet Capture uses Android `VpnService` to capture traffic from selected apps without root access. Traffic forwarding, HTTPS decryption, storage, and inspection run on the phone, with no computer or external capture service required at runtime. HTTPS decryption is intended for test apps whose certificate trust you can configure.

**Android 8.0+ · Capture by app · HTTP/1.1 / HTTP/2 · HAR / cURL export · Local SOCKS5 proxy**

## Features

| Feature | Description |
| --- | --- |
| Capture by app | Search by app name or package name, select one or more apps, and monitor upload/download totals and request counts. |
| HTTP / HTTPS inspection | Inspect HTTP/1.1 and HTTP/2 URLs, methods, status codes, request and response headers, trailers, bodies, and duration. |
| Connection details | View addresses, ports, transport protocol, app attribution, TLS version, decryption status, and failure reasons; HTTP/2 requests retain stream IDs. |
| Body parsing | Read formatted JSON with decoded escapes, URL-encoded forms, multipart fields, and gzip / deflate content; switch between parsed and raw text views. |
| Base64 and images | View binary bodies as Base64, attempt image detection and preview, or decode standard and URL-safe Base64 into UTF-8 text. |
| Search and filters | Search domains, paths, and other URL text; filter by method, HTTP status code, package name, or errors. |
| Session history | Store sessions locally, then search, inspect, delete, or export them. Bodies load when opening request details. |
| Exports | Export sessions as HAR 1.2, individual requests as cURL ZIP bundles, or the current body view as UTF-8 text. |
| Proxy forwarding | Forward captured TCP, UDP, and DNS traffic through a local SOCKS5 proxy. |
| Appearance and background capture | Choose light, dark, or system theme. A foreground service keeps capture running after leaving the screen, with a stop action in the notification. |

## Quick start

1. Generate and install an APK using the [build instructions](#build-from-source) below.
2. Open the app and select target apps on the Capture page (`抓包`).
3. To inspect HTTPS plaintext, export this installation's CA certificate from Settings (`设置`), configure your test app to trust it, and enable HTTPS decryption (`HTTPS 解密`).
4. Tap Start capture (`开始抓包`), approve Android's VPN permission prompt, and generate traffic in the target app.
5. Return to the request list and open a request to inspect Overview, Request, Response, and Connection details. Tap Stop (`停止`) or use the notification action when finished.

Changes to target apps, HTTPS decryption, passthrough domains, and upstream proxy settings take effect on the next capture start. The app interface is currently primarily in Chinese.

### HTTPS certificate setup

Each installation generates its own CA and exports only the public certificate. The CA private key stays in app-private storage, encrypted with an Android Keystore key.

<details>
<summary>Trust the capture CA in your Android test app's debug build</summary>

Copy the exported certificate to `app/src/debug/res/raw/capture_ca.cer` in your test app project.

Add `app/src/debug/res/xml/debug_network_security.xml`:

```xml
<network-security-config>
    <debug-overrides>
        <trust-anchors>
            <certificates src="@raw/capture_ca" />
        </trust-anchors>
    </debug-overrides>
</network-security-config>
```

Reference it from that test project's `app/src/debug/AndroidManifest.xml`:

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application android:networkSecurityConfig="@xml/debug_network_security" />
</manifest>
```

If the project already has a network security configuration, merge `debug-overrides` into the existing debug configuration. Apps using a custom TrustManager or certificate pinning need additional trust configuration in their own debug builds.

</details>

Packet Capture does not automatically change other apps' trust settings. If clearing app data or reinstalling regenerates the CA, export and configure it again. Add domains that should remain encrypted to Passthrough domains (`透传域名`); exact names and `*.example.com` are supported. The wildcard does not match the bare domain `example.com`.

### Forward through a local SOCKS5 proxy

1. Start a SOCKS5 service on the phone. For example, use v2rayNG in proxy-only mode.
2. Open Settings → Upstream proxy (`设置 → 上游代理`), enable proxy forwarding (`通过代理转发`), and save the actual listening port. The address is fixed at `127.0.0.1`, the default port is `10808`, and authentication is not supported.
3. Exclude Packet Capture itself and the proxy app from the target app selection, then restart capture.

Traffic flows from the target app → Packet Capture VPN / HTTP(S) recording → local SOCKS5 proxy → destination server.

The proxy must support `UDP ASSOCIATE`. Availability is checked before capture starts, and forwarding failures do not fall back to a direct connection. In proxy mode, TCP and UDP traffic, including DNS, use the proxy; the VPN uses `1.1.1.1` and `8.8.8.8` for DNS. HTTP/3 / QUIC is forwarded with connection metadata only, without decryption. IP protocols unsupported by SOCKS5, such as ICMP, are not forwarded.

## Inspection and export

- **Body reading**: Scroll through all saved content. The JSON reading view decodes escape sequences; switch to Raw (`原文`) when you need the original JSON. Copy and body export follow the current text view. Use text export for larger bodies.
- **Image previews**: Image detection uses the actual bytes and system-supported formats rather than requiring an image `Content-Type`. Only the first frame is shown, with a maximum preview edge of 2048 pixels. Copying and exporting in image mode still produces Base64 text.
- **HAR 1.2**: Export all recorded HTTP requests in a session, including duplicate headers, trailers, connection / stream IDs, and body completeness metadata. The request list display limit does not restrict exports.
- **cURL ZIP**: Contains a POSIX shell command in `request.sh` and a separate request body file when present. Extract and run from the same directory. Active requests and requests with incomplete bodies cannot be exported as replayable commands.
- **Credentials**: HAR and cURL exports redact `Authorization`, `Proxy-Authorization`, `Cookie`, and `Set-Cookie` values by default. URLs, query parameters, and bodies retain their original data; review them before sharing.

## Scope and limitations

- HTTPS decryption requires the target app to trust the capture CA. Certificate pinning bypass and upstream mutual TLS client authentication are not supported. Upstream server certificates are still validated during decryption.
- TLS connections with decryption disabled, matching a passthrough rule, lacking SNI, or using ECH are forwarded with connection metadata only. Other non-HTTP traffic does not receive HTTP body parsing either.
- HTTP/3 decryption, in-app request replay, and request / response modification are not supported.
- Android generally allows one ordinary VPN per user at a time. When combining capture with a proxy app, run that app in proxy-only mode.
- App attribution is unavailable on Android 8 / 9 and is shown as unknown.
- Each request / response body is saved up to **5 MiB** by default, with a **500 MiB** total body storage quota and a **5 MiB** decompressed read limit. If a storage or recording queue limit is reached, forwarding continues and incomplete recording is indicated.
- The request list shows the latest **200** matching records; history shows up to **100** matching sessions.

## Build from source

Requires **JDK 21**, **Android SDK 36**, **NDK 28.2.13676358**, and **CMake 3.22.1**. The project uses Gradle Wrapper **8.13**, Android Gradle Plugin **8.13.0**, and Kotlin **2.0.21**.

1. Open the project in Android Studio, install the SDK / NDK / CMake components above, set the Gradle JDK or `JAVA_HOME` to JDK 21, and configure `sdk.dir` in your local `local.properties`.
2. Run the build from the project root. The default modules are `:app`, `:core`, `:capture`, and `:data`. If `test-client/build.gradle` exists locally, that test module is included automatically.

Windows (PowerShell):

```powershell
.\gradlew.bat :app:assembleDebug
```

macOS / Linux:

```bash
bash ./gradlew :app:assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`. The minimum supported version is Android 8.0 / API 26. Included ABIs are `arm64-v8a`, `armeabi-v7a`, `x86_64`, and `x86`. Configure your own signing before distributing a release build.

## Project structure

| Module | Responsibility |
| --- | --- |
| `app/` | Jetpack Compose UI, state management, body inspection, and Android system integration. |
| `core/` | Capture state, domain models, lifecycle, and interfaces between modules. |
| `capture/` | VPN foreground service, native forwarding with zdtun, Netty HTTP/HTTP2 handling, TLS, and SOCKS5 proxying. |
| `data/` | Room database, DataStore settings, body file storage, parsing, and HAR / cURL exports. |

## Third-party components

The project uses zdtun, Netty, Conscrypt, Bouncy Castle, AndroidX, and other components. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for sources, versions, and license notices.
