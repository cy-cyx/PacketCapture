package com.packetcapture.capture.engine

import com.packetcapture.core.*
import java.util.concurrent.ConcurrentHashMap

/** 原始五元组与回环 socket 的端口绑定，端口不能单独作为持久化主键，关闭连接时移除映射。 */
internal class ConnectionRegistry(private val sessionId: String, private val owner: (String, Int, String, Int, Int) -> Pair<Int?, String?>,
    private val recorder: TrafficRecorder, private val proxied: Boolean = false) {
    private val native = ConcurrentHashMap<Long, ConnectionRecord>()
    private val ports = ConcurrentHashMap<Int, Long>()
    fun opened(id: Long, source: String, sourcePort: Int, target: String, targetPort: Int, protocol: Int) {
        val (uid, pkg) = owner(source, sourcePort, target, targetPort, protocol)
        val connection = ConnectionRecord("$sessionId-$id", sessionId, source, sourcePort, target, targetPort,
            if (protocol == 6) "TCP" else if (protocol == 17) "UDP" else "IP/$protocol", uid, pkg,
            note = if (protocol == 17) (if (proxied) "UDP 经 SOCKS5 转发；不解析 QUIC/HTTP3" else "UDP 透传；不解析 QUIC/HTTP3") else null)
        native[id] = connection
        recorder.connection(connection)
    }
    fun mapped(id: Long, port: Int) { if (native.containsKey(id)) ports[port] = id }
    fun forPort(port: Int): ConnectionRecord? = ports[port]?.let(native::get)
    fun failed(id: Long, message: String) {
        native.computeIfPresent(id) { _, old -> old.copy(note = message, completion = Completion.FAILED) }?.let(recorder::connection)
    }
    fun update(connection: ConnectionRecord) {
        val id = connection.id.substringAfterLast('-').toLongOrNull()
        if (id != null) native.computeIfPresent(id) { _, old ->
            // 原生侧可能先观察到 FIN；协议层补充元数据时不能把已结束连接恢复成 ACTIVE。
            if (old.endedAt != null) connection.copy(endedAt = old.endedAt, completion = old.completion) else connection
        }?.let(recorder::connection)
    }
    fun closed(id: Long, error: String?) {
        native.remove(id)?.let { recorder.connection(it.copy(endedAt = System.currentTimeMillis(),
            completion = if (error != null || it.completion == Completion.FAILED) Completion.FAILED else Completion.COMPLETE, note = it.note ?: error)) }
        ports.entries.removeAll { it.value == id }
    }
}
