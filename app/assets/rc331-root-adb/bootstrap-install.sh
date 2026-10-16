#!/system/bin/sh
set -eu

STAGE="${1:-/data/local/tmp/rc331-root-adb-install}"
PERSIST=/data/adb-persist
DJI_DIR=/dji_apk/.adb-dev
AGENT_RUNTIME=/data/local/tmp/librc331_root_adb_agent.so

fail() {
    echo "错误：$*" >&2
    exit 1
}

[ "$(id -u)" = "0" ] || fail "引导安装需要 uid 0"
[ "$(getprop ro.product.device)" = "rc331" ] || fail "目标设备不是 rc331"
[ "$(getenforce 2>/dev/null || echo Unknown)" != "Enforcing" ] || \
    fail "此版本不支持 SELinux Enforcing 模式"

for file in adb-root-launcher librc331_root_adb_agent.so cap_ptrace_injector \
            set_file_inheritable adbd-root start-root-adbd.sh \
            injected-start-root-adbd.sh; do
    [ -f "$STAGE/$file" ] || fail "缺少载荷文件：$file"
done

mkdir -p "$DJI_DIR" "$PERSIST"
chown 0:0 "$DJI_DIR" "$PERSIST"
chmod 0755 "$DJI_DIR"
chmod 0700 "$PERSIST"

install_one() {
    source="$1"
    target="$2"
    mode="$3"
    rm -f "$target.new"
    cp "$source" "$target.new"
    chown 0:0 "$target.new"
    chmod "$mode" "$target.new"
    mv -f "$target.new" "$target"
}

install_one "$STAGE/adb-root-launcher" "$DJI_DIR/adb-root-launcher" 4755
install_one "$STAGE/librc331_root_adb_agent.so" \
            "$DJI_DIR/librc331_root_adb_agent.so" 0755
# setuid 启动器有意以 Android 应用的空 capability 集合启动。
# 它可以执行桥接程序，但无法可靠地在 /data/local/tmp 中新建文件。
# 因此趁安装程序仍持有临时的完整权限 root ADB shell，
# 先把运行时代理放置到位。
install_one "$STAGE/librc331_root_adb_agent.so" "$AGENT_RUNTIME" 0755
install_one "$STAGE/cap_ptrace_injector" "$DJI_DIR/cap_ptrace_injector" 0755
install_one "$STAGE/adbd-root" "$PERSIST/adbd-root" 0700
install_one "$STAGE/start-root-adbd.sh" "$PERSIST/start-root-adbd.sh" 0755
install_one "$STAGE/injected-start-root-adbd.sh" \
            "$PERSIST/injected-start-root-adbd.sh" 0755

# 该辅助程序必须继承 system_server 的 capability 掩码。这个小程序会写入
# 带有所需可继承位的 VFS_CAP_REVISION_2 security.capability xattr；
# 普通的 chmod/chown 无法创建它。
chmod 0755 "$STAGE/set_file_inheritable"
"$STAGE/set_file_inheritable" "$DJI_DIR/cap_ptrace_injector" || \
    fail "无法为 cap_ptrace_injector 设置 capability xattr"

rm -f "$PERSIST/disable-custom-adbd" "$PERSIST/root-adbd-ready" \
      "$PERSIST/root-adbd.pid" "$PERSIST/.launch-lock/pid"
rmdir "$PERSIST/.launch-lock" 2>/dev/null || true

restorecon -RF "$PERSIST" 2>/dev/null || true
restorecon -RF "$DJI_DIR" 2>/dev/null || true
restorecon "$AGENT_RUNTIME" 2>/dev/null || true

# restorecon 不会修改 security.capability，但这里重复执行一次，
# 以确保最终安装的 inode 始终是携带 capability xattr 的那个。
"$STAGE/set_file_inheritable" "$DJI_DIR/cap_ptrace_injector" || \
    fail "最终打标后 capability xattr 丢失"

sync
echo "bootstrap_install=ok"
echo "launcher=$DJI_DIR/adb-root-launcher"
echo "persist=$PERSIST"
