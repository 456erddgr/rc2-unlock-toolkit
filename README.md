# RC2 解锁工具箱 / RC2 Unlock Toolkit

> 给 **DJI RC2 带屏遥控器**（平台代号 `rc331`，高通 QCS5430，Android 11）用的 Root / 提权 / 系统调整工具箱。
>
> ⚠️ **本仓库是逆向工程与设备研究记录，不是给普通用户的一键刷机包。** 动手前请完整读完「[风险警告](#风险警告先读这个)」。

---

## 目录

- [这是什么](#这是什么)
- [风险警告（先读这个）](#风险警告先读这个)
- [设备信息](#设备信息)
- [功能一览](#功能一览)
- [怎么用](#怎么用)
  - [方式一：直接装 APK（推荐）](#方式一直接装-apk推荐)
  - [方式二：只用命令行工具](#方式二只用命令行工具)
  - [方式三：自己编译](#方式三自己编译)
- [目录结构](#目录结构)
- [工作原理](#工作原理)
- [下载与文件清单](#下载与文件清单)
- [常见问题](#常见问题)
- [第三方组件与来源说明](#第三方组件与来源说明)
- [免责声明](#免责声明)

---

## 这是什么

DJI RC2 是一台跑 Android 11 的带屏遥控器。它出厂时把 ADB 关着、把开发者选项藏着，普通手段进不去系统。

这个工具箱做的事情是：**利用 RC2 固件里自带的一个工厂测试接口，把 root 权限打开，然后给你一个图形界面来管理这套权限。** 打开之后你可以：

- 用 `su` 拿 root，跑任何命令
- 装自己的 App、改系统设置，绕过原本的限制
- 用 root 通道启动 DJI 藏起来没导出的工厂 Activity（开发助手、产测、设置向导）
- 改 `ro.*` 这类只读系统属性（借 KernelSU 的 `resetprop`，不需要内核模块）
- 管理 KernelSU 风格的模块目录、超级用户白名单

**它不是**一键刷机工具，**不会**帮你解 BL 锁，**也做不到**改内核——见下面的「工作原理」，那部分是这个项目最诚实的地方。

---

## 风险警告（先读这个）

按严重程度排序，每条都是真出过事的：

| # | 警告 | 说明 |
|---|---|---|
| 1 | **🔴 eFuse 熔断不可逆** | 一旦触发，芯片里的那个位**永久烧死**，没有任何软件手段恢复。这是 DJI 用来检测改装的手段。 |
| 2 | **🔴 严禁回锁 Bootloader** | 回锁会因 `vbmeta` 校验失败导致**硬砖**，且售后拒修。已经解锁的就让它保持解锁。 |
| 3 | **🔴 Root 之后千万别升级 DJI Fly** | DJI Fly 自动升级后，安全启动链会检测到 TEE 校验失效，机器卡在 boot logo 起不来。**本项目的开发机就是这么变砖的。** |
| 4 | **🟠 变砖后基本救不回来** | 出事之后 ADB / USB 层面的所有软件恢复手段都会失效，只剩两种选择：拆机从 PCB 测试点进 EDL 9008 模式（需要 1kΩ 电阻 + 万用表 + 手工焊接），或者寄 DJI 官方售后。 |
| 5 | **🟠 失去保修** | 改装过的机器 DJI 拒修，这是明文政策。 |
| 6 | **🟡 合规风险** | 在部分国家和地区（含中国大陆、美国），解锁/绕过设备安全机制可能触及法律。见「[免责声明](#免责声明)」。 |

> **本项目的开发机已经变砖了。** 它是在一次 DJI Fly 自动更新后卡在 boot logo 的，所有软件恢复手段都试过并且失败。README 里记录的流程是这条路走到尽头之后的完整复盘，请把它当作「此路不通」的证据，而不是「照着做就能成功」的教程。

---

## 设备信息

| 项目 | 值 |
|---|---|
| 机型 | DJI RC2 带屏遥控器 |
| 内部代号 | KATMAI-IDP |
| 平台 / 板级 | `rc331` |
| SoC | Qualcomm QCS5430（lahaina） |
| 系统 | Android 11 |
| 内核 | 5.4.210-qgki (DJI 5.4.210) |
| CPU | Cortex-A55，**运行在 32 位 armv8l 模式** |
| 内存 | 1.75 GB |
| 固件版本 | V00.01.12.50 |
| 构建指纹 | `DJI/rc331/rc331:11/V00.01.12.50/11250:user/test-keys` |
| 系统签名 | **test-keys**（非正式发布签名，这是能改系统的前提之一） |
| 序列号 | `[REDACTED]` |
| USB SN Hash | `[REDACTED]` |

**VID:PID**

| 模式 | VID:PID |
|---|---|
| 正常模式 | `2ca3:1021` |
| ADB 模式 | `18d1:4ee7` |
| DIAG 模式 | `05c6:901d` |

> ⚠️ **CPU 是 32 位的。** 市面上绝大多数 arm64-only 的 APK 装了也跑不起来，需要 `armeabi-v7a`。这个坑踩过的都懂。

---

## 功能一览

工具箱主界面按 KernelSU 的样式分成四个标签页：

### 首页 / 状态
- 一键探测设备当前状态（内核、`/data/adb/ksu` 是否存在、`su` 有没有生效、KernelSU 官方 App 装没装）
- 显示 root 通道的双路状态：`ksu/bin` 路径 + PATH 里的 `su`

### 超级用户
- **白名单管理**：`root(0)` / `system(1000)` / `shell(2000)` 永远放行，其余 UID 要显式授权
- 快捷按钮：清空白名单 / 只授权自己 / 授权 DJI Fly / 授权全部（`*`）
- 从已装应用列表里勾选授权
- 生成 `su` 入口：KernelSU / Magisk 兼容的命令行前端，支持 `su -c "命令"`、`su -v`、管道和引号透传

### 模块
- 浏览 `/data/adb/modules`，看详情 / 启用 / 停用 / 删除
- 从系统文件选择器装模块 zip
- 安全模式

### 设置 / 系统
- **临时 root**：免解锁、可随时开关的一套 root 通道
- **su 进 PATH**：让所有进程都能直接解析到 `su`（做法见「工作原理」，代价很小）
- **内核补丁探测**：只读地盘点内核 / boot 分区 / LKM 支持情况，**绝不写块设备**
- **系统属性编辑**：`ro.debuggable`、`ro.adb.secure`、机型伪装、DPI、时区等
- **提取 DJI 隐藏工厂工具**：用 root 通道启动没导出的 `com.dpad.fuli`（开发助手）、`com.dpad.devicetest`（产测 2.0）、`com.dpad.setup`（设置向导）
- **文件管理 / 应用管理 / 进程内存查看 / 救援**
- 清理临时文件、卸载管理器

---

## 怎么用

### 方式一：直接装 APK（推荐）

**前提**：设备已经能连 ADB（本机 `127.0.0.1:5555` 上的 adbd 是免认证的，或者用 `ADB夺取rc信号.exe` 打开）。

```bash
# 1. 确认能连上
adb devices

# 2. 装工具箱
adb install -r RC2工具箱v7.19.apk

# 3. 打开 App，点「一键部署」
#    它会依次：
#      - 建 /data/adb/ksu 和 bin 目录、模块目录
#      - 写入 su 与 ksud 入口
#      - 安装管理器
#      - 设白名单
#      - 复核每一步的结果
```

装完之后，在 App 里点「测试 su」应该能看到 `uid=0`。

如果只是想临时用一下、之后要还原，用「**临时 root**」那一栏：点「开启」建立通道，点「关闭」撤掉。它不碰 `/system`，重启即消失。

### 方式二：只用命令行工具

不想装 App 的话，仓库里两个 Windows 工具可以直接用：

```
ADB夺取rc信号.exe     # 打开 RC2 的网络 ADB（127.0.0.1:5555）
Root-ADB.exe          # 持久化完整权限 Root ADB 安装工具
```

典型流程：

```powershell
# 1. 用 ADB夺取rc信号.exe 把 ADB 打开

# 2. 连接
adb connect 127.0.0.1:5555

# 3. 用 Root-ADB.exe 装持久化 root

# 4. 验证
adb shell "id"
adb shell "su -c id"
```

> 这两个工具是编译好的 exe，不含源码，不方便审计。想看清楚每一步做了什么，用方式一或方式三。

### 方式三：自己编译

**不需要 Gradle，不需要完整 Android SDK。**

需要准备：
- JDK 21（脚本默认找 `C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot`，可在脚本里改）
- Android build-tools（`aapt2` / `zipalign` / `apksigner`）
- `android.jar`（platform-30）
- `dx.jar`（build-tools 29 里那个）

```powershell
# 编译
.\tools\build.ps1 -Project .\app -Out .\app\out

# 签名需要两个环境变量（不要把口令写进仓库）
$env:RC2_KS_PASS  = "你的keystore口令"
$env:RC2_KEY_PASS = "你的key口令"
```

产物在 `app\out\signed.apk`。

> **签名说明**：`build.ps1` 通过 `env:RC2_KS_PASS` / `env:RC2_KEY_PASS` 读口令，不再硬编码。请务必用自己的 keystore——**不要用本项目历史版本里的那个测试签名**，那个口令已经公开，任何人都能拿它签出能被系统接受为「同一个 App」的更新包。

---

## 目录结构

```
rc2-unlock-toolkit/
├── app/
│   ├── AndroidManifest.xml          # 25 个 Activity/Service/Receiver 声明
│   ├── src/com/rc2/toolbox/         # 25 个 Java 源文件，纯 AOSP API，无第三方依赖
│   │   ├── MainActivity.java        # 四标签主界面
│   │   ├── KsuActivity.java         # root 管理器（白名单/su/模块/PATH 注入）
│   │   ├── TempRoot.java            # 临时 root 通道
│   │   ├── RootAdb.java             # root adbd 持久化
│   │   ├── AdbShell.java            # ADB 指令执行封装
│   │   ├── SuProvider.java          # su 入口点
│   │   ├── SuReceiver.java          # su 广播桥
│   │   ├── SysProp.java             # resetprop 属性改写
│   │   ├── DjiActivity.java         # DJI 隐藏工厂工具
│   │   ├── Rescue.java              # 救援
│   │   └── ...                      # 其余界面与工具类
│   ├── res/                         # 图标、配色、字符串
│   └── assets/
│       ├── rc331-root-adb/          # root payload（脚本 + 二进制）
│       └── hidden.tsv               # 隐藏组件清单
├── tools/
│   └── build.ps1                    # 无 Gradle 构建脚本
├── docs/
└── release-assets/                  # 大文件，通过 Release 分发
```

源码一共 **25 个 Java 文件、约 38 万字节**，全部只用 AOSP 自带 API，没有引入任何第三方库。

---

## 工作原理

这一节是这个项目最值得看的部分，因为它记录了**哪些路走通了、哪些走不通**。

### 1. Root 是怎么拿到的

RC2 固件里有一个叫 `djilink` 的 Binder 服务，是工厂测试用的。它的 `setMicStatus(...)` 方法会把参数拼进一个 shell 字符串里执行：

```
tinycap %s -D 0 -d 0 -i %d -c %d -r 48000 -b 16 -t %d
```

第一个 `%s`（录音文件名）**没有任何过滤**，直接来自 Binder parcel。于是用 `;` 就能注入任意命令（整条命令必须 < 128 字符）：

```
service call djilink 1030 i32 1 i32 1 i32 2 i32 1 s16 ";whoami > /sdcard/whoami;"
```

事务码 `1030`（`0x406`），参数是 4 个 `i32` + 1 个 `s16`。

拿到这个之后，用 `am attach-agent system` 把脚本注入 `system_server`，干掉原版 adbd，起一个 `adbd-root --root_seclabel=u:r:su:s0` 在 5555 端口。这就是「Root-ADB」。

### 2. `su` 是怎么进 PATH 的

这一步挺巧，值得单独说：

`/system`、`/product`、`/vendor` 都是 dm-verity 只读分区，`mkdir /system/xbin` 必然失败（Read-only file system），rootfs 也 `remount,rw` 不动。但应用进程的 PATH 头一项是 `/product/bin`，而它**只装了一个文件**（`init.qti.display.sh`，1.4 KB）。

所以做法是：

1. 用 `mount -t tmpfs` 把 `/product/bin` 盖住
2. 把原文件从持久备份复制回去
3. 把 `su` 放进去

同一时刻只要有一个进程挂了这个 tmpfs，`/proc/<pid>/mounts` 里就能看到，zygote 也看得到，之后新起的进程同样看得到。**代价只是遮蔽了一个 1.4 KB 的脚本，而且原件仍然可见。重启后 tmpfs 消失、`/product/bin` 自动恢复原样，不留痕迹。**

### 3. 内核补丁：做不到，而且是老实说的

KernelSU 官方 App 可以装，但**内核里没有 KernelSU 的代码**（`5.4.210-qgki`），内核那一半没法从用户态凭空变出来。

工具箱里的「内核补丁」按钮干了什么？它把一个诚实的流程走完并**如实报告每一步的真实输出**：

1. 从内置的管理器 APK 里提取官方工具 `ksud` 和 `magiskboot`
2. 只读盘点内核 / boot 分区 / LKM 支持情况
3. 如果存在 `.ko`，尝试加载
4. dump 真实 boot 镜像，交给 `ksud boot-patch`
5. 报告结果

**它从头到尾不写块设备。** 原因很直接：RC2 没有 fastboot 兜底，刷错一个 boot 镜像就是变砖。这个项目宁可少一个功能，也不做可能变砖的操作。

### 4. Root 通道的双路结构

```
[App] --广播--> SuReceiver --> /data/local/tmp/nexus-su/req.PID
                                    |
                              [root adbd] 执行
                                    |
                             out.PID / out.PID.rc  <-- 结果回传
```

`su` 是个 shell 前端，支持 `su -c "ls | head -3"` 这种带管道和引号的调用，也支持 `su -v` / `su -V` / `su --version`。

---

## 下载与文件清单

### 仓库内（小文件，直接可见）

| 文件 | 说明 |
|---|---|
| `app/` | 完整 Java 源码 |
| `tools/build.ps1` | 构建脚本 |

### Release 附件（大文件）

> 以下文件通过 GitHub Release 分发，**不在仓库主分支里**（GitHub 单文件硬限制 100 MB）。

| 文件 | 大小 | 说明 |
|---|---|---|
| `RC2工具箱v7.19.apk` | 8 MB | 工具箱本体，装这个 |
| `教程.mp4` | 1.04 GB | 完整视频教程 |
| `ADB夺取rc信号.exe` | 9.2 MB | 打开 RC2 网络 ADB |
| `Root-ADB.exe` | 20 MB | 持久化 Root ADB 安装工具 |
| `RCRoot-ADB.apk` | 678 KB | 同上，APK 版 |

### 第三方 App（**不是本项目作品**，仅作本机备份列出）

| 文件 | 大小 | 说明 |
|---|---|---|
| `MT2.26.10.apk` | 36 MB | MT 管理器，第三方文件管理器，**商业软件** |
| `PUDFly-1.21.10-zh-CN-signed.apk` | 457 MB | PUDFly，第三方 DJI 参数工具，**商业软件** |
| `PUDFly-1.21.12-fixed手机版.apk` | 679 MB | 同上，另一版本 |
| `桌面.apk` | 54 MB | DJI 原厂桌面，从设备提取 |
| `开发助手.apk` | 8.4 MB | DJI 工厂工具 `com.dpad.fuli`，从设备提取 |
| `系统隐藏工程文件.apk` | 8.4 MB | 与 `开发助手.apk` 内容完全相同（同一文件） |
| `ADB整过之后才可使用.apk` | 2.5 MB | — |
| `实现自由装软件.apk` | 3.1 MB | — |
| `全面屏手势加返回键.apk` | 842 KB | — |
| `更新文件管理器更新之后无法打开谨慎更新.apk` | 5.3 MB | — |
| `开发者设置.apk` | 13 KB | — |
| `跳转隐藏模式打开开发者设置.apk` | 13 KB | — |

> ⚠️ **这些第三方 App 的著作权属于各自的作者/公司，不是本项目所有，也未获得再分发授权。** 它们仅作为「让 RC2 可用」所依赖的组件被列出。如果你需要它们，**请从官方渠道获取**——MT 管理器和 PUDFly 都在正常销售。

---

## 常见问题

**Q：装完 App 打不开 / 闪退？**
A：CPU 是 32 位的（armv8l）。确认你的 APK 包含 `armeabi-v7a`，纯 arm64 的装不了。

**Q：`su` 命令找不到？**
A：`su` 只在 `/data/adb/ksu/bin` 里，还没进 PATH。在工具箱里点「安装 su 到 PATH」，它走的是 tmpfs 遮蔽 `/product/bin` 那套。重启后要重新点（或者开「开机自愈」）。

**Q：能刷 KernelSU 内核模块吗？**
A：**不能。** 这个内核里没有 KSU 代码，内核那一半没法从用户态补上。工具箱里的 `ksud` 是用户态的 shim，只提供 `resetprop` 这类不依赖内核的能力。

**Q：「一键部署」会不会写坏什么？**
A：它只写 `/data/adb/ksu`、`/data/adb/modules`、`/data/local/tmp` 这几个位置，不碰分区表、不碰块设备、不改 `/system`。「内核补丁」那一栏也**只读**，绝不写 boot 分区。

**Q：出事了怎么救？**
A：ADB / USB 层面救不回来。只剩两条路：拆机从 PCB 测试点进 EDL 9008（需 1kΩ 电阻 + 万用表），或者寄 DJI 售后。**提前想清楚再动手。**

**Q：为什么仓库里没有 KernelSU.apk？**
A：它是第三方 GPL-3.0 软件，请从 [KernelSU 官方仓库](https://github.com/tiann/KernelSU) 获取。

---

## 第三方组件与来源说明

本项目在研究过程中参考、提取或依赖了以下第三方成果。**这些都归各自的作者所有：**

| 组件 | 来源 | 许可 | 说明 |
|---|---|---|---|
| KernelSU | [tiann/KernelSU](https://github.com/tiann/KernelSU) | GPL-3.0 | `resetprop` 能力与 CLI 兼容性参考；未在仓库内分发其二进制 |
| Magisk | [topjohnwu/Magisk](https://github.com/topjohnwu/Magisk) | GPL-3.0 | `magiskboot` 用于 boot 镜像处理 |
| APatch | [bmax121/APatch](https://github.com/bmax121/APatch) | GPL-2.0 | 内核补丁思路参考 |
| DUMLRacer | CunningLogic / jcase | 未声明 | DJI 协议研究参考 |
| margerine | [fpv-wtf/margerine](https://github.com/fpv-wtf/margerine) | 未声明 | DJI 提权思路参考 |
| icanhack.nl | Willem Melching | 版权归作者 | DJI RM500 提权研究，**仅引用未再分发** |
| AOSP / 各 ROM | Google / 社区 | Apache-2.0 | 设备树与系统组件参考 |

DJI、DJI RC2、DJI Fly 是深圳市大疆创新科技有限公司的商标。本项目**与大疆无任何关联，未获其授权或认可**。

---

## 免责声明

> **本仓库内容仅供安全研究与设备取证学习之用。**

1. **风险自负。** 按本仓库内容操作可能导致设备损坏、数据丢失、保修失效、eFuse 永久熔断。作者不对任何后果负责。
2. **合规责任自负。** 在部分司法辖区（包括但不限于中国大陆、美国），绕过设备安全机制可能违反《网络安全法》《刑法》第 285/286 条、DMCA §1201 等法律法规。**是否合法取决于你的用途和所在地，请自行咨询专业人士。**
3. **禁止非法使用。** 不得将本项目用于未经授权访问他人设备、规避技术保护措施、或其他违法用途。
4. **第三方内容。** 仓库中引用的第三方项目、文档、商标归各自权利人所有。若权利人认为本仓库内容侵犯其权益，请提 Issue 或联系，将立即处理。
5. **无担保。** 本项目按「现状」提供，不附带任何明示或暗示的担保。

---

<div align="center">

**再次提醒：动手前请重读一遍「[风险警告](#风险警告先读这个)」。**

本项目的那台 RC2 已经变砖了。

</div>
