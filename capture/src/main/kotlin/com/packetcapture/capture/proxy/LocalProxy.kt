package com.packetcapture.capture.proxy

import android.net.Network
import android.net.VpnService
import com.packetcapture.core.*
import com.packetcapture.capture.crypto.LocalCertificateAuthority
import com.packetcapture.capture.engine.*
import io.netty.bootstrap.Bootstrap
import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.*
import io.netty.channel.group.DefaultChannelGroup
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.codec.ByteToMessageDecoder
import io.netty.handler.codec.http.*
import io.netty.handler.codec.http2.*
import io.netty.handler.ssl.SslHandler
import io.netty.handler.proxy.Socks5ProxyHandler
import io.netty.handler.timeout.ReadTimeoutHandler
import io.netty.util.ReferenceCountUtil
import io.netty.util.concurrent.GlobalEventExecutor
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.*

internal class LocalProxy(
    private val service: VpnService, private val network: Network,
    private val config: CaptureConfig, private val registry: ConnectionRegistry,
    private val recorder: TrafficRecorder, private val certificates: LocalCertificateAuthority,
) : AutoCloseable {
    val username = UUID.randomUUID().toString()
    val password = UUID.randomUUID().toString()
    private val boss = NioEventLoopGroup(1)
    private val workers = NioEventLoopGroup(2)
    private val channels = DefaultChannelGroup(GlobalEventExecutor.INSTANCE)
    private val certificateWorkers = ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, ArrayBlockingQueue(128))
    private var listener: Channel? = null
    fun start(): Int {
        listener = ServerBootstrap().group(boss, workers).channel(NioServerSocketChannel::class.java)
            .childOption(ChannelOption.TCP_NODELAY, true)
            .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK, WriteBufferWaterMark(32768, 131072))
            .childHandler(object : ChannelInitializer<SocketChannel>() {
                override fun initChannel(ch: SocketChannel) {
                    channels.add(ch)
                    ch.pipeline().addLast("idle-timeout", ReadTimeoutHandler(120))
                    ch.pipeline().addLast("socks", SocksHandshake())
                }
            }).bind("127.0.0.1", 0).syncUninterruptibly().channel()
        return (listener!!.localAddress() as InetSocketAddress).port
    }
    override fun close() {
        listener?.close()?.syncUninterruptibly()
        channels.close().awaitUninterruptibly()
        workers.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly()
        boss.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly()
        certificateWorkers.shutdownNow()
    }
    private inner class ProtectedSocket : NioSocketChannel() {
        override fun doConnect(remoteAddress: SocketAddress, localAddress: SocketAddress?): Boolean {
            // javaChannel().socket() 是公开 socket 适配，不反射 Android 隐藏的 FileDescriptor 字段。
            check(service.protect(javaChannel().socket())) { "无法将上游 socket 排除出 VPN" }
            // 回环 SOCKS 服务不能绑定 Wi-Fi / 移动网络，否则到不了本机监听端口。
            if (!config.upstreamProxy.enabled) network.bindSocket(javaChannel().socket())
            return super.doConnect(remoteAddress, localAddress)
        }
    }
    private fun connect(client: Channel, connection: ConnectionRecord, secure: Boolean, desiredProtocol: String,
        callback: (Channel, String) -> Unit) {
        val socks = config.upstreamProxy.takeIf { it.enabled }?.let {
            Socks5ProxyHandler(InetSocketAddress(it.host, it.port)).apply { setConnectTimeoutMillis(10000) }
        }
        Bootstrap().group(client.eventLoop()).channelFactory(ChannelFactory { ProtectedSocket() })
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000).option(ChannelOption.TCP_NODELAY, true)
            .option(ChannelOption.WRITE_BUFFER_WATER_MARK, WriteBufferWaterMark(32768, 131072))
            .handler(object : ChannelInitializer<SocketChannel>() {
                override fun initChannel(ch: SocketChannel) {
                    channels.add(ch)
                    client.closeFuture().addListener { ch.close() }
                    if (socks != null) ch.pipeline().addLast("upstream-socks", socks)
                    ch.pipeline().addLast(ReadTimeoutHandler(120))
                    if (secure) {
                        val engine = certificates.clientEngine(connection.host ?: connection.destinationAddress, connection.destinationPort, desiredProtocol)
                        ch.pipeline().addLast("upstream-tls", SslHandler(engine).apply { handshakeTimeoutMillis = 10000 })
                    }
                    ch.pipeline().addLast("upstream-errors", object : ChannelInboundHandlerAdapter() {
                        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
                            fail(client, connection, "上游连接失败: ${cause.message}"); ctx.close()
                        }
                    })
                }
            }).connect(InetSocketAddress(connection.destinationAddress, connection.destinationPort)).addListener(ChannelFutureListener { future ->
                if (!future.isSuccess) { fail(client, connection, "上游连接失败: ${future.cause()?.message}"); return@ChannelFutureListener }
                val remote = future.channel()
                if (!client.isActive) { remote.close(); return@ChannelFutureListener }
                fun connected() {
                    if (!client.isActive) { remote.close(); return }
                    val ssl = remote.pipeline().get(SslHandler::class.java)
                    if (ssl == null) callback(remote, desiredProtocol)
                    else ssl.handshakeFuture().addListener { handshake ->
                        if (handshake.isSuccess && client.isActive) callback(remote, certificates.applicationProtocol(ssl.engine()).orEmpty().ifEmpty { "http/1.1" })
                        else { remote.close(); fail(client, connection, "上游证书或 TLS 握手失败: ${handshake.cause()?.message}") }
                    }
                }
                // TCP 连接到 SOCKS 端口不等于已连接到目标；必须等待 CONNECT 回复。
                if (socks == null) connected() else socks.connectFuture().addListener { handshake ->
                    if (handshake.isSuccess) connected()
                    else { remote.close(); fail(client, connection, "SOCKS5 代理连接失败: ${handshake.cause()?.message}") }
                }
            })
    }
    private fun fail(client: Channel, connection: ConnectionRecord, message: String) {
        registry.update(connection.copy(note = message, completion = Completion.FAILED))
        client.close()
    }
    private inner class SocksHandshake : ByteToMessageDecoder() {
        private var stage = 0
        override fun decode(ctx: ChannelHandlerContext, input: ByteBuf, out: MutableList<Any>) {
            if (input.readableBytes() > 2048) { ctx.close(); return }
            val p = input.readerIndex()
            if (stage == 0) {
                if (input.readableBytes() < 2) return
                val count = input.getUnsignedByte(p + 1).toInt()
                if (input.readableBytes() < 2 + count) return
                val supported = input.getUnsignedByte(p).toInt() == 5 && (0 until count).any { input.getUnsignedByte(p + 2 + it).toInt() == 2 }
                input.skipBytes(2 + count)
                ctx.writeAndFlush(Unpooled.wrappedBuffer(byteArrayOf(5, if (supported) 2 else 0xff.toByte())))
                if (!supported) { ctx.close(); return }; stage = 1
            } else if (stage == 1) {
                if (input.readableBytes() < 2) return
                val userLength = input.getUnsignedByte(p + 1).toInt()
                if (input.readableBytes() < 3 + userLength) return
                val passLength = input.getUnsignedByte(p + 2 + userLength).toInt()
                if (input.readableBytes() < 3 + userLength + passLength) return
                val user = ByteArray(userLength); input.getBytes(p + 2, user)
                val pass = ByteArray(passLength); input.getBytes(p + 3 + userLength, pass)
                val valid = input.getUnsignedByte(p).toInt() == 1 &&
                    MessageDigest.isEqual(user, username.toByteArray()) && MessageDigest.isEqual(pass, password.toByteArray())
                input.skipBytes(3 + userLength + passLength)
                ctx.writeAndFlush(Unpooled.wrappedBuffer(byteArrayOf(1, if (valid) 0 else 1)))
                if (!valid) { ctx.close(); return }; stage = 2
            } else {
                if (input.readableBytes() < 4) return
                val type = input.getUnsignedByte(p + 3).toInt()
                val length = when (type) { 1 -> 10; 4 -> 22; else -> { ctx.close(); return } }
                if (input.readableBytes() < length) return
                if (input.getUnsignedByte(p).toInt() != 5 || input.getUnsignedByte(p + 1).toInt() != 1) { ctx.close(); return }
                val port = (ctx.channel().remoteAddress() as InetSocketAddress).port
                val connection = registry.forPort(port)
                if (connection == null) { ctx.close(); return }
                input.skipBytes(length)
                ctx.writeAndFlush(Unpooled.wrappedBuffer(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, 0, 0)))
                ctx.pipeline().addAfter(ctx.name(), "sniff", ProtocolSniffer(connection))
                ctx.pipeline().remove(this)
            }
        }
        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) { ctx.close() }
    }
    private inner class ProtocolSniffer(private var connection: ConnectionRecord) : ByteToMessageDecoder() {
        private var decided = false
        private var timeout: ScheduledFuture<*>? = null
        override fun handlerAdded(ctx: ChannelHandlerContext) {
            // SSH 等协议会先由服务器发送数据；有界等待后透传，避免永远等待客户端首包。
            timeout = ctx.executor().schedule({ if (!decided) raw(ctx, "未识别协议，透传") }, 1500, TimeUnit.MILLISECONDS)
        }
        override fun decode(ctx: ChannelHandlerContext, input: ByteBuf, out: MutableList<Any>) {
            if (decided) return
            if (input.readableBytes() > 65536) { raw(ctx, "协议探测超过缓冲上限，透传"); return }
            if (input.readableBytes() < 4) return
            val p = input.readerIndex()
            if (input.getUnsignedByte(p).toInt() == 22 && input.getUnsignedByte(p + 1).toInt() == 3) {
                val bytes = ByteArray(input.readableBytes()); input.getBytes(p, bytes)
                val hello = ClientHelloReader.read(bytes)
                if (!hello.complete) return
                val host = hello.host
                connection = connection.copy(host = host)
                if (!config.decryptHttps || host == null || hello.ech || matchesBypass(host, config.bypassDomains)) {
                    raw(ctx, if (hello.ech) "ECH 连接透传" else "HTTPS 未解密，透传"); return
                }
                tls(ctx, host); return
            }
            val prefix = input.toString(p, minOf(input.readableBytes(), 24), Charsets.US_ASCII)
            if (prefix.startsWith("PRI ")) { http(ctx, false, "h2"); return }
            if (listOf("GET ", "POST", "PUT ", "HEAD", "DELE", "OPTI", "PATC", "CONN", "TRAC").any(prefix::startsWith)) {
                http(ctx, false, "http/1.1")
            } else raw(ctx, "非 HTTP 流量透传")
        }
        private fun pause(ctx: ChannelHandlerContext) { decided = true; timeout?.cancel(false); ctx.channel().config().isAutoRead = false }
        private fun raw(ctx: ChannelHandlerContext, note: String) {
            pause(ctx); connection = connection.copy(note = note); registry.update(connection)
            connect(ctx.channel(), connection, false, "raw") { remote, _ ->
                ctx.pipeline().addAfter(ctx.name(), "raw-client", RawRelay(remote))
                remote.pipeline().addLast("raw-server", RawRelay(ctx.channel()))
                ctx.pipeline().remove(this); ctx.channel().config().isAutoRead = true
            }
        }
        private fun http(ctx: ChannelHandlerContext, secure: Boolean, protocol: String) {
            pause(ctx); registry.update(connection)
            connect(ctx.channel(), connection, secure, protocol) { remote, negotiated ->
                setupHttp(ctx.channel(), remote, connection, secure, protocol, negotiated)
                ctx.pipeline().remove(this); ctx.channel().config().isAutoRead = true
            }
        }
        private fun tls(ctx: ChannelHandlerContext, host: String) {
            pause(ctx)
            try {
                certificateWorkers.execute {
                    try {
                        val context = certificates.serverContext(host)
                        ctx.executor().execute {
                            if (ctx.channel().isActive) {
                                val ssl = SslHandler(certificates.serverEngine(context)).apply { handshakeTimeoutMillis = 10000 }
                                val gate = ByteGate()
                                ctx.pipeline().addAfter(ctx.name(), "client-tls", ssl)
                                ctx.pipeline().addAfter("client-tls", "tls-gate", gate)
                                ssl.handshakeFuture().addListener { result ->
                                    if (!result.isSuccess) { fail(ctx.channel(), connection, "目标应用拒绝证书或 TLS 握手失败: ${result.cause()?.message}") }
                                    else {
                                        connection = connection.copy(decrypted = true, tlsVersion = ssl.engine().session.protocol)
                                        registry.update(connection)
                                        ctx.channel().config().isAutoRead = false
                                        val protocol = certificates.applicationProtocol(ssl.engine()).orEmpty().ifEmpty { "http/1.1" }
                                        connect(ctx.channel(), connection, true, protocol) { remote, negotiated ->
                                            val saved = gate.take()
                                            ctx.pipeline().remove(gate)
                                            setupHttp(ctx.channel(), remote, connection, true, protocol, negotiated)
                                            val sslContext = ctx.pipeline().context("client-tls")
                                            saved.forEach { sslContext.fireChannelRead(it) }
                                            ctx.channel().config().isAutoRead = true
                                        }
                                    }
                                }
                                ctx.pipeline().remove(this)
                                ctx.channel().config().isAutoRead = true
                            }
                        }
                    } catch (e: Exception) { ctx.executor().execute { fail(ctx.channel(), connection, "证书生成失败: ${e.message}") } }
                }
            } catch (e: RejectedExecutionException) { fail(ctx.channel(), connection, "证书任务队列已满") }
        }
        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) { fail(ctx.channel(), connection, cause.message ?: "代理连接错误") }
    }
    private fun setupHttp(client: Channel, upstream: Channel, connection: ConnectionRecord, secure: Boolean,
        downstreamProtocol: String, upstreamProtocol: String) {
        if (downstreamProtocol == "h2") {
            if (upstreamProtocol == "h2") {
                upstream.pipeline().addLast(Http2FrameCodecBuilder.forClient().initialSettings(Http2Settings().pushEnabled(false)).build())
                upstream.pipeline().addLast(Http2MultiplexHandler(object : ChannelInitializer<Channel>() {
                    override fun initChannel(ch: Channel) { ch.close() }
                }))
            } else upstream.close()
            client.pipeline().addLast(Http2FrameCodecBuilder.forServer()
                .initialSettings(Http2Settings().maxConcurrentStreams(100).maxHeaderListSize(65536)).build())
            client.pipeline().addLast(Http2MultiplexHandler(object : ChannelInitializer<Http2StreamChannel>() {
                override fun initChannel(ch: Http2StreamChannel) {
                    val pending = PendingObjects()
                    ch.pipeline().addLast(Http2StreamFrameToHttpObjectCodec(true))
                    ch.pipeline().addLast(pending)
                    ch.config().isAutoRead = false
                    if (upstreamProtocol == "h2") {
                        Http2StreamChannelBootstrap(upstream).handler(object : ChannelInitializer<Http2StreamChannel>() {
                            override fun initChannel(remote: Http2StreamChannel) { remote.pipeline().addLast(Http2StreamFrameToHttpObjectCodec(false)) }
                        }).open().addListener { future ->
                            if (!future.isSuccess) { pending.abort(ch); return@addListener }
                            val remote = future.getNow() as Http2StreamChannel
                            ExchangeBridge(ch, remote, connection, recorder, secure, "HTTP/2", ch.stream().id()).install()
                            pending.drain(ch); ch.config().isAutoRead = true
                        }
                    } else {
                        // 两侧 ALPN 独立：下游 h2、上游 h1 时按 stream 建立连接，发送前转换 HTTP 语义。
                        connect(ch, connection, secure, "http/1.1") { remote, _ ->
                            remote.pipeline().addLast(HttpClientCodec())
                            ExchangeBridge(ch, remote, connection, recorder, secure, "HTTP/2", ch.stream().id()).install()
                            pending.drain(ch); ch.config().isAutoRead = true
                        }
                    }
                }
            }))
        } else {
            client.pipeline().addLast(HttpServerCodec(4096, 65536, 8192))
            upstream.pipeline().addLast(HttpClientCodec(4096, 65536, 8192))
            ExchangeBridge(client, upstream, connection, recorder, secure, "HTTP/1.1").install()
        }
    }
    private class RawRelay(private val peer: Channel) : ChannelInboundHandlerAdapter() {
        override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
            if (peer.isActive) peer.writeAndFlush(msg).addListener(ChannelFutureListener { if (!it.isSuccess) ctx.close() })
            else { ReferenceCountUtil.release(msg); ctx.close() }
        }
        override fun channelWritabilityChanged(ctx: ChannelHandlerContext) {
            if (peer.isActive) peer.config().isAutoRead = ctx.channel().isWritable
            ctx.fireChannelWritabilityChanged()
        }
        override fun channelInactive(ctx: ChannelHandlerContext) { peer.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE) }
        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) { ctx.close(); peer.close() }
    }
    private class ByteGate : ChannelInboundHandlerAdapter() {
        private val pending = ArrayList<Any>()
        private var size = 0
        override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
            size += (msg as? ByteBuf)?.readableBytes() ?: 0
            if (size > 262144) { ReferenceCountUtil.release(msg); ctx.close() } else pending.add(msg)
        }
        fun take(): List<Any> = pending.toList().also { pending.clear() }
        override fun channelInactive(ctx: ChannelHandlerContext) { pending.forEach(ReferenceCountUtil::release); pending.clear(); ctx.fireChannelInactive() }
    }
    private class PendingObjects : ChannelInboundHandlerAdapter() {
        private val pending = ArrayList<Any>()
        private var size = 0
        override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
            size += (msg as? HttpContent)?.content()?.readableBytes() ?: 0
            if (size > 262144 || pending.size >= 64) { ReferenceCountUtil.release(msg); abort(ctx.channel()) } else pending.add(msg)
        }
        fun drain(channel: Channel) {
            val context = channel.pipeline().context(this)
            pending.forEach { context.fireChannelRead(it) }
            pending.clear(); channel.pipeline().remove(this)
        }
        fun abort(channel: Channel) { pending.forEach(ReferenceCountUtil::release); pending.clear(); channel.close() }
        override fun channelInactive(ctx: ChannelHandlerContext) { abort(ctx.channel()); ctx.fireChannelInactive() }
    }
}
