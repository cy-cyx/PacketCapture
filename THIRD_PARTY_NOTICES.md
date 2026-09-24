# 第三方组件

此工程保留各组件原始许可。依赖 JAR/AAR 的许可证及 NOTICE 由 Gradle 打包保留。

| 组件 | 固定版本 | 来源 / 许可 |
|---|---|---|
| zdtun | commit 80b7941d4e685d8354346820878eaec0e35b6b1e | https://github.com/emanuele-f/zdtun ，LGPL-3.0-or-later |
| Netty | 4.1.138.Final | https://github.com/netty/netty ，Apache-2.0 |
| Conscrypt Android | 2.7.0 | https://github.com/google/conscrypt ，Apache-2.0 及其内含 BoringSSL 通知 |
| Bouncy Castle bcpkix/bcprov/bcutil | 1.83 | https://www.bouncycastle.org ，Bouncy Castle License |
| Room / DataStore | 2.7.2 / 1.1.7 | AndroidX，Apache-2.0 |
| Gson | 2.11.0 | https://github.com/google/gson ，Apache-2.0 |
| Kotlin / Coroutines | 2.0.21 / 1.9.0 | JetBrains，Apache-2.0 |
| Compose / AndroidX | 见 gradle/libs.versions.toml | AndroidX，Apache-2.0 |
| OkHttp（仅独立测试 App） | 4.12.0 | https://github.com/square/okhttp ，Apache-2.0 |

## zdtun 的使用与修改

完整来源保存在 `capture/src/main/cpp/zdtun`，原作者 Emanuele Faranda 的版权声明与 `COPYING` 保留。zdtun 编译为独立 `libzdtun.so`，由 `libcapture_jni.so` 动态链接；源码包提供修改后的完整源码及构建脚本。

对上游的修改：`on_socket_open` 回调返回值从 void 改为 int；当 `VpnService.protect()` 或底层网络绑定失败时，关闭 socket 并拒绝建立连接，避免进入 VPN 回环。修改位于 `zdtun.h` 和 `zdtun.c`。JNI 轮询顺序和生命周期逻辑位于项目自己的 `capture_jni.c`。

重新分发时保留此通知、上游许可文本、对应修改源码和重新构建/链接所需工程。测试私钥、设备 CA 私钥和本机 SDK 路径不属于分发内容。

`tools/python-libs` 仅用于开发测试，不进入 APK。Python 测试依赖为 h2 4.3.0、cryptography 46.0.5，详见 tools/requirements.txt。
