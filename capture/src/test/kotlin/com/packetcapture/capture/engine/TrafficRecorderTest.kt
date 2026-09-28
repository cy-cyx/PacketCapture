package com.packetcapture.capture.engine

import com.packetcapture.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

/** 精确触发停止与数据库提交竞争，确保取消不会吞掉已从队列取出的元数据。 */
class TrafficRecorderTest {
    @Test fun stopRetriesCancelledConnectionWriteAndFlushesExchange() = runBlocking {
        val saving = CompletableDeferred<Unit>()
        var attempts = 0
        var connection: ConnectionRecord? = null
        var exchange: HttpExchange? = null
        val repository = object : CaptureRepository {
            override fun sessions(query: String) = flowOf(emptyList<CaptureSession>())
            override fun exchanges(sessionId: String?, filter: ExchangeFilter, limit: Int) = flowOf(emptyList<HttpExchange>())
            override fun connections(sessionId: String?) = flowOf(emptyList<ConnectionRecord>())
            override fun exchange(id: String) = flowOf<HttpExchange?>(null)
            override suspend fun session(id: String): CaptureSession? = null
            override suspend fun connection(id: String): ConnectionRecord? = null
            override suspend fun allExchanges(sessionId: String) = emptyList<HttpExchange>()
            override suspend fun saveSession(session: CaptureSession) {}
            override suspend fun saveConnection(value: ConnectionRecord) {
                if (attempts++ == 0) { saving.complete(Unit); awaitCancellation() }
                connection = value
            }
            override suspend fun saveExchange(value: HttpExchange) { exchange = value }
            override suspend fun deleteSession(id: String) {}
            override suspend fun clearStorage() {}
            override suspend fun recoverInterrupted() {}
        }
        val bodies = object : BodyStore {
            override fun open(sessionId: String, exchangeId: String, part: BodyPart, limit: Long, totalLimit: Long) = object : BodySink {
                override fun append(bytes: ByteArray) {}
                override suspend fun finish() = BodyRef("empty")
            }
            override suspend fun read(ref: BodyRef, maxBytes: Int) = byteArrayOf()
            override suspend fun preview(ref: BodyRef?, headers: List<Header>) = BodyPreview("", false, false)
            override suspend fun deleteSession(sessionId: String) {}
            override suspend fun clearAll() {}
            override suspend fun usedBytes() = 0L
            override suspend fun recoverOrphans(liveSessionIds: Set<String>) {}
        }
        val warnings = mutableListOf<String>()
        val recorder = TrafficRecorder(repository, bodies, CaptureConfig(), "s", warnings::add)
        val original = ConnectionRecord("c", "s", "10.0.0.1", 1000, "10.0.0.2", 80, "TCP")
        recorder.connection(original)
        recorder.begin(original, "GET", "http://example.test/", "HTTP/1.1", emptyList()).apply { requestEnded(); finish() }
        withTimeout(5000) { saving.await(); recorder.close() }
        assertEquals(original, connection)
        assertEquals(Completion.COMPLETE, exchange?.completion)
        assertTrue(warnings.toString(), warnings.isEmpty())
    }
}
