package cn.luoyi0914.ofmgateway

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 界面渲染所需的全部状态：单一快照，避免各页各拿一份导致互相打架。 */
data class GatewayUiState(
    val status: GatewayStatus = GatewayStatus.UNKNOWN,
    val runtimeInstalled: Boolean = false,
    val installError: String? = null,
    val pid: Int = -1,
    val port: Int = ServiceInfo.DEFAULT_PORT,
    val version: String? = null,
    val apiKey: String? = null,
    val managementKey: String? = null,
    val loginUrl: String? = null,
    val bootstrapAgeMs: Long = -1,
    val days: List<String> = emptyList(),
    val selectedDay: String? = null,
    val logText: String = "",
    val logLevel: LogStore.Level = LogStore.Level.ALL,
    val probe: String? = null,
    val bootAuto: Boolean = false,
    val appVersion: String = "",
    val notificationGranted: Boolean = true,
    val batteryOptimized: Boolean = false,
    val busy: Boolean = false,
    /** 新版本提示；null 表示已是最新或尚未检查。 */
    val newerRelease: UpdateChecker.Release? = null,
    /** 用户选定的推荐模型：用于接入片段与「复制接入信息」，不影响网关本身。 */
    val preferredModel: String? = null,
) {
    val running: Boolean get() = status.running
    val canStart: Boolean get() = runtimeInstalled && !running && !busy
}

/**
 * 状态仓储：所有磁盘/网络/原生调用都从这里出去，并且都在 IO 线程。
 *
 * 之前这些调用散在 Composable 的 `LaunchedEffect` 里，主线程会被 `File.readText()`、
 * 日志尾部读取和 `NativeGateway.status()` 一起拖住；现在只保留一个轮询循环和一个
 * 显式的 `refresh`，界面只管订阅 [state]。
 */
class GatewayRepository(private val context: Context) {
    // 每次 start() 重建作用域：stop() 会 cancel 掉上一个，复用会让后续 launch 静默失效。
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val filesDirPath: String = context.filesDir.absolutePath
    private val nativeDir: String = context.applicationInfo.nativeLibraryDir

    private val prefs = context.getSharedPreferences("ofm_ui", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(GatewayUiState())
    val state: StateFlow<GatewayUiState> = _state.asStateFlow()

    private companion object {
        const val KEY_MODEL = "preferred_model"
    }

    private var pollJob: Job? = null
    // 轮询与手动刷新会同时触发；串行化避免两次轮询各自读一遍日志与锁文件。
    private val refreshLock = Mutex()

    fun start() {
        if (pollJob?.isActive == true) return
        if (scope.coroutineContext[Job]?.isActive != true) {
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        }
        pollJob = scope.launch {
            // 首帧：先把本地已知信息填上，避免界面空白几秒。
            refreshStatic()
            while (true) {
                refreshRuntime()
                delay(3_000)
            }
        }
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
        scope.cancel()
    }

    /** 一次性读取（版本、开关、凭据等变化很慢的信息）。 */
    private suspend fun refreshStatic() = withContext(Dispatchers.IO) {
        val appVersion = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "?"
        val bootAuto = ServiceInfo.isBootAutoStart(context)
        val granted = if (android.os.Build.VERSION.SDK_INT >= 33) {
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        } else true
        val preferredModel = prefs.getString(KEY_MODEL, null)?.takeIf { it.isNotBlank() }
        val optimizing = runCatching {
            val pm = context.getSystemService(android.os.PowerManager::class.java)
            pm?.isIgnoringBatteryOptimizations(context.packageName) != true
        }.getOrDefault(true)
        _state.update {
            it.copy(
                appVersion = appVersion,
                bootAuto = bootAuto,
                notificationGranted = granted,
                batteryOptimized = optimizing,
                preferredModel = preferredModel,
            )
        }
    }

    /** 每 3 秒刷新运行时状态、日志与凭据。 */
    fun refresh() = scope.launch { refreshRuntime() }

    private suspend fun refreshRuntime() = refreshLock.withLock {
        withContext(Dispatchers.IO) {
            val install = RuntimeInstaller.ensure(context)
            val status = if (install.isFailure) GatewayStatus.RUNTIME_MISSING
            else NativeGateway.status(filesDirPath, nativeDir)
            val pid = if (install.isSuccess) runCatching { NativeGateway.pid() }.getOrDefault(-1) else -1
            val port = ServiceInfo.apiPort(filesDirPath)
            val days = LogStore.listDays(filesDirPath)
            val current = _state.value
            val day = current.selectedDay?.takeIf { it in days } ?: days.firstOrNull()
            val logText = day?.let { LogStore.readDay(filesDirPath, it) } ?: ""
            // 版本与登录链接只需要日志尾部：一次读取，避免同一轮里把文件读两遍。
            val tail = day?.let { LogStore.readDay(filesDirPath, it, 20_000) } ?: ""
            // 日志里的链接已被脱敏成 #login=***，真实令牌从原生层 0600 文件读。
            val loginUrl = ServiceInfo.loginUrl(filesDirPath, port, tail)
            val version = ServiceInfo.findVersion(tail)
            _state.update {
                it.copy(
                    runtimeInstalled = install.isSuccess,
                    installError = install.exceptionOrNull()?.message,
                    status = status,
                    pid = pid,
                    port = port,
                    days = days,
                    selectedDay = day,
                    logText = logText,
                    loginUrl = loginUrl,
                    bootstrapAgeMs = ServiceInfo.bootstrapTokenAgeMs(filesDirPath),
                    version = version ?: it.version,
                    apiKey = ServiceInfo.readApiKey(filesDirPath) ?: it.apiKey,
                    managementKey = ServiceInfo.readManagementKey(filesDirPath) ?: it.managementKey,
                )
            }
        }
    }

    fun selectDay(day: String) {
        _state.update { it.copy(selectedDay = day, logText = "（读取中…）") }
        scope.launch {
            val text = withContext(Dispatchers.IO) { LogStore.readDay(filesDirPath, day) }
            _state.update { if (it.selectedDay == day) it.copy(logText = text) else it }
        }
    }

    fun setLogLevel(level: LogStore.Level) = _state.update { it.copy(logLevel = level) }

    fun startGateway() {
        _state.update { it.copy(busy = true) }
        scope.launch {
            GatewayService.start(context)
            // 解包 + 启动需要时间，给界面一个明确的「进行中」再刷新。
            delay(1_500)
            refresh()
            _state.update { it.copy(busy = false) }
        }
    }

    fun stopGateway() {
        _state.update { it.copy(busy = true) }
        scope.launch {
            // 只发停止指令，由服务线程按「进程组收尾 → 确认无存活 → stopSelf」执行。
            // 界面这边先自己 kill 会和服务看门狗抢跑：看门狗看到「未运行」会立刻重启，
            // 于是用户点了停止却又起来一个进程。
            GatewayService.requestStop(context)
            delay(1_200)
            refresh()
            _state.update { it.copy(busy = false) }
        }
    }

    fun probePort() {
        scope.launch {
            val port = _state.value.port
            val result = withContext(Dispatchers.IO) { Diagnostics.probePort(port) }
            _state.update { it.copy(probe = result) }
        }
    }

    fun setBootAuto(enabled: Boolean) {
        ServiceInfo.setBootAutoStart(context, enabled)
        _state.update { it.copy(bootAuto = enabled) }
    }

    fun reportLog(): String = LogStore.reportLog(filesDirPath, _state.value.selectedDay)

    fun diagnostic(): String = Diagnostics.build(context, _state.value)

    /** 选/取消推荐模型；只影响接入片段，网关不关心这个值。 */
    fun setPreferredModel(model: String?) {
        prefs.edit().apply { if (model.isNullOrBlank()) remove(KEY_MODEL) else putString(KEY_MODEL, model) }.apply()
        _state.update { it.copy(preferredModel = model?.takeIf { m -> m.isNotBlank() }) }
    }

    /** 生成某个客户端形态的接入片段；纯本地拼装，不发请求。 */
    fun snippet(client: AccessSnippet.Client): String = with(_state.value) {
        AccessSnippet.build(client, ServiceInfo.baseUrl(port), apiKey.orEmpty(), preferredModel)
    }

    /** 三项清单（地址/密钥/模型），复制到剪贴板用。 */
    fun accessList(): String = with(_state.value) {
        AccessSnippet.compact(ServiceInfo.baseUrl(port), apiKey.orEmpty(), preferredModel)
    }

    /**
     * 检查更新（进设置页时调用，12 小时内不重复打 API）。
     *
     * 只做提示：常驻服务不自动下载安装，避免在用户不知情时替换正在运行的组件。
     */
    fun checkUpdate(force: Boolean = false) {
        if (!force && !UpdateChecker.shouldCheck(context)) return
        scope.launch {
            val current = _state.value.appVersion
            val result = withContext(Dispatchers.IO) { UpdateChecker.fetchLatest() }
            result.onSuccess { release ->
                UpdateChecker.markChecked(context)
                _state.update {
                    it.copy(newerRelease = release.takeIf { r -> UpdateChecker.isNewer(r.version, current) })
                }
            }
        }
    }
}
