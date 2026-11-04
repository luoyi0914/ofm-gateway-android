# OFM Gateway Android

独立 Android APK 工程；**不修改**上游的 Magisk/KernelSU 模块，自带完整运行时，安装即用，无需 root/Shizuku。

## 当前状态

- UI：**Android 原生 Material 3**（Compose），四页底部导航：首页 / 模型列表 / 日志 / 设置；品牌色板 light+dark、状态 hero 卡、矢量图标、危险操作二次确认。
- 运行时：APK 内置 Node（`libnode.so`）、Magpie 引擎（`libmagpie.so`）及全部 `.so` 依赖，以及上游 OFM 服务代码（`assets/app/`，首启解压到私有目录 `filesDir/ofm-runtime/app`）。
- 进程监督：Rust JNI（`libofm_gateway.so`）真实 `spawn` / 停止 Node 进程（进程组 SIGTERM→SIGKILL），日志按天写入 `filesDir/ofm-runtime/logs/gateway-YYYY-MM-DD.log`。
- 前台服务承载网关（`foregroundServiceType=specialUse`，常驻通知）；开机自启可在设置页开关（默认关闭）。
- 端口：默认 **127.0.0.1:15000**（`--no-lan`，仅本机可访问）；实际端口以 `ofm-data/settings.json` 的 `standalonePort` 为准，UI 动态读取。
- Shizuku/root 特权后端仍为**明确不可用的占位实现**。
- 仅 `arm64-v8a`；`minSdk 26`；`compileSdk/targetSdk 37`。

> 说明：以上为服务器端构建 + 静态/产物校验结果；真机验收由使用者按下方清单完成。

## 工具链

| 组件 | 版本 |
| --- | --- |
| Gradle | 9.8.2 |
| Android Gradle Plugin | 9.5.0 |
| Kotlin | 2.4.21 |
| Compose BOM | 2026.09.00 |
| activity-compose | 1.13.0 |
| material-icons-core | 随 Compose BOM |
| NDK | 29.0.14206865 |
| build-tools | 37.0.0 |
| Rust | 1.99.1（target `aarch64-linux-android`） |

## 工程结构

- `app/`：Android UI（Material 3 Compose）、生命周期、`RuntimeInstaller`（assets 解包）、`NativeGateway`（JNI 边界）、`GatewayService`、`LogStore`、`ServiceInfo`、`ApiClient`、`ConsoleActivity`。
- `native-gateway/`：Rust JNI crate（`src/lib.rs`），导出 `nativeRuntimeAvailable/nativeStatus/nativeStart/nativeStop/nativePid`。
- `app/src/main/assets/app/`：上游 OFM 服务源码（首启复制到私有目录）。
- `app/src/main/jniLibs/arm64-v8a/`：`libofm_gateway.so`（入库）及构建时生成的 `libnode.so`、`libmagpie.so` 与依赖库（不入库）。

## 关键实现说明

1. **原生库命名**：Android Gradle 只打包 `*.so`，而 Node 的 `verneed`/`DT_NEEDED` 引用带版本号（如 `libcrypto.so.3`）。`scripts/prepare-runtime-libs.sh` 解析 ELF 动态段，成对改写 `DT_NEEDED` 与 `DT_SONAME`，统一为无版本名，安装后从 `nativeLibraryDir` 加载/执行，规避 Android 10+ 对可写目录 exec 的限制。
2. **16 KB 页对齐**：所有 `.so` 的 `LOAD` 段按 16 KB 对齐，满足 Android 15+ / targetSdk≥35 要求；Magpie 引擎用 NDK 29 + `-Wl,-z,max-page-size=16384` 重编。
3. **运行时依赖注入**：`nativeStart` 注入 `LD_LIBRARY_PATH`、`OFM_MAGPIE_BINARY`、`OFM_HOME`、`HOME`、`TMPDIR`、`XDG_CACHE_HOME`、`OFM_MANAGED_ROOT` 后启动 Node。
4. **陈旧锁处理**：启动前读取 `ofm-data/service.lock` 中的 pid，仅当该进程仍在 `/proc` 中存在才跳过启动；否则清理后启动，避免与孤儿进程并发。
5. **凭据脱敏**：一次性 `#login=` 链接在日志导出、复制、分享、提交议题前统一替换为 `***`。

## 构建

首次构建前先还原原生运行时库（jniLibs 内派生的 `.so` 不入库）：

```bash
./scripts/prepare-runtime-libs.sh   # 从 ../../.cache 提取 Node/Magpie 并改写 soname
```

随后：

```bash
./gradlew --no-daemon assembleDebug     # 调试包
./gradlew --no-daemon assembleRelease   # 正式包
# 产物：app/build/outputs/apk/{debug,release}/app-{debug,release}.apk
```

- 正式包签名密钥：默认 `keystore/ofm-release.jks`（alias `ofm`，**不入库**）。口令与别名可通过 `OFM_STORE_PASSWORD` / `OFM_KEY_ALIAS` / `OFM_KEY_PASSWORD` 环境变量或 `local.properties` 覆盖。请务必替换为自有密钥后再分发。
- `buildNative` / `syncNative` Gradle 任务会自动 `cargo build` 并同步 `libofm_gateway.so`；`prepareRuntimeLibs` / `verifyRuntimeLibs` 负责派生运行库的生成与校验，构建前强制生效（clean clone 可直接构建）。NDK/链接器由 `local.properties` 的 `sdk.dir` + `android.ndkVersion` 解析。

## 真机验收清单

1. 安装并首次启动：观察是否解包运行时（首启约数秒），UI 状态从「准备中」变为「就绪（未启动）」。
2. 点「启动」→ 状态变为「运行中」、显示 PID 与端口、通知栏出现常驻通知。
3. 「打开管理页」可无登录进入（一次性链接有效期内）。
4. 模型列表页能拉到 `/v1/models` 返回的模型；模型 ID 可长按复制。
5. 日志页按天切换、复制/分享内容与所选日期一致。
6. 按 Home 回到桌面后，浏览器仍能访问 `http://127.0.0.1:15000`（检验前台保活）。
7. 「停止」弹出确认，确认后端口立即不可达、Magpie 无残留进程。
8. 深色模式与 360dp 窄屏下无文字截断、无系统栏遮挡。

## 尚未覆盖（诚实边界）

- 未在本仓库内置 adb/模拟器做自动化验收，上述清单由真机手动执行。
- Shizuku/root 特权提升未接入。
- 上游 OFM 服务代码来自上游发布包，业务逻辑差异仅在 Android 沙箱适配层。

## 与上游内置服务的一处必要差异（Android 适配）

`app/src/main/assets/app/packages/standalone/library.mjs` 的 `inspect()` 原本从文件系统根
逐级检查路径上每一段是否符号链接，遇到即抛 `Symlinks are not allowed in managed storage`。
Android 应用数据目录上方存在系统级符号链接别名（如 `/data/data` -> `/data/user/0`），
从根检查会在真机上必然失败。现改为：仅对宿主注入的 `OFM_MANAGED_ROOT`（应用私有 files 根）
及其以下路径禁止符号链接，系统级别名放行；未设置该环境变量时保持上游原行为。
宿主（Rust `nativeStart`）会注入 `OFM_MANAGED_ROOT=<filesDir>`。
