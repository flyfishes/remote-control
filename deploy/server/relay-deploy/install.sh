#!/usr/bin/env bash
# ============================================================
# S20 远程控制 - 中转服务器一键安装（Linux / systemd）
#
# 用法：
#   sudo bash install.sh                    # 交互输入 RELAY_TOKEN
#   sudo RELAY_TOKEN=xxx bash install.sh    # 非交互
#   sudo RELAY_PORT=9000 RELAY_TOKEN=xxx bash install.sh
#   sudo RELAY_DB_BACKEND=mysql RELAY_DB_HOST=... RELAY_DB_NAME=s20relay \
#        RELAY_DB_USER=... RELAY_DB_PASS=... RELAY_TOKEN=xxx bash install.sh
#
# 做的事：建用户 → 拷程序 → 建 venv 装依赖 → 装 systemd → 启动
# 不做的事：不动你的 nginx / 防火墙（避免误改现网配置，见结尾提示）
# ============================================================
set -euo pipefail

APP_DIR="${APP_DIR:-/opt/s20relay}"
DATA_DIR="${DATA_DIR:-/var/lib/s20relay}"
SVC_USER="${SVC_USER:-s20relay}"
RELAY_PORT="${RELAY_PORT:-9000}"
RELAY_HOST="${RELAY_HOST:-127.0.0.1}"
LOG_LEVEL="${LOG_LEVEL:-INFO}"

[ "$(id -u)" -eq 0 ] || { echo "请用 root 执行：sudo bash install.sh"; exit 1; }
SRC_DIR="$(cd "$(dirname "$0")" && pwd)"
[ -f "$SRC_DIR/relay_server.py" ] || { echo "缺少 relay_server.py，请在解压后的 relay-deploy 目录内执行"; exit 1; }
command -v python3 >/dev/null 2>&1 || { echo "缺少 python3，请先安装（apt install python3 python3-venv）"; exit 1; }
python3 -c "import venv" 2>/dev/null || { echo "缺少 python3-venv，请先安装（apt install python3-venv）"; exit 1; }

if [ -z "${RELAY_TOKEN:-}" ]; then
    read -r -p "RELAY_TOKEN（必须与手机端 Config.token 完全一致）: " RELAY_TOKEN
fi
[ -n "${RELAY_TOKEN:-}" ] || { echo "RELAY_TOKEN 不能为空"; exit 1; }

echo "==> 1/5 创建运行用户 $SVC_USER"
id -u "$SVC_USER" >/dev/null 2>&1 || useradd -r -s /usr/sbin/nologin "$SVC_USER"

echo "==> 2/5 拷贝程序到 $APP_DIR"
mkdir -p "$APP_DIR" "$DATA_DIR"
cp -f "$SRC_DIR/relay_server.py" "$APP_DIR/relay_server.py"
rm -rf "$APP_DIR/relay"
cp -a "$SRC_DIR/relay" "$APP_DIR/relay"
chown -R root:root "$APP_DIR"
chown -R "$SVC_USER:$SVC_USER" "$DATA_DIR"

echo "==> 3/5 安装 Python 依赖到 $APP_DIR/venv"
[ -x "$APP_DIR/venv/bin/python" ] || python3 -m venv "$APP_DIR/venv"
"$APP_DIR/venv/bin/pip" install -q --upgrade pip
"$APP_DIR/venv/bin/pip" install -q -r "$APP_DIR/relay/requirements.txt"
DB_DESC="$DATA_DIR/s20relay.db (SQLite)"
DB_ENV="Environment=RELAY_DB=$DATA_DIR/s20relay.db"
if [ "${RELAY_DB_BACKEND:-}" = "mysql" ] || [ -n "${RELAY_DB_HOST:-}" ]; then
    "$APP_DIR/venv/bin/pip" install -q PyMySQL
    DB_ENV=$(cat <<MYSQL
Environment=RELAY_DB_BACKEND=mysql
Environment=RELAY_DB_HOST=${RELAY_DB_HOST:-127.0.0.1}
Environment=RELAY_DB_PORT=${RELAY_DB_PORT:-3306}
Environment=RELAY_DB_USER=${RELAY_DB_USER:-root}
Environment=RELAY_DB_PASS=${RELAY_DB_PASS:-}
Environment=RELAY_DB_NAME=${RELAY_DB_NAME:-s20relay}
MYSQL
)
    DB_DESC="mysql://${RELAY_DB_USER:-root}@${RELAY_DB_HOST:-127.0.0.1}:${RELAY_DB_PORT:-3306}/${RELAY_DB_NAME:-s20relay}"
fi

echo "==> 4/5 安装 systemd 服务 + 写入 token 覆盖配置"
install -m 644 "$APP_DIR/relay/s20relay.service" /etc/systemd/system/s20relay.service
mkdir -p /etc/systemd/system/s20relay.service.d
cat >/etc/systemd/system/s20relay.service.d/override.conf <<EOF
[Service]
Environment=RELAY_TOKEN=$RELAY_TOKEN
Environment=RELAY_PORT=$RELAY_PORT
Environment=RELAY_HOST=$RELAY_HOST
Environment=LOG_LEVEL=$LOG_LEVEL
$DB_ENV
EOF
systemctl daemon-reload
systemctl enable s20relay >/dev/null 2>&1
systemctl restart s20relay

echo "==> 5/5 检查状态"
sleep 2
systemctl --no-pager --lines=8 status s20relay || true

echo
echo "---------- 安装完成 ----------"
echo "监听：$RELAY_HOST:$RELAY_PORT（明文，交给 nginx 终止 TLS）"
echo "数据库：$DB_DESC"
echo "日志：journalctl -u s20relay -f（级别 LOG_LEVEL=$LOG_LEVEL，可传 LOG_LEVEL=WARNING 只记错）"
echo
echo "下一步（手动，避免动到现网配置）："
echo "  1) 配 nginx 反代："
echo "     cp $APP_DIR/relay/nginx-s20.conf /etc/nginx/conf.d/s20.conf"
echo "     nano /etc/nginx/conf.d/s20.conf   # 改 server_name 与证书路径"
echo "     nginx -t && nginx -s reload"
echo "  2) 自检："
echo "     curl -s -o /dev/null -w 'no-token=%{http_code}\\n' https://你的域名/"
echo "     curl -s -o /dev/null -w 'with-token=%{http_code}\\n' 'https://你的域名/?token=$RELAY_TOKEN'"
echo "  3) 手机端 Config.relayUrl 填：wss://你的域名/ws"
