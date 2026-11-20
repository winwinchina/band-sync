# BandSync v0.1 — 智能手表一键同步（诊断版）

## 这是干嘛的
一个最小 Android App：**扫描 → 连手表 → 按 Veepoo 协议握手读数据 → 原始回包 dump + 写 CSV**。
当前是**诊断版**，第一目标是确认你那块 Z06 到底跑不跑 Veepoo/S226 协议，不是成品。

> 设备身份（已联网核实）：Z06 = Makibes Z06，芯片 Nordic NRF52832，配套 APP 正是 **H Band**（Veepoo 系）。链条 Z06 → H Band → Veepoo → F008 服务，与实测"找到 F008"吻合。（2026-11-20 之前误记为 Z60，已更正。）

## 为什么这么写
你的需求演进：截图给 AI 解析 → 太繁琐 → 想一键同步。Z06 无导出功能、APP 缓存读不到（沙箱）、BLE 独占连接，只剩"自己写 APP 直连手表读"这一条路。协议公开，故有此工程。

工程路径（库外，按 AGENTS.md 约定）：`D:\AI-Projects\band-sync`

## 怎么用（两条路，云端最省事）

### 路线 A：云端编译（推荐，本机无需装任何东西）
本机 Dell 没装 Android SDK / Studio，用 GitHub Actions 免费云端编译出 APK：

1. 把整个 `band-sync` 目录推到 GitHub 一个新仓库（公开或私有都行，私有每月也有 2000 分钟免费额度，够用）。
2. 进仓库 → Actions 标签 → 左侧点 `Build BandSync APK` → 右侧 `Run workflow` → 再点一次绿色按钮。
3. 等 2–5 分钟跑完（变绿），页面底部 `Artifacts` 里下载 `bandsync-debug-apk`，解压得到 `app-debug.apk`。
4. 把 APK 传到安卓手机安装（debug 版自带签名，直接能装）。

⚠️ 三个已修好的坑（别再改回去）：
- `build.gradle` 里**不能有** `allprojects { repositories }` —— 与 `settings.gradle` 的 `FAIL_ON_PROJECT_REPOS` 冲突，会构建失败。仓库统一在 settings.gradle 声明。
- 本工程**缺 `gradle-wrapper.jar`**，所以 workflow 里删掉 wrapper 目录、改用 `setup-gradle` 云端装 Gradle 8.2 + `gradle assembleDebug`，**不跑 `./gradlew`**。
- AGP 8.1.4 需 **JDK 17**（不是 8），workflow 已配 `setup-java@v4` + temurin 17。
- 如果国内 `git push` 超时但网页能开，可用镜像 `git push https://ghfast.top/https://github.com/<用户>/<仓库>.git`；或用 GitHub Contents API 逐文件上传触发构建。

### 路线 B：本地编译（需装 Android Studio）
1. Android Studio 打开本目录（AGP 8.1.4 / compileSdk 34 / minSdk 21），Studio 会自动补齐 wrapper 并下载依赖。

### 装上手机后的操作步骤（两条路线通用）
2. **先彻底退出 H-Band**，系统蓝牙设置里"忘记此设备"解除配对 —— BLE 独占，不清干净连不上。
3. 打开 App，授权蓝牙/附近设备/定位权限，打开手机蓝牙。
4. 点亮手表屏幕（唤醒广播）。
5. 点「1 扫描」等 10 秒 → 设备列表按信号强度列出。
6. 点「2 连接并同步」→ 看日志区输出。
7. 点「复制日志/CSV 路径」拿到 CSV 位置（在 App 外部私有目录 `files/bandsync.csv`）。

## 怎么判读日志（关键）
| 日志出现 | 结论 | 下一步 |
|---|---|---|
| `没有 F008 服务` | ❌ 不是 Veepoo 协议 | 把打印出来的服务 UUID 列表发给 AI，换协议重写 |
| `找到 F008 服务` | ✅ 协议对上了 | 往下看握手 |
| `握手状态：…成功 ✅` | ✅ 密码 0000 有效 | 能读到数据 |
| `步数A8=...` 有非零值 | ✅ 步数命令确认 | 可正式做同步 |
| `没收到任何 notify` | ⚠️ H-Band 没退干净 / 表没唤醒 / 密码非0000 | 逐项排查 |

## 协议出处（承重墙，已联网核对）
- 项目：github.com/Grumbel/S226 —— `PROTOCOL.md` + `VeePoo_HBand_BLE_Protocol.md`
- 两源一致：服务 `f0080001-…`、写特征 `f0080003-…`、通知特征 `f0080002-…`；握手 `A1` 包带当前时间，默认密码 `0000`。
- **两源有分歧（代码里两种都试 + 原始包全 dump）**：
  - 电量：`A0 00`，一源说回包 `[4]` 是 0–4 档，另一源说 `[2]` 是 `0x80|百分比`。
  - 步数：一源用 `A8`（`[1..4]` 大端），另一源用 `D8`（今日统计，逐字节布局未公开）。
- 命令间隔 ~120–200ms（官方 App 节奏，太短会丢包）；每包固定 20 字节补齐。

## v0.2 修复说明（2026-11-20 二次实测后）
- 第一次实测日志：F008 服务✅、CCCD 订阅 `onDescriptorWrite status=0`✅、握手包字节正确✅，但 0 notify、且全程无 `onCharacteristicWrite` 回调。
- 定位：Veepoo/S226 协议原文（Grumbel/S226 PROTOCOL.md）明确——命令用 **Write Command（无应答，WRITE_TYPE_NO_RESPONSE）**，CCCD 才用 Write Request。原代码未设 writeType，Android 默认成需应答的 Write Request，设备不回 response，导致命令回调永不触发、也不回数据。
- 修复：① `writeRaw()` 显式设 `WRITE_TYPE_NO_RESPONSE`；② 握手包 offset[13] 由 0 改为 0x04（H-Band 抓包原文 `01 01 04`）；③ 加长命令间隔、finishSync 延到 5.5s；④ 写返回值/writeType 全打日志。
- 源码出处：https://github.com/Grumbel/S226 （PROTOCOL.md + VeePoo_HBand_BLE_Protocol.md）。

## 已知约束
- 手表 BLE 只认一个 central：H-Band 必须退干净，否则连不上（即使广播还在）。
- 采集端必须常驻手表旁边（BLE ~10m），手机带走就断。
- 消费级参考数据，非医疗级。
- 密码非 0000 的情况本版未处理（需从改造包/抓包拿）。

## 下一步（诊断通过后）
把"读一次"扩展成"读今日步数/心率/睡眠 + 固定字段 CSV"，落到本地或在线表格。分析留到攒够数据后再说。
