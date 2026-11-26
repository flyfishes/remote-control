#!/usr/bin/env python3
"""
S20 中转服务器 - MySQL 8 持久化（可选后端）

与 store_sqlite.py 保持完全相同的公开函数签名，store.py 按环境变量路由。
驱动：PyMySQL（纯 Python，可选依赖，仅 MySQL 模式需要）
  pip install PyMySQL

环境变量：
  RELAY_DB_HOST      默认 127.0.0.1
  RELAY_DB_PORT      默认 3306
  RELAY_DB_USER      默认 root
  RELAY_DB_PASS      默认空
  RELAY_DB_NAME      默认 s20relay
  RELAY_DB_CHARSET   默认 utf8mb4

注意：数据库/表需账号有 CREATE 权限（首次启动自动建表建索引），
      或提前手工执行下面的 DDL。
"""
import json
import os
import threading
import time

try:
    import pymysql
except ImportError as _e:
    pymysql = None
    _IMPORT_ERROR = _e
else:
    _IMPORT_ERROR = None

HOST = os.environ.get("RELAY_DB_HOST", "127.0.0.1")
PORT = int(os.environ.get("RELAY_DB_PORT", "3306"))
USER = os.environ.get("RELAY_DB_USER", "root")
PASSWORD = os.environ.get("RELAY_DB_PASS", "")
DATABASE = os.environ.get("RELAY_DB_NAME", "s20relay")
CHARSET = os.environ.get("RELAY_DB_CHARSET", "utf8mb4")

# utf8mb4_bin：与 SQLite TEXT 的二进制比较保持一致（大小写敏感）
_DDL = (
    """CREATE TABLE IF NOT EXISTS device_state (
         device      VARCHAR(191) NOT NULL PRIMARY KEY,
         ts          DOUBLE NULL,
         server_ts   DOUBLE NULL,
         abnormal    TINYINT NOT NULL DEFAULT 0,
         status_json TEXT NULL,
         last_seen   DOUBLE NULL,
         last_type   VARCHAR(32) NULL
       ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin""",
    """CREATE TABLE IF NOT EXISTS events (
         id        BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
         device    VARCHAR(191) NULL,
         ts        DOUBLE NULL,
         server_ts DOUBLE NULL,
         type      VARCHAR(64) NULL,
         dir       VARCHAR(32) NULL,
         abnormal  TINYINT NOT NULL DEFAULT 0,
         reasons   TEXT NULL,
         payload   MEDIUMTEXT NULL
       ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin""",
)

# MySQL 不支持 CREATE INDEX IF NOT EXISTS：先查 information_schema 再建
_INDEXES = (
    ("idx_events_device_id", "CREATE INDEX idx_events_device_id ON events(device, id DESC)"),
    ("idx_events_ts", "CREATE INDEX idx_events_ts ON events(server_ts)"),
)

_lock = threading.RLock()
_conn = None


def _require_driver():
    if pymysql is None:
        raise RuntimeError(
            "MySQL 模式需要 PyMySQL，请先安装：pip install PyMySQL（原始错误：%s）" % _IMPORT_ERROR
        )


def _connect():
    return pymysql.connect(
        host=HOST, port=PORT, user=USER, password=PASSWORD, database=DATABASE,
        charset=CHARSET, cursorclass=pymysql.cursors.DictCursor,
        autocommit=False, connect_timeout=10, read_timeout=15, write_timeout=15,
    )


def _now() -> float:
    return time.time()


def _lost(e) -> bool:
    """连接断开类错误（2006/2013/2014，以及 pymysql 抛出的 0）"""
    code = e.args[0] if getattr(e, "args", None) else None
    return code in (0, 2006, 2013, 2014)


def init(path: str = None) -> None:
    """建立连接并建表建索引（幂等）。path 参数仅为兼容 SQLite 版本，忽略。"""
    global _conn
    _require_driver()
    with _lock:
        if _conn is not None:
            return
        _conn = _connect()
        cur = _conn.cursor()
        try:
            for ddl in _DDL:
                cur.execute(ddl)
            for name, ddl in _INDEXES:
                cur.execute(
                    "SELECT COUNT(*) AS n FROM information_schema.statistics "
                    "WHERE table_schema=DATABASE() AND table_name='events' AND index_name=%s",
                    (name,),
                )
                if not cur.fetchone()["n"]:
                    cur.execute(ddl)
            _conn.commit()
        except Exception:
            _conn.rollback()
            _conn.close()
            _conn = None
            raise
        finally:
            cur.close()


def _c():
    global _conn
    _require_driver()
    with _lock:
        if _conn is None:
            init()
        try:
            _conn.ping(reconnect=True)
        except Exception:
            try:
                _conn.close()
            except Exception:
                pass
            _conn = _connect()
        return _conn


def _run(sql: str, args=(), fetch: str = None, commit: bool = False):
    """锁内执行一条语句；断线自动重连重试一次。fetch: one/all/None。"""
    global _conn
    with _lock:
        for attempt in (0, 1):
            c = _c()
            cur = c.cursor()
            try:
                cur.execute(sql, args)
                if commit:
                    c.commit()
                if fetch == "one":
                    return cur.fetchone()
                if fetch == "all":
                    return cur.fetchall()
                return None
            except Exception as e:
                if commit:
                    try:
                        c.rollback()
                    except Exception:
                        pass
                if attempt == 0 and (_lost(e) or isinstance(e, pymysql.err.InterfaceError)):
                    try:
                        c.close()
                    except Exception:
                        pass
                    _conn = None
                    continue
                raise
            finally:
                try:
                    cur.close()
                except Exception:
                    pass


# ===== device_state =====

def save_state(device: str, status: dict, ts: float, abnormal: bool) -> None:
    """写入/更新最新完整状态（status 为空时只更新异常标记与活跃时间）。"""
    now = _now()
    if status is None:
        _run(
            "UPDATE device_state SET abnormal=%s, last_seen=%s, server_ts=%s, ts=%s WHERE device=%s",
            (1 if abnormal else 0, now, now, ts or now, device), commit=True,
        )
    else:
        _run(
            """INSERT INTO device_state(device, ts, server_ts, abnormal, status_json, last_seen, last_type)
               VALUES(%s,%s,%s,%s,%s,%s,'status')
               ON DUPLICATE KEY UPDATE
                 ts=VALUES(ts), server_ts=VALUES(server_ts), abnormal=VALUES(abnormal),
                 status_json=VALUES(status_json), last_seen=VALUES(last_seen), last_type='status'""",
            (device, ts or now, now, 1 if abnormal else 0,
             json.dumps(status, ensure_ascii=False), now),
            commit=True,
        )


def touch(device: str, ts: float = None) -> None:
    """心跳（state=ok）：只刷新活跃时间，不写事件表，不动异常标记。"""
    now = _now()
    _run(
        """INSERT INTO device_state(device, ts, server_ts, abnormal, status_json, last_seen, last_type)
           VALUES(%s,%s,%s,0,NULL,%s,'heartbeat')
           ON DUPLICATE KEY UPDATE ts=VALUES(ts), last_seen=VALUES(last_seen)""",
        (device, ts or now, now, now), commit=True,
    )


def set_abnormal(device: str, abnormal: bool) -> None:
    _run("UPDATE device_state SET abnormal=%s WHERE device=%s",
         (1 if abnormal else 0, device), commit=True)


def get_state(device: str) -> dict:
    row = _run("SELECT * FROM device_state WHERE device=%s", (device,), fetch="one")
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
    rows = _run("SELECT device, abnormal, last_seen FROM device_state ORDER BY last_seen DESC",
                fetch="all") or []
    return [{"name": r["device"], "abnormal": bool(r["abnormal"]), "lastSeen": r["last_seen"]} for r in rows]


# ===== events =====

def add_event(device: str, etype: str, payload=None, ts: float = None,
              abnormal: bool = False, reasons=None, direction: str = None) -> None:
    now = _now()
    reasons_json = json.dumps(reasons or [], ensure_ascii=False)
    payload_json = json.dumps(payload, ensure_ascii=False) if payload is not None else None
    _run(
        "INSERT INTO events(device, ts, server_ts, type, dir, abnormal, reasons, payload)"
        " VALUES(%s,%s,%s,%s,%s,%s,%s,%s)",
        (device, ts or now, now, etype, direction, 1 if abnormal else 0, reasons_json, payload_json),
        commit=True,
    )


def get_events(device: str, limit: int = 50, before_id: int = None) -> list:
    limit = max(1, min(int(limit or 50), 500))
    sql = "SELECT * FROM events WHERE device=%s"
    args = [device]
    if before_id:
        sql += " AND id < %s"
        args.append(int(before_id))
    sql += " ORDER BY id DESC LIMIT %s"
    args.append(limit)
    rows = _run(sql, tuple(args), fetch="all") or []
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
    ev = _run("SELECT COUNT(*) AS n FROM events", fetch="one")
    dv = _run("SELECT COUNT(*) AS n FROM device_state", fetch="one")
    sz = _run(
        "SELECT COALESCE(SUM(data_length+index_length),0) AS n FROM information_schema.tables "
        "WHERE table_schema=DATABASE() AND table_name IN ('events','device_state')",
        fetch="one",
    )
    return {
        "events": int(ev["n"] if ev else 0),
        "devices": int(dv["n"] if dv else 0),
        "dbPath": "mysql://%s:%d/%s" % (HOST, PORT, DATABASE),
        "dbSize": int(sz["n"] if sz else 0),
    }
