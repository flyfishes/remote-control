#!/usr/bin/env bash
# ============================================================
#  S20 relay server - Linux 后台启动脚本（SQLite 3.30 运行时）
#  用法： bash start-relay.sh        启动（已在运行则跳过）
#         kill $(cat relay.pid)      停止
#  error.log 只记录 WARNING/ERROR/CRITICAL 及堆栈，INFO 正常日志不写入
# ============================================================
set -e
cd "$(dirname "$0")"

# 让 Python 的 _sqlite3 链接到 3.30 的 libsqlite3.so.0（UPSERT 需 >= 3.24）
export LD_LIBRARY_PATH="/usr/local/sqlite-3.30.1/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"

# 启动时把实际 sqlite 版本打到终端（不进 error.log）
echo "[relay] sqlite runtime: $(python3 -c 'import sqlite3; print(sqlite3.sqlite_version)')"

PID_FILE=relay.pid
if [ -f "$PID_FILE" ] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
  echo "[relay] already running, pid=$(cat "$PID_FILE")"
  exit 0
fi

# stdout 丢弃；stderr 经进程替换过滤掉 logging 的 INFO/DEBUG 行后再写入 error.log
# 保留 WARNING/ERROR/CRITICAL 以及 Python traceback（堆栈行无级别前缀，不会被过滤）
nohup python3 relay_server.py \
  >/dev/null \
  2> >(grep -vE '^[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9:,]+ (INFO|DEBUG) ' >> error.log) \
  &
echo $! > "$PID_FILE"

echo "[relay] started pid=$(cat "$PID_FILE")"
echo "[relay] stdout -> /dev/null, stderr(WARNING+) -> error.log"
