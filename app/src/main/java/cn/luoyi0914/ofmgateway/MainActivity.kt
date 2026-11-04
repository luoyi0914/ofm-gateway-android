package cn.luoyi0914.ofmgateway

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/* --------------------------- 品牌 Token（消除 M3 默认紫） --------------------------- */

private val Accent = Color(0xFF3B5BDB)

private val LightScheme = lightColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE1FF),
    onPrimaryContainer = Color(0xFF00174B),
    secondary = Color(0xFF565E71),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCE2F9),
    onSecondaryContainer = Color(0xFF131C2B),
    tertiary = Color(0xFF585E71),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFDDE2F9),
    onTertiaryContainer = Color(0xFF151B2C),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    background = Color(0xFFFBFBFF),
    onBackground = Color(0xFF1A1B21),
    surface = Color(0xFFFBFBFF),
    onSurface = Color(0xFF1A1B21),
    surfaceVariant = Color(0xFFE1E2EC),
    onSurfaceVariant = Color(0xFF45464F),
    outline = Color(0xFF757780),
    outlineVariant = Color(0xFFC5C6D0),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF5F4FA),
    surfaceContainer = Color(0xFFEFEDF4),
    surfaceContainerHigh = Color(0xFFE9E7EF),
    surfaceContainerHighest = Color(0xFFE3E2EA),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFB4C5FF),
    onPrimary = Color(0xFF08218A),
    primaryContainer = Color(0xFF2A46C0),
    onPrimaryContainer = Color(0xFFDCE1FF),
    secondary = Color(0xFFBFC6DC),
    onSecondary = Color(0xFF293042),
    secondaryContainer = Color(0xFF3F4759),
    onSecondaryContainer = Color(0xFFDBE2F9),
    tertiary = Color(0xFFBFC6DC),
    onTertiary = Color(0xFF293042),
    tertiaryContainer = Color(0xFF3F4759),
    onTertiaryContainer = Color(0xFFDBE2F9),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF131318),
    onBackground = Color(0xFFE4E1E9),
    surface = Color(0xFF131318),
    onSurface = Color(0xFFE4E1E9),
    surfaceVariant = Color(0xFF45464F),
    onSurfaceVariant = Color(0xFFC5C6D0),
    outline = Color(0xFF8F9099),
    outlineVariant = Color(0xFF45464F),
    surfaceContainerLowest = Color(0xFF0E0E13),
    surfaceContainerLow = Color(0xFF1B1B21),
    surfaceContainer = Color(0xFF1F1F25),
    surfaceContainerHigh = Color(0xFF2A292F),
    surfaceContainerHighest = Color(0xFF35343A),
)

/* 状态语义色 */
private val SuccessLight = Color(0xFF1B873F)
private val SuccessDark = Color(0xFF6FD08C)
private val WarnLight = Color(0xFF8A5300)
private val WarnDark = Color(0xFFFFB95C)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
        setContent {
            val dark = isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme) {
                AppShell(
                    filesDirPath = filesDir.absolutePath,
                    onStart = { startForegroundService(Intent(this, GatewayService::class.java)) },
                    onStop = { stopService(Intent(this, GatewayService::class.java)) },
                )
            }
        }
    }
}

/* ----------------------------- 应用外壳 / 底部导航 ----------------------------- */

@Composable
private fun AppShell(filesDirPath: String, onStart: () -> Unit, onStop: () -> Unit) {
    val context = LocalContext.current
    val nativeDir = context.applicationInfo.nativeLibraryDir
    val scope = rememberCoroutineScope()

    var tab by remember { mutableStateOf(0) }

    var runtimeAvailable by remember { mutableStateOf(false) }
    var rawStatus by remember { mutableStateOf("正在准备运行时…") }
    var days by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedDay by remember { mutableStateOf<String?>(null) }
    var logText by remember { mutableStateOf("") }
    var latestLog by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf<String?>(null) }
    var managementKey by remember { mutableStateOf<String?>(null) }
    var probe by remember { mutableStateOf("尚未检测") }
    var bootAuto by remember { mutableStateOf(false) }
    var pid by remember { mutableStateOf(-1) }
    var port by remember { mutableStateOf(ServiceInfo.apiPort(filesDirPath)) }
    val currentTab by rememberUpdatedState(tab)

    val running = rawStatus == "gateway running"
    val statusText = ServiceInfo.statusLabel(rawStatus)
    val loginUrl = remember(latestLog) { ServiceInfo.findLoginUrl(latestLog) }
    val version = remember(latestLog) { ServiceInfo.findVersion(latestLog) }
    val versionName = remember { BuildConfigCompat.versionName(context) }

    val notify: (String) -> Unit = { msg ->
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }

    LaunchedEffect(Unit) {
        bootAuto = ServiceInfo.isBootAutoStart(context)
        val install = withContext(Dispatchers.IO) { RuntimeInstaller.ensure(context) }
        val installError = install.exceptionOrNull()?.message
        while (true) {
            runtimeAvailable = NativeGateway.runtimeAvailable(filesDirPath, nativeDir)
            rawStatus = when {
                !install.isSuccess -> "运行时准备失败：${installError ?: "解包失败"}"
                runtimeAvailable -> NativeGateway.status(filesDirPath, nativeDir)
                else -> "运行时未就绪：运行库缺失"
            }
            pid = runCatching { NativeGateway.pid() }.getOrDefault(-1)
            port = withContext(Dispatchers.IO) { ServiceInfo.apiPort(filesDirPath) }
            days = withContext(Dispatchers.IO) { LogStore.listDays(filesDirPath) }
            if (selectedDay == null || selectedDay !in days) selectedDay = days.firstOrNull()
            if (currentTab == 2) {
                logText = withContext(Dispatchers.IO) {
                    selectedDay?.let { LogStore.readDay(filesDirPath, it) } ?: Diagnostics.logTail(filesDirPath)
                }
            }
            latestLog = withContext(Dispatchers.IO) {
                val latest = days.firstOrNull()
                if (latest != null) LogStore.readDay(filesDirPath, latest, maxChars = 8_000)
                else Diagnostics.logTail(filesDirPath, maxChars = 8_000)
            }
            apiKey = withContext(Dispatchers.IO) { ServiceInfo.readApiKey(filesDirPath) }
            managementKey = withContext(Dispatchers.IO) { ServiceInfo.readManagementKey(filesDirPath) }
            delay(3000)
        }
    }

    Scaffold(
        bottomBar = { BottomBar(tab) { tab = it } },
    ) { inner ->
        Box(Modifier.fillMaxSize().padding(inner)) {
            when (tab) {
                0 -> HomeScreen(
                    port = port,
                    statusText = statusText,
                    running = running,
                    pid = pid,
                    version = version,
                    apiKey = apiKey,
                    managementKey = managementKey,
                    probe = probe,
                    loginUrl = loginUrl,
                    runtimeAvailable = runtimeAvailable,
                    onStart = {
                        onStart()
                        notify("正在启动网关…")
                    },
                    onStop = {
                        onStop()
                        notify("已请求停止网关")
                    },
                    onProbe = { scope.launch { probe = withContext(Dispatchers.IO) { Diagnostics.probePort(port) } } },
                    onOpenConsole = { openConsole(context, loginUrl ?: ServiceInfo.baseUrl(port)) },
                    onCopy = { label, text ->
                        copyToClipboard(context, label, text)
                        notify("已复制 $label")
                    },
                )
                1 -> ModelsScreen(apiUrl = ServiceInfo.apiUrl(port), apiKey = apiKey, running = running)
                2 -> LogsScreen(
                    filesDirPath = filesDirPath,
                    days = days,
                    selectedDay = selectedDay,
                    logText = logText,
                    onSelectDay = { day ->
                        selectedDay = day
                        scope.launch {
                            logText = withContext(Dispatchers.IO) { LogStore.readDay(filesDirPath, day) }
                        }
                    },
                    onCopy = { text ->
                        copyToClipboard(context, "OFM Gateway 日志", text)
                        notify("日志已复制")
                    },
                    onShare = { text -> shareText(context, "OFM Gateway 日志", text) },
                    onReport = {
                        scope.launch {
                            val diagnostic = buildDiagnostic(context, statusText, runtimeAvailable, probe, selectedDay)
                            val log = withContext(Dispatchers.IO) { LogStore.reportLog(filesDirPath, selectedDay) }
                            openUrl(
                                context,
                                IssueReporter.buildIssueUrl(
                                    IssueReporter.defaultTitle(),
                                    IssueReporter.composeBody(diagnostic, log),
                                ),
                            )
                        }
                    },
                )
                else -> SettingsScreen(
                    bootAuto = bootAuto,
                    versionName = versionName,
                    onToggleBoot = {
                        bootAuto = !bootAuto
                        ServiceInfo.setBootAutoStart(context, bootAuto)
                    },
                    onOpenConsole = { openConsole(context, loginUrl ?: ServiceInfo.baseUrl(port)) },
                    running = running,
                    onOpenModuleRepo = { openUrl(context, IssueReporter.moduleRepoUrl()) },
                    onOpenAppRepo = { openUrl(context, IssueReporter.appRepoUrl()) },
                )
            }
        }
    }
}

@Composable
private fun BottomBar(tab: Int, onSelect: (Int) -> Unit) {
    val items = listOf(
        Triple(Icons.Outlined.Home, "首页", "进入首页"),
        Triple(Icons.AutoMirrored.Outlined.List, "模型列表", "查看模型列表"),
        Triple(Icons.Outlined.DateRange, "日志", "查看日志"),
        Triple(Icons.Outlined.Settings, "设置", "打开设置"),
    )
    NavigationBar {
        items.forEachIndexed { i, (icon, label, desc) ->
            NavigationBarItem(
                selected = i == tab,
                onClick = { onSelect(i) },
                icon = { androidx.compose.material3.Icon(icon, contentDescription = desc) },
                label = { Text(label) },
            )
        }
    }
}

/* --------------------------------- 通用块 --------------------------------- */

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun CardBox(content: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}

@Composable
private fun PageHeader(title: String, subtitle: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/* --------------------------------- 首页 --------------------------------- */

@Composable
private fun HomeScreen(
    port: Int,
    statusText: String,
    running: Boolean,
    pid: Int,
    version: String?,
    apiKey: String?,
    managementKey: String?,
    probe: String,
    loginUrl: String?,
    runtimeAvailable: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onProbe: () -> Unit,
    onOpenConsole: () -> Unit,
    onCopy: (String, String) -> Unit,
) {
    val dark = isSystemInDarkTheme()
    val success = if (dark) SuccessDark else SuccessLight
    val warn = if (dark) WarnDark else WarnLight
    val dotColor = when {
        running -> success
        statusText.contains("失败") || statusText.contains("未就绪") -> warn
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    var keyVisible by remember { mutableStateOf(false) }
    var mgmtVisible by remember { mutableStateOf(false) }
    var confirmStop by remember { mutableStateOf(false) }

    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text("停止网关？") },
            text = { Text("停止后本机 127.0.0.1:$port 将不再响应，已连接的应用会请求失败。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmStop = false
                        onStop()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("停止") }
            },
            dismissButton = { TextButton(onClick = { confirmStop = false }) { Text("取消") } },
        )
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PageHeader("首页", "OFM 本机网关 · 状态与配置")

        /* ---- 状态 hero 卡 ---- */
        OutlinedCard(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.outlinedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
        ) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        Modifier.size(12.dp).background(
                            color = dotColor, shape = CircleShape,
                        ),
                    )
                    Text(
                        statusText,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (running) success else MaterialTheme.colorScheme.onSurface,
                    )
                }
                Text(
                    ServiceInfo.baseUrl(port),
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onStart,
                        enabled = runtimeAvailable && !running,
                        modifier = Modifier.weight(1.4f),
                    ) { Text("启动") }
                    OutlinedButton(
                        onClick = { confirmStop = true },
                        enabled = running,
                        modifier = Modifier.weight(1.4f),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) { Text("停止") }
                    OutlinedButton(onClick = onProbe, modifier = Modifier.weight(1f)) { Text("检测端口") }
                }
                if (probe != "尚未检测") {
                    Text(probe, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        SectionTitle("运行详情")
        CardBox {
            InfoRow("PID", if (pid > 0) pid.toString() else "—")
            HorizontalDivider()
            InfoRow("监听端口", port.toString())
            HorizontalDivider()
            InfoRow("局域网", "未开启（仅本机）")
            HorizontalDivider()
            InfoRow("服务版本", version ?: "—")
        }

        SectionTitle("服务入口")
        CardBox {
            InfoRow("管理页面", ServiceInfo.baseUrl(port))
            HorizontalDivider()
            InfoRow("API 地址", ServiceInfo.apiUrl(port))
            Text(
                if (loginUrl != null) "已获取一次性登录链接（约 10 分钟有效），可在应用内直接打开管理页。"
                else "启动后日志会打印一次性登录链接。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onOpenConsole, enabled = running, modifier = Modifier.weight(1f)) {
                Text(if (loginUrl != null) "打开管理页（免登录）" else "打开管理页")
            }
            OutlinedButton(onClick = { onCopy("OFM API 地址", ServiceInfo.apiUrl(port)) }, modifier = Modifier.weight(1f)) {
                Text("复制 API 地址")
            }
        }

        SectionTitle("凭据")
        CardBox {
            SecretRow(
                label = "API Key",
                value = apiKey,
                visible = keyVisible,
                onToggle = { keyVisible = !keyVisible },
                onCopy = { apiKey?.let { onCopy("OFM API Key", it) } },
            )
            HorizontalDivider()
            SecretRow(
                label = "管理员密钥",
                value = managementKey,
                visible = mgmtVisible,
                onToggle = { mgmtVisible = !mgmtVisible },
                onCopy = { managementKey?.let { onCopy("OFM 管理员密钥", it) } },
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun InfoRow(label: String, value: String, highlight: Boolean = false) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1.6f),
        )
    }
}

@Composable
private fun SecretRow(
    label: String,
    value: String?,
    visible: Boolean,
    onToggle: () -> Unit,
    onCopy: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                when {
                    value == null -> "启动后生成"
                    visible -> value
                    else -> ServiceInfo.mask(value)
                },
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.End,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1.6f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onToggle, enabled = value != null) {
                Text(if (visible) "隐藏" else "显示")
            }
            OutlinedButton(onClick = onCopy, enabled = value != null) { Text("复制") }
        }
    }
}

/* ------------------------------- 模型列表 ------------------------------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelsScreen(apiUrl: String, apiKey: String?, running: Boolean) {
    val scope = rememberCoroutineScope()
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    suspend fun refresh() {
        loading = true
        error = null
        val result = withContext(Dispatchers.IO) { ApiClient.fetchModels(apiUrl, apiKey) }
        result.onSuccess { models = it }.onFailure {
            error = it.message ?: it.javaClass.simpleName
            models = emptyList()
        }
        loading = false
    }

    LaunchedEffect(running, apiKey) {
        if (running) refresh()
    }

    val filtered = remember(models, query) {
        if (query.isBlank()) models else models.filter { it.contains(query, ignoreCase = true) }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PageHeader("模型列表", "可用模型（/v1/models）")

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            placeholder = { Text("搜索模型…") },
            modifier = Modifier.fillMaxWidth(),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { scope.launch { refresh() } }, enabled = running && !loading) {
                Text(if (loading) "刷新中…" else "刷新")
            }
            Text(
                when {
                    !running -> "服务未运行，启动后可加载模型"
                    error != null -> "加载失败：$error"
                    else -> "共 ${models.size} 个模型"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
        }

        if (loading && filtered.isEmpty()) {
            CardBox { Text("加载中…", style = MaterialTheme.typography.bodyMedium) }
        } else if (filtered.isNotEmpty()) {
            Column(
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                filtered.forEach { id ->
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        SelectionContainer {
                            Text(
                                id,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                            )
                        }
                    }
                }
            }
        } else {
            CardBox {
                Text(
                    if (!running) "启动网关后，这里会列出 /v1/models 返回的可用模型。"
                    else if (error != null) "未能获取模型列表，可点「刷新」重试。"
                    else "没有匹配的模型。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

/* --------------------------------- 日志 --------------------------------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogsScreen(
    filesDirPath: String,
    days: List<String>,
    selectedDay: String?,
    logText: String,
    onSelectDay: (String) -> Unit,
    onCopy: (String) -> Unit,
    onShare: (String) -> Unit,
    onReport: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PageHeader("日志", "网关运行日志（按天）")

        if (days.isEmpty()) {
            CardBox {
                Text("尚无按天日志；启动服务后按本地日期生成 gateway-YYYY-MM-DD.log。", style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                days.forEach { day ->
                    FilterChip(
                        selected = day == selectedDay,
                        onClick = { onSelectDay(day) },
                        label = { Text(day) },
                    )
                }
            }
        }

        OutlinedCard(Modifier.fillMaxWidth().weight(1f)) {
            SelectionContainer {
                Text(
                    logText.ifBlank { "（等待日志…）" },
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = { scope.launch { onCopy(withContext(Dispatchers.IO) { LogStore.reportLog(filesDirPath, selectedDay) }) } },
                modifier = Modifier.weight(1f),
            ) { Text("复制日志") }
            OutlinedButton(
                onClick = { scope.launch { onShare(withContext(Dispatchers.IO) { LogStore.reportLog(filesDirPath, selectedDay) }) } },
                modifier = Modifier.weight(1f),
            ) { Text("分享日志") }
        }
        Button(onClick = onReport, modifier = Modifier.fillMaxWidth()) { Text("提交议题到仓库（附日志）") }
    }
}

/* --------------------------------- 设置 --------------------------------- */

@Composable
private fun SettingsScreen(
    bootAuto: Boolean,
    versionName: String,
    running: Boolean,
    onToggleBoot: () -> Unit,
    onOpenConsole: () -> Unit,
    onOpenModuleRepo: () -> Unit,
    onOpenAppRepo: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PageHeader("设置", "应用偏好与关于信息")

        SectionTitle("通用")
        CardBox {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("开机自启", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "开机后自动拉起前台网关服务",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = bootAuto, onCheckedChange = { onToggleBoot() })
            }
        }

        SectionTitle("工具")
        CardBox {
            OutlinedButton(
                onClick = onOpenConsole,
                enabled = running,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (running) "打开管理控制台" else "启动网关后可用") }
        }

        SectionTitle("两个仓库")
        CardBox {
            Text(
                "本应用与 Magisk / KernelSU 模块共用同一套上游服务与运行时，只是宿主管道不同：",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider()
            InfoRow("本应用（APK）", "无需 root，沙箱内运行，适合日常使用")
            HorizontalDivider()
            InfoRow("Magisk 模块", "需 root，模块内运行，可静默开机自启、增量升级")
            HorizontalDivider()
            OutlinedButton(onClick = onOpenModuleRepo, modifier = Modifier.fillMaxWidth()) {
                Text("查看 Magisk / KernelSU 模块仓库")
            }
            OutlinedButton(
                onClick = onOpenAppRepo,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) { Text("查看应用仓库与更新日志") }
        }

        SectionTitle("关于")
        CardBox {
            InfoRow("应用版本", versionName)
            HorizontalDivider()
            InfoRow("服务", "Our Free Model 本机网关")
            HorizontalDivider()
            InfoRow("包名", "cn.luoyi0914.ofmgateway")
            Text(
                "内置 Node / Magpie 运行时，与 Magisk 模块同源；差异仅在 Android 沙箱适配层。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

/* --------------------------------- 工具函数 --------------------------------- */

private object BuildConfigCompat {
    fun versionName(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    }.getOrDefault("?")
}

private fun buildDiagnostic(
    context: Context,
    status: String,
    runtimeAvailable: Boolean,
    probe: String,
    selectedDay: String?,
): String {
    val pkg = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
    val versionName = pkg?.versionName ?: "?"
    @Suppress("DEPRECATION")
    val versionCode = pkg?.versionCode?.toString() ?: "?"
    return buildString {
        appendLine("应用版本：$versionName ($versionCode)")
        appendLine("设备：Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT}) · ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        appendLine("ABI：${android.os.Build.SUPPORTED_ABIS.joinToString(", ")}")
        appendLine("运行状态：$status")
        appendLine("运行时可用：$runtimeAvailable")
        appendLine("端口检测：$probe")
        appendLine("当前日志：${selectedDay?.let { "gateway-$it.log" } ?: "gateway.log"}")
    }
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
}

private fun shareText(context: Context, subject: String, text: String) {
    runCatching {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(send, "分享日志"))
    }
}

private fun openConsole(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(context, ConsoleActivity::class.java).putExtra(ConsoleActivity.EXTRA_URL, url))
    }
}

private fun openUrl(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}
