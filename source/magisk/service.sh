#!/system/bin/sh
# S20Remote 开机脚本（Magisk, root）
# 1) 自动模拟输入 PIN 解锁锁屏，使 CE 区挂载、短信 provider 可用
# 2) 等待开机 + PM 就绪后以 root 拉起主服务（带重试自启兜底）

# ---- 自动解锁配置 ----
# 默认 PIN；若 App 配置里已改过 unlockPin（DE 区 SharedPreferences），以 App 为准（单一来源）
UNLOCK_PIN="000000"
PIN_XML=/data/user_de/0/com.s20.remote/shared_prefs/s20_remote.xml
if [ -f "$PIN_XML" ]; then
  PIN_FROM_APP=$(sed -n 's/.*name="unlockPin">\([^<]*\)<.*/\1/p' "$PIN_XML" 2>/dev/null | head -1)
  [ -n "$PIN_FROM_APP" ] && UNLOCK_PIN="$PIN_FROM_APP"
fi
UNLOCK_MAX=20          # 最多尝试轮数（每轮约 4s，总约 80s）

is_locked() {
  dumpsys trust 2>/dev/null | grep -q 'deviceLocked=1'
}

auto_unlock() {
  i=0
  while [ $i -lt $UNLOCK_MAX ]; do
    if ! is_locked; then
      return 0
    fi
    i=$((i+1))
    # 点亮屏幕（Dozing/Asleep -> Awake）
    input keyevent 26 >/dev/null 2>&1
    sleep 1
    # 上滑打开 Bouncer（PIN 输入界面）；坐标适配 1080x2400
    input swipe 540 2000 540 800 300 >/dev/null 2>&1
    sleep 1
    # 输入 PIN
    input text "$UNLOCK_PIN" >/dev/null 2>&1
    sleep 2
    if ! is_locked; then
      return 0
    fi
  done
  return 1
}

(
  # 等系统开机完成
  until [ "$(getprop sys.boot_completed)" = "1" ]; do sleep 2; done
  sleep 3

  # 自动解锁（失败也不影响服务自启，仅短信在锁屏期不可用）
  auto_unlock

  # 拉起主服务（带重试）
  j=0
  while [ $j -lt 12 ]; do
    if [ -n "$(pidof com.s20.remote)" ]; then break; fi
    am start-foreground-service --user 0 -n com.s20.remote/.service.MainService >/dev/null 2>&1
    sleep 5
    j=$((j+1))
  done
) &
