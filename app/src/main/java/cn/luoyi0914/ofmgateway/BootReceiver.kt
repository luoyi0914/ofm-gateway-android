package cn.luoyi0914.ofmgateway

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        // 开机启动前台服务在某些系统版本可能被限制，失败时静默忽略，不影响系统。
        runCatching { context.startForegroundService(Intent(context, GatewayService::class.java)) }
    }
}
