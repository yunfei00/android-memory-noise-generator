# Android Memory Noise Generator v0.2.0

这是首个包含 **Pattern Sweep** 的正式实验版本，用于近场电磁探测实验中的稳定、可控、可重复内存写入。App 不采集电磁信号。

主要功能：

- Pattern：IDLE、ZERO、ONE、AA、55、AA55，以及自定义 HEX Pattern。
- Continuous：持续循环写入整个测试 Buffer。
- Burst：按自定义 WRITE / IDLE 时长周期性写入和停止。
- AA ↔ 55 Toggle：整段 Buffer 交替写入 AA / 55。
- Pattern Sweep：自动遍历 128 组互补 Pattern：`00 ↔ FF`、`01 ↔ FE`、…、`55 ↔ AA`、…、`7F ↔ 80`。
- Sweep 每组时长支持 10/20/30/60 秒和自定义；组间 Idle Gap 支持 0/1/2/5 秒和自定义。
- Sweep 支持 PAUSE / RESUME / STOP；暂停期间停止主动内存写入，恢复后继续当前组。
- 大字体显示当前组、当前 Pattern、Remaining 和 Next，便于实验人员站在频谱仪旁人工观察。
- 实时统计：状态、分配内存、累计写入、平均/当前带宽、Elapsed、Loop/Toggle 等。
- 内存大小：16、32、64、128、256、512 MB 和自定义 MB；分配失败可恢复。
- 单后台 Worker，重复启动保护；STOP、离开页面和 Activity 重建时清理 Worker 和 Buffer 引用。

版本信息：

- versionName: `0.2.0`
- versionCode: `3`
- minSdk: Android 10 / API 29
- targetSdk: 36

验收：

- `assembleDebug`、`test`、`lint` 均已通过。
- 17 项 JVM 测试通过，Lint 0 error。
- HONOR ALI-AN00 / Android 15 真机 instrumentation 8 项测试通过。
- 真机验证覆盖 `00 ↔ FF`、`55 ↔ AA`、自动切组、倒计时、Idle Gap、Pause/Resume、STOP/释放、重复启停和生命周期清理。
- Continuous、Burst、Toggle 原模式回归通过。

附件为 GitHub Actions 自动构建的 debug 签名实验 APK。App 无网络权限和用户文件访问权限。

**实验解释边界：**观察到不同 Pattern 的近场差异，并不能直接证明探测到了 RAM 中存储的 0/1。变化可能同时来自 CPU、Cache、Memory Controller、DDR Bus、PMIC、DVFS 等因素。Sweep/Burst 受 Android 调度影响，不保证硬实时，应用统计带宽也不等于物理 DDR 带宽。
