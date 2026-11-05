package cn.luoyi0914.ofmgateway

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo as PlatformServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * 前台服务：宿主 Node 网关进程，并在它意外退出时按退避策略重启。
 *
 * 两条硬规则（来自真机踩坑）：
 *  1. 判活只认原生层的子进程句柄 /「锁 pid + cmdline」，不看端口、不看 UI 文案；
 *  2. 停止 = 先让原生层按进程组收尾，确认无存活后才结束服务，避免留下孤儿 Node。
 */
class GatewayService : Service() {
    private val nativeDir by lazy { applicationInfo.nativeLibraryDir }
    private val filesDirPath by lazy { filesDir.absolutePath }

    @Volatile private var startWorker: Thread? = null
    @Volatile private var watchdog: Thread? = null
    @Volatile private var shouldRun = true

    override fun onCreate() {
        super.onCreate()
        alive = true
        createChannel()
        startForegroundCompat(buildNotification(GatewayStatus.READY))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shouldRun = false
            stopGatewayAsync { stopSelf(startId) }
            return START_NOT_STICKY
        }
        shouldRun = true
        if (startWorker?.isAlive != true) {
            // 解包 assets 与拉起 Node 都是重 IO，必须离开主线程，否则会 ANR。
            startWorker = Thread({ firstStart() }, "ofm-start").also { it.start() }
        }
        if (watchdog?.isAlive != true) {
            watchdog = Thread({ watchLoop() }, "ofm-watchdog").also { it.start() }
        }
        return START_STICKY
    }

    private fun firstStart() {
        val installed = RuntimeInstaller.ensure(this)
        if (installed.isFailure) {
            notifyState(GatewayStatus.RUNTIME_MISSING)
            stopSelf()
            return
        }
        val status = if (startGateway()) GatewayStatus.RUNNING else
            NativeGateway.status(filesDirPath, nativeDir)
        notifyState(status)
        if (!status.usable) stopSelf()
    }

    /**
     * 看门狗：每 5 秒核对一次真实状态。
     *
     * 只在「本该运行却已停止」时重启，且退避递增（5s/10s/20s/40s/60s 上限），
     * 避免运行时损坏时无限快循环把电池和日志打爆。
     */
    private fun watchLoop() {
        var backoffMs = 5_000L
        var lastRestartAt = 0L
        while (shouldRun) {
            Thread.sleep(5_000)
            if (!shouldRun) break
            val status = NativeGateway.status(filesDirPath, nativeDir)
            if (status.running) {
                backoffMs = 5_000L
                notifyState(GatewayStatus.RUNNING)
                continue
            }
            if (status.severity != Severity.NEUTRAL && status.severity != Severity.OK) {
                // 运行时缺失/路径无效这类问题重启也没用，如实显示，不假装在恢复。
                notifyState(status)
                continue
            }
            val now = System.currentTimeMillis()
            if (now - lastRestartAt < backoffMs) continue
            lastRestartAt = now
            if (startGateway()) {
                backoffMs = 5_000L
                notifyState(GatewayStatus.RUNNING)
            } else {
                notifyState(GatewayStatus.INTERNAL_ERROR)
                backoffMs = (backoffMs * 2).coerceAtMost(60_000L)
            }
        }
    }

    private fun startGateway(): Boolean {
        val port = ServiceInfo.apiPort(filesDirPath)
        return NativeGateway.start(filesDirPath, nativeDir, port)
    }

    private fun stopGatewayAsync(onDone: () -> Unit) {
        Thread({
            NativeGateway.stop(filesDirPath, nativeDir)
            onDone()
        }, "ofm-stop").start()
    }

    override fun onDestroy() {
        alive = false
        shouldRun = false
        startWorker?.interrupt()
        watchdog?.interrupt()
        NativeGateway.stop(filesDirPath, nativeDir)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /* --------------------------- 通知 --------------------------- */

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "OFM Gateway", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun buildNotification(status: GatewayStatus): Notification {
        val port = ServiceInfo.apiPort(filesDirPath)
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, GatewayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = if (status == GatewayStatus.RUNNING) "127.0.0.1:$port · 运行中" else status.label
        return Notification.Builder(this, CHANNEL_ID)
            // 自带的单色小图标：系统「上传完成」图标会让用户误以为是别的任务。
            .setSmallIcon(cn.luoyi0914.ofmgateway.R.drawable.ic_notification)
            .setContentTitle("OFM 网关")
            .setContentText(text)
            .setContentIntent(contentIntent)
            .addAction(Notification.Action.Builder(null, "停止", stopIntent).build())
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }

    /** 状态变化时更新常驻通知；文案跟着真实状态走，不写死「运行中」。 */
    private fun notifyState(status: GatewayStatus) {
        runCatching {
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(NOTIFICATION_ID, buildNotification(status))
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, PlatformServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        /** 服务是否在运行中；用于区分「发停止指令」与「直接收尾」两条路径。 */
        @Volatile
        private var alive = false

        const val CHANNEL_ID = "ofm_gateway"
        const val NOTIFICATION_ID = 15656
        const val ACTION_STOP = "cn.luoyi0914.ofmgateway.action.STOP"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, GatewayService::class.java))
        }

        /**
         * 请求停止。
         *
         * 服务在线时发 action 让它自己收尾；不在线时不要 `startService`——
         * 那会把服务重新拉起来只为立刻停掉，且在后台会触发启动限制。
         */
        fun requestStop(context: Context) {
            if (!alive) {
                val filesDir = context.filesDir.absolutePath
                NativeGateway.stop(filesDir, context.applicationInfo.nativeLibraryDir)
                return
            }
            context.startService(
                Intent(context, GatewayService::class.java).setAction(ACTION_STOP),
            )
        }

        fun isAlive(): Boolean = alive
    }
}
