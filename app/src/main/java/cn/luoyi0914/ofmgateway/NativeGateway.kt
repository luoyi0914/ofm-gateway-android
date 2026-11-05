package cn.luoyi0914.ofmgateway

/**
 * JNI boundary for the bundled Node/Magpie runtime and its process supervisor.
 *
 * The native layer returns *stable ASCII keys* (`running`, `ready`, `node_missing`, …)
 * rather than localised sentences, so presentation can never silently depend on
 * string matching. `GatewayStatus.fromKey` is the single mapping point.
 */
object NativeGateway {
    private val loaded = runCatching { System.loadLibrary("ofm_gateway") }.isSuccess

    fun isLoaded(): Boolean = loaded

    fun runtimeAvailable(filesDir: String, nativeLibDir: String): Boolean =
        loaded && nativeRuntimeAvailable(filesDir, nativeLibDir)

    /** Raw status key; `jni_missing` when the library failed to load. */
    fun statusKey(filesDir: String, nativeLibDir: String): String =
        if (loaded) nativeStatus(filesDir, nativeLibDir) else "jni_missing"

    fun status(filesDir: String, nativeLibDir: String): GatewayStatus =
        GatewayStatus.fromKey(statusKey(filesDir, nativeLibDir))

    fun start(filesDir: String, nativeLibDir: String, port: Int): Boolean =
        loaded && nativeStart(filesDir, nativeLibDir, tzOffsetMinutes(), port)

    fun stop(filesDir: String, nativeLibDir: String): Boolean =
        loaded && nativeStop(filesDir, nativeLibDir)

    /** pid of the running gateway (own child or adopted instance); -1 when idle. */
    fun pid(): Int = if (loaded) nativePid() else -1

    /** Device UTC offset in minutes, so the native supervisor can roll logs at local midnight. */
    private fun tzOffsetMinutes(): Int =
        java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000

    private external fun nativeRuntimeAvailable(filesDir: String, nativeLibDir: String): Boolean
    private external fun nativeStatus(filesDir: String, nativeLibDir: String): String
    private external fun nativeStart(
        filesDir: String,
        nativeLibDir: String,
        tzOffsetMinutes: Int,
        port: Int,
    ): Boolean
    private external fun nativeStop(filesDir: String, nativeLibDir: String): Boolean
    private external fun nativePid(): Int
}
