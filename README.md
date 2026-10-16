# RC2 解锁工具箱 / RC2 Unlock Toolkit

> 给 **DJI RC2 带屏遥控器**（平台代号 `rc331`，高通 QCS5430，Android 11）用的 Root / 提权 / 系统调整工具箱。
>
> ⚠️ **本仓库是逆向工程与设备研究记录，不是给普通用户的一键刷机包。** 动手前请完整读完「[这个工具不做什么](#这个工具不做什么先读这个)」。

---

## 📥 下载

**👉 [**完整下载清单（DOWNLOADS.md）**](DOWNLOADS.md)** —— 17 个文件全部可直接下载，**不需要 GitHub 账号**。

| | |
|---|---|
| 🚀 **只想用起来** | [`ADB-Unlock-RC.exe`](https://github.com/456erddgr/rc2-unlock-toolkit/releases/download/v7.19/ADB-Unlock-RC.exe) + [`RC2-Toolbox-v7.19.apk`](https://github.com/456erddgr/rc2-unlock-toolkit/releases/download/v7.19/RC2-Toolbox-v7.19.apk)，**共 17 MB** |
| 📋 **看全部文件** | [**DOWNLOADS.md**](DOWNLOADS.md) — 按用途分类、带直链和大小 |
| 🎬 **视频教程** | [`Tutorial.mp4`](https://github.com/456erddgr/rc2-unlock-toolkit/releases/download/v7.19/Tutorial.mp4)（1.04 GB） |
| 📦 **Release 主页** | [RC2 Toolkit v7.19](https://github.com/456erddgr/rc2-unlock-toolkit/releases/tag/v7.19)（2.30 GB） |

---

## 目录

- [下载](#下载)
- [这是什么](#这是什么)
- [这个工具不做什么（先读这个）](#这个工具不做什么先读这个)
- [设备信息](#设备信息)
- [功能一览](#功能一览)
- [怎么用](#怎么用)
  - [方式一：直接装 APK（推荐）](#方式一直接装-apk推荐)
  - [方式二：只用命令行工具](#方式二只用命令行工具)
  - [方式三：自己编译](#方式三自己编译)
- [目录结构](#目录结构)
- [工作原理](#工作原理)
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

## 这个工具不做什么（先读这个）

网上关于"破解 DJI 遥控器"的讨论，绝大部分讲的是**刷机**——解锁 Bootloader、刷 Magisk、写 boot 分区。**本项目走的完全是另一条路。**

它用的是一个**出厂就存在的 root adbd**（uid=0，监听 `127.0.0.1:5555`），通过 `am attach-agent system` 注入一段脚本拿到全 capability，再从用户态把 `su` 入口铺出去。整条链路**只走 ADB**。

所以下面这些**都不会发生**：

| 你可能担心的 | 实际情况 |
|---|---|
| ❌ 烧 eFuse / 熔断校验位 | **不会。** 熔丝位只在执行 `fastboot flashing unlock` 时才烧。本工具不碰 fastboot 写操作。 |
| ❌ 需要解锁 Bootloader | **不需要。** root 入口是出厂自带的，跟 BL 状态无关。 |
| ❌ 回锁 BL 导致校验失败 | **不涉及。** 你本来就没解锁过，也就没有回锁这回事。 |
| ❌ 刷机 / 写 boot 分区 / 改分区表 | **不会写任何块设备。** 「内核补丁」那一栏全程只读。 |
| ❌ 动 `/system`、`/product` 等系统分区 | **一个字节都不改。** `su` 只落在 `/data/adb/ksu` 与 `/data/local/tmp`。 |
| ❌ 失去保修（因改装被拒） | **无需担心改装**。系统分区未被修改，关掉开关或恢复出厂即回到原样。 |
| ❌ 变砖 | **做不到。** 不写分区就没有砖的路径。设备完全可恢复。 |

> **唯一需要注意的**：如果你**自己去**执行 `fastboot flashing unlock` 或 `fastboot flash`，那些是写操作，后果与本项目无关——本工具从头到尾不引导你这么做，也不提供这类按钮。
>
> **Root 之后建议不要升级 DJI Fly**（这一条是经验之谈，不是本工具的副作用）：开发机在一次 DJI Fly 自动更新后落到了 fastboot 模式，可进入 fastboot、bootloader 正常，`fastboot continue` 即可继续启动。详见「[常见问题](#常见问题)」。

**说明**：本项目针对自有设备，运行的是开源 Android 系统。详见「[免责声明](#免责声明)」。

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

# 2. 装工具箱（APK 在 Releases 页面，文件名 RC2-Toolbox-v7.19.apk）
adb install -r RC2-Toolbox-v7.19.apk

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

不想装 App 的话，Releases 里两个 Windows 工具可以直接用：

```
ADB-Unlock-RC.exe     # 打开 RC2 的网络 ADB（127.0.0.1:5555）
Root-ADB.exe          # 持久化完整权限 Root ADB 安装工具
```

典型流程：

```powershell
# 1. 用 ADB-Unlock-RC.exe 把 ADB 打开

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

**它从头到尾不写块设备。** 原因很直接：`fastboot flash` / `erase` 这类写操作在这台机器上没有安全边界，刷错一个镜像就真回不来了。这个项目宁可少一个功能，也不做不可逆的操作。

注意区分：**进 fastboot 本身是安全的**，`fastboot continue` / `fastboot reboot` 都是正常救援手段，App 内置了 Fastboot 面板专门做这件事。危险的是 `flash`、`erase`、`flashing unlock` 这些写操作。详见「[常见问题](#常见问题)」。

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

## 下载

### 👉 [**点这里下载 Release v7.19**](https://github.com/456erddgr/rc2-unlock-toolkit/releases/tag/v7.19)

17 个附件，共 2.30 GB。**用这个工具只需要下其中几个**，标 ⭐ 的是必需的：

### 仓库内（小文件，直接可见）

### 仓库内（小文件，直接可见）

| 文件 | 说明 |
|---|---|
| `app/` | 完整 Java 源码 |
| `tools/build.ps1` | 构建脚本 |
| `docs/` | 安装指南与技术记录 |
| `DOWNLOADS.md` | **完整下载清单**（17 个文件，带直链） |

### Release 附件

17 个文件、共 2.30 GB，**全部可直接下载，不需要 GitHub 账号**。

> 📋 **完整清单见 [DOWNLOADS.md](DOWNLOADS.md)** —— 按用途分类、带直链和大小。
>
> 速览：需要的是 `ADB-Unlock-RC.exe`（9.2 MB）+ `RC2-Toolbox-v7.19.apk`（7.8 MB），**共 17 MB** 就能用起来。
> 其余为视频教程（1.04 GB）和第三方 App（MT管理器、PUDFly、DJI 原厂 App 等）。
>
> 文件名使用 ASCII，因为 GitHub 会剥离附件名中的中文字符；原始中文名对照见 [DOWNLOADS.md](DOWNLOADS.md#-关于文件名)。

校验和见仓库根目录的 [`SHA256SUMS.txt`](SHA256SUMS.txt)。

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
A：**先分清是哪一层出问题**，别一上来就拆机。

- 如果是**落了 fastboot**（屏幕显示 FASTBOOT 界面、`fastboot devices` 能认到机器）：设备是活的，用 `fastboot continue` 继续正常启动，或 `fastboot reboot` 重启。工具箱里的 Fastboot 面板就是干这个的。**这不算变砖。**
- 如果是**停在 boot logo**：先接 USB 等一两分钟，看 `adb devices` 有没有反应；换一根数据线、换一个 USB 口再试。
- 只有在上面这些都确认无效、设备完全无响应时，才轮到物理手段：拆机从 PCB 测试点进 EDL 9008（需 1kΩ 电阻 + 万用表）。

**任何情况下都不要执行 `fastboot flash` / `erase` / `flashing unlock`** —— 这些写操作才真的会把设备弄到回不来。

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

> **本仓库记录的是对自己所拥有的设备进行安全研究的完整过程。**

1. **这是你自己的设备。** 本项目针对的是**你本人拥有**的 DJI RC2 遥控器，记录的是在自有硬件上做 root 与调试的过程。**请勿用于他人设备。**

2. **运行的是开源 Android。** RC2 底层是 Android（AOSP）——**开源系统**。在自有设备上获取 root、装第三方 App，是 Android 平台长期以来的常规做法。刷机与熔断校验相关的限制主要存在于**闭源系统**（如 iOS、部分游戏主机）的生态里，不适用于本场景。

3. **本项目不刷机。** 走 ADB 通道，用出厂自带的 root adbd，不写任何分区、不解锁 Bootloader、不触发 eFuse。因此刷机路线才有的那些后果（熔断、校验失败、失去保修、变砖）在这里都不存在。

4. **风险自负。** 虽无上述风险，但操作仍需自行评估。作者不对任何后果负责。

5. **第三方内容。** 仓库中引用的第三方项目、文档、商标归各自权利人所有。若权利人认为本仓库内容侵犯其权益，请提 Issue 或联系，将立即处理。

6. **无担保。** 本项目按「现状」提供，不附带任何明示或暗示的担保。

---

<div align="center">

**再次提醒：动手前请重读一遍「[这个工具不做什么](#这个工具不做什么先读这个)」。**

本项目的那台 RC2 目前停在 fastboot 模式。

</div>
