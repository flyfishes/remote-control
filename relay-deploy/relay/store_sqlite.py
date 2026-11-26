#!/usr/bin/env python3
"""
S20 中转服务器 - SQLite 持久化

存储内容：
  device_state  每个设备一行：最新完整状态、异常标记、最后活跃时间
  events        收到的所有事件（心跳 state=ok 不写库）：
                  status / heartbeat(异常) / recover / hello / online / offline
                  / cmd(控制端指令) / response(设备回执)

DB 文件：默认 <本文件目录>/s20relay.db，可用环境变量 RELAY_DB 覆盖。
"""
import json
import os
import sqlite3
import threading
import time

DB_PATH = os.environ.get(
    "RELAY_DB",
    os.path.join(os.path.dirname(os.path.abspath(__file__)), "s20relay.db"),
)

_SCHEMA = """
CREATE TABLE IF NOT EXISTS device_state (
  device      TEXT PRIMARY KEY,
  ts          REAL,
  server_ts   REAL,
  abnormal    INTEGER DEFAULT 0,
  status_json TEXT,
  last_seen   REAL,
  last_type   TEXT
);
CREATE TABLE IF NOT EXISTS events (
  id        INTEGER PRIMARY KEY AUTOINCREMENT,
  device    TEXT,
  ts        REAL,
  server_ts REAL,
  type      TEXT,
  dir       TEXT,
  abnormal  INTEGER DEFAULT 0,
  reasons   TEXT,
  payload   TEXT
);
CREATE INDEX IF NOT EXISTS idx_events_device_id ON events(device, id DESC);
CREATE INDEX IF NOT EXISTS idx_events_ts ON events(server_ts);
"""

_lock = threading.RLock()
_conn = None


def init(path: str = None) -> None:
    """建立连接并建表（幂等）。"""
    global _conn
    p = path or DB_PATH
    os.makedirs(os.path.dirname(os.path.abspath(p)), exist_ok=True)
    with _lock:
        if _conn is not None:
            return
        _conn = sqlite3.connect(p, check_same_thread=False, timeout=10)
        _conn.row_factory = sqlite3.Row
        try:
            _conn.execute("PRAGMA journal_mode=WAL")
            _conn.execute("PRAGMA synchronous=NORMAL")
        except Exception:
            pass
        _conn.executescript(_SCHEMA)
        _conn.commit()


def _c():
    if _conn is None:
        init()
    return _conn


def _now() -> float:
    return time.time()


# ===== device_state =====

def save_state(device: str, status: dict, ts: float, abnormal: bool) -> None:
    """写入/更新最新完整状态（status 为空时只更新异常标记与活跃时间）。"""
    now = _now()
    with _lock:
        c = _c()
        if status is None:
            c.execute(
                "UPDATE device_state SET abnormal=?, last_seen=?, server_ts=?, ts=? WHERE device=?",
                (1 if abnormal else 0, now, now, ts or now, device),
            )
        else:
            # ON CONFLICT ... DO UPDATE 为 SQLite 3.24+ 语法，需确保 Python 链接的
            # 运行时 >= 3.24（可用 python -c "import sqlite3;print(sqlite3.sqlite_version)" 确认）。
            c.execute(
                """INSERT INTO device_state(device, ts, server_ts, abnormal, status_json, last_seen, last_type)
                   VALUES(?,?,?,?,?,?,'status')
                   ON CONFLICT(device) DO UPDATE SET
                     ts=excluded.ts, server_ts=excluded.server_ts, abnormal=excluded.abnormal,
                     status_json=excluded.status_json, last_seen=excluded.last_seen, last_type='status'""",
                (device, ts or now, now, 1 if abnormal else 0,
                 json.dumps(status, ensure_ascii=False), now),
            )
        c.commit()


def touch(device: str, ts: float = None) -> None:
    """心跳（state=ok）：只刷新活跃时间，不写事件表，不动异常标记。"""
    now = _now()
    with _lock:
        c = _c()
        cur = c.execute("UPDATE device_state SET last_seen=?, ts=? WHERE device=?",
                        (now, ts or now, device))
        if cur.rowcount == 0:
            c.execute(
                "INSERT INTO device_state(device, ts, server_ts, abnormal, status_json, last_seen, last_type)"
                " VALUES(?,?,?,0,NULL,?,'heartbeat')",
                (device, ts or now, now, now),
            )
        c.commit()


def set_abnormal(device: str, abnormal: bool) -> None:
    with _lock:
        c = _c()
        c.execute("UPDATE device_state SET abnormal=? WHERE device=?", (1 if abnormal else 0, device))
        c.commit()


def get_state(device: str) -> dict:
    with _lock:
        row = _c().execute("SELECT * FROM device_state WHERE device=?", (device,)).fetchone()
    if row is None:
        return None
    st = None
    try:
        st = json.loads(row["status_json"]) if row["status_json"] else None
    except Exception:
        st = None
    return {
        "device": row["device"],
        "ts": row["ts"] or 0,
        "serverTs": row["server_ts"] or 0,
        "abnormal": bool(row["abnormal"]),
        "status": st,
        "lastSeen": row["last_seen"] or 0,
    }


def all_devices() -> list:
    with _lock:
        rows = _c().execute("SELECT device, abnormal, last_seen FROM device_state ORDER BY last_seen DESC").fetchall()
    return [{"name": r["device"], "abnormal": bool(r["abnormal"]), "lastSeen": r["last_seen"]} for r in rows]


# ===== events =====

def add_event(device: str, etype: str, payload=None, ts: float = None,
              abnormal: bool = False, reasons=None, direction: str = None) -> None:
    now = _now()
    reasons_json = json.dumps(reasons or [], ensure_ascii=False)
    payload_json = json.dumps(payload, ensure_ascii=False) if payload is not None else None
    with _lock:
        c = _c()
        c.execute(
            "INSERT INTO events(device, ts, server_ts, type, dir, abnormal, reasons, payload)"
            " VALUES(?,?,?,?,?,?,?,?)",
            (device, ts or now, now, etype, direction, 1 if abnormal else 0, reasons_json, payload_json),
        )
        c.commit()


def get_events(device: str, limit: int = 50, before_id: int = None) -> list:
    limit = max(1, min(int(limit or 50), 500))
    sql = "SELECT * FROM events WHERE device=?"
    args = [device]
    if before_id:
        sql += " AND id < ?"
        args.append(int(before_id))
    sql += " ORDER BY id DESC LIMIT ?"
    args.append(limit)
    with _lock:
        rows = _c().execute(sql, args).fetchall()
    out = []
    for r in rows:
        out.append({
            "id": r["id"],
            "time": time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(r["server_ts"] or r["ts"] or 0)),
            "deviceTs": r["ts"],
            "type": r["type"],
            "dir": r["dir"],
            "abnormal": bool(r["abnormal"]),
            "reasons": json.loads(r["reasons"]) if r["reasons"] else [],
            "payload": json.loads(r["payload"]) if r["payload"] else None,
        })
    return out


def stats() -> dict:
    with _lock:
        c = _c()
        ev = c.execute("SELECT COUNT(*) AS n FROM events").fetchone()["n"]
        dv = c.execute("SELECT COUNT(*) AS n FROM device_state").fetchone()["n"]
    size = os.path.getsize(DB_PATH) if os.path.exists(DB_PATH) else 0
    return {"events": ev, "devices": dv, "dbPath": DB_PATH, "dbSize": size}
