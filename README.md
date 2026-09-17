# Android Memory Noise Generator

当前开发版本：**0.2.0-beta.2**（versionCode 2）。本轮不创建 Tag 或 GitHub Release。

原生 Kotlin Android App，用于近场电磁探测实验中的受控内存活动。只产生内存写入，不采集电磁信号；无网络权限、文件权限、账号、数据库、后台服务或 root 功能。minSdk 29（Android 10），compile/targetSdk 36。

## 构建与安装

使用 JDK 17+（本机验证使用 Android Studio 自带 JDK 21）、Android SDK Platform 36 / Build Tools 36.0.0。Gradle Wrapper 9.4.1 + Android Gradle Plugin 9.2.0，使用其内置 Kotlin。

配置 `local.properties` 的 `sdk.dir` 为自己的 SDK 路径，然后执行：

```sh
./gradlew assembleDebug
./gradlew test lint
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.example.memorynoise/.MainActivity
# 已连接并授权 USB 调试的设备：
./gradlew connectedDebugAndroidTest
```

Windows PowerShell 使用 `.\gradlew.bat`。Linux/macOS 首次使用若缺少执行权限，先执行 `chmod +x gradlew`。如果 JAVA_HOME 指向旧版本 Java，请改为 Android Studio 的 `jbr` 目录。

APK：`app/build/outputs/apk/debug/app-debug.apk`。首次构建需要下载构建依赖；App 本身不进行网络通信。

## GitHub Release

推送与 Android `versionName` 一致的 `v*` tag（例如 `v0.1.0`）会触发 `.github/workflows/release.yml`。Actions 使用 Java 21 和 Android SDK 36 构建 debug APK，执行 JVM 测试及 Lint，然后自动创建 GitHub Release 并上传 `android-memory-noise-generator-v0.1.0.apk`（文件名随 tag 变化）。Release Notes 来自 `.github/release-notes.md`，后续版本发布前同步更新。

下载：[GitHub Releases](https://github.com/yunfei00/android-memory-noise-generator/releases)。CI 的 debug 签名与本机构建签名可能不同，跨签名安装不能直接覆盖；本项目尚未配置长期 release 签名密钥。

## Pattern 与模式

| Pattern | 重复写入内容 |
| --- | --- |
| IDLE | 分配测试 Buffer 后不主动写入，用作基线 |
| ZERO | 00 |
| ONE | FF |
| AA | AA，即 10101010 |
| 55 | 55，即 01010101 |
| AA55 | AA 55 AA 55… |
| CUSTOM | 偶数个 HEX 字符，例如 12345678、DEADBEEF，最多 4096 字节 |

- **Continuous**：连续写整个 Buffer，完成一遍记一次 Loop / Write。
- **Burst**：按 Write/Idle 时长交替运行，默认各 1000 ms。采用 `System.nanoTime()` 的固定时间基准，按绝对相位计算边界，避免每周期累积相对休眠误差。写操作每 64 KiB 检查取消和时间；空闲使用可中断休眠，不忙等。边界允许截断一遍 Buffer，下次 WRITE 从该位置继续。Cycle Count 为已过去的完整周期；极端调度延迟下可能跳过某些相位，不能保证硬实时。
- **AA ↔ 55 Toggle**：忽略 Pattern 选择，完整写 AA 后完整写 55，循环进行。Write Count = 完整 Buffer 遍数，Toggle Count = 已完成遍数减一（首遍 AA 不计切换）。最后未完成的一遍计入 Total Written，但不计入 Write/Toggle Count。
- **Pattern Sweep / Complement Sweep**：按顺序运行 128 组 `00 ↔ FF`、`01 ↔ FE`、…、`55 ↔ AA`、…、`7F ↔ 80`。每组 A 为组号（0–127），B 为 A 的逐位取反。反复对同一个 Buffer 完整写 A、完整写 B，而不是只写一次，也不是将 AA55 字节交错填充。保留 64 KiB 分块；新组从 Buffer 起点、A 开始。组间停止写入并等待 Idle Gap；最后一组结束后直接停止并释放 Buffer，无末尾 Gap。

## 在频谱仪旁使用 Sweep

选择 Mode → Pattern Sweep。单组时间可选 10/20/30/60 秒或 Custom（1–3600 秒），Idle Gap 可选 0/1/2/5 秒或 Custom（0–60 秒）。默认仍是 **20 秒/组、2 秒 Gap**。Sweep 不使用普通模式的总时长设置。

START 后隐藏配置区域，大字显示 Pattern Sweep、当前序号 / 128、当前互补 Pattern、Remaining、Next。WRITE 为绿色，IDLE GAP 为橙色，PAUSED 为蓝色。Gap 时 Remaining 表示 Gap 剩余时间；同时保留分配内存、当前/平均带宽、总写入量和实际经过时间。

- PAUSE 请求在分块边界确认，确认前显示 PAUSING，确认后显示 PAUSED。PAUSED 期间测试 Buffer 没有主动写入，当前带宽为零，组号与阶段剩余时间冻结；Buffer 保留，以便继续同一内存区域。
- RESUME 保留当前组、A/B 遍数奇偶和未完成分块偏移。暂停在 Gap 时继续剩余 Gap，暂停在 WRITE 时继续当前写入，不回到 00。
- STOP 可在 WRITE、GAP、PAUSED 中使用。Worker 退出、Buffer 引用清空后才允许重新 START；旋转、重建或离开页面也会停止。
- 每个阶段用单调时钟计时，逐组推进；调度迟到不跳过 Pattern，并给下个阶段完整配置时长。因此总时间可能略长于 `128 × 单组时间 + 127 × Gap`，再加上暂停时间。不是硬实时控制器。
- Sweep Toggle Count 为每组已完成 A/B 全 Buffer 遍之间的转换次数，各组第一遍不计 Toggle；Total Written 包含停止/换组时的部分遍。

快速人工验收可在界面临时选择 Custom 2 秒、Gap 1 秒，运行前几组；不会修改正式默认值。自动真机测试还会用 2 秒/组、零 Gap 实际遍历到 55↔AA，耗时约三分钟。

内存预设 16/32/64/128/256/512 MB，可自定义 1–2047 MB；界面 MB 统一按 MiB（1024² bytes）解释。实际分配受设备进程堆上限限制，未启用 largeHeap。分配失败显示错误，可降低容量后重试。

实验时长支持 Unlimited、10/30/60/120/300 秒和自定义 1–86400 秒。计时从 Buffer 分配和模板准备完成后开始；Start Time 是按下 START 时的墙上时钟，Stop Time 是完成清理的墙上时钟，因此二者之差包含分配时间。

## 实现与统计口径

`ExperimentEngine` 只持有配置、统计和单个后台线程，不持有 Activity/Context/View。`PatternWriter` 隔离 managed-memory 写入，后续可替换为 JNI 实现。单字节使用批量 fill，多字节使用 64 KiB 模板 arraycopy，按 Buffer 绝对偏移保持 Pattern 连续。模板准备不计写入量；arraycopy 也会引入模板读取流量。

Total Written 是已完成分块的目标写入字节数。Average Bandwidth = Total Written / 已运行时间（包括 Burst IDLE、Sweep Gap 和 PAUSE）；Current Bandwidth 为最近约 200 ms 采样区间的平均值，IDLE/GAP/PAUSED 时显示 0。单位 MiB/s，**不是实际 DDR 带宽**。UI 每约 100 ms 刷新，显示状态存在刷新/调度延迟，不能充当精确仪表触发信号。

STOP 设置取消标志并中断休眠；写入在下一个分块边界结束。清理在 finally 中移除 Buffer 强引用，不强制 GC；这意味着可被回收，不保证进程 RSS 立即下降。STOPPING 期间禁止 START，旧线程真正终止后才能重新启动。内存分配本身不可中断，分配中 STOP 要等待分配返回或失败。

离开 Activity（包括 Home、旋转、重建）自动停止，配置通过 saved instance state 恢复，最近结果在进程内保留。不会自动恢复写入。进程被系统终止时操作系统回收内存，最近结果不会跨进程保存。运行时屏幕常亮，停止或离开页面后清除标志。小屏、横屏和大字体情况下允许滚动。

## 基本实验步骤

1. 固定手机位置、探头位置/方向、屏幕亮度、供电方式和频谱仪设置。
2. 先选择 IDLE、64 MB、30 秒，采集基线；分配阶段会由虚拟机清零内存，应排除该瞬态。
3. 以相同容量和时长分别运行 ZERO、ONE、AA、55、AA55、CUSTOM，多次重复记录 Experiment ID。
4. 使用 Burst 1000/1000 ms，再比较 100/100、500/500 等周期，观察是否存在可重复的同步变化。
5. 使用 Toggle 对比整段 AA/55 交替。每组记录设备温度、供电、仪表参数和结果；控制温升并留出相同冷却时间。
6. STOP 后确认 STOPPED、Allocated = 0，再进行下一组。

**观察到 Pattern 之间的近场差异，并不能直接证明探测到了 RAM 中存储的 0/1。** 近场变化可能同时受 CPU、Cache、Memory Controller、DDR Bus、PMIC、DVFS、屏幕和系统后台活动影响。Managed array 写入可能被 Cache 吸收；本 App 不控制 DDR 锁频、Cache flush 或物理总线事务。只产生内存写入必要的 CPU 开销，没有额外 CPU/GPU 压力任务。

## 测试

JVM 测试覆盖 Pattern 跨块/尾部对齐、非法输入、计时与带宽、快速重复 Start/Stop、模拟 512 MB OOM 后恢复、IDLE 零写入、Burst 空闲可中断和周期计算、Toggle 计数。

设备 instrumentation 测试覆盖真实 512 MB 分配（失败或成功均必须正常结束）、失败后重新运行、旋转自动停止与 Buffer 清理、快速启停、Burst 周期、实际 START/STOP 按钮和常亮标志恢复。详见 `ACCEPTANCE.md`。

第一阶段未添加 CSV 导出或 JNI。后续建议先开展更长时间的设备稳定性和外部仪表相关性实验，再评估 JNI/Native Buffer 与 CSV 导出。
