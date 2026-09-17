# Android Memory Noise Generator 使用说明

版本：0.2.1

## 1. 工具用途

Android Memory Noise Generator 用于近场电磁探测实验中的受控内存写入。它让 Android 手机按照固定规则持续向一块内存 Buffer 写入指定数据，从而便于实验人员使用频谱仪和近场探头观察不同内存访问 Pattern 是否对应可重复的近场噪声变化。

本工具只负责产生受控内存活动，不采集频谱数据，也不控制频谱仪。

> 注意：观察到不同 Pattern 的近场差异，并不能直接证明探测到了 RAM 中保存的 0/1。实际信号还可能来自 CPU、Cache、Memory Controller、DDR Bus、PMIC、DVFS 和系统后台活动。

## 2. 安装

1. 将发布包中的 `android-memory-noise-generator-v0.2.1.apk` 复制到 Android 手机。
2. 建议先卸载旧版，再安装 v0.2.1，避免部分手机桌面缓存旧的应用名称或图标信息。
3. 在手机上点击 APK 安装。
4. 如系统提示“禁止安装未知来源应用”，按照系统提示允许当前文件管理器或浏览器安装 APK。
5. 安装完成后，桌面应用名称应显示为 **Memory Noise**。
6. Android 10 及以上系统可使用。

如果手机已经安装过本工具，但提示签名不一致无法覆盖，请先卸载旧版本，再安装本版本。GitHub Actions 构建的 APK 使用 CI debug 签名。

## 3. 主界面主要参数

### Memory

测试 Buffer 大小。预设：16 / 32 / 64 / 128 / 256 / 512 MB，也支持 Custom。

界面中的 MB 按 MiB（1024 × 1024 bytes）计算。实际可分配大小受手机单进程堆限制影响。如果出现 OutOfMemoryError，请降低 Memory。

### Pattern

普通模式下可以选择：

- `IDLE`：分配 Buffer 后不主动写入，作为基线。
- `ZERO`：持续写 0x00。
- `ONE`：持续写 0xFF。
- `AA`：持续写 0xAA，即 10101010。
- `55`：持续写 0x55，即 01010101。
- `AA55`：重复写 AA 55 AA 55 …
- `CUSTOM`：用户自定义 HEX Pattern，例如 DEADBEEF。

### Mode

#### Continuous

持续循环写入整个 Buffer，适合比较固定 Pattern 的稳定近场特征。

#### Burst

按照 Write / Idle 时间周期性进行：

`WRITE → IDLE → WRITE → IDLE ...`

适合观察频谱变化是否跟随固定节奏。

#### AA ↔ 55 Toggle

对同一块 Buffer 反复进行完整覆盖：

`AA → 55 → AA → 55 ...`

不是把 AA55 交错写入，而是整块 Buffer 先写 AA，再整块写 55。

#### Pattern Sweep

自动遍历 128 组互补 Pattern：

`00 ↔ FF`

`01 ↔ FE`

`02 ↔ FD`

……

`55 ↔ AA`

……

`7F ↔ 80`

每一组都对同一块 Buffer 反复完整写 A、B、A、B，持续指定时间，然后进入下一组。

## 4. Pattern Sweep 使用方法

这是 0.2.x 的重点功能，适合实验人员站在频谱仪旁人工观察。

推荐首次实验：

- Memory：64 MB
- Mode：Pattern Sweep
- Pattern Time：20 秒
- Idle Gap：2 秒

点击 START 后，界面会突出显示：

- 当前序号，例如 `#86 / 128`
- 当前 Pattern，例如 `55 ↔ AA`
- Remaining 剩余时间
- Next 下一个 Pattern
- Allocated Memory
- Current / Average Bandwidth
- Total Written
- Elapsed Time

每组 Pattern 结束后进入 Idle Gap。Gap 期间停止主动写入，可以帮助实验人员在频谱仪上明显识别 Pattern 切换边界。

128 组全部运行时，20 秒/组 + 2 秒 Gap 总耗时约 47 分钟。

## 5. PAUSE / RESUME / STOP

### PAUSE

暂停当前实验。确认进入 PAUSED 后：

- 不再主动写入 Buffer。
- Current Bandwidth 显示为 0。
- 当前 Pattern、剩余时间和当前阶段被冻结。
- Buffer 保留，不释放。

### RESUME

从暂停位置继续：

- 不会重新从 00 ↔ FF 开始。
- 当前组继续运行。
- 如果暂停发生在 Idle Gap，则继续剩余 Gap。

### STOP

停止实验：

- 后台 Worker 退出。
- Buffer 强引用释放。
- 状态恢复 STOPPED。
- 可以重新 START。

离开页面、旋转屏幕或 Activity 重建时，也会主动停止实验，避免后台遗留 Worker。

## 6. 推荐近场实验流程

为了提高可重复性，建议固定以下条件：

1. 手机位置和方向。
2. 近场探头位置、方向和高度。
3. 手机屏幕亮度。
4. 是否插 USB、电池供电状态。
5. 频谱仪中心频率、Span、RBW、VBW、Detector、Trace 模式。
6. 手机温度范围。

建议顺序：

1. 先运行 IDLE，记录基线。
2. 比较 ZERO、ONE、AA、55、AA55。
3. 比较 AA ↔ 55 Toggle。
4. 使用 Pattern Sweep 自动遍历 128 组。
5. 记录明显高于其他 Pattern 的候选。
6. 对候选 Pattern 单独重复测试多次。

如果 55 ↔ AA 仍然明显最大，可以重点比较：

- 00 ↔ FF
- 33 ↔ CC
- 0F ↔ F0
- 55 ↔ AA

并确认结果是否在多次实验中保持一致。

## 7. 实时统计说明

- `Allocated`：当前已分配测试 Buffer 大小。
- `Time / Elapsed`：当前实验运行时间。
- `Total Written`：已写入目标 Buffer 的累计字节数。
- `Avg`：Total Written / 总运行时间。
- `Now`：最近采样时间窗口内的写入速率。
- `Loops / Writes`：完整覆盖 Buffer 的次数。
- `Cycles`：Burst 周期计数。
- `Toggles`：完整 Buffer Pattern 切换次数。

界面显示的带宽是 App 统计的目标写入速率，不等于真实 DDR 物理总线带宽。

## 8. 常见问题

### 安装后桌面只有图标，没有应用名称

v0.2.1 已增加显式 Launcher 图标和名称元数据，并在 GitHub Actions 发布前强制检查最终 APK 中的名称和图标。若手机上仍保留旧显示，先卸载旧版，再安装 v0.2.1；部分 OEM Launcher 会缓存旧应用的桌面元数据。

### 点击 START 后提示内存不足

降低 Memory，例如从 512 MB 改为 256 MB、128 MB 或 64 MB。

### 安装新版时提示无法覆盖

可能是旧 APK 与 GitHub Actions APK 签名不同。卸载旧版后重新安装。

### Sweep 时间和理论时间有轻微差异

Android 不是硬实时系统，线程调度会产生少量误差。Sweep 采用单调时钟控制阶段，但不保证硬实时边界。

### Pattern 切换时为什么加入 Idle Gap

主要是方便人工观察频谱仪。Gap 期间停止主动写入，使不同 Pattern 的切换更加明显。

### 为什么 App 没有频谱仪控制功能

当前版本目标是把手机端激励做稳定、简单、可重复，避免手机控制和仪表控制同时引入复杂度。当前方案更适合人工观察频谱仪。

## 9. 发布包内容

正式发布 ZIP 包包含：

- `android-memory-noise-generator-v0.2.1.apk`：Android 安装包。
- `USER_GUIDE_CN.md`：本使用说明。
- `RELEASE_NOTES.md`：版本更新说明。
- `VERSION.txt`：版本与提交信息。
- `SHA256SUMS.txt`：APK 校验值。

## 10. 当前版本范围

v0.2.1 已包含：

- 固定 Pattern 内存写入。
- Continuous / Burst / Toggle。
- 128 组 Complement Pattern Sweep。
- Pattern Time 和 Idle Gap 自定义。
- PAUSE / RESUME。
- 大字体实验观察界面。
- STOP / 生命周期清理。
- 实时统计。
- 显式 Launcher 名称与图标资源。

当前未包含：频谱仪自动控制、CSV 导出、JNI Native Buffer、DDR 锁频、Cache flush、GPU/NPU 压力任务。
