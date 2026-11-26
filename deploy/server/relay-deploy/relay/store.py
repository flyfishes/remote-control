#!/usr/bin/env python3
"""
S20 中转服务器 - 统一存储入口

按环境变量选择后端，对外 API 与两个后端完全一致，上层 relay_server.py 无需改动：

  RELAY_DB_BACKEND=sqlite   SQLite 3（默认，零额外依赖，DB 路径由 RELAY_DB 指定）
  RELAY_DB_BACKEND=mysql    MySQL 8（需 pip install PyMySQL，连接由下列变量指定）

未显式设置 RELAY_DB_BACKEND 时：出现任一 RELAY_DB_HOST/PORT/USER/PASS/NAME 即自动选 mysql，
否则保持 sqlite（兼容所有已有部署）。

MySQL 连接变量：
  RELAY_DB_HOST(127.0.0.1) RELAY_DB_PORT(3306) RELAY_DB_USER(root)
  RELAY_DB_PASS("") RELAY_DB_NAME(s20relay) RELAY_DB_CHARSET(utf8mb4)
"""
import os

_MYSQL_VARS = ("RELAY_DB_HOST", "RELAY_DB_PORT", "RELAY_DB_USER", "RELAY_DB_PASS", "RELAY_DB_NAME")
_raw = os.environ.get("RELAY_DB_BACKEND", "").strip().lower()
if not _raw:
    _raw = "mysql" if any(os.environ.get(k) for k in _MYSQL_VARS) else "sqlite"

if _raw in ("sqlite", "sqlite3"):
    BACKEND = "sqlite"
    import store_sqlite as _impl
    DB_PATH = _impl.DB_PATH
elif _raw in ("mysql", "mysql8"):
    BACKEND = "mysql"
    import store_mysql as _impl
    DB_PATH = "mysql://%s:%s/%s" % (
        _impl.HOST, _impl.PORT, _impl.DATABASE,
    )
else:
    raise RuntimeError("RELAY_DB_BACKEND 只支持 sqlite / mysql，当前值：%s" % _raw)


def init(path: str = None) -> None:
    return _impl.init(path)


def save_state(device: str, status: dict, ts: float, abnormal: bool) -> None:
    return _impl.save_state(device, status, ts, abnormal)


def touch(device: str, ts: float = None) -> None:
    return _impl.touch(device, ts)


def set_abnormal(device: str, abnormal: bool) -> None:
    return _impl.set_abnormal(device, abnormal)


def get_state(device: str) -> dict:
    return _impl.get_state(device)


def all_devices() -> list:
    return _impl.all_devices()


def add_event(device: str, etype: str, payload=None, ts: float = None,
              abnormal: bool = False, reasons=None, direction: str = None) -> None:
    return _impl.add_event(device, etype, payload, ts, abnormal, reasons, direction)


def get_events(device: str, limit: int = 50, before_id: int = None) -> list:
    return _impl.get_events(device, limit, before_id)


def stats() -> dict:
    return _impl.stats()
