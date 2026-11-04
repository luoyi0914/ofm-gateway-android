package cn.luoyi0914.ofmgateway

import android.content.Context
import java.io.File

object RuntimeInstaller {
    private const val APP_DIR = "ofm-runtime"

    /**
     * 确保 assets/app 已解包到 filesDir/ofm-runtime/app。
     *
     * 解包标记绑定 versionCode：应用升级后必定重新解包，避免旧 assets/补丁被跳过。
     * 只重建 `app/` 子树，保留 `logs/`（历史日志）与 `tmp/`；用户数据在 `files/ofm-data`，不受影响。
     * 方法加锁：MainActivity 与 GatewayService 可能并发调用，避免重复解包相互破坏。
     */
    @Synchronized
    fun ensure(context: Context): Result<File> = runCatching {
        val root = File(context.filesDir, APP_DIR)
        val app = File(root, "app")
        @Suppress("DEPRECATION")
        val versionCode = context.packageManager.getPackageInfo(context.packageName, 0).versionCode
        val marker = File(root, ".installed-v$versionCode")
        if (!marker.isFile || !File(app, "packages/standalone/cli.mjs").isFile) {
            app.deleteRecursively()
            root.listFiles()?.forEach { if (it.name.startsWith(".installed-")) it.delete() }
            app.mkdirs()
            copyTree(context, "app", app)
            marker.writeText("1\n")
        }
        root
    }

    private fun copyTree(context: Context, assetPath: String, destination: File) {
        val children = context.assets.list(assetPath) ?: emptyArray()
        if (children.isEmpty()) {
            context.assets.open(assetPath).use { input ->
                destination.parentFile?.mkdirs()
                destination.outputStream().use { output -> input.copyTo(output) }
            }
            return
        }
        destination.mkdirs()
        for (child in children) copyTree(context, "$assetPath/$child", File(destination, child))
    }
}
