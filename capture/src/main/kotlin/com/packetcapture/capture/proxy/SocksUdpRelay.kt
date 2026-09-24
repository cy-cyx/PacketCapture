package com.packetcapture.capture.proxy

import com.packetcapture.core.UpstreamProxy
import io.netty.bootstrap.Bootstrap
import io.netty.buffer.Unpooled
import io.netty.channel.*
import io.netty.channel.group.DefaultChannelGroup
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.DatagramPacket
import io.netty.channel.socket.nio.NioDatagramChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.util.concurrent.GlobalEventExecutor
import java.net.*
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Native UDP DNAT 到独立回环端口，再封装为 SOCKS5 UDP；所有 I/O 在两个共享事件线程运行。 */
internal class SocksUdpRelay(
    private val proxy: UpstreamProxy,
    private val protectTcp: (Socket) -> Boolean,
    private val protectUdp: (DatagramSocket) -> Boolean,
    private val onFailure: (Long, String) -> Unit,
) : AutoCloseable {
    private val group = NioEventLoopGroup(2)
    private val channels = DefaultChannelGroup(GlobalEventExecutor.INSTANCE)
    private val flows = ConcurrentHashMap<Long, Flow>()
    @Synchronized fun open(id: Long, destination: String, port: Int): Int {
        check(flows.size < 128) { "UDP 代理连接达到 128 条上限" }
        val flow = Flow(id, InetSocketAddress(destination, port))
        flows[id] = flow
        return try { flow.start() } catch (e: Exception) { flows.remove(id); flow.close(); throw e }
    }
    fun close(id: Long) { flows.remove(id)?.close() }
    override fun close() {
        flows.values.forEach { it.close() }; flows.clear()
        channels.close().awaitUninterruptibly()
        group.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly()
    }
    private inner class TcpSocket : NioSocketChannel() {
        override fun doConnect(remoteAddress: SocketAddress, localAddress: SocketAddress?): Boolean {
            check(protectTcp(javaChannel().socket())) { "无法保护 UDP 关联连接" }
            return super.doConnect(remoteAddress, localAddress)
        }
    }
    private fun udpSocket(ipv6: Boolean): NioDatagramChannel {
        val socket = java.nio.channels.DatagramChannel.open(if (ipv6) StandardProtocolFamily.INET6 else StandardProtocolFamily.INET)
        try {
            check(protectUdp(socket.socket())) { "无法保护 UDP 中继连接" }
            return NioDatagramChannel(socket)
        } catch (e: Exception) { socket.close(); throw e }
    }
    private inner class Flow(val id: Long, val destination: InetSocketAddress) {
        private val loop = group.next()
        private var gateway: Channel? = null
        private var control: Channel? = null
        private var remote: Channel? = null
        private var relay: InetSocketAddress? = null
        private var sender: InetSocketAddress? = null
        private var stopped = false
        private val pending = ArrayDeque<ByteArray>()
        private var pendingBytes = 0
        fun start(): Int {
            // Netty 的 family 构造器把 Android 判作 Java 6；传入已打开的 JDK channel 绕过该平台检测。
            val bound = Bootstrap().group(loop).channelFactory(ChannelFactory { NioDatagramChannel(java.nio.channels.DatagramChannel.open()) })
                .option(ChannelOption.RCVBUF_ALLOCATOR, FixedRecvByteBufAllocator(65535))
                .option(ChannelOption.WRITE_BUFFER_WATER_MARK, WriteBufferWaterMark(32768, 131072))
                .handler(object : SimpleChannelInboundHandler<DatagramPacket>() {
                    override fun channelRead0(ctx: ChannelHandlerContext, msg: DatagramPacket) {
                        if (stopped) return
                        if (sender == null) sender = msg.sender()
                        if (msg.sender() != sender) return
                        val bytes = ByteArray(msg.content().readableBytes()).also { msg.content().getBytes(msg.content().readerIndex(), it) }
                        if (relay != null) send(bytes)
                        else if (pending.size < 32 && pendingBytes + bytes.size <= 65536) { pending.add(bytes); pendingBytes += bytes.size }
                        else fail(IllegalStateException("UDP 代理握手等待队列已满"))
                    }
                    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) = fail(cause)
                }).bind("127.0.0.1", 0).syncUninterruptibly().channel()
            channels.add(bound); gateway = bound
            loop.execute {
                if (!stopped) {
                    val future = Bootstrap().group(loop).channelFactory(ChannelFactory { TcpSocket() })
                        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000)
                        .handler(UdpAssociateHandler(::associated, ::fail)).connect(proxy.host, proxy.port)
                    control = future.channel(); channels.add(future.channel())
                    future.addListener(ChannelFutureListener { if (!it.isSuccess) fail(it.cause()) })
                }
            }
            return (bound.localAddress() as InetSocketAddress).port
        }
        private fun associated(address: InetSocketAddress) {
            if (stopped) return
            val future = Bootstrap().group(loop).channelFactory(ChannelFactory { udpSocket(address.address is Inet6Address) })
                .option(ChannelOption.RCVBUF_ALLOCATOR, FixedRecvByteBufAllocator(65535))
                .option(ChannelOption.WRITE_BUFFER_WATER_MARK, WriteBufferWaterMark(32768, 131072))
                .handler(object : SimpleChannelInboundHandler<DatagramPacket>() {
                    override fun channelRead0(ctx: ChannelHandlerContext, msg: DatagramPacket) {
                        if (stopped || msg.sender() != address) return
                        val bytes = Socks5Udp.decode(msg.content(), destination) ?: return
                        sender?.let { if (gateway?.isWritable == true) gateway?.writeAndFlush(DatagramPacket(Unpooled.wrappedBuffer(bytes), it)) }
                    }
                    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) = fail(cause)
                }).connect(address)
            remote = future.channel(); channels.add(future.channel())
            future.addListener(ChannelFutureListener {
                if (!it.isSuccess) fail(it.cause())
                else if (!stopped) { relay = address; while (pending.isNotEmpty()) send(pending.removeFirst()); pendingBytes = 0 }
            })
        }
        private fun send(bytes: ByteArray) {
            if (stopped) return
            // UDP 拥塞时丢包，让上层重传，不在抓包进程中无限累积发送队列。
            if (remote?.isWritable != true) return
            remote?.writeAndFlush(DatagramPacket(Socks5Udp.encode(destination, bytes), relay!!))
                ?.addListener(ChannelFutureListener { if (!it.isSuccess) fail(it.cause()) })
        }
        private fun fail(cause: Throwable) {
            if (stopped) return
            onFailure(id, "SOCKS5 UDP 转发失败: ${cause.message ?: cause.javaClass.simpleName}")
            dispose()
        }
        fun close() { loop.execute { dispose() } }
        private fun dispose() {
            if (stopped) return
            stopped = true; pending.clear(); pendingBytes = 0
            gateway?.close(); remote?.close(); control?.close()
        }
    }
}
