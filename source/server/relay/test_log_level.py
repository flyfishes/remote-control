#!/usr/bin/env python3
"""验证 relay_server.py 的 LOG_LEVEL 解析（子进程 import，避免占位端口）。"""
import os
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

PROBE = r'''
import logging, relay_server as r
print("LEVEL=%d NAME=%s" % (logging.getLogger().level, logging.getLevelName(logging.getLogger().level)))
'''


def probe(env_level=None):
    env = dict(os.environ)
    env.pop("LOG_LEVEL", None)
    if env_level is not None:
        env["LOG_LEVEL"] = env_level
    env["PYTHONPATH"] = ROOT
    out = subprocess.check_output([sys.executable, "-c", PROBE], cwd=ROOT, env=env,
                                  stderr=subprocess.STDOUT, timeout=20).decode()
    line = [x for x in out.splitlines() if x.startswith("LEVEL=")][-1]
    return line


def main():
    cases = [
        (None, "LEVEL=20 NAME=INFO", "未设置 -> 默认 INFO"),
        ("INFO", "LEVEL=20 NAME=INFO", "INFO"),
        ("DEBUG", "LEVEL=10 NAME=DEBUG", "DEBUG"),
        ("WARNING", "LEVEL=30 NAME=WARNING", "WARNING"),
        ("error", "LEVEL=40 NAME=ERROR", "小写 error"),
        (" Critical ", "LEVEL=50 NAME=CRITICAL", "带空格大写 CRITICAL"),
        ("BOGUS", "LEVEL=20 NAME=INFO", "非法值回退 INFO"),
        ("", "LEVEL=20 NAME=INFO", "空值回退 INFO"),
    ]
    for inp, expect, desc in cases:
        got = probe(inp)
        ok = got.strip() == expect
        print("[%s] %-22s -> %s" % ("OK" if ok else "FAIL", desc, got.strip()))
        if not ok:
            print("   expected:", expect)
            sys.exit(1)
    print("ALL LOG_LEVEL TESTS PASSED")


if __name__ == "__main__":
    main()
