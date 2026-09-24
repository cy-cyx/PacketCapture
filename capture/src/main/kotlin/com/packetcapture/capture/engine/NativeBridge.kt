package com.packetcapture.capture.engine

import android.net.VpnService
import com.packetcapture.capture.proxy.SocksUdpRelay
import java.util.concurrent.atomic.AtomicLong

/** run 阻塞专用工作线程，接管传入的 TUN 副本和唤醒管道读端；返回时 native 负责关闭两者。 */
internal class NativeBridge(private val service: VpnService, private val registry: ConnectionRegistry, private val udp: SocksUdpRelay? = null) {
    val uploaded = AtomicLong()
    val downloaded = AtomicLong()
    external fun run(tunFd: Int, wakeFd: Int, proxyPort: Int, username: String, password: String, networkHandle: Long, upstreamProxy: Boolean): Int
    @Suppress("unused") fun protectSocket(fd: Int): Boolean = service.protect(fd)
    @Suppress("unused") fun connectionOpened(id: Long, source: String, sourcePort: Int, target: String, targetPort: Int, protocol: Int): Int {
        registry.opened(id, source, sourcePort, target, targetPort, protocol)
        if (udp == null || protocol == 6) return 0
        if (protocol != 17) { registry.failed(id, "SOCKS5 不支持此 IP 协议，已阻止直连"); return -1 }
        return try { udp.open(id, target, targetPort) }
        catch (e: Exception) { registry.failed(id, "UDP 代理连接失败: ${e.message}"); -1 }
    }
    @Suppress("unused") fun connectionMapped(id: Long, port: Int) = registry.mapped(id, port)
    @Suppress("unused") fun connectionClosed(id: Long, error: String?) { udp?.close(id); registry.closed(id, error) }
    @Suppress("unused") fun traffic(up: Long, down: Long) { uploaded.set(up); downloaded.set(down) }
    companion object { init { System.loadLibrary("capture_jni") } }
}
