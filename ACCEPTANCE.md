# 第一阶段验收记录

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
