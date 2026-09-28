package com.packetcapture

import android.app.Application
import com.packetcapture.capture.CaptureDependencyProvider
import com.packetcapture.capture.CaptureRuntime
import com.packetcapture.capture.crypto.LocalCertificateAuthority
import com.packetcapture.core.*
import com.packetcapture.data.*
import com.packetcapture.export.DocumentExporter
import kotlinx.coroutines.*

/** 唯一的依赖装配入口。页面只拿 core 接口，具体实现之间通过构造函数注入。 */
class AppContainer(application: Application) {
    val bodies: BodyStore = FileBodyStore(application)
    val repository: CaptureRepository = RoomCaptureRepository(application, bodies)
    val settings: SettingsRepository = PreferenceSettings(application)
    private val authority = LocalCertificateAuthority(application)
    val certificates: CertificateManager = authority
    val exports: ExportService = TrafficExporter(bodies)
    val documents = DocumentExporter(repository, bodies, certificates, exports)
    val runtime = CaptureRuntime(application, repository, bodies, authority)
    val controller: CaptureController = runtime
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val ready = scope.async { repository.recoverInterrupted() }
}
class PacketCaptureApp : Application(), CaptureDependencyProvider {
    lateinit var container: AppContainer
        private set
    override val captureRuntime get() = container.runtime
    override fun onCreate() { super.onCreate(); container = AppContainer(this) }
}
