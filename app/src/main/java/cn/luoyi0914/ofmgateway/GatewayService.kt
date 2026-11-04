package cn.luoyi0914.ofmgateway

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

class GatewayService : Service() {
    private val nativeDir by lazy { applicationInfo.nativeLibraryDir }
    @Volatile private var worker: Thread? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "OFM Gateway", NotificationManager.IMPORTANCE_LOW),
        )
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("OFM 网关运行中")
            .setContentText("127.0.0.1:15000 · 点按返回应用")
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (worker?.isAlive == true) return START_STICKY
        // 解包 assets 与拉起 Node 子进程都是重 IO，放到工作线程执行，避免阻塞主线程（ANR）。
        worker = Thread({
            val runtime = RuntimeInstaller.ensure(this)
            val started = runtime.isSuccess && NativeGateway.start(filesDir.absolutePath, nativeDir)
            if (!started) stopSelf(startId)
        }, "ofm-gateway-start").also { it.start() }
        return START_STICKY
    }

    override fun onDestroy() {
        worker?.interrupt()
        NativeGateway.stop(filesDir.absolutePath, nativeDir)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL_ID = "ofm_gateway"
        const val NOTIFICATION_ID = 15656
    }
}
