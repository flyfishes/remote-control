#!/usr/bin/env python3
"""
存储层双后端回归测试（无需真实 MySQL 实例）

  python relay/test_store_backends.py

- SQLite：临时库跑完 9 个公开函数
- MySQL：注入假 PyMySQL 驱动，校验 MySQL 8 方言、upsert、建索引幂等、断线重试
- 路由：子进程验证 store.py 按 RELAY_DB_BACKEND 选择后端
"""
import os
import subprocess
import sys
import tempfile
import types

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)


def check_sqlite():
    tmp = tempfile.mkdtemp(prefix="s20db-")
    os.environ["RELAY_DB"] = os.path.join(tmp, "t.db")
    for k in ("RELAY_DB_BACKEND", "RELAY_DB_HOST", "RELAY_DB_PORT", "RELAY_DB_USER", "RELAY_DB_PASS", "RELAY_DB_NAME"):
        os.environ.pop(k, None)
    sys.modules.pop("store_sqlite", None)
    import store_sqlite as s

    s.init()
    s.save_state("s20", {"battery": 88, "中文": "测试"}, 1700000000.5, False)
    s.touch("s20", 1700000001.5)
    s.set_abnormal("s20", True)
    st = s.get_state("s20")
    assert st["status"]["中文"] == "测试" and st["abnormal"] is True, st
    assert any(d["name"] == "s20" for d in s.all_devices())

    s.add_event("s20", "status", {"a": 1}, ts=1700000000.5, reasons=["r"], direction="in")
    s.add_event("s20", "cmd", {"b": 2}, direction="out")
    ev = s.get_events("s20", limit=1)
    assert len(ev) == 1 and ev[0]["type"] == "cmd", ev
    ev2 = s.get_events("s20", limit=10, before_id=ev[0]["id"])
    assert ev2 and ev2[0]["type"] == "status", ev2
    assert s.stats()["events"] == 2
    print("[sqlite] 9 APIs OK; events=%d devices=%d" % (s.stats()["events"], s.stats()["devices"]))


class FakeCursor:
    def __init__(self, conn):
        self.conn = conn
        self.rows = []

    def execute(self, sql, args=()):
        self.conn.calls.append((sql, tuple(args)))
        if self.conn.fail_next:
            self.conn.fail_next = False
            raise self.conn.err_class(2006, "MySQL server has gone away")
        s = sql.strip()
        if "information_schema.statistics" in s:
            self.rows = [{"n": 0 if self.conn.no_index else 1}]
        elif "COUNT(*) AS n FROM events" in s:
            self.rows = [{"n": 2}]
        elif "COUNT(*) AS n FROM device_state" in s:
            self.rows = [{"n": 1}]
        elif "SUM(data_length+index_length)" in s:
            self.rows = [{"n": 4096}]
        elif "SELECT * FROM device_state" in s:
            self.rows = [{"device": "s20", "ts": 1700000000.5, "server_ts": 1700000001.5,
                          "abnormal": 1, "status_json": '{"battery": 88, "中文": "测试"}',
                          "last_seen": 1700000002.5, "last_type": "status"}]
        elif "SELECT device, abnormal, last_seen" in s:
            self.rows = [{"device": "s20", "abnormal": 1, "last_seen": 1700000002.5}]
        elif "SELECT * FROM events" in s:
            self.rows = [{"id": 2, "ts": 1700000001.0, "server_ts": 1700000002.0, "type": "cmd",
                          "dir": "out", "abnormal": 0, "reasons": "[]", "payload": '{"b": 2}'}]
        else:
            self.rows = []
        return 1

    def fetchone(self):
        return self.rows[0] if self.rows else None

    def fetchall(self):
        return list(self.rows)

    def close(self):
        self.conn.closed_cursors += 1


class FakeConn:
    def __init__(self, err_class):
        self.calls = []
        self.commits = 0
        self.rollbacks = 0
        self.closes = 0
        self.closed_cursors = 0
        self.fail_next = False
        self.no_index = False
        self.err_class = err_class

    def cursor(self):
        return FakeCursor(self)

    def commit(self):
        self.commits += 1

    def rollback(self):
        self.rollbacks += 1

    def ping(self, reconnect=False):
        pass

    def close(self):
        self.closes += 1


def check_mysql():
    errs = types.SimpleNamespace(
        OperationalError=type("OperationalError", (Exception,), {}),
        InterfaceError=type("InterfaceError", (Exception,), {}),
    )
    cons = []
    state = {"no_index": False}

    def connect(**kw):
        c = FakeConn(errs.OperationalError)
        c.no_index = state["no_index"]
        cons.append((kw, c))
        return c

    fake = types.ModuleType("pymysql")
    fake.cursors = types.SimpleNamespace(DictCursor=object)
    fake.connect = connect
    fake.err = errs
    sys.modules["pymysql"] = fake

    sys.modules.pop("store_mysql", None)
    import store_mysql as s

    s.init()
    c = cons[-1][1]
    ddl = " ".join(sql for sql, _ in c.calls)
    assert "AUTO_INCREMENT" in ddl and "DOUBLE" in ddl and "utf8mb4_bin" in ddl, ddl[:200]
    assert "VARCHAR(191)" in ddl
    assert "CREATE INDEX" not in ddl, "索引已存在时不应重复建"  # 默认 fake 返回 n=1

    s.save_state("s20", {"battery": 88, "中文": "测试"}, 1700000000.5, False)
    s.touch("s20")
    s.set_abnormal("s20", True)
    s.add_event("s20", "status", {"a": 1}, reasons=["r"], direction="in")
    ev = s.get_events("s20", limit=10, before_id=5)
    assert ev[0]["type"] == "cmd"
    assert s.get_state("s20")["status"]["中文"] == "测试"
    assert s.all_devices()[0]["name"] == "s20"
    stat = s.stats()
    assert stat["events"] == 2 and stat["dbPath"].startswith("mysql://") and stat["dbSize"] == 4096, stat

    calls = c.calls
    assert all("?" not in sql for sql, _ in calls), "存在 SQLite 占位符"
    assert any("ON DUPLICATE KEY UPDATE" in sql for sql, _ in calls)
    assert any("VALUES(" in sql for sql, _ in calls)
    print("[mysql] 9 APIs OK; %d SQL calls; dialect=MySQL8" % len(calls))

    # 索引幂等：information_schema 返回 0 时应生成 CREATE INDEX
    state["no_index"] = True
    sys.modules.pop("store_mysql", None)
    import store_mysql as s3
    s3.init()
    created = [sql for sql, _ in cons[-1][1].calls if "CREATE INDEX" in sql]
    assert len(created) == 2, created
    print("[mysql] index idempotency OK:", [x[:40] for x in created])

    # 断线重试：第一次 execute 抛 2006，应重连并重试同一条 SQL
    sys.modules.pop("store_mysql", None)
    import store_mysql as s4
    s4.init()
    c4 = cons[-1][1]
    c4.fail_next = True
    before = len(c4.calls)
    s4.touch("s20")
    new_c = cons[-1][1]
    assert new_c is not c4, "应建立新连接"
    assert new_c.calls and "INSERT INTO device_state" in new_c.calls[-1][0], new_c.calls
    assert c4.rollbacks >= 1
    print("[mysql] reconnect+retry OK")


def check_routing():
    code = ("import sys,os;sys.path.insert(0,%r);import store;"
            "print(store.BACKEND, store.DB_PATH)" % HERE)
    env = dict(os.environ)
    env.pop("RELAY_DB_BACKEND", None)
    for k in ("RELAY_DB_HOST", "RELAY_DB_PORT", "RELAY_DB_USER", "RELAY_DB_PASS", "RELAY_DB_NAME"):
        env.pop(k, None)
    env["RELAY_DB"] = os.path.join(tempfile.gettempdir(), "route.db")
    r = subprocess.run([sys.executable, "-c", code], env=env, capture_output=True, text=True)
    assert r.stdout.startswith("sqlite "), r.stdout + r.stderr
    env["RELAY_DB_BACKEND"] = "mysql"
    env["RELAY_DB_HOST"] = "db.internal"
    env["RELAY_DB_NAME"] = "relay"
    r2 = subprocess.run([sys.executable, "-c", code], env=env, capture_output=True, text=True)
    assert r2.stdout.startswith("mysql mysql://db.internal:3306/relay"), r2.stdout + r2.stderr
    print("[routing] sqlite(default) + mysql(explicit/auto-detect) OK")


if __name__ == "__main__":
    check_sqlite()
    check_mysql()
    check_routing()
    print("ALL DUAL-BACKEND TESTS PASSED")
