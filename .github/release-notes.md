这是 Android Memory Noise Generator 的**第一阶段实验版本**，用于近场电磁探测实验中的稳定、可控、可重复内存写入。App 不采集电磁信号。

包含功能：

- Pattern：IDLE、ZERO、ONE、AA、55、AA55，以及自定义 HEX Pattern。
- Continuous：持续循环写入整个测试 Buffer。
- Burst：按自定义 WRITE / IDLE 时长周期性写入和停止，显示当前状态及 Cycle Count。
- Toggle：整段 Buffer 交替写入 AA / 55，统计 Toggle Count 和 Write Count。
- 内存大小：16、32、64、128、256、512 MB 和自定义 MB；分配失败显示错误。
- 实验计时：Unlimited、10、30、60、120、300 秒和自定义时长，到时自动停止。
- 实时统计：状态、Pattern、内存、已运行时间、累计写入量、平均/当前带宽及循环次数。
- 实验记录：自动生成 Experiment ID，保留最近一次实验的开始/停止时间、配置及统计。
- 屏幕常亮：运行期间保持屏幕唤醒，停止后恢复系统行为。
- 稳定性：单后台 Worker、重复启停保护、STOP 后释放 Buffer；离开页面或旋转会停止实验。

支持 Android 10 及以上。附件为 GitHub Actions 自动构建的 debug 签名实验 APK，versionName 为 0.1.0，versionCode 为 1；无网络或用户文件访问权限。

第一阶段代码已在 HONOR ALI-AN00（Android 15）通过真机测试，覆盖 512 MB 分配失败恢复、快速启停、旋转清理、START/STOP 按钮与屏幕常亮。CI 自动执行 APK 构建、JVM 测试和 Android Lint；CI 不执行真机测试。

**实验解释边界：**观察到 Pattern 之间的近场差异，并不能直接证明探测到了 RAM 中存储的 0/1。变化可能同时来自 CPU、Cache、Memory Controller、DDR Bus、PMIC、DVFS 等因素。Burst 不保证硬实时，应用统计带宽不等于物理 DDR 带宽。
