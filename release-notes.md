## OFM Gateway 0.9.4

无 root 的本机 OpenAI 兼容网关：APK 自带 Node 运行时与 Magpie 引擎，服务只监听 `127.0.0.1`，数据不出设备。

这一版修好了「模型工作台起不来」。

### 这次是真的修了

上一版（0.9.3）把失败原因打出来之后，真机日志直接给出了答案：

```
Magpie engine start failed: Magpie engine could not start (at lease; EACCES;
permission denied, link '.../magpie/owner.lock.xxxx.tmp' -> '.../magpie/owner.lock')
```

引擎启动时要在数据目录里建一个 `owner.lock` 租约文件，用的方式是**硬链接**
（先写临时文件，再 `link()` 到目标名，这样要么成功要么不覆盖别人的记录）。
问题是部分 Android 设备的 App 存储卷不支持硬链接，`link()` 直接返回 `EACCES`，
于是每次启动都卡在同一处——和引擎二进制、网络、权限都没有关系。

### 改了什么

- **不再依赖硬链接**：`link()` 遇到「该卷不支持硬链接」这类错误码（`EPERM` /
  `EACCES` / `ENOTSUP` 等）时，回退到 `O_EXCL` 独占创建。两者保证一致——要么新建
  成功，要么失败，绝不覆盖已有租约——互斥语义不变；支持硬链接的文件系统仍走原路径。
- **修掉上一版诊断代码的一个自伤 bug**：采集 stderr 与退出码的变量原先声明在
  `boot()` 内部，而报告函数在工厂作用域，重试时会抛 `spawnFailed is not defined`
  把真实原因盖住。现在提到工厂作用域并在每次 spawn 前重置。

### 你应该感受到的变化

- 之前「模型工作台」一直起不来、点重试也没用的设备，这一版应该能正常启动。
- 万一还是不行，日志页照旧会有 `Magpie engine start failed: …`，把那行贴到 issue 即可，它不含密钥。

### 安装

下载 `OFM-Gateway-0.9.4-arm64-v8a.apk` 直接覆盖安装（保留配置、密钥与日志）。仅支持 arm64 设备。

`SHA256` 见随包的 `SHA256SUMS`。

### 已知限制

- 仅 `arm64-v8a`；未开放局域网访问；Shizuku/root 后端仍是占位；极端省电策略下的长时保活未实测。
