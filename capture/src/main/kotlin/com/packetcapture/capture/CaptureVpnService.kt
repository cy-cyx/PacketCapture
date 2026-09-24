package com.packetcapture.capture

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import androidx.core.app.NotificationCompat

/** Android 生命周期适配层，不包含协议解析、数据库查询或页面逻辑。 */
class CaptureVpnService : VpnService() {
    private val runtime get() = (application as CaptureDependencyProvider).captureRuntime
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "抓包服务", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 1, Intent(this, CaptureVpnService::class.java).setAction(STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 2, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        val notification = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_capture_notification)
            .setContentTitle("本地抓包正在运行").setContentText("仅处理所选应用的流量").setOngoing(true)
            .setContentIntent(open).addAction(0, "停止", stop).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1001, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
        else startForeground(1001, notification)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) runtime.stop() else if (intent?.action == START) runtime.attached(this) else stopSelf()
        return START_NOT_STICKY
    }
    override fun onRevoke() { runtime.stopWithReason("VPN 权限已被撤销"); super.onRevoke() }
    override fun onDestroy() { runtime.destroyed(this); super.onDestroy() }
    companion object {
        const val START = "com.packetcapture.START"
        const val STOP = "com.packetcapture.STOP"
        private const val CHANNEL = "capture-service"
    }
}
