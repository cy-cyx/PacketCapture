package com.packetcapture.capture.proxy

import com.packetcapture.core.UpstreamProxy
import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream
import java.net.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

class SocksUdpRelayTest {
    @Test fun concurrentFlowsToSameTargetRemainIsolatedAndReleaseAssociations() {
        FakeProxy().use { server ->
            val failures = CopyOnWriteArrayList<String>()
            val protectedTcp = AtomicInteger(); val protectedUdp = AtomicInteger()
            SocksUdpRelay(UpstreamProxy(true, server.port), { protectedTcp.incrementAndGet(); true },
                { protectedUdp.incrementAndGet(); true }, { _, message -> failures += message }).use { relay ->
                val workers = Executors.newFixedThreadPool(6)
                try {
                    val ports = (1L..6L).associateWith { relay.open(it, "8.8.8.8", 53) }
                    ports.map { (id, port) -> workers.submit {
                        DatagramSocket().use { socket ->
                            socket.soTimeout = 5000
                            repeat(3) { sequence ->
                                val bytes = "flow-$id-packet-$sequence".toByteArray()
                                socket.send(DatagramPacket(bytes, bytes.size, InetSocketAddress("127.0.0.1", port)))
                                val response = DatagramPacket(ByteArray(65535), 65535); socket.receive(response)
                                assertArrayEquals(bytes, response.data.copyOf(response.length))
                            }
                        }
                    } }.forEach { it.get(10, TimeUnit.SECONDS) }
                    assertTrue(failures.toString(), failures.isEmpty())
                    assertEquals(6, protectedTcp.get()); assertEquals(6, protectedUdp.get())
                    ports.keys.forEach(relay::close)
                    await { server.active.get() == 0 }
                } finally { workers.shutdownNow() }
            }
        }
    }
    @Test fun lossOfControlConnectionFailsTheFlowWithoutFallback() {
        FakeProxy().use { server ->
            val failed = CountDownLatch(1)
            SocksUdpRelay(UpstreamProxy(true, server.port), { true }, { true }, { id, _ -> if (id == 7L) failed.countDown() }).use { relay ->
                relay.open(7, "1.1.1.1", 53)
                await { server.active.get() == 1 }
                server.controls.forEach { it.close() }
                assertTrue(failed.await(5, TimeUnit.SECONDS))
            }
        }
    }
    @Test fun probeChecksUdpSupportAndReportsWrongPort() {
        FakeProxy().use { server -> Socks5Udp.probe(UpstreamProxy(true, server.port)) { true } }
        val unused = ServerSocket(0).use { it.localPort }
        val error = assertThrows(IllegalStateException::class.java) { Socks5Udp.probe(UpstreamProxy(true, unused)) { true } }
        assertTrue(error.message!!.contains("127.0.0.1:$unused"))
    }
    private fun await(condition: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < until) Thread.sleep(10)
        assertTrue(condition())
    }
    /** 回显 SOCKS UDP 帧，不连接任何公网目标；TCP 生命周期控制每条 UDP 关联。 */
    private class FakeProxy : AutoCloseable {
        private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val port = server.localPort
        val active = AtomicInteger()
        val controls = CopyOnWriteArrayList<Socket>()
        private val workers = Executors.newCachedThreadPool { task -> Thread(task, "fake-socks").apply { isDaemon = true } }
        init {
            workers.submit {
                while (!server.isClosed) {
                    val control = runCatching { server.accept() }.getOrNull() ?: break
                    controls += control
                    workers.submit {
                        val udp = DatagramSocket(InetSocketAddress("127.0.0.1", 0))
                        try {
                            val input = DataInputStream(control.getInputStream())
                            val greeting = ByteArray(3).also(input::readFully)
                            check(greeting.contentEquals(byteArrayOf(5, 1, 0)))
                            control.getOutputStream().write(byteArrayOf(5, 0))
                            val request = ByteArray(10).also(input::readFully)
                            check(request.contentEquals(Socks5Udp.associate))
                            active.incrementAndGet()
                            control.getOutputStream().write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, (udp.localPort ushr 8).toByte(), udp.localPort.toByte()))
                            workers.submit {
                                while (!udp.isClosed) {
                                    try { val packet = DatagramPacket(ByteArray(65535), 65535); udp.receive(packet); udp.send(packet) }
                                    catch (_: Exception) { break }
                                }
                            }
                            try { while (input.read() >= 0) { } } finally { active.decrementAndGet() }
                        } catch (_: Exception) { }
                        finally { udp.close(); control.close(); controls.remove(control) }
                    }
                }
            }
        }
        override fun close() { server.close(); controls.forEach { it.close() }; workers.shutdown(); workers.awaitTermination(5, TimeUnit.SECONDS); workers.shutdownNow() }
    }
}
