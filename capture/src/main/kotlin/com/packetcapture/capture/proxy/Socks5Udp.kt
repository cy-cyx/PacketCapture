package com.packetcapture.capture.proxy

import com.packetcapture.core.UpstreamProxy
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.ByteToMessageDecoder
import io.netty.util.NetUtil
import java.io.DataInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

/** RFC 1928：本机、无认证的 SOCKS5；地址解码从不触发系统 DNS 查询。 */
internal object Socks5Udp {
    val greeting = byteArrayOf(5, 1, 0)
    val associate = byteArrayOf(5, 3, 0, 1, 0, 0, 0, 0, 0, 0)
    fun checkGreeting(version: Int, method: Int) {
        require(version == 5) { "该端口不是 SOCKS5 服务" }
        require(method == 0) { "代理需要认证；请使用本机无认证 SOCKS5 端口" }
    }
    private data class Endpoint(val address: InetSocketAddress, val size: Int)
    private fun endpoint(input: ByteBuf, offset: Int): Endpoint? {
        if (input.writerIndex() <= offset) return null
        val type = input.getUnsignedByte(offset).toInt()
        val length = when (type) {
            1 -> 4
            4 -> 16
            3 -> { if (input.writerIndex() <= offset + 1) return null; input.getUnsignedByte(offset + 1).toInt() }
            else -> error("SOCKS5 地址类型无效")
        }
        val start = offset + if (type == 3) 2 else 1
        if (input.writerIndex() < start + length + 2) return null
        val bytes = ByteArray(length).also { input.getBytes(start, it) }
        val ip = if (type != 3) bytes else {
            val name = bytes.toString(Charsets.US_ASCII)
            if (name.equals("localhost", true)) byteArrayOf(127, 0, 0, 1)
            else NetUtil.createByteArrayFromIpAddressString(name) ?: error("代理返回的地址必须是本机 IP")
        }
        val port = input.getUnsignedShort(start + length)
        return Endpoint(InetSocketAddress(InetAddress.getByAddress(ip), port), start + length + 2 - offset)
    }
    fun readAssociateReply(input: ByteBuf): InetSocketAddress? {
        if (input.readableBytes() < 4) return null
        val p = input.readerIndex()
        require(input.getUnsignedByte(p).toInt() == 5 && input.getByte(p + 2).toInt() == 0) { "无效的 SOCKS5 回复" }
        val status = input.getUnsignedByte(p + 1).toInt()
        require(status == 0) { "SOCKS5 UDP 转发被拒绝（$status）；请确认代理支持 UDP" }
        val result = endpoint(input, p + 3) ?: return null
        input.skipBytes(3 + result.size)
        require(result.address.port != 0) { "代理返回了无效的 UDP 端口" }
        val ip = result.address.address
        require(ip.isAnyLocalAddress || ip.isLoopbackAddress) { "仅支持本机 SOCKS5 UDP 中继地址" }
        return if (ip.isAnyLocalAddress) InetSocketAddress("127.0.0.1", result.address.port) else result.address
    }
    fun encode(target: InetSocketAddress, payload: ByteArray): ByteBuf {
        val ip = target.address.address
        return Unpooled.buffer(6 + ip.size + payload.size).writeShort(0).writeByte(0)
            .writeByte(if (ip.size == 4) 1 else 4).writeBytes(ip).writeShort(target.port).writeBytes(payload)
    }
    fun decode(input: ByteBuf, expected: InetSocketAddress): ByteArray? {
        if (input.readableBytes() < 4) return null
        val p = input.readerIndex()
        // 不支持 SOCKS 层分片；不将不完整或其他来源的数据当作本连接的回复。
        if (input.getUnsignedShort(p) != 0 || input.getByte(p + 2).toInt() != 0) return null
        val result = runCatching { endpoint(input, p + 3) }.getOrNull() ?: return null
        if (result.address != expected) return null
        val start = p + 3 + result.size
        return ByteArray(input.writerIndex() - start).also { input.getBytes(start, it) }
    }
    fun probe(proxy: UpstreamProxy, protect: (Socket) -> Boolean) {
        try {
            // Android Socket() 尚未创建 fd，protect 会返回 false；NIO open 先创建真实 socket。
            java.nio.channels.SocketChannel.open().use { channel ->
                val socket = channel.socket()
                check(protect(socket)) { "无法保护代理连接" }
                socket.soTimeout = 5000
                socket.connect(InetSocketAddress(proxy.host, proxy.port), 5000)
                val input = DataInputStream(socket.getInputStream())
                socket.getOutputStream().write(greeting)
                checkGreeting(input.readUnsignedByte(), input.readUnsignedByte())
                socket.getOutputStream().write(associate)
                val reply = Unpooled.buffer(262)
                try {
                    while (reply.readableBytes() < 262) {
                        reply.writeByte(input.readUnsignedByte())
                        if (readAssociateReply(reply) != null) return
                    }
                    error("SOCKS5 回复过长")
                } finally { reply.release() }
            }
        } catch (e: Exception) {
            throw IllegalStateException("无法使用本机代理 ${proxy.host}:${proxy.port}：${e.message ?: e.javaClass.simpleName}。请检查 v2rayNG 已启动“仅代理”模式和端口配置。", e)
        }
    }
}

/** 每个 UDP 流保留其独立 TCP 关联，避免相同目的地址的并发连接互相收到数据。 */
internal class UdpAssociateHandler(private val ready: (InetSocketAddress) -> Unit, private val failed: (Throwable) -> Unit) : ByteToMessageDecoder() {
    private var stage = 0
    private var timeout: io.netty.util.concurrent.ScheduledFuture<*>? = null
    override fun channelActive(ctx: ChannelHandlerContext) {
        timeout = ctx.executor().schedule({ failed(IllegalStateException("SOCKS5 UDP 握手超时")); ctx.close() }, 10, TimeUnit.SECONDS)
        ctx.writeAndFlush(Unpooled.wrappedBuffer(Socks5Udp.greeting))
        super.channelActive(ctx)
    }
    override fun decode(ctx: ChannelHandlerContext, input: ByteBuf, out: MutableList<Any>) {
        if (stage == 0) {
            if (input.readableBytes() < 2) return
            Socks5Udp.checkGreeting(input.readUnsignedByte().toInt(), input.readUnsignedByte().toInt())
            stage = 1
            ctx.writeAndFlush(Unpooled.wrappedBuffer(Socks5Udp.associate))
        }
        if (stage == 1) {
            val address = Socks5Udp.readAssociateReply(input) ?: return
            stage = 2; timeout?.cancel(false); ready(address)
        }
        if (stage == 2) input.skipBytes(input.readableBytes())
    }
    override fun channelInactive(ctx: ChannelHandlerContext) {
        timeout?.cancel(false); failed(IllegalStateException("SOCKS5 UDP 关联已关闭")); super.channelInactive(ctx)
    }
    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) { failed(cause); ctx.close() }
}
