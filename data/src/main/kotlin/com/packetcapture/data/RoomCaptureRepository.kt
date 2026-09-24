package com.packetcapture.data

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import com.google.gson.Gson
import com.packetcapture.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*

class RoomCaptureRepository(context: Context, private val bodies: BodyStore) : CaptureRepository {
    private val db = Room.databaseBuilder(context.applicationContext, CaptureDatabase::class.java, "capture.db").build()
    private val dao = db.dao()
    private val json = Gson()
    override fun sessions(query: String) = dao.sessions(query).map { rows -> rows.map { json.fromJson(it.payload, CaptureSession::class.java) } }.flowOn(Dispatchers.IO)
    override fun exchanges(sessionId: String?, filter: ExchangeFilter, limit: Int) =
        dao.exchanges(sessionId, filter.query, filter.method, filter.errorsOnly, filter.packageName, filter.status, limit.coerceIn(1, 10000))
            .map { rows -> rows.map { json.fromJson(it.payload, HttpExchange::class.java) } }.flowOn(Dispatchers.IO)
    override fun connections(sessionId: String?) = dao.connections(sessionId)
        .map { rows -> rows.map { json.fromJson(it.payload, ConnectionRecord::class.java) } }.flowOn(Dispatchers.IO)
    override fun exchange(id: String) = dao.exchange(id).map { it?.let { json.fromJson(it.payload, HttpExchange::class.java) } }.flowOn(Dispatchers.IO)
    override suspend fun session(id: String) = dao.session(id)?.let { json.fromJson(it.payload, CaptureSession::class.java) }
    override suspend fun connection(id: String) = dao.connection(id)?.let { json.fromJson(it.payload, ConnectionRecord::class.java) }
    override suspend fun allExchanges(sessionId: String) = dao.allExchanges(sessionId).map { json.fromJson(it.payload, HttpExchange::class.java) }
    override suspend fun saveSession(session: CaptureSession) = dao.save(SessionEntity(session.id, session.startedAt, session.title, json.toJson(session)))
    override suspend fun saveConnection(connection: ConnectionRecord) = dao.save(ConnectionEntity(connection.id, connection.sessionId, connection.startedAt, json.toJson(connection)))
    override suspend fun saveExchange(exchange: HttpExchange) = dao.save(ExchangeEntity(
        exchange.id, exchange.sessionId, exchange.connectionId, exchange.startedAt, exchange.method,
        exchange.url, exchange.packageName, exchange.status, exchange.completion == Completion.FAILED, json.toJson(exchange)))
    override suspend fun deleteSession(id: String) {
        require(session(id)?.completion != Completion.ACTIVE) { "请先停止该会话的抓包" }
        // 数据库先提交删除；文件清理可在下次启动重试，避免记录引用已被删除的正文。
        db.withTransaction { dao.deleteExchanges(id); dao.deleteConnections(id); dao.deleteSession(id) }
        bodies.deleteSession(id)
    }
    override suspend fun recoverInterrupted() {
        val now = System.currentTimeMillis()
        db.withTransaction {
            dao.allSessions().map { json.fromJson(it.payload, CaptureSession::class.java) }
                .filter { it.completion == Completion.ACTIVE }.forEach { session ->
                    saveSession(session.copy(completion = Completion.INTERRUPTED, endedAt = now))
                    allExchanges(session.id).filter { it.completion == Completion.ACTIVE }.forEach {
                        saveExchange(it.copy(completion = Completion.INTERRUPTED, endedAt = now, error = "上次进程结束，记录未完成",
                            requestBody = (it.requestBody ?: BodyRef("")).copy(truncated = true, reason = "进程中断，无法确认正文完整性"),
                            responseBody = (it.responseBody ?: BodyRef("")).copy(truncated = true, reason = "进程中断，无法确认正文完整性")))
                    }
                    dao.allConnections(session.id).map { json.fromJson(it.payload, ConnectionRecord::class.java) }
                        .filter { it.completion == Completion.ACTIVE }.forEach {
                            saveConnection(it.copy(completion = Completion.INTERRUPTED, endedAt = now, note = "上次进程结束"))
                        }
                }
        }
        bodies.recoverOrphans(dao.allSessions().map { it.id }.toSet())
    }
}
