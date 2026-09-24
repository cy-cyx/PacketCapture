package com.packetcapture.capture.proxy

import com.packetcapture.core.*
import com.packetcapture.capture.engine.TrafficRecorder
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.*
import io.netty.handler.codec.http.*
import io.netty.handler.codec.http2.HttpConversionUtil
import io.netty.util.ReferenceCountUtil
import java.util.ArrayDeque

/** 只规范化记录副本；H2 适配器的内部路由字段和虚拟 chunked 头不属于实际 H2 消息。 */
internal fun recordableHeaders(headers: HttpHeaders, protocol: String): List<Header> {
    val synthetic = if (protocol == "HTTP/2")
        HttpConversionUtil.ExtensionHeaderNames.entries.map { it.text().toString() }.toSet() + "transfer-encoding"
    else emptySet()
    return headers.filterNot { it.key.lowercase() in synthetic }.map { Header(it.key, it.value) }
}

/** 一对 channel 在同一 event loop 上运行。HTTP/2 的每个 stream 各有独立实例，绝不共享请求队列。 */
internal class ExchangeBridge(
    private val client: Channel, private val upstream: Channel,
    private val connection: ConnectionRecord, private val recorder: TrafficRecorder,
    private val secure: Boolean, private val protocol: String, private val streamId: Int? = null,
) {
    private val pending = ArrayDeque<TrafficRecorder.ExchangeRecording>()
    private var incoming: TrafficRecorder.ExchangeRecording? = null
    private var outgoing: TrafficRecorder.ExchangeRecording? = null
    private var interim = false
    private var upgraded = false
    fun install() {
        client.pipeline().addLast("request-recorder", Relay(true))
        upstream.pipeline().addLast("response-recorder", Relay(false))
    }
    private inner class Relay(private val requestSide: Boolean) : ChannelInboundHandlerAdapter() {
        private val peer get() = if (requestSide) upstream else client
        override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
            try {
                if (!upgraded) {
                    if (requestSide) recordRequest(msg) else recordResponse(msg)
                }
                if (peer.isActive) peer.writeAndFlush(msg).addListener { future ->
                    if (!future.isSuccess) fail("转发失败: ${future.cause()?.message}")
                } else { ReferenceCountUtil.release(msg); fail("对端连接已关闭") }
            } catch (e: Exception) { ReferenceCountUtil.release(msg); fail(e.message ?: "HTTP 解析失败") }
        }
        override fun channelWritabilityChanged(ctx: ChannelHandlerContext) {
            if (peer.isActive) peer.config().isAutoRead = ctx.channel().isWritable
            ctx.fireChannelWritabilityChanged()
        }
        override fun channelInactive(ctx: ChannelHandlerContext) {
            pending.forEach { it.finish("连接提前结束") }; pending.clear()
            incoming?.finish("连接提前结束"); outgoing?.finish("连接提前结束")
            if (peer.isActive) peer.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE)
            ctx.fireChannelInactive()
        }
        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) { fail(cause.message ?: cause.javaClass.simpleName) }
    }
    private fun recordRequest(msg: Any) {
        if (msg is HttpObject && !msg.decoderResult().isSuccess) throw IllegalArgumentException("无效 HTTP 请求", msg.decoderResult().cause())
        if (msg is HttpRequest) {
            val headers = recordableHeaders(msg.headers(), protocol)
            val fallbackHost = if (connection.destinationAddress.contains(':')) "[${connection.destinationAddress}]" else connection.destinationAddress
            val authority = msg.headers()[HttpHeaderNames.HOST] ?: connection.host ?: "$fallbackHost:${connection.destinationPort}"
            val url = if (msg.uri().startsWith("http://") || msg.uri().startsWith("https://")) msg.uri()
                else "${if (secure) "https" else "http"}://$authority${msg.uri()}"
            incoming = recorder.begin(connection, msg.method().name(), url, protocol, headers, streamId)
            pending.add(incoming)
        }
        if (msg is HttpContent) {
            incoming?.body(msg.content(), BodyPart.REQUEST)
            if (msg is LastHttpContent) {
                incoming?.requestEnded(msg.trailingHeaders().map { Header(it.key, it.value) })
                incoming = null
            }
        }
    }
    private fun recordResponse(msg: Any) {
        if (msg is HttpObject && !msg.decoderResult().isSuccess) throw IllegalArgumentException("无效 HTTP 响应", msg.decoderResult().cause())
        if (msg is HttpResponse) {
            interim = msg.status().code() in 100..199 && msg.status().code() != 101
            outgoing = pending.peek()
            if (!interim) outgoing?.response(msg.status().code(), recordableHeaders(msg.headers(), protocol))
            if (msg.status().code() == 101) {
                outgoing?.finish(); pending.poll(); outgoing = null
                // 首版只记录 WebSocket 升级握手；升级后的字节继续透明转发。
                upgraded = true
                client.eventLoop().execute {
                    client.pipeline().get(HttpServerCodec::class.java)?.let { client.pipeline().remove(it) }
                    upstream.pipeline().get(HttpClientCodec::class.java)?.let { upstream.pipeline().remove(it) }
                }
            }
        }
        if (msg is HttpContent && !interim && !upgraded) {
            outgoing?.body(msg.content(), BodyPart.RESPONSE)
            if (msg is LastHttpContent) {
                outgoing?.finish(trailers = msg.trailingHeaders().map { Header(it.key, it.value) })
                pending.poll(); outgoing = null
            }
        }
        if (msg is LastHttpContent && interim) { interim = false; outgoing = null }
    }
    private fun fail(message: String) {
        pending.forEach { it.finish(message) }; pending.clear()
        incoming?.finish(message); outgoing?.finish(message)
        client.close(); upstream.close()
    }
}
