#!/system/bin/sh
# S20Remote 乐视 1 Pro (X800 / Android 6.0) 开机脚本（Magisk service.d, root）
# 背景：EUI 自启动管控拦截 BOOT_COMPLETED，用 root 脚本拉服务绕过（与 S20 同架构）
# 1) 若锁屏则尝试自动解锁（PIN 优先取 App 配置）
# 2) 拉起主服务（带重试）

# ---- 自动解锁配置 ----
# App 配置单一来源：Android 6.0 无 DE 区，读 CE 区 SharedPreferences
PIN_XML=/data/data/com.s20.remote/shared_prefs/s20_remote.xml
UNLOCK_PIN=""
if [ -f "$PIN_XML" ]; then
  UNLOCK_PIN=$(sed -n 's/.*name="unlockPin">\([^<]*\)<.*/\1/p' "$PIN_XML" 2>/dev/null | head -1)
fi
UNLOCK_MAX=10          # 最多尝试轮数（每轮约 4s）

is_locked() {
  dumpsys trust 2>/dev/null | grep -q 'deviceLocked=1'
}

auto_unlock() {
  # 未配置 PIN 或未锁屏则跳过
  [ -z "$UNLOCK_PIN" ] && return 0
  i=0
  while [ $i -lt $UNLOCK_MAX ]; do
    if ! is_locked; then return 0; fi
    i=$((i+1))
    # 点亮屏幕（Dozing/Asleep -> Awake）
    input keyevent 26 >/dev/null 2>&1
    sleep 1
    # 上滑打开 Bouncer（坐标按 1440x2560 动态取，兼容其它分辨率）
    SIZE=$(wm size 2>/dev/null | grep -o '[0-9]*x[0-9]*' | head -1)
    W=${SIZE%%x*}; H=${SIZE##*x}
    [ -z "$W" ] && W=1440; [ -z "$H" ] && H=2560
    Y1=$((H * 3 / 4)); Y2=$((H * 3 / 10))
    input swipe $((W / 2)) $Y1 $((W / 2)) $Y2 300 >/dev/null 2>&1
    sleep 1
    input text "$UNLOCK_PIN" >/dev/null 2>&1
    sleep 2
    if ! is_locked; then return 0; fi
  done
  return 1
}

(
  # 等系统开机完成
  until [ "$(getprop sys.boot_completed)" = "1" ]; do sleep 2; done
  sleep 3

  # 自动解锁（失败不影响服务自启）
  auto_unlock

  # 拉起主服务（带重试）；Android 6.0 无 start-foreground-service，用 startservice
  # 注意：乐视 6.0 的 pidof 忽略参数返回所有 PID；且 force-stop 后可能进程存活而服务已停，
  # 因此以 HTTPS 端口 8999 是否监听作为「服务已跑」的判据
  svc_ok() {
    netstat -tln 2>/dev/null | grep -q ":8999 "
  }
  j=0
  while [ $j -lt 12 ]; do
    if svc_ok; then break; fi
    am startservice --user 0 -n com.s20.remote/.service.MainService >/dev/null 2>&1
    sleep 5
    j=$((j+1))
  done
) &
