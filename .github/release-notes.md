# Android Memory Noise Generator v0.2.1

这是 v0.2.0 的修正版，重点修复部分 Android / OEM Launcher 安装 GitHub Release APK 后桌面只显示图标、不显示应用名称的问题。

本版本修复：

- 为 Application 和 Launcher Activity 显式配置应用名称 `Memory Noise`。
- 新增明确的 Launcher 图标资源，并在 Manifest 中显式设置 `android:icon` / `android:roundIcon`。
- GitHub Actions 发布前不再只检查应用名称，还会检查 Application 与 Launcher Activity 的图标资源是否实际打进 APK；缺失时直接阻止发布。
- versionName 更新为 `0.2.1`，versionCode 更新为 `4`。

主要功能保持不变：

- Pattern：IDLE、ZERO、ONE、AA、55、AA55、自定义 HEX。
- Continuous / Burst / AA↔55 Toggle。
- Pattern Sweep：自动遍历 128 组互补 Pattern：`00 ↔ FF`、`01 ↔ FE`、…、`55 ↔ AA`、…、`7F ↔ 80`。
- Sweep 支持 10/20/30/60 秒和自定义组时长、0/1/2/5 秒和自定义 Idle Gap。
- PAUSE / RESUME / STOP。
- 大字体显示当前组、当前 Pattern、Remaining 和 Next。
- 实时统计内存、累计写入、平均/当前带宽、Elapsed、Loop/Toggle 等。

安装说明：

- 推荐先卸载旧的 `0.2.0`，再安装 `0.2.1`，以避免部分桌面 Launcher 缓存旧的图标/名称元数据。
- 安装后桌面名称应显示为 **Memory Noise**。
- 推荐下载 `android-memory-noise-generator-v0.2.1-package.zip`，其中包含 APK、中文使用说明、Release Notes、版本信息和 SHA256 校验文件。

App 无网络权限和用户文件访问权限。GitHub Actions 生成的 APK 使用 CI debug 签名；如果旧版本签名不同，需要先卸载旧版再安装。

**实验解释边界：**观察到不同 Pattern 的近场差异，并不能直接证明探测到了 RAM 中存储的 0/1。变化可能同时来自 CPU、Cache、Memory Controller、DDR Bus、PMIC、DVFS 等因素。Sweep/Burst 受 Android 调度影响，不保证硬实时，应用统计带宽也不等于物理 DDR 带宽。
