# RC2 遥控器 Magisk 模块合集

适用于 DJI RC2（rc331 / Android 11）
作者：软糖布丁

## 用法

每按需在 Magisk App 里「从本地安装」对应 zip，或：
```
adb push xxx.zip /data/local/tmp/
adb shell su -c "magisk --install-module /data/local/tmp/xxx.zip"
```
安装后**重启生效**。

## 模块清单

| 模块 | 作用 | 是否必需 |
|---|---|---|
| **rc2_zygote64.zip** | 64 位 Zygote 解锁（policy=3 双 zygote） | ★ 装官方 DJI Fly 必需 |
| **rc2_adbd_root.zip** | ADB 直接 root，adb shell 即 uid=0 | ★ 强烈建议 |
| **rc2_autogrant.zip** | 开机自动给工具箱永久 Magisk 授权，不弹窗 | ★ 工具箱必需 |
| **rc2_allscripts.zip** | 26 个运维脚本（黑屏急救、备份、性能等） | 建议 |
| **rc2_memexp.zip** | dalvik 堆提到 512m/384m | 建议（内存紧张时） |
| **rc2_swapfile.zip** | 磁盘 swap 开机自动启用 | 可选（会拖慢，默认不用） |
| **rc2_bootanim.zip** | 自定义开机动画（由工具箱生成） | 可选 |
| **rc2_kugou_nokick.zip** | 酷狗保活 | 可选 |
| **Automatic_brick_rescue.zip** | 自动救砖保护（第三方） | 可选 |

## 顺序建议

1. `rc2_adbd_root.zip` —— 拿到 root
2. `rc2_autogrant.zip` —— 让工具箱免弹窗
3. `rc2_zygote64.zip` —— 要装官方 DJI Fly 才需要
4. 其余按需

## 重要提醒

- **rc2_swapfile 默认禁用**：1.7GB 小内存设备上磁盘 swap 会拖慢系统，建议只用 zram
- **不要同时装 rc2_bootanim 的多个版本**，会冲突
- 模块出问题进不了系统时：recovery 里删 `/data/adb/modules/<模块名>`
- 卸载模块：Magisk App 里移除，或删目录后重启

## 与工具箱配合

工具箱的「一键开启 adb root」「模块管理」等功能依赖：
- `rc2_adbd_root`（给 root）
- `rc2_autogrant`（免授权弹窗）

两个都装齐，工具箱才能完整发挥作用。
