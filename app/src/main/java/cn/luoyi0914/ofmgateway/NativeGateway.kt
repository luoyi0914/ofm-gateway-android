package cn.luoyi0914.ofmgateway

/** JNI boundary for the bundled Node/Magpie runtime and its process supervisor. */
object NativeGateway {
    private val loaded = runCatching { System.loadLibrary("ofm_gateway") }.isSuccess

    fun runtimeAvailable(filesDir: String, nativeLibDir: String): Boolean =
        loaded && nativeRuntimeAvailable(filesDir, nativeLibDir)

    fun status(filesDir: String, nativeLibDir: String): String =
        if (loaded) nativeStatus(filesDir, nativeLibDir) else "Rust JNI library missing"

    fun start(filesDir: String, nativeLibDir: String): Boolean =
        loaded && nativeStart(filesDir, nativeLibDir, tzOffsetMinutes())

    fun stop(filesDir: String, nativeLibDir: String): Boolean =
        loaded && nativeStop(filesDir, nativeLibDir)

    /** 当前网关子进程 PID；未运行返回 -1。 */
    fun pid(): Int = if (loaded) nativePid() else -1

    /** Device UTC offset in minutes, so the native supervisor can roll logs at local midnight. */
    private fun tzOffsetMinutes(): Int =
        java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000

    private external fun nativeRuntimeAvailable(filesDir: String, nativeLibDir: String): Boolean
    private external fun nativeStatus(filesDir: String, nativeLibDir: String): String
    private external fun nativeStart(filesDir: String, nativeLibDir: String, tzOffsetMinutes: Int): Boolean
    private external fun nativeStop(filesDir: String, nativeLibDir: String): Boolean
    private external fun nativePid(): Int
}
