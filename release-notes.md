## OFM Gateway 0.9.5

无 root 的本机 OpenAI 兼容网关：APK 自带 Node 运行时与 Magpie 引擎，服务只监听 `127.0.0.1`，数据不出设备。

这一版修好了「装社区插件必定失败」。

### 问题在哪

在「发现」页装 `@magpie-community/*` 插件时，会看到两条报错：

```
bun add @magpie-community/opencode-mimo-auth@latest: fork/exec
/data/user/0/cn.luoyi0914.ofmgateway/files/ofm-data/magpie/cache/magpie/bun/1.4.2/magpie-bun:
permission denied
(and from the community repo's dist branch: … read: connection reset by peer)
```

第一条是主因，第二条是被牵连的。Magpie 引擎执行 `bun add` 前会把一份 Bun
下载进自己的缓存目录（`ofm-data/magpie/cache/magpie/bun/<版本>/magpie-bun`），再直接
`fork/exec` 它。Android 从 10 起就禁止执行应用私有目录里落盘的文件（W^X），
所以这一步**每次**都会 `permission denied`。引擎把这次失败当成「装包失败」，
于是又去回退社区仓库的 dist 分支；而那条回退只是绕过 npm 直接取 tarball，
最终仍要 bun 可执行，于是第二次也失败，两条错误拼在一起显示。

### 改了什么

- **Bun 随 APK 分发**：以 `libbun.so` 的形式打进 `nativeLibraryDir`（arm64，
  约 83 MB）。那个目录是只读、可执行的，Android 本来就要求从那里 exec 原生库，
  所以不存在上面的限制。启动 Node 网关时用 `OFM_MAGPIE_BUN` 把路径传给引擎，
  引擎读到 `MAGPIE_BUN` 就直接用它，不再下载、不再往私有目录落盘。
- **`libbun.so` 不能被 strip**：AGP 默认对 `jniLibs` 里的 `.so` 跑
  `stripReleaseDebugSymbols`，第一次构建时它把 Bun 的符号表裁掉了
  （86,800,440 → 86,739,592 字节），我们校验过的二进制和用户装到的就不是同一份。
  已用 `keepDebugSymbols` 单独豁免，并在 `verify-release.sh` 里加了字节级
  sha256 断言——被改写就拦住不发版。
- **「国内镜像」默认打开**：新装默认写 `chinaMirror: true`，让社区仓库 dist
  分支走 jsDelivr、npm 走 npmmirror。用户显式关过的保持关闭。

### 你应该感受到的变化

- 装 `@magpie-community/*` 插件不再报 `permission denied`，也不需要先自己
  准备 Bun。
- 大陆网络下插件列表与 npm 源明显更容易通。

### 代价

APK 从 60.1 MB 涨到 93.7 MB。Bun 二进制本身 83 MB（压缩后约 33 MB），
这是「随包分发」的固有体积；换来的是首次装插件不再依赖联网下载 + 私有目录执行。

### 已知限制

- 仅 `arm64-v8a`；未开放局域网访问；Shizuku/root 后端仍是占位；极端省电策略下的长时保活未实测。
- 从 0.9.4 升级后**首次启动会重新解包运行时**（解包标记绑 `versionCode`），
  耗时比平时长几秒，属正常。

