# OFM Gateway · Android

> 把 Our Free Model 的本机网关做成一款**自带全部运行时的 Android 应用**——装好就能用，无需 root、无需 Shizuku、无需 Termux。

OFM（Our Free Model）上游自带一个本地网关：在本机起一个兼容 OpenAI 协议的 HTTP 服务，供各类 AI 客户端接入。但它原本是跟着宿主环境跑的。本项目把同一套服务、同版本的 Node 运行时与 Magpie 模型引擎一起打进 APK，让它在手机上以普通应用的身份常驻运行。

| 首页 | 模型列表 | 日志 |
| --- | --- | --- |
| ![首页](docs/screenshots/home.jpg) | ![模型列表](docs/screenshots/models.jpg) | ![日志](docs/screenshots/logs.jpg) |

> **上游声明前提**：本项目不实现任何模型能力，只是把上游服务搬运到 Android 上运行。模型额度、地区限制、账号渠道与上游条款，均由上游决定。
>
> **姊妹项目**：同一套运行时的 [Magisk / KernelSU 模块版](https://github.com/luoyi0914/ofm-gateway-magisk)——需要 root 时用它（开机静默常驻、端口 `15656`）；没有 root，或者想要一个能可视化操作的界面，就用本 APK（端口 `15000`）。两者数据目录独立，可共存。

---

## 核心特性

| 特性 | 说明 |
| --- | --- |
| **安装即用** | APK 内置 Node 运行时、Magpie 引擎与上游服务代码，首启自动解包到应用私有目录，不依赖 root / Shizuku / Termux |
| **原生 Material 3 界面** | Compose 实现，底部四页：首页 / 模型列表 / 日志 / 设置；自带浅色 + 深色两套品牌色板 |
| **真实进程监督** | Rust JNI（`libofm_gateway.so`）直接 `spawn` / 停止 Node 进程；停止时对**整个进程组**发 SIGTERM，超时再 SIGKILL，不留 Magpie 孙进程 |
| **前台服务保活** | 以 `foregroundServiceType=specialUse` 常驻，系统通知栏常驻提示；按 Home 回到桌面后网关仍可访问 |
| **状态一眼可见** | 首页状态卡直接给出运行中 / 未启动、监听地址、PID、端口、服务版本，启停按钮就在同一张卡上 |
| **按天日志** | 日志按本地日期切分为 `gateway-YYYY-MM-DD.log`，长时间运行也会在本地午夜自动滚动 |
| **一键反馈** | 日志页可「复制 / 分享 / 提交议题到仓库」，议题正文自动附带诊断信息与最近日志 |
| **仅本机监听** | 网关固定以 `--no-lan` 启动，只监听 `127.0.0.1`；网络安全配置默认禁止明文流量，仅放行回环地址 |

---

## 快速开始

### 1. 安装

> [!IMPORTANT]
> **签名变更**：从 v0.8.3 起使用专用签名密钥（证书 SHA-256 `42f84523…54e6a236`）。
> 安装过 v0.8.2 及更早测试包的用户，需**先卸载旧版**再安装（签名不同，无法覆盖升级）。旧包使用的是已弃用的公开开发证书。

- 系统要求：**Android 8.0（API 26）及以上**，**仅支持 arm64-v8a** 设备。
- 安装包约 **67 MB**（其中 Magpie 引擎约 61 MB），这是内置完整运行时的代价。
- 从本仓库 [Releases](https://github.com/luoyi0914/ofm-gateway-android/releases) 下载 APK 安装。

### 2. 启动网关

1. 首次打开会显示三步引导卡并解包运行时（约数秒），首页状态从「准备中」变为「就绪（未启动）」。引导点过「知道了」就不再出现。
2. 点「启动」——状态变为「运行中」，显示 PID 与监听端口，通知栏出现常驻通知。
3. 点「打开管理页」进入网关注入的网页控制台；启动时日志里会打印一次性登录链接（约 10 分钟有效，登录一次即失效）。

### 3. 接入你自己的 AI 客户端

首页「服务入口」区域显示两个地址，可直接复制：

- **API 地址**：`http://127.0.0.1:15000/v1`（兼容 OpenAI 接口的基础地址）
- **API Key**：首页「凭据」卡片中查看 / 复制（默认脱敏显示）

网关提供 `GET /v1/models`、`POST /v1/chat/completions`、`POST /v1/responses` 等上游已实现的接口，具体可用模型取决于上游的渠道与账号状态——「模型列表」页就是从 `/v1/models` 实时拉取的结果。

选好模型后，在「模型列表」页点一次「选它」存为推荐模型，首页「凭据」卡就多出两档复制：

- **复制接入信息**：三行清单（Base URL / API Key / 模型名），照着填任意 OpenAI 兼容客户端。
- **ChatBox / NextChat 片段**：含该客户端的填写位置提示，以及「客户端必须装在同一台手机上」的说明。

片段不内置客户端截图——那些会随客户端改版过期。未选模型时片段会写明「去模型页选一个」，不会编一个不存在的模型名。

> 实际监听端口以服务写入的 `settings.json` 中的 `standalonePort` 为准，应用界面会动态读取，不写死。

### 4. 排障

首页「一键体检」会把状态、运行时解包结果、原生库、PID 与端口、端口探测、开机自启、通知权限、电池白名单和数据目录汇成一段文本，可直接复制贴出来。这段文本不含 API Key、管理密钥与登录链接，可以放心发在公开 issue 里。

### 5. 停止

点「停止」会弹出二次确认；确认后端口立即不可达，并清理整个进程组。设置页可打开「开机自启」（默认关闭）。

设置页底部提供「检查更新」：只读本仓库最新 Release 比对版本号，只提示、不自动下载安装（静默替换一个正在常驻的服务不在本应用的权限边界内），12 小时内不重复请求，网络失败静默。

---

## 适用人群

**适合你，如果：**

- 你想在手机上跑一个**本机 AI 网关**，让其他 App、脚本或浏览器页面通过 `127.0.0.1` 调用模型；
- 你手里只有一台手机，**不想 root、不想装 Shizuku、不想折腾 Termux**；
- 你已经了解上游 OFM 的工作方式，只想把它搬进一个 Android 应用里长期挂着；
- 你需要能自己排障的东西：按天日志、PID / 端口可见、一键把日志带进议题。

**不适合你，如果：**

- 你需要 `x86_64` / 32 位设备——本项目**只打包 `arm64-v8a`**；
- 你希望从局域网或公网访问这个网关——当前**强制关闭局域网**（`--no-lan`），只监听回环；需要局域网请用模块版；
- 你需要稳定的生产级 SLA——这是自带完整运行时的个人向工具，仍在早期版本；
- 你不想处理上游账号与额度——本项目不提供任何模型额度。

---

## 常见问题（FAQ）

**Q：需要 root 吗？需要 Shizuku 吗？**
不需要。应用以普通应用身份运行，网关靠前台服务保活。Shizuku / root 特权后端目前是**明确不可用的占位实现**。

**Q：启动后回到桌面，浏览器里的管理页还能打开吗？**
设计目标是能——前台服务常驻，网关不随 Activity 销毁。请把它列进系统的后台省电白名单；不同厂商的省电策略差异较大，这是最常见的「跑着跑着就没了」的原因。

**Q：为什么包这么大？**
因为要把运行时一起打包：Node（约 43 MB）、Magpie 引擎（约 61 MB）、ICU 数据等依赖库，合计压缩后约 67 MB。好处是装完立即可用，不需要额外下载任何运行时。

**Q：端口可以改成别的吗？**
CLI 启动参数固定为 `--port 15000`。实际端口以服务写入的 `settings.json` 中 `standalonePort` 为准，界面会动态读取；如果设备上 15000 被占用，请关注日志中的实际端口。

**Q：日志在哪？**
应用私有目录下 `ofm-runtime/logs/gateway-YYYY-MM-DD.log`，按本地日期切分。日志页可选择日期、复制或分享，导出范围与所选日期一致。

**Q：应用里的「提交议题到仓库」会把我上传到哪？**
它会打开浏览器，在**本仓库**预填一个 issue（标题 + 诊断信息 + 最近日志），由你的 GitHub 账号确认提交。应用本身不持有任何 GitHub 凭据。日志以纯文本塞进正文（截断到 4000 字符），附件不会被真正上传；需要完整日志请用「复制 / 分享日志」。提交前正文里的 `#login=` 一次性链接会统一脱敏为 `***`。

**Q：和 Magisk / KernelSU 模块版有什么区别？**
模块版要求 root，开机由系统层拉起，端口默认 `15656`，可开局域网中继；本项目是普通 APK，前台服务承载，端口默认 `15000`，界面内可直接看状态、模型与日志。两者同源（同一份上游服务、同一版 Magpie 引擎），数据目录互相独立。设置页有到模块仓库的跳转入口。

---

## 技术说明

### 架构

```
MainActivity (Compose UI)
   └── GatewayService (前台服务, specialUse)
         ├── RuntimeInstaller: assets/app → filesDir/ofm-runtime/app（按 versionCode 打标记）
         └── NativeGateway (JNI) ──► libofm_gateway.so (Rust)
                                        └── spawn: nativeLibraryDir/libnode.so packages/standalone/cli.mjs
                                              --data-dir filesDir/ofm-data --port 15000 --no-lan
                                              └── OFM_MAGPIE_BINARY → libmagpie.so
```

### 工程结构

- `app/`：Android UI（Material 3 Compose，`MainActivity` 四页 + `ui/Theme.kt` 品牌色板）、`GatewayRepository`（单一状态流，磁盘/JNI 全在 IO 线程）、`GatewayStatus`（状态枚举与文案）、`AccessSnippet`（ChatBox / NextChat 接入片段拼装）、`UpdateChecker`（检查更新）、`RuntimeInstaller`（assets 解包）、`NativeGateway`（JNI 边界）、`GatewayService`（前台服务 + 看门狗）、`LogStore`、`ServiceInfo`、`ApiClient`、`ConsoleActivity`、`IssueReporter`、`Diagnostics`。
- `native-gateway/`：Rust JNI crate，导出 `nativeRuntimeAvailable / nativeStatus / nativeStart / nativeStop / nativePid`。
- `app/src/main/assets/app/`：上游服务源码，首启复制到私有目录。
- `app/src/main/jniLibs/arm64-v8a/`：`libofm_gateway.so`（入库）；`libnode.so`、`libmagpie.so` 及其依赖库由脚本生成，**不入库**。

### 关键实现

1. **原生库无版本名改写**：Android Gradle 只打包 `*.so`，而 Node 的 `DT_NEEDED` 引用带版本号的 soname。`scripts/prepare-runtime-libs.sh` 解析 ELF 动态段，**成对改写 `DT_NEEDED` 与 `DT_SONAME`** 为无版本名（原位覆盖，不改变文件布局），安装后直接从 `nativeLibraryDir` 加载，规避 Android 10+ 对可写目录 exec 的限制。
2. **16 KB 页对齐**：所有 `LOAD` 段按 16 KB 对齐，满足 Android 15+ / targetSdk ≥ 35 的要求；Magpie 引擎用 NDK 29 + `-Wl,-z,max-page-size=16384` 重编。
3. **运行时依赖注入**：`nativeStart` 在 spawn 前注入 `LD_LIBRARY_PATH`、`OFM_MAGPIE_BINARY`、`OFM_HOME`、`OFM_MANAGED_ROOT`、`HOME`、`TMPDIR`、`XDG_CACHE_HOME`；子进程以独立进程组启动（`process_group(0)`）。
4. **存活判定**：优先用子进程句柄；重启应用后没有句柄时，读 `ofm-data/service.lock` 里的 pid，要求 `/proc/<pid>/stat` 状态不是 `Z`/`X` **且** `/proc/<pid>/cmdline` 含 `cli.mjs`。僵尸进程不算活，端口有监听也不算活（端口可能被别的进程占用）。
5. **停止语义**：先对整个进程组发 SIGTERM 让 Node 有机会释放锁，轮询至多 3 秒后再 SIGKILL；只有确实终止了自己的子进程时才清理锁文件。界面点停止只发指令，由服务线程收尾，避免与看门狗抢跑导致「停了又起」。
6. **状态即契约**：JNI 返回稳定的 ASCII 状态键（`running` / `ready` / `runtime_missing` / …），Kotlin 侧 `GatewayStatus.fromKey` 映射成带颜色、文案与 `usable` 的语义状态；未知键降级为 `UNKNOWN` 而不是当作正常。
7. **凭据脱敏**：日志页的复制 / 分享 / 提交议题统一经 `ServiceInfo.scrubToken()`（`TokenScrubber`，有单测），一次性 `#login=` 链接替换为 `***`；原生层写日志时也做同样脱敏，管理控制台退出清 cookie 与 WebStorage。

### 工具链

| 组件 | 版本 |
| --- | --- |
| Gradle | 9.8.2 |
| Android Gradle Plugin | 9.5.0 |
| Kotlin | 2.4.21 |
| Compose BOM | 2026.09.00 |
| NDK | 29.0.14206865 |
| build-tools | 37.0.0 |
| Rust | 1.99.1（target `aarch64-linux-android`） |
| 内置 Node 运行时 | v24.18.0 |
| minSdk / targetSdk | 26 / 37 |

### 从源码构建

派生运行库（约 98MB 的 Node 与 Magpie）不入库。clean clone 的首次构建分两步：

```bash
./scripts/fetch-runtime-bundle.sh    # 从固定 Release 拉取运行时制品并校验 SHA-256（落到仓库根 .cache）
./scripts/prepare-runtime-libs.sh    # 解包并成对改写 DT_NEEDED / DT_SONAME
```

随后：

```bash
./gradlew --no-daemon :app:testDebugUnitTest   # 单元测试
./gradlew --no-daemon assembleDebug            # 调试包
./gradlew --no-daemon assembleRelease          # 正式包
# 产物：app/build/outputs/apk/{debug,release}/app-{debug,release}.apk
```

发版前必须过产物校验（读包本身，不看构建日志）：

```bash
./scripts/verify-release.sh app/build/outputs/apk/release/app-release.apk --expect-version 0.9.4
```

校验项：versionCode/versionName 与 minSdk/targetSdk、ABI 纯净、证书指纹（固定断言发布密钥）、
v2 + v3 签名、非 debuggable、体积预算、zip 16KB 对齐、13 个运行库 + 1 个 JNI 库齐全，
以及每个 `.so` 的 PT_LOAD 是否 16KB 对齐、`DT_NEEDED` 能否在包内解析（带版本名的 soname 会在这里被拦下）。

版本号唯一来源是 `version.properties`（Gradle、CI、脚本都读它）；改动它之后打 `vX.Y.Z` tag，
CI 会校验 tag 与 versionName 一致、versionCode 单调递增、`CHANGELOG.md` 有对应小节。
流水线：`preflight`（无密钥）→ `build`（`environment: release`，需审批）→ `publish`（先发 pre-release）。

- 签名：官方包使用专用 4096 位 RSA 密钥（alias `ofm`，有效期 100 年），密钥文件与口令**均不入库**；构建时从 `OFM_STORE_PASSWORD` / `OFM_KEY_ALIAS` / `OFM_KEY_PASSWORD` 环境变量或 `local.properties`（`ofm.storeFile` / `ofm.storePassword` / `ofm.keyAlias` / `ofm.keyPassword`）读取，缺任一口令时 release 构建直接失败，不会用默认值兜底。自行构建请生成自己的密钥。
- `buildNative` / `syncNative` 自动 `cargo build` 并同步 `libofm_gateway.so`；`prepareRuntimeLibs` / `verifyRuntimeLibs` 在打包前强制生效，缺库会直接让构建失败，避免打出「无运行时」的包。

### 真机验收清单

1. 安装并首次启动：观察运行时解包，状态从「准备中」变为「就绪（未启动）」。
2. 点「启动」→ 状态「运行中」、显示 PID 与端口、通知栏出现常驻通知。
3. 「打开管理页」可进入；日志中可见一次性登录链接。
4. 模型列表页能拉到 `/v1/models` 返回的模型，模型 ID 可长按复制。
5. 日志页按天切换，复制 / 分享内容与所选日期一致。
6. 按 Home 回桌面后，浏览器仍能访问 `http://127.0.0.1:<端口>`。
7. 「停止」确认后端口立即不可达，无残留 Magpie 进程。
8. 深色模式与 360dp 窄屏下无文字截断、无系统栏遮挡。

---

## 与上游的一处必要差异

**符号链接检查范围收窄。** 上游 `packages/standalone/library.mjs` 的 `inspect()` 会从文件系统根逐级检查路径上每段是否为符号链接，遇到即抛 `Symlinks are not allowed in managed storage`。Android 的应用数据目录上方存在系统级符号链接别名（如 `/data/data` → `/data/user/0`），从根检查在真机上必然失败。

现改为：**仅对宿主注入的 `OFM_MANAGED_ROOT`（应用私有 files 根）及其以下路径**禁止符号链接，系统级别名放行；未设置该环境变量时保持上游原行为。其余业务逻辑不做改动。

---

## 免责声明与法律边界

本项目是**非官方**的 Android 打包工程，与上游项目、任何模型供应商均无隶属或背书关系。

- **上游来源**：服务代码取自 [dsh-our-free-model](https://github.com/Ebony-Vinyl/dsh-our-free-model)（MIT）；Magpie 引擎取自 [yetone/magpie](https://github.com/yetone/magpie)（MIT，钉子 `6a900e0d048e592cbc9120dd2c3f515aab9ef04a`，以 `nogui` 标签构建）；渠道实现基于 Jet（iJetLi）的 MIT 渠道包再构建。各自的 `LICENSE` 与 `NOTICE` 随 `assets/app/vendor/` 一同分发。
- **Node 运行时**：取自早期官方 Android 安装包，其分发许可需由使用者自行确认；若你是权利人且不希望被这样分发，请开 issue 告知，我们会移除相关制品。
- **不提供任何额度或账号**：本项目不包含、不代理、不出售任何模型 API 额度；模型可用性、地区限制、账号渠道规则完全由上游及其供应商决定。
- **不做模型内容担保**：模型输出由上游服务与供应商产生，本项目不对其准确性、合法性或适合作性做任何保证。
- **仅本机监听**：网关固定 `--no-lan`，不开放局域网 / 公网访问；即便自行修改为监听局域网，也请评估暴露风险。
- **应用自身唯一的外联**：设置页「检查更新」会向 `api.github.com` 请求本仓库最新 Release 的版本号。该请求不携带任何设备标识、不携带 API Key 或管理密钥、不带安装 ID；服务端日志里能看到的只有 GitHub 一侧常规的 IP 与 UA。除此之外，应用本体不向任何第三方发送数据——模型请求全部由你配置的渠道地址处理，与本项目无关。
- **按「现状」提供**：本项目按 MIT 许可「AS IS」提供，不附带任何明示或默示担保。使用本项目造成的账号风险、数据损失或其他后果由使用者自行承担。
- **合规使用**：请遵守上游项目条款、各模型供应商的服务条款以及你所在地区的法律法规。不建议将其用于任何规避供应商限制的用途。

---

## 开发与反馈

- 应用内「提交议题到仓库」直接指向本仓库；设置页有到[模块版仓库](https://github.com/luoyi0914/ofm-gateway-magisk)与[上游服务](https://github.com/Ebony-Vinyl/dsh-our-free-model)的入口。
- 变更记录见 [CHANGELOG.md](CHANGELOG.md)。版本号以根目录 `version.properties` 为唯一来源，正式包不带 `-dev` / `-SNAPSHOT` 后缀。

## 许可

本仓库自有代码（Android UI、Rust JNI 监督器、构建脚本）以 [MIT](LICENSE) 许可发布。随包分发的第三方组件保留其原有许可，见上文与 `assets/app/vendor/*/LICENSE`。

## 相关项目

- 上游服务：[Ebony-Vinyl/dsh-our-free-model](https://github.com/Ebony-Vinyl/dsh-our-free-model)
- 同源 Magisk / KernelSU 模块版：[luoyi0914/ofm-gateway-magisk](https://github.com/luoyi0914/ofm-gateway-magisk)
- Magpie 模型引擎：[yetone/magpie](https://github.com/yetone/magpie)
