#!/usr/bin/env bash
# ============================================================
#  S20 relay server - Linux 后台启动脚本（SQLite 3.30 运行时）
#  用法： bash start-relay.sh        启动（已在运行则跳过）
#         kill $(cat relay.pid)      停止
#  error.log 默认只记 WARNING/ERROR（LOG_LEVEL=WARNING），
#  改日志级别： LOG_LEVEL=INFO bash start-relay.sh
# ============================================================
set -e
cd "$(dirname "$0")"

# 让 Python 的 _sqlite3 链接到 3.30 的 libsqlite3.so.0（UPSERT 需 >= 3.24）
export LD_LIBRARY_PATH="/usr/local/sqlite-3.30.1/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"

# 日志级别默认 WARNING：INFO 正常日志不写 error.log，只保留警告与错误
# 过滤由 relay_server.py 的 LOG_LEVEL 完成（代码层），脚本不再做文本过滤
export LOG_LEVEL="${LOG_LEVEL:-WARNING}"

# 启动时把实际 sqlite 版本与日志级别打到终端（不进 error.log）
echo "[relay] sqlite runtime: $(python3 -c 'import sqlite3; print(sqlite3.sqlite_version)')"
echo "[relay] LOG_LEVEL=$LOG_LEVEL"

PID_FILE=relay.pid
if [ -f "$PID_FILE" ] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
  echo "[relay] already running, pid=$(cat "$PID_FILE")"
  exit 0
fi

# stdout 丢弃；stderr 写入 error.log，级别由 LOG_LEVEL 控制
nohup python3 relay_server.py >/dev/null 2>>error.log &
echo $! > "$PID_FILE"

echo "[relay] started pid=$(cat "$PID_FILE")"
echo "[relay] stdout -> /dev/null, stderr($LOG_LEVEL+) -> error.log"
