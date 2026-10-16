#!/system/bin/sh

D=/data/adb-persist
LOG=$D/root-adbd.log
LOCK=$D/.launch-lock
AGENT_STORE=/dji_apk/.adb-dev/librc331_root_adb_agent.so
AGENT=/data/local/tmp/librc331_root_adb_agent.so
HELPER=/dji_apk/.adb-dev/cap_ptrace_injector
INJECTED=$D/injected-start-root-adbd.sh

if [ -e "$D/disable-custom-adbd" ]; then
    echo "$(date '+%Y-%m-%d %H:%M:%S') 维护标记已禁用自定义 adbd" >> "$LOG"
    exit 0
fi

full_adbd_running() {
    for pid in $(/system/bin/busybox pidof adbd-root 2>/dev/null); do
        [ -r "/proc/$pid/status" ] || continue
        if grep -q '^CapEff:[[:space:]]*0000003fffffffff$' "/proc/$pid/status" 2>/dev/null; then
            echo "$pid"
            return 0
        fi
    done
    return 1
}

pid="$(full_adbd_running)"
if [ -n "$pid" ]; then
    setprop ctl.stop adbd
    echo "$pid" > "$D/root-adbd.pid"
    touch "$D/root-adbd-ready"
    echo "$(date '+%Y-%m-%d %H:%M:%S') 完整权限 adbd 已在运行 pid=$pid" >> "$LOG"
    exit 0
fi

acquire_lock() {
    if mkdir "$LOCK" 2>/dev/null; then
        echo $$ > "$LOCK/pid"
        return 0
    fi

    owner="$(cat "$LOCK/pid" 2>/dev/null)"
    if [ -n "$owner" ] && kill -0 "$owner" 2>/dev/null &&
       tr '\000' ' ' < "/proc/$owner/cmdline" 2>/dev/null | grep -q "$D/start-root-adbd.sh"; then
        return 1
    fi

    rm -f "$LOCK/pid"
    rmdir "$LOCK" 2>/dev/null || return 1
    mkdir "$LOCK" 2>/dev/null || return 1
    echo $$ > "$LOCK/pid"
    return 0
}

if ! acquire_lock; then
    echo "$(date '+%Y-%m-%d %H:%M:%S') 另一个启动器正持有有效锁" >> "$LOG"
    exit 0
fi
trap 'rm -f "$LOCK/pid"; rmdir "$LOCK" 2>/dev/null' EXIT

echo "$(date '+%Y-%m-%d %H:%M:%S') 启动器进入 uid=$(id -u) context=$(cat /proc/self/attr/current)" >> "$LOG"

if [ ! -r "$AGENT" ]; then
    cp "$AGENT_STORE" "$AGENT" || exit 1
    chown 0:0 "$AGENT"
    chmod 0755 "$AGENT"
fi

rm -f "$D/root-adbd-ready" "$D/root-adbd.pid"
echo "$(date '+%Y-%m-%d %H:%M:%S') capability 桥接启动器 uid=$(id -u) context=$(cat /proc/self/attr/current)" >> "$LOG"

if [ -S /dev/socket/zygote_secondary ] && [ -x "$HELPER" ] && [ -r "$AGENT" ] && [ -x "$INJECTED" ]; then
    /system/bin/am attach-agent system "$AGENT=exec=$HELPER;arg=$INJECTED" >> "$LOG" 2>&1
else
    echo "$(date '+%Y-%m-%d %H:%M:%S') capability 桥接组件缺失" >> "$LOG"
fi

i=0
while [ "$i" -lt 140 ]; do
    # setuid 启动器仍然只拥有应用的空 capability 集合，
    # 并且可能因 hidepid 而无法检查无关进程。
    # 注入的完整权限启动器只会在验证过 adbd-root 的 CapEff 之后
    # 才创建这些文件，因此以最新的标记文件为准。
    if [ -e "$D/root-adbd-ready" ] && [ -s "$D/root-adbd.pid" ]; then
        pid="$(cat "$D/root-adbd.pid" 2>/dev/null)"
        if [ -n "$pid" ]; then
            setprop ctl.stop adbd
            echo "$(date '+%Y-%m-%d %H:%M:%S') 完整权限 adbd 就绪 pid=$pid（已由注入启动器验证）" >> "$LOG"
            exit 0
        fi
    fi

    pid="$(full_adbd_running)"
    if [ -n "$pid" ]; then
        setprop ctl.stop adbd
        echo "$pid" > "$D/root-adbd.pid"
        touch "$D/root-adbd-ready"
        echo "$(date '+%Y-%m-%d %H:%M:%S') 完整权限 adbd 就绪 pid=$pid" >> "$LOG"
        exit 0
    fi
    sleep 0.5
    i=$((i + 1))
done

echo "$(date '+%Y-%m-%d %H:%M:%S') capability 桥接超时；正在恢复系统自带 adbd" >> "$LOG"
setprop service.adb.tcp.port ""
setprop service.adb.listen_addrs ""
setprop ctl.start adbd
exit 1
