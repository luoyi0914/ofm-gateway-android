package cn.luoyi0914.ofmgateway

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import cn.luoyi0914.ofmgateway.ui.OfmTheme
import cn.luoyi0914.ofmgateway.ui.StatusColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OfmTheme { AppShell() }
        }
    }
}

// 上游 management.mjs 的 BOOTSTRAP_MS 就是 10 分钟，这里只是提前提示，不改变服务端行为。
private const val BOOTSTRAP_TTL_MS = 10 * 60 * 1000L

private enum class Tab(val label: String, val icon: ImageVector) {
    HOME("首页", Icons.Outlined.Home),
    MODELS("模型", Icons.AutoMirrored.Outlined.List),
    LOGS("日志", Icons.Outlined.DateRange),
    SETTINGS("设置", Icons.Outlined.Settings),
}

@Composable
private fun AppShell() {
    val context = LocalContext.current
    val repository = remember { GatewayRepository(context.applicationContext) }
    val state by repository.state.collectAsState()
    var tab by rememberSaveable { mutableStateOf(Tab.HOME.name) }
    val current = Tab.entries.firstOrNull { it.name == tab } ?: Tab.HOME

    DisposableEffect(repository) {
        repository.start()
        onDispose { repository.stop() }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { entry ->
                    NavigationBarItem(
                        selected = current == entry,
                        onClick = { tab = entry.name },
                        icon = { Icon(entry.icon, contentDescription = entry.label) },
                        label = { Text(entry.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (current) {
                Tab.HOME -> HomeScreen(state, repository)
                Tab.MODELS -> ModelsScreen(state, repository)
                Tab.LOGS -> LogsScreen(state, repository)
                Tab.SETTINGS -> SettingsScreen(state, repository)
            }
        }
    }
}

/* --------------------------- 通用组件 --------------------------- */

@Composable
private fun PageHeader(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun CardBox(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

@Composable
private fun InfoRow(label: String, value: String, mono: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium.let {
                if (mono) it.copy(fontFamily = FontFamily.Monospace) else it
            },
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SectionGap() = Spacer(Modifier.height(12.dp))

/* --------------------------- 首页 --------------------------- */

@Composable
private fun HomeScreen(state: GatewayUiState, repository: GatewayRepository) {
    var confirmStop by remember { mutableStateOf(false) }
    var showDiag by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val notify: (String) -> Unit = { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        PageHeader("OFM 网关", "本机运行的 OpenAI 兼容网关，数据不出设备")

        if (state.showOnboarding) {
            CardBox {
                Text("第一次用，三步上手", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                listOf(
                    "① 点下面的「启动」，状态变绿说明网关已在跑。",
                    "② 去「模型」页刷新列表，点一次「选它」存下你要用的模型。",
                    "③ 回到这里复制接入信息，填进你自己的 AI 客户端（同一台手机）。",
                ).forEach { line ->
                    Text(
                        line,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                }
                Text(
                    "网关只监听 127.0.0.1：电脑连不上属正常，不是配置错误。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { repository.dismissOnboarding() }) { Text("知道了") }
                    if (!state.running) {
                        TextButton(onClick = { repository.startGateway() }, enabled = state.canStart) {
                            Text(if (state.busy) "启动中…" else "直接启动")
                        }
                    }
                }
            }
            SectionGap()
        }

        CardBox {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val dotColor = when (state.status.severity) {
                    Severity.OK -> StatusColors.success()
                    Severity.WARN -> StatusColors.warn()
                    Severity.ERROR -> MaterialTheme.colorScheme.error
                    Severity.NEUTRAL -> MaterialTheme.colorScheme.outline
                }
                Box(Modifier.size(12.dp).background(dotColor, CircleShape))
                Spacer(Modifier.width(10.dp))
                Text(state.status.label, style = MaterialTheme.typography.titleLarge)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                state.status.hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            SelectionContainer {
                Text(
                    ServiceInfo.baseUrl(state.port),
                    style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                )
            }
            Spacer(Modifier.height(14.dp))
            // 四个按钮挤一行会把「一键体检」压成竖排单字（窄屏实测），
            // 因此按「主操作 / 辅助操作」分两行，各按钮等宽并禁止换行。
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { repository.startGateway() },
                    enabled = state.canStart,
                    modifier = Modifier.weight(1f),
                ) { Text(if (state.busy) "启动中…" else "启动", maxLines = 1) }
                OutlinedButton(
                    onClick = { confirmStop = true },
                    enabled = state.running && !state.busy,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.weight(1f),
                ) { Text("停止", maxLines = 1) }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { repository.probePort() },
                    modifier = Modifier.weight(1f),
                ) { Text("检测端口", maxLines = 1) }
                OutlinedButton(
                    onClick = { showDiag = true },
                    modifier = Modifier.weight(1f),
                ) { Text("一键体检", maxLines = 1) }
            }
            if (state.probe != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    state.probe,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SectionGap()

        CardBox {
            Text("运行详情", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            InfoRow("PID", if (state.pid > 0) state.pid.toString() else "—", mono = true)
            InfoRow("监听端口", state.port.toString(), mono = true)
            InfoRow("局域网", "未开启（仅本机可访问）")
            InfoRow("服务版本", state.version ?: "—")
            InfoRow("应用版本", state.appVersion)
        }

        if (showDiag) {
            val text = repository.diagnostic()
            AlertDialog(
                onDismissRequest = { showDiag = false },
                title = { Text("体检结果") },
                text = {
                    SelectionContainer {
                        Text(
                            text,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        clipboard.setText(AnnotatedString(text))
                        notify("已复制诊断信息（不含密钥）")
                        showDiag = false
                    }) { Text("复制并关闭") }
                },
                dismissButton = {
                    TextButton(onClick = { showDiag = false }) { Text("关闭") }
                },
            )
        }

        SectionGap()

        CardBox {
            Text("接入凭据", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            CredentialRow(
                label = "API Key",
                value = state.apiKey,
                onCopy = { text -> clipboard.setText(AnnotatedString(text)); notify("已复制 API Key") },
            )
            CredentialRow(
                label = "管理密钥",
                value = state.managementKey,
                onCopy = { text -> clipboard.setText(AnnotatedString(text)); notify("已复制管理密钥") },
            )
            HorizontalDivider(Modifier.padding(vertical = 10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        val url = state.loginUrl
                        when {
                            !state.running -> notify("先启动网关")
                            url == null -> notify("登录令牌还没生成，稍等几秒或重启网关")
                            state.bootstrapAgeMs > BOOTSTRAP_TTL_MS -> {
                                // 令牌约 10 分钟有效，过期后打开只会落到登录页，不如直接说清楚。
                                notify("登录链接已过期，请在设置页重启网关换新链接")
                            }
                            else -> context.startActivity(
                                Intent(context, ConsoleActivity::class.java)
                                    .putExtra(ConsoleActivity.EXTRA_URL, url),
                            )
                        }
                    },
                    enabled = state.running,
                ) { Text("打开管理控制台", maxLines = 1) }
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        clipboard.setText(AnnotatedString(repository.accessList()))
                        notify("已复制接入信息")
                    },
                    enabled = state.running,
                ) { Text("复制接入信息", maxLines = 1) }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "接入第三方客户端的配置片段",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AccessSnippet.Client.entries.forEach { client ->
                    OutlinedButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(repository.snippet(client)))
                            notify("已复制 ${client.label} 接入片段")
                        },
                        enabled = state.running,
                        modifier = Modifier.weight(1f),
                    ) { Text(client.label) }
                }
            }
            if (state.preferredModel == null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "还没选推荐模型：去「模型」页点一次「选它」，片段里就会带上模型名。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Spacer(Modifier.height(4.dp))
                Text(
                    "推荐模型：${state.preferredModel}",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!state.running) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "启动网关后可用",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(20.dp))
    }

    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text("停止网关？") },
            text = { Text("停止后已连接的客户端会立即断开，配置与密钥不受影响。") },
            confirmButton = {
                TextButton(
                    onClick = { confirmStop = false; repository.stopGateway() },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("停止") }
            },
            dismissButton = { TextButton(onClick = { confirmStop = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun CredentialRow(label: String, value: String?, onCopy: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp),
        )
        Text(
            value?.let { ServiceInfo.mask(it) } ?: "—",
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { value?.let(onCopy) }, enabled = value != null) { Text("复制") }
    }
}

/* --------------------------- 模型列表 --------------------------- */

@Composable
private fun ModelsScreen(state: GatewayUiState, repository: GatewayRepository) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val notify: (String) -> Unit = { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var reloadKey by remember { mutableStateOf(0) }

    LaunchedEffect(state.running, state.port, reloadKey) {
        if (!state.running) return@LaunchedEffect
        loading = true
        error = null
        val result = withContext(Dispatchers.IO) {
            ApiClient.fetchModels(ServiceInfo.apiUrl(state.port), state.apiKey)
        }
        result.onSuccess { models = it }.onFailure { error = it.message ?: it.javaClass.simpleName }
        loading = false
    }

    val filtered = remember(models, query) {
        if (query.isBlank()) models else models.filter { it.contains(query, ignoreCase = true) }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
        PageHeader("可用模型", "来自本机网关 /v1/models，共 ${models.size} 个")

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            label = { Text("搜索模型 ID") },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { reloadKey++ }, enabled = state.running && !loading) {
                Text(if (loading) "加载中…" else "刷新")
            }
            if (!state.running) {
                Text(
                    "启动网关后可读取模型列表",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
            }
        }
        Spacer(Modifier.height(8.dp))

        when {
            error != null -> CardBox {
                Text("读取失败", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(4.dp))
                Text(error!!, style = MaterialTheme.typography.bodySmall)
            }

            !state.running -> CardBox {
                Text("网关未运行", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "模型列表由本机网关提供，先在首页启动服务。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            filtered.isEmpty() && !loading -> CardBox {
                Text(
                    if (models.isEmpty()) "暂无模型" else "没有匹配「$query」的模型",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                filtered.forEach { id ->
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        ),
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SelectionContainer {
                                Text(
                                    id,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            TextButton(onClick = {
                                clipboard.setText(AnnotatedString(id))
                                notify("已复制模型 ID")
                            }) { Text("复制") }
                            val picked = state.preferredModel == id
                            TextButton(onClick = { repository.setPreferredModel(if (picked) null else id) }) {
                                Text(if (picked) "已选" else "选它")
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

/* --------------------------- 日志 --------------------------- */

@Composable
private fun LogsScreen(state: GatewayUiState, repository: GatewayRepository) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val notify: (String) -> Unit = { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    val shown = remember(state.logText, state.logLevel) {
        LogStore.filterLines(state.logText, state.logLevel)
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
        PageHeader("运行日志", "按天留存，最新一天在最前")

        if (state.days.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                state.days.take(4).forEach { day ->
                    FilterChip(
                        selected = state.selectedDay == day,
                        onClick = { repository.selectDay(day) },
                        label = { Text(day.substring(5)) },
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LogStore.Level.entries.forEach { level ->
                FilterChip(
                    selected = state.logLevel == level,
                    onClick = { repository.setLogLevel(level) },
                    label = { Text(level.label) },
                )
            }
        }
        Spacer(Modifier.height(8.dp))

        Card(
            modifier = Modifier.fillMaxWidth().weight(1f),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            SelectionContainer {
                Text(
                    shown,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        // 同首页：四个按钮挤一行在窄屏会被压成竖排单字，分两行等宽排。
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { repository.refresh() },
                modifier = Modifier.weight(1f),
            ) { Text("刷新", maxLines = 1) }
            OutlinedButton(
                onClick = {
                    clipboard.setText(AnnotatedString(repository.reportLog()))
                    notify("已复制日志（含脱敏）")
                },
                modifier = Modifier.weight(1f),
            ) { Text("复制", maxLines = 1) }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    val text = repository.reportLog()
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, text)
                    }
                    context.startActivity(Intent.createChooser(intent, "分享日志"))
                },
                modifier = Modifier.weight(1f),
            ) { Text("分享", maxLines = 1) }
            OutlinedButton(
                onClick = {
                    val url = IssueReporter.buildIssueUrl(
                        IssueReporter.defaultTitle(),
                        IssueReporter.composeBody(repository.diagnostic(), repository.reportLog()),
                    )
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                },
                modifier = Modifier.weight(1f),
            ) { Text("提交议题", maxLines = 1) }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/* --------------------------- 设置 --------------------------- */

@Composable
private fun SettingsScreen(state: GatewayUiState, repository: GatewayRepository) {
    val context = LocalContext.current
    val notify: (String) -> Unit = { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    // 进设置页时顺带查一次更新；内部按 12 小时节流，不会每切一次页面就打一次 API。
    LaunchedEffect(Unit) { repository.checkUpdate() }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        PageHeader("设置", "后台常驻、通知与仓库入口")

        CardBox {
            SwitchRow("开机自启", "重启设备后自动拉起网关", state.bootAuto) { repository.setBootAuto(it) }
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            SwitchRow(
                label = "通知权限",
                hint = if (state.notificationGranted) "已授权，可显示常驻状态" else "未授权，后台状态不可见",
                checked = state.notificationGranted,
                enabled = false,
                onCheckedChange = {},
            )
            if (!state.notificationGranted) {
                TextButton(
                    onClick = {
                        if (Build.VERSION.SDK_INT >= 33) {
                            (context as? ComponentActivity)?.requestPermissions(
                                arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001,
                            )
                        } else {
                            notify("该系统版本无需单独授权")
                        }
                    },
                ) { Text("去授权") }
            }
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            SwitchRow(
                label = "电池优化白名单",
                hint = if (state.batteryOptimized) "未加入，长时间后台可能被系统冻结" else "已加入，后台更稳",
                checked = !state.batteryOptimized,
                enabled = false,
                onCheckedChange = {},
            )
            if (state.batteryOptimized) {
                TextButton(
                    onClick = {
                        // 直接弹系统的「忽略电池优化」确认框，比自己翻列表成功率高得多；
                        // 少数 ROM 移除了该 action，再退回设置列表。
                        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                            .setData(Uri.parse("package:${context.packageName}"))
                        val ok = runCatching { context.startActivity(direct) }.isSuccess ||
                            runCatching {
                                context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                            }.isSuccess
                        if (!ok) notify("该系统没有电池优化设置页")
                    },
                ) { Text("加入白名单") }
            }
        }

        SectionGap()

        CardBox {
            Text("两个仓库", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "同一套网关的两个形态：Magisk/KernelSU 模块以 root 身份常驻，不需要安装 App；" +
                    "本应用自带运行时，无 root 也能用。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(IssueReporter.moduleRepoUrl()))) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("查看 Magisk / KernelSU 模块仓库") }
            Spacer(Modifier.height(6.dp))
            OutlinedButton(
                onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(IssueReporter.appRepoUrl()))) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("查看应用仓库与更新日志") }
            val release = state.newerRelease
            if (release != null) {
                Spacer(Modifier.height(6.dp))
                FilledTonalButton(
                    onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.url))) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("发现新版本 ${release.version}，去下载") }
            } else {
                Spacer(Modifier.height(6.dp))
                TextButton(
                    onClick = { repository.checkUpdate(force = true); notify("正在检查更新…") },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("检查更新") }
            }
        }

        SectionGap()

        CardBox {
            InfoRow("应用版本", state.appVersion)
            InfoRow("运行时", if (state.runtimeInstalled) "已解包" else "未就绪")
            InfoRow("服务版本", state.version ?: "—")
            InfoRow("监听端口", state.port.toString(), mono = true)
        }

        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun SwitchRow(
    label: String,
    hint: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}
