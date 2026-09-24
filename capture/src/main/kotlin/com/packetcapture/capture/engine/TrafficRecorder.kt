package com.packetcapture.capture.engine

import com.packetcapture.core.*
import io.netty.buffer.ByteBuf
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*

/** 元数据按 id 合并后批量落盘，正文使用独立有界队列；网络线程不直接执行数据库操作。 */
internal class TrafficRecorder(
    private val repository: CaptureRepository, private val bodies: BodyStore, private val config: CaptureConfig,
    private val sessionId: String, private val warning: (String) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val exchanges = ConcurrentHashMap<String, HttpExchange>()
    private val connections = ConcurrentHashMap<String, ConnectionRecord>()
    private val active = ConcurrentHashMap<String, ExchangeRecording>()
    val count = AtomicInteger()
    @Volatile private var storageFull = false
    private val flushJob = scope.launch {
        while (isActive) {
            delay(250)
            storageFull = runCatching { bodies.usedBytes() >= config.storageLimit - 256 * 1024 }.getOrDefault(true)
            flush()
        }
    }
    fun connection(connection: ConnectionRecord) {
        if (connections.size < 4096 || connections.containsKey(connection.id)) connections[connection.id] = connection
        else warning("元数据队列已满，部分连接未记录；转发继续")
    }
    fun begin(connection: ConnectionRecord, method: String, url: String, protocol: String, headers: List<Header>, streamId: Int? = null): ExchangeRecording {
        val exchange = HttpExchange(sessionId = sessionId, connectionId = connection.id, packageName = connection.packageName,
            method = method, url = url, protocol = protocol, requestHeaders = headers, streamId = streamId)
        // 对磁盘过慢和无响应请求同时设上限；跳过记录时仍保留代理请求队列，避免影响网络协议。
        val enabled = !storageFull && active.size < 2048 && exchanges.size < 4096
        val recording = ExchangeRecording(exchange, enabled)
        count.incrementAndGet()
        if (enabled) { active[exchange.id] = recording; exchanges[exchange.id] = exchange }
        else warning(if (storageFull) "总存储额度不足，新请求仅转发" else "记录队列已满，部分请求未记录；转发继续")
        return recording
    }
    private suspend fun flush() {
        for ((id, item) in connections.entries.toList()) {
            if (connections.remove(id, item)) try { repository.saveConnection(item) }
            catch (e: CancellationException) {
                // 停止会取消周期任务；正在提交的快照要交回最终 flush，且不能覆盖刚到达的新状态。
                connections.putIfAbsent(id, item); throw e
            } catch (e: Exception) { warning("连接记录写入失败: ${e.message}") }
        }
        for ((id, item) in exchanges.entries.toList()) {
            if (exchanges.remove(id, item)) try { repository.saveExchange(item) }
            catch (e: CancellationException) { exchanges.putIfAbsent(id, item); throw e }
            catch (e: Exception) { warning("请求记录写入失败: ${e.message}") }
        }
    }
    suspend fun close() {
        active.values.toList().forEach { it.finish("抓包停止或连接中断", Completion.INTERRUPTED) }
        flushJob.cancelAndJoin()
        scope.coroutineContext[Job]!!.children.toList().forEach { it.join() }
        flush()
        scope.cancel()
    }
    inner class ExchangeRecording(initial: HttpExchange, private val enabled: Boolean) {
        @Volatile var exchange = initial
            private set
        private val request = if (enabled) bodies.open(sessionId, initial.id, BodyPart.REQUEST, config.bodyLimit, config.storageLimit) else null
        private val response = if (enabled) bodies.open(sessionId, initial.id, BodyPart.RESPONSE, config.bodyLimit, config.storageLimit) else null
        private var finished = false
        private var requestEnded = false
        @Synchronized fun response(status: Int, headers: List<Header>) {
            if (!enabled || finished || (status in 100..199 && status != 101)) return
            exchange = exchange.copy(status = status, responseHeaders = headers, responseStartedAt = System.currentTimeMillis())
            exchanges[exchange.id] = exchange
        }
        fun body(buffer: ByteBuf, part: BodyPart) {
            val sink = (if (part == BodyPart.REQUEST) request else response) ?: return
            var index = buffer.readerIndex()
            var remaining = buffer.readableBytes()
            // 复制时不移动原缓冲区游标；原 msg 的 retain/release 仍由转发管线负责。
            while (remaining > 0) {
                val bytes = ByteArray(minOf(16384, remaining))
                buffer.getBytes(index, bytes); sink.append(bytes); index += bytes.size; remaining -= bytes.size
            }
        }
        @Synchronized fun requestEnded(trailers: List<Header> = emptyList()) {
            if (requestEnded) return
            requestEnded = true
            exchange = exchange.copy(requestTrailers = trailers)
        }
        @Synchronized fun finish(error: String? = null, completion: Completion = if (error == null) Completion.COMPLETE else Completion.FAILED,
            trailers: List<Header> = emptyList()) {
            if (!enabled || finished) return
            finished = true
            exchange = exchange.copy(endedAt = System.currentTimeMillis(), completion = completion, error = error,
                responseTrailers = trailers)
            val metadata = exchange
            val incompleteRequest = !requestEnded
            scope.launch {
                val requestRef = request!!.finish().let { if (incompleteRequest) it.copy(truncated = true, reason = "请求未完整接收") else it }
                val responseRef = response!!.finish().let { if (completion != Completion.COMPLETE) it.copy(truncated = true, reason = error ?: "响应中断") else it }
                val final = metadata.copy(requestBody = requestRef, responseBody = responseRef)
                exchanges[final.id] = final
                active.remove(final.id)
                if (requestRef.truncated || responseRef.truncated) warning(requestRef.reason ?: responseRef.reason ?: "正文不完整")
            }
        }
    }
}
