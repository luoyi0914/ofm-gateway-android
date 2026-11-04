package cn.luoyi0914.ofmgateway

/** Privileged execution remains optional; every provider must report real capability. */
enum class BackendKind { FOREGROUND, SHIZUKU, ROOT }

data class BackendState(val kind: BackendKind, val available: Boolean, val detail: String)

interface PrivilegedBackend {
    fun state(): BackendState
    fun start(command: List<String>): Result<Unit>
    fun stop(processId: Long): Result<Unit>
}

class ShizukuBackend : PrivilegedBackend {
    override fun state() = BackendState(BackendKind.SHIZUKU, false, "Shizuku provider not integrated")
    override fun start(command: List<String>) = Result.failure<Unit>(UnsupportedOperationException("Shizuku provider not integrated"))
    override fun stop(processId: Long) = Result.failure<Unit>(UnsupportedOperationException("Shizuku provider not integrated"))
}

class RootBackend : PrivilegedBackend {
    override fun state() = BackendState(BackendKind.ROOT, false, "Root provider not integrated")
    override fun start(command: List<String>) = Result.failure<Unit>(UnsupportedOperationException("Root provider not integrated"))
    override fun stop(processId: Long) = Result.failure<Unit>(UnsupportedOperationException("Root provider not integrated"))
}
