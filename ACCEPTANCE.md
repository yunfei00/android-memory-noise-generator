# 验收记录

## 0.2.0-beta.2 Pattern Sweep（2026-09-17）

版本保持 `versionName=0.2.0-beta.2`、`versionCode=2`。设备为已连接的 HONOR ALI-AN00 / Android 15。正式默认值仍为 20 秒/组、2 秒 Gap，未为验收修改默认配置。

执行结果：

- `.\gradlew.bat assembleDebug test lint`：BUILD SUCCESSFUL。17 项 JVM 测试全部通过；Lint 0 errors、26 项非阻断警告。
- `.\gradlew.bat assembleDebug assembleDebugAndroidTest`：成功。
- `adb install -r`：主 APK 和测试 APK 均安装成功，主 APK 的版本已用 aapt 和设备包信息核验。
- `adb shell am start -n com.example.memorynoise/.MainActivity`：启动成功。
- `adb shell am instrument -w com.example.memorynoise.test/androidx.test.runner.AndroidJUnitRunner`：**OK (8 tests)**，耗时 239.228 秒。
- 主 APK 没有声明网络、存储或其他 uses-permission。截图写入仅存在于独立 instrumentation 测试代码中，生产 App 未增加文件 I/O。

| 检查 | 结果与范围 |
| --- | --- |
| 128 组真正覆盖 Buffer | 单元测试使用同一个数组，对全部 128 组各写 A/B/A/B 四遍，逐字节验证每遍结果，包括 64 KiB 边界和尾部。B 均为 A 的逐位取反。生产 Worker 使用同一个 ComplementWriter；每一遍在同一个测试 Buffer 上写入。 |
| Sweep 正确性 | 改为逐阶段推进，调度迟到不跳组；Pattern 文案只在切组时格式化，热循环不再每块创建字符串/Pair。末组不追加 Gap。完整 128 组时间线、零 Gap、迟到与末组结束均有单元测试。 |
| WRITE / GAP / PAUSE | 真机界面临时选择 2 秒/组、1 秒 Gap，验证 00↔FF、倒计时递减、进入 Idle Gap、自动进入 01↔FE。WRITE 与 GAP 均能暂停和恢复。 |
| 暂停真的停止写入 | 单元测试直接保留引擎分配的真实数组，在 PAUSED 确认后复制并比较，等待超过整组时长后仍逐字节相同；总写入、组号和剩余时间也不变。Gap 同样验证数组不变。真机验证暂停计数/剩余时间稳定、当前带宽为零。 |
| 55↔AA 真机观察 | 使用真实 2 秒/组、零 Gap，从 00 开始顺序运行约 170 秒到 #86 / 128；检查未跳组。界面显示 55↔AA、Remaining、Next 56↔A9。暂停 2.2 秒后恢复，验证同组继续完成多遍写入及 Toggle 增长，再自动进入 56↔A9。没有修改时钟或从 55 起跑。 |
| STOP / 重复启动 | 真机 10 次 Sweep START/STOP，重复 start 被拒绝；join 确认线程退出，hasBuffer=false，STOPPED 后可重新开始。单测另覆盖 25 次快速 Start/Pause/Resume/Stop，以及 PAUSED 中 STOP。 |
| 生命周期 | Sweep 暂停期间旋转屏幕：Worker 退出且 Buffer 释放；恢复页面后重新启动再将 Activity 移至后台停止状态，确认无遗留 Worker。 |
| 原模式回归 | 原有 4 项真机测试全部通过，包含 OOM 恢复、按钮/常亮、旋转、Burst/快速启停；新增设备测试再次验证 Continuous、Burst、Toggle 的实际写入、计数及清理。 |
| 人工可读性 | 已查看真机 WRITE、暂停 GAP、暂停 55↔AA 截图，Pattern、序号、Remaining、Next、按钮与统计竖屏同屏可见。WRITE/IDLE GAP/PAUSED 有不同状态和颜色。 |

本轮没有进行频谱仪信号采集，也没有宣称观察到 RAM 0/1 的电磁信号；验收范围是 App 的控制行为和界面。Burst/Sweep 仍受 Android 调度影响，不保证硬实时。暂停保留 Buffer，STOP 清除引用但不强制 GC。没有新增网络、文件导出、GPU 负载、Tag 或 GitHub Release。

可复验的测试源码为 `SweepTest.kt` 和 `SweepDeviceAcceptanceTest.kt`；本机完整设备输出保存在被 Git 忽略的 `app/build/device-acceptance.txt`，截图在 `app/build/sweep-*.png`。

## 第一阶段（历史验收）

日期：2026-09-16。设备：HONOR ALI-AN00，Android 15 / API 35，应用堆上限 384 MiB。

## 构建和自动化测试

使用 Android Studio JBR 21、SDK 36、AGP 9.2.0、Gradle 9.4.1：

```powershell
$env:JAVA_HOME='D:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug connectedDebugAndroidTest
```

最终结果：BUILD SUCCESSFUL；9 项 JVM 测试、4 项真机 instrumentation 测试全部通过。

APK：`app/build/outputs/apk/debug/app-debug.apk`。通过 aapt 检查，主 APK 未声明任何 uses-permission（包括网络/存储权限）。

Lint：0 errors，20 warnings。警告为更新 SDK/Gradle 提示、未设置专用图标、备份配置建议及固定文案未国际化；第一阶段使用本机已安装的 SDK 36，未压制 Lint 错误。

## 验收项目

| 项目 | 结果及证据范围 |
| --- | --- |
| 明显内存泄漏 | 控制器不引用 Activity/Context/View；onStop 移除 UI 回调；反复启停和旋转后测试 Buffer 引用为空。未进行长时间堆转储分析，不能据此声称完全没有泄漏。 |
| STOP 后 Worker 停止 | JVM 25 次重复启停、真机 10 次重复启停，join 验证线程退出；运行时重复 START 被拒绝。 |
| STOP 后 Buffer 释放 | finally 清空引用，测试断言 hasBuffer=false / allocated=0；不强制 GC，不承诺 RSS 立即降低。 |
| Burst 周期 | 单调时钟固定原点，64 KiB 写入边界检查，IDLE 可中断休眠；测试验证 IDLE 字节数不增长、长 IDLE 可停止、周期统计按时间计算。尚未用外部仪表测量时序抖动。 |
| Bandwidth | 按完成分块的目标字节累计，平均值除以包括 IDLE 的运行时间；单元测试验证公式和整遍数关系。未测量物理 DDR 总线吞吐。 |
| 512 MB 分配失败 | 真机进程堆 384 MiB，512 MiB 分配触发被捕获的 OOM；测试断言错误非空、Buffer 释放，并成功重新运行 16 MiB。 |
| 旋转 / Activity 重建 | 真机切换横屏，验证 Worker 退出、Buffer 清空、停止原因为页面离开/重建。ActivityScenario 管理测试 Activity，确保用例之间无遗留页面。 |
| 常亮与按钮 | 真机操作 START/STOP，验证运行中常亮、START 禁用；停止后常亮标志清除、START 恢复。 |
| Pattern / Toggle | 单元测试对 00/FF/AA/55/AA55/123456/DEADBEEF 校验跨 64 KiB 边界及尾部所有字节；验证 Toggle 与完整写入次数关系。 |
| UI | 已查看真机截图，竖屏主要配置、按钮、状态、统计同屏可见；小屏、横屏或长 Custom Pattern 允许滚动。 |

## 创建 / 修改文件

- 工程：`settings.gradle.kts`、`build.gradle.kts`、`gradle.properties`、`gradlew`、`gradlew.bat`、`gradle/wrapper/*`、`.gitignore`。
- App：`app/build.gradle.kts`、`app/src/main/AndroidManifest.xml`。
- 核心：`app/src/main/java/com/example/memorynoise/Experiment.kt`。
- 界面：`app/src/main/java/com/example/memorynoise/MainActivity.kt`。
- 测试：`app/src/test/java/com/example/memorynoise/ExperimentTest.kt`、`app/src/androidTest/java/com/example/memorynoise/DeviceAcceptanceTest.kt`。
- 文档：`README.md`、`ACCEPTANCE.md`。
- 本机生成 `local.properties`，已忽略，不提交机器路径。

## 已知限制与下一阶段

- 旋转或离开页面会结束当前实验，不会在后台继续；最近结果仅保存在当前进程。
- 普通 Android 调度不是硬实时，Burst 边界有分块写入和调度延迟；UI 刷新约 100 ms。
- JVM 数组分配不可中断；分配期间点击 STOP 须等待分配成功或失败。
- 不控制 Cache、DVFS 或 DDR 总线，观测差异不能直接解释成 RAM 中 0/1 的辐射。
- 只完成该手机上的短时验收，Android 10 等其他系统及长时间运行尚未实机验证。
- 下一阶段优先做固定仪表条件下的重复性、周期抖动及长时稳定性实验，再评估 JNI / Native Buffer 和 CSV 导出；当前未加入这些功能。
