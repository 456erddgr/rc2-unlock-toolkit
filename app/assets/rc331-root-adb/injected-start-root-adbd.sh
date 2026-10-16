#!/system/bin/sh

D=/data/adb-persist
LOG=$D/root-adbd.log

{
    echo "$(date '+%Y-%m-%d %H:%M:%S') 注入启动器 pid=$$ uid=$(id -u) context=$(cat /proc/self/attr/current)"
    grep -E '^(CapInh|CapPrm|CapEff|CapBnd|CapAmb|NoNewPrivs|Seccomp):' /proc/self/status
} >> "$LOG" 2>&1

if [ -e "$D/disable-custom-adbd" ]; then
    echo "$(date '+%Y-%m-%d %H:%M:%S') 存在维护标记；已跳过注入式启动" >> "$LOG"
    exec /system/bin/sleep 1
fi

for pid in $(/system/bin/busybox pidof adbd-root 2>/dev/null); do
    if grep -q '^CapEff:[[:space:]]*0000003fffffffff$' "/proc/$pid/status" 2>/dev/null; then
        echo "$pid" > "$D/root-adbd.pid"
        touch "$D/root-adbd-ready"
        setprop ctl.stop adbd
        exec /system/bin/sleep 1
    fi
done

rm -f "$D/root-adbd-ready" "$D/root-adbd.pid"
setprop ctl.stop adbd

i=0
while [ "$(getprop init.svc.adbd)" != "stopped" ] && [ "$i" -lt 30 ]; do
    sleep 0.2
    i=$((i + 1))
done

setprop service.adb.tcp.port 5555
setprop service.adb.listen_addrs ""
export LD_LIBRARY_PATH=/apex/com.android.adbd/lib64
setsid "$D/adbd-root" --root_seclabel=u:r:su:s0 </dev/null >> "$LOG" 2>&1 &

i=0
while [ "$i" -lt 30 ]; do
    for pid in $(/system/bin/busybox pidof adbd-root 2>/dev/null); do
        if grep -q '^CapEff:[[:space:]]*0000003fffffffff$' "/proc/$pid/status" 2>/dev/null; then
            echo "$pid" > "$D/root-adbd.pid"
            touch "$D/root-adbd-ready"
            echo "$(date '+%Y-%m-%d %H:%M:%S') 注入的完整权限 adbd 正在运行 pid=$pid" >> "$LOG"
            exec /system/bin/sleep 1
        fi
    done
    sleep 0.25
    i=$((i + 1))
done

echo "$(date '+%Y-%m-%d %H:%M:%S') 注入的 adbd 启动失败；正在恢复系统自带 adbd" >> "$LOG"
setprop service.adb.tcp.port ""
setprop service.adb.listen_addrs ""
setprop ctl.start adbd
exec /system/bin/sleep 1
