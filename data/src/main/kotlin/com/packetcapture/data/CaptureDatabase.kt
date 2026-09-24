package com.packetcapture.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "sessions")
internal data class SessionEntity(@PrimaryKey val id: String, val startedAt: Long, val title: String, val payload: String)
@Entity(tableName = "connections", indices = [Index("sessionId")])
internal data class ConnectionEntity(@PrimaryKey val id: String, val sessionId: String, val startedAt: Long, val payload: String)
@Entity(tableName = "exchanges", indices = [Index(value = ["sessionId", "startedAt"]), Index("connectionId")])
internal data class ExchangeEntity(
    @PrimaryKey val id: String, val sessionId: String, val connectionId: String, val startedAt: Long,
    val method: String, val url: String, val packageName: String?, val status: Int?,
    val failed: Boolean, val payload: String,
)
@Dao
internal interface CaptureDao {
    @Query("SELECT * FROM sessions WHERE title LIKE '%' || :query || '%' ORDER BY startedAt DESC LIMIT 100")
    fun sessions(query: String): Flow<List<SessionEntity>>
    @Query("""SELECT * FROM exchanges WHERE (:session IS NULL OR sessionId=:session)
        AND (url LIKE '%' || :query || '%' OR method LIKE '%' || :query || '%' OR CAST(status AS TEXT) LIKE '%' || :query || '%')
        AND (:method IS NULL OR method=:method) AND (:pkg IS NULL OR packageName=:pkg)
        AND (:status IS NULL OR status=:status) AND (:errors=0 OR failed=1 OR status>=400)
        ORDER BY startedAt DESC LIMIT :limit""")
    fun exchanges(session: String?, query: String, method: String?, errors: Boolean, pkg: String?, status: Int?, limit: Int): Flow<List<ExchangeEntity>>
    @Query("SELECT * FROM connections WHERE (:session IS NULL OR sessionId=:session) ORDER BY startedAt DESC LIMIT 200")
    fun connections(session: String?): Flow<List<ConnectionEntity>>
    @Query("SELECT * FROM exchanges WHERE id=:id") fun exchange(id: String): Flow<ExchangeEntity?>
    @Query("SELECT * FROM sessions WHERE id=:id") suspend fun session(id: String): SessionEntity?
    @Query("SELECT * FROM connections WHERE id=:id") suspend fun connection(id: String): ConnectionEntity?
    @Query("SELECT * FROM exchanges WHERE sessionId=:id ORDER BY startedAt") suspend fun allExchanges(id: String): List<ExchangeEntity>
    @Query("SELECT * FROM sessions") suspend fun allSessions(): List<SessionEntity>
    @Query("SELECT * FROM connections WHERE sessionId=:id") suspend fun allConnections(id: String): List<ConnectionEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun save(entity: SessionEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun save(entity: ExchangeEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun save(entity: ConnectionEntity)
    @Query("DELETE FROM exchanges WHERE sessionId=:id") suspend fun deleteExchanges(id: String)
    @Query("DELETE FROM connections WHERE sessionId=:id") suspend fun deleteConnections(id: String)
    @Query("DELETE FROM sessions WHERE id=:id") suspend fun deleteSession(id: String)
}
@Database(entities = [SessionEntity::class, ConnectionEntity::class, ExchangeEntity::class], version = 1, exportSchema = true)
internal abstract class CaptureDatabase : RoomDatabase() { abstract fun dao(): CaptureDao }
