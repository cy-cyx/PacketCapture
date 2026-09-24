package com.packetcapture.core

import java.io.OutputStream
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * 抓包运行边界。调用 start 前由平台取得 VPN 授权，配置不能为空；启停调用可来自任意线程。
 * 方法只提交操作，实际结果由 state 发布；重复启停幂等，运行资源归前台服务而非页面所有。
 */
interface CaptureController {
    val state: StateFlow<CaptureState>
    fun start(config: CaptureConfig)
    fun stop()
}

/** 持久化边界：实现必须在 IO 线程执行磁盘操作；列表只返回指定窗口，正文不进入列表。 */
interface CaptureRepository {
    fun sessions(query: String = ""): Flow<List<CaptureSession>>
    fun exchanges(sessionId: String?, filter: ExchangeFilter = ExchangeFilter(), limit: Int = 200): Flow<List<HttpExchange>>
    fun connections(sessionId: String?): Flow<List<ConnectionRecord>>
    fun exchange(id: String): Flow<HttpExchange?>
    suspend fun session(id: String): CaptureSession?
    suspend fun connection(id: String): ConnectionRecord?
    suspend fun allExchanges(sessionId: String): List<HttpExchange>
    suspend fun saveSession(session: CaptureSession)
    suspend fun saveConnection(connection: ConnectionRecord)
    suspend fun saveExchange(exchange: HttpExchange)
    suspend fun deleteSession(id: String)
    suspend fun recoverInterrupted()
}

/**
 * 正文接收端。append 不执行磁盘 IO，不得阻塞转发线程；调用方移交字节数组后不能再修改。
 * 建议每块不超过 16 KiB。finish 可挂起，须在非网络事件线程调用，并等待已入队块提交；重复调用返回同一结果。
 */
interface BodySink {
    fun append(bytes: ByteArray)
    suspend fun finish(): BodyRef
}
/** 正文文件归实现管理，UI 只使用不透明 BodyRef。所有 suspend 读取/删除在实现的 IO 调度器执行。 */
interface BodyStore {
    fun open(sessionId: String, exchangeId: String, part: BodyPart, limit: Long, totalLimit: Long): BodySink
    suspend fun read(ref: BodyRef, maxBytes: Int = DEFAULT_BODY_LIMIT.toInt()): ByteArray
    suspend fun preview(ref: BodyRef?, headers: List<Header>): BodyPreview
    suspend fun deleteSession(sessionId: String)
    suspend fun usedBytes(): Long
    /** 启动恢复时清理未提交文件和已删除会话目录；只能在采集启动前调用。 */
    suspend fun recoverOrphans(liveSessionIds: Set<String>)
}
/** 设置以完整快照原子保存；update 可从主线程调用，但实现不得在主线程做磁盘 IO。 */
interface SettingsRepository {
    val settings: Flow<AppSettings>
    suspend fun update(settings: AppSettings)
}

/** 只导出公开证书；CA 私钥的生成、加密和加载留在平台实现中，调用可能触发 IO。 */
interface CertificateManager {
    suspend fun ensureCertificate(): CertificateInfo
    suspend fun exportCertificate(output: OutputStream)
}
/** 导出可挂起并在 IO 线程执行。输出流由调用方打开和关闭；实现只写入及 flush，不关闭外部流。 */
interface ExportService {
    suspend fun har(exchanges: List<HttpExchange>, output: OutputStream, options: ExportOptions = ExportOptions())
    suspend fun curl(exchange: HttpExchange, options: ExportOptions = ExportOptions()): CurlExport
}
