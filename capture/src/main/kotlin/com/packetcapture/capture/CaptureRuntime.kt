package com.packetcapture.capture

import android.content.Context
import android.content.Intent
import android.net.*
import android.os.Build
import android.os.ParcelFileDescriptor
import com.packetcapture.core.*
import com.packetcapture.capture.crypto.LocalCertificateAuthority
import com.packetcapture.capture.engine.*
import com.packetcapture.capture.proxy.LocalProxy
import com.packetcapture.capture.proxy.Socks5Udp
import com.packetcapture.capture.proxy.SocksUdpRelay
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import android.system.Os
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

interface CaptureDependencyProvider { val captureRuntime: CaptureRuntime }

/** Application 级运行容器：Service 只适配 Android 生命周期，所有启动、清理均经同一互斥锁串行化。 */
class CaptureRuntime(
    context: Context,
    private val repository: CaptureRepository,
    private val bodies: BodyStore,
    private val certificates: LocalCertificateAuthority,
) : CaptureController {
    private val context = context.applicationContext
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val lifecycle = CaptureLifecycle()
    private val mutableState = MutableStateFlow(CaptureState())
    override val state = mutableState.asStateFlow()
    private val stopping = AtomicBoolean(false)
    private val generation = AtomicLong()
    @Volatile private var requested = CaptureConfig()
    private var service: CaptureVpnService? = null
    private var network: Network? = null
    private var session: CaptureSession? = null
    private var tun: ParcelFileDescriptor? = null
    private var wake: ParcelFileDescriptor? = null
    private var nativeJob: Job? = null
    private var statsJob: Job? = null
    private var native: NativeBridge? = null
    private var proxy: LocalProxy? = null
    private var udpProxy: SocksUdpRelay? = null
    private var recorder: TrafficRecorder? = null
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var networkChangeJob: Job? = null
    @Synchronized private fun phase(next: CapturePhase, error: String? = null) {
        lifecycle.transition(next)
        mutableState.update { it.copy(phase = next, error = error) }
    }
    @Synchronized override fun start(config: CaptureConfig) {
        if (state.value.phase !in setOf(CapturePhase.IDLE, CapturePhase.FAILED)) return
        phase(CapturePhase.STARTING)
        mutableState.update { it.copy(upstreamProxy = config.upstreamProxy) }
        if (config.packages.isEmpty()) { phase(CapturePhase.FAILED, "请先选择至少一个测试应用"); return }
        if (context.packageName in config.packages || config.upstreamProxy.enabled && "com.v2ray.ang" in config.packages) {
            phase(CapturePhase.FAILED, "目标应用不能包含抓包应用自身；使用代理时也不能选择 v2rayNG，以免流量循环"); return
        }
        if (VpnService.prepare(context) != null) { phase(CapturePhase.FAILED, "需要先授予 VPN 权限"); return }
        requested = config
        stopping.set(false)
        try { context.startForegroundService(Intent(context, CaptureVpnService::class.java).setAction(CaptureVpnService.START)) }
        catch (e: Exception) { phase(CapturePhase.FAILED, "无法启动抓包服务: ${e.message}") }
    }
    override fun stop() = stopWithReason(null)
    override suspend fun clearStorage() = withContext(NonCancellable) {
        mutex.withLock {
            check(state.value.phase in setOf(CapturePhase.IDLE, CapturePhase.FAILED)) { "请先停止抓包后再清空存储" }
            repository.clearStorage()
            mutableState.update { it.copy(sessionId = null, startedAt = null, uploadedBytes = 0,
                downloadedBytes = 0, recordingWarning = null) }
        }
    }
    internal fun stopWithReason(reason: String?) {
        stopping.set(true)
        scope.launch { mutex.withLock { cleanup(reason, true) } }
    }
    internal fun attached(service: CaptureVpnService) {
        scope.launch {
            mutex.withLock {
                if (this@CaptureRuntime.service != null || stopping.get()) { if (stopping.get()) service.stopSelf(); return@withLock }
                this@CaptureRuntime.service = service
                try { startEngine(service) }
                catch (e: Exception) { cleanup("启动失败: ${e.message ?: e.javaClass.simpleName}", true) }
            }
        }
    }
    internal fun destroyed(instance: CaptureVpnService) {
        if (service === instance) stopWithReason("抓包服务已结束")
    }
    private fun selectNetwork(): Network {
        return connectivity.allNetworks.filter { n ->
            connectivity.getNetworkCapabilities(n)?.let { it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) } == true
        }.sortedByDescending { n ->
            connectivity.getNetworkCapabilities(n)?.let {
                (if (it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) 2 else 0) +
                    (if (it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) 1 else 0)
            } ?: 0
        }.firstOrNull() ?: error("没有可用的 Wi-Fi 或移动网络")
    }
    private suspend fun startEngine(service: CaptureVpnService) {
        if (state.value.phase != CapturePhase.STARTING) phase(CapturePhase.STARTING)
        val config = requested
        if (config.upstreamProxy.enabled) Socks5Udp.probe(config.upstreamProxy) { service.protect(it) }
        val underlying = selectNetwork()
        network = underlying
        val captureSession = CaptureSession(title = config.packages.map { pkg ->
            runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
        }.joinToString("、"), packages = config.packages.toList())
        repository.saveSession(captureSession)
        session = captureSession
        mutableState.value = CaptureState(CapturePhase.STARTING, captureSession.id, captureSession.startedAt, upstreamProxy = config.upstreamProxy)
        if (config.decryptHttps) certificates.ensureCertificate()
        check(!stopping.get()) { "启动已取消" }
        val writer = TrafficRecorder(repository, bodies, config, captureSession.id) { message ->
            mutableState.update { it.copy(recordingWarning = message) }
        }
        recorder = writer
        val registry = ConnectionRegistry(captureSession.id, ::owner, writer, config.upstreamProxy.enabled)
        val udp = if (config.upstreamProxy.enabled) SocksUdpRelay(config.upstreamProxy, { service.protect(it) }, { service.protect(it) }, registry::failed) else null
        udpProxy = udp
        val local = LocalProxy(service, underlying, config, registry, writer, certificates)
        proxy = local
        val port = local.start()
        val builder = service.Builder().setSession("本地抓包").setMtu(1500)
            .addAddress("10.111.0.1", 32).addAddress("fd00:7063:6170::1", 128)
            .addRoute("0.0.0.0", 0).addRoute("::", 0).setBlocking(false)
        // 白名单为空会变成全设备 VPN，所以 start 已禁止空选择；配置变更只在下一次启动生效。
        config.packages.forEach { builder.addAllowedApplication(it) }
        // 公共 DNS 也通过 SOCKS5 UDP 中继，避免代理出口访问不了当前 Wi-Fi 的私有 DNS。
        if (config.upstreamProxy.enabled) { builder.addDnsServer("1.1.1.1"); builder.addDnsServer("8.8.8.8") }
        else connectivity.getLinkProperties(underlying)?.dnsServers?.forEach { builder.addDnsServer(it) }
        if (Build.VERSION.SDK_INT >= 29) builder.setMetered(connectivity.getNetworkCapabilities(underlying)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) != true)
        check(!stopping.get()) { "启动已取消" }
        val descriptor = builder.establish() ?: error("VPN 权限被撤销")
        tun = descriptor
        service.setUnderlyingNetworks(arrayOf(underlying))
        val pipe = ParcelFileDescriptor.createPipe()
        wake = pipe[1]
        val bridge = NativeBridge(service, registry, udp)
        native = bridge
        val fd = ParcelFileDescriptor.dup(descriptor.fileDescriptor).detachFd()
        val wakeFd = pipe[0].detachFd()
        val runId = generation.incrementAndGet()
        nativeJob = scope.launch {
            val result = bridge.run(fd, wakeFd, port, local.username, local.password, underlying.networkHandle, config.upstreamProxy.enabled)
            if (generation.get() == runId && !stopping.get()) stopWithReason("流量转发引擎意外结束（$result）")
        }
        phase(CapturePhase.CAPTURING)
        statsJob = scope.launch {
            while (isActive) {
                delay(1000)
                val snapshot = captureSession.copy(uploadedBytes = bridge.uploaded.get(), downloadedBytes = bridge.downloaded.get(), requestCount = writer.count.get())
                session = snapshot
                mutableState.update { it.copy(uploadedBytes = snapshot.uploadedBytes, downloadedBytes = snapshot.downloadedBytes) }
                runCatching { repository.saveSession(snapshot) }.onFailure { mutableState.update { it.copy(recordingWarning = "会话保存失败") } }
            }
        }
        registerNetworkMonitor()
    }
    private fun owner(source: String, sourcePort: Int, target: String, targetPort: Int, protocol: Int): Pair<Int?, String?> {
        if (Build.VERSION.SDK_INT < 29 || protocol !in setOf(6, 17)) return null to null
        return runCatching {
            val uid = connectivity.getConnectionOwnerUid(protocol, InetSocketAddress(source, sourcePort), InetSocketAddress(target, targetPort))
            if (uid < 0) null to null else uid to context.packageManager.getPackagesForUid(uid)?.firstOrNull { it in requested.packages }
        }.getOrDefault(null to null)
    }
    private fun registerNetworkMonitor() {
        if (callback != null) return
        callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = scheduleNetworkCheck()
            override fun onLost(network: Network) = scheduleNetworkCheck()
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = scheduleNetworkCheck()
        }.also { connectivity.registerNetworkCallback(NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), it) }
    }
    @Synchronized private fun scheduleNetworkCheck() {
        networkChangeJob?.cancel()
        networkChangeJob = scope.launch {
            delay(1000)
            mutex.withLock {
                if (state.value.phase != CapturePhase.CAPTURING || stopping.get()) return@withLock
                val next = runCatching { selectNetwork() }.getOrNull()
                if (next != network) {
                    val currentService = service ?: return@withLock
                    cleanup("底层网络变化，旧连接已中断", false)
                    if (next == null) { phase(CapturePhase.STARTING); phase(CapturePhase.FAILED, "网络不可用，请恢复网络后重新开始"); service = null; currentService.stopSelf() }
                    else {
                        try { startEngine(currentService) } catch (e: Exception) { cleanup("网络重连失败: ${e.message}", true) }
                    }
                }
            }
        }
    }
    private suspend fun cleanup(reason: String?, stopService: Boolean) {
        if (service == null && session == null) {
            if (state.value.phase == CapturePhase.STARTING) { phase(CapturePhase.STOPPING); phase(CapturePhase.IDLE) }
            return
        }
        phase(CapturePhase.STOPPING)
        generation.incrementAndGet()
        statsJob?.cancelAndJoin(); statsJob = null
        callback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }; callback = null
        // 先唤醒并等待 native 释放其 fd 副本，再关闭 Service 持有的 TUN；每个 fd 只关闭一次。
        wake?.let { runCatching { Os.write(it.fileDescriptor, byteArrayOf(1), 0, 1) }; runCatching { it.close() } }; wake = null
        nativeJob?.join(); nativeJob = null
        runCatching { tun?.close() }; tun = null
        runCatching { proxy?.close() }; proxy = null
        runCatching { udpProxy?.close() }; udpProxy = null
        runCatching { recorder?.close() }
        session?.let {
            val final = it.copy(endedAt = System.currentTimeMillis(), completion = if (reason == null) Completion.COMPLETE else Completion.INTERRUPTED,
                uploadedBytes = native?.uploaded?.get() ?: it.uploadedBytes, downloadedBytes = native?.downloaded?.get() ?: it.downloadedBytes,
                requestCount = recorder?.count?.get() ?: it.requestCount)
            runCatching { repository.saveSession(final) }
        }
        session = null; recorder = null; native = null; network = null
        phase(CapturePhase.IDLE)
        if (stopService) {
            val current = service; service = null
            current?.stopForeground(android.app.Service.STOP_FOREGROUND_REMOVE); current?.stopSelf()
            if (reason != null) { phase(CapturePhase.STARTING); phase(CapturePhase.FAILED, reason) }
        }
    }
}
