#!/usr/bin/env python3
"""
S20 远程控制 - 公网中转服务器（通道B）

部署（二选一）：
  A. nginx 终止 TLS（推荐）  nginx 443 -> 反代 127.0.0.1:9000，脚本跑明文
  B. 内置 TLS               RELAY_SSL_CERT=/path/fullchain.pem RELAY_SSL_KEY=/path/key.pem

环境变量：
  RELAY_TOKEN      与手机端 config.token 一致（默认 s20-default-token）
  RELAY_PORT       监听端口，默认 9000
  RELAY_HOST       监听地址，默认 0.0.0.0（nginx 反代时建议 127.0.0.1）
  RELAY_DB         SQLite 路径，默认 relay/s20relay.db
  RELAY_DB_BACKEND sqlite(默认) / mysql；mysql 需 pip install PyMySQL
  RELAY_DB_HOST/PORT/USER/PASS/NAME/CHARSET   MySQL 8 连接参数
  RELAY_SSL_CERT / RELAY_SSL_KEY   内置 TLS 证书与私钥
  LOG_LEVEL        日志级别 DEBUG/INFO/WARNING/ERROR/CRITICAL，默认 INFO
                   设为 WARNING 可只输出警告与错误（error.log 场景）

路由：
  GET  /                  Web 控制台（需 token，成功后种 cookie）
  GET  /cert              仅手机端使用，这里不提供
  WS   /ws                手机 (?device=&token=) / 控制端 (?role=controller&token=)
  POST /api/cmd           同步指令 {"device","cmd","params"}
  GET  /api/status        设备在线概览
  GET  /api/device/state  最新状态 + 最近事件（默认 50）
  GET  /api/events        事件分页（before_id）
"""
import asyncio
import json
import logging
import os
import ssl
import sys
import time
import uuid

from aiohttp import web, WSMsgType

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "relay"))
import store  # noqa: E402  (relay/store.py)

# ===== 日志级别：LOG_LEVEL 控制，非法值回退 INFO =====
_LOG_LEVEL_NAME = os.environ.get("LOG_LEVEL", "INFO").strip().upper()
_LOG_LEVEL = getattr(logging, _LOG_LEVEL_NAME, None)
if not isinstance(_LOG_LEVEL, int):
    _LOG_LEVEL = logging.INFO
logging.basicConfig(level=_LOG_LEVEL, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("relay")

# ===== 配置 =====
TOKEN = os.environ.get("RELAY_TOKEN", "s20-default-token")   # 与手机端 config.token 一致
PORT = int(os.environ.get("RELAY_PORT", "9000"))
HOST = os.environ.get("RELAY_HOST", "0.0.0.0")
WEB_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "relay", "web")
COOKIE_NAME = "relaytk"
HISTORY_MAX = 50

# device_name -> websocket
devices = {}
# controller_id -> websocket
controllers = {}
# device_name -> 上次心跳时间
device_last_seen = {}
# device_name -> {"status": {...}, "ts": float, "abnormal": bool}（实时缓存，历史在 sqlite）
device_state = {}

routes_app = web.Application()

UNAUTHORIZED_HTML = """<!DOCTYPE html><html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>401 未授权</title>
<style>body{font-family:-apple-system,"Segoe UI","Microsoft YaHei",sans-serif;background:#f5f6fa;
color:#2c2c2a;display:flex;align-items:center;justify-content:center;height:100vh;margin:0}
.box{background:#fff;border:1px solid #e2e1db;border-radius:12px;padding:28px 32px;max-width:420px}
h1{font-size:18px;margin:0 0 10px;color:#a32d2d}p{font-size:13px;line-height:1.8;margin:6px 0;color:#555}
code{background:#f1efe8;border-radius:4px;padding:2px 6px;font-size:12px}</style></head>
<body><div class="box"><h1>401 未授权</h1>
<p>访问令牌缺失或错误，控制台不会在此显示。</p>
<p>请在地址后附加令牌：<br><code>/?token=你的访问令牌</code></p>
<p>首次校验通过后会写入 cookie，后续刷新无需再带参数。</p></div></body></html>"""


# ===== 鉴权 =====

def _header_token(request) -> str:
    t = request.headers.get("X-Token") or request.headers.get("x-token") or ""
    if t:
        return t.strip()
    auth = request.headers.get("Authorization", "")
    if auth.lower().startswith("bearer "):
        return auth[7:].strip()
    return ""


def token_of(request) -> str:
    """取值优先级：X-Token 头 -> ?token= -> cookie"""
    t = _header_token(request)
    if t:
        return t
    q = request.rel_url.query.get("token", "")
    if q:
        return q.strip()
    return (request.cookies.get(COOKIE_NAME) or request.cookies.get("s20tk") or "").strip()


def check_token(request) -> bool:
    return token_of(request) == TOKEN and TOKEN != ""


def is_secure(request) -> bool:
    """兼容 nginx 反代：X-Forwarded-Proto 优先。"""
    xf = (request.headers.get("X-Forwarded-Proto") or "").split(",")[0].strip().lower()
    if xf:
        return xf == "https"
    try:
        return request.scheme == "https"
    except Exception:
        return False


def client_ip(request) -> str:
    xff = request.headers.get("X-Forwarded-For") or ""
    if xff:
        return xff.split(",")[0].strip()
    return request.remote or "-"


def unauthorized(request):
    if request.path.startswith("/api/") or request.path == "/api/cmd":
        return web.json_response({"code": 401, "msg": "unauthorized: bad or missing token"}, status=401)
    return web.Response(text=UNAUTHORIZED_HTML, status=401, content_type="text/html", charset="utf-8")


def set_auth_cookie(resp, request):
    resp.set_cookie(
        COOKIE_NAME, TOKEN, max_age=30 * 86400, path="/",
        httponly=True, samesite="Strict", secure=is_secure(request),
    )


# ===== WebSocket =====

async def ws_handler(request):
    ws = web.WebSocketResponse(heartbeat=30)
    await ws.prepare(request)
    q = request.rel_url.query
    if not check_token(request):
        log.warning("ws rejected from %s (bad token)", client_ip(request))
        await ws.send_json({"type": "error", "msg": "unauthorized: bad token"})
        await ws.close(code=4003, message=b"unauthorized")
        return ws

    role = q.get("role", "device")
    if role == "controller":
        cid = str(uuid.uuid4())[:8]
        controllers[cid] = ws
        log.info("controller %s connected (%d online) from %s", cid, len(controllers), client_ip(request))
        try:
            async for msg in ws:
                if msg.type == WSMsgType.TEXT:
                    await handle_controller_msg(cid, ws, msg.data)
                elif msg.type in (WSMsgType.ERROR, WSMsgType.CLOSE):
                    break
        finally:
            controllers.pop(cid, None)
            log.info("controller %s disconnected", cid)
    else:
        device = q.get("device", "unknown")
        # 同名设备顶掉旧连接
        old = devices.get(device)
        if old is not None and not old.closed:
            await old.close(code=4001, message=b"replaced")
        devices[device] = ws
        device_last_seen[device] = time.time()
        log.info("device '%s' connected (%d online: %s) from %s",
                 device, len(devices), list(devices), client_ip(request))
        await ws.send_json({"type": "welcome", "device": device})
        store.add_event(device, "online", {"ip": client_ip(request)})
        try:
            async for msg in ws:
                if msg.type == WSMsgType.TEXT:
                    await handle_device_msg(device, msg.data)
                elif msg.type in (WSMsgType.ERROR, WSMsgType.CLOSE):
                    break
        finally:
            devices.pop(device, None)
            store.add_event(device, "offline", None)
            log.info("device '%s' disconnected (%d online)", device, len(devices))
    return ws


def _slim_for_store(msg: dict) -> dict:
    """事件入库前瘦身：截图回执带 base64 图片（可达数百 KB），
    只保留尺寸等摘要并标记，避免 events 表膨胀拖慢查询。"""
    resp = msg.get("response")
    data = resp.get("data") if isinstance(resp, dict) else None
    if not isinstance(data, dict) or ("b64" not in data and "image" not in data):
        return msg
    slim = dict(msg)
    r2 = dict(resp)
    r2["data"] = {k: v for k, v in data.items() if k not in ("b64", "image")}
    slim["response"] = r2
    slim["slimmed"] = "image-dropped"
    return slim


async def handle_device_msg(device: str, raw: str):
    device_last_seen[device] = time.time()
    try:
        msg = json.loads(raw)
    except json.JSONDecodeError:
        return
    mtype = msg.get("type")
    ts = msg.get("ts")

    # 设备主动上报：完整状态 / 心跳
    if mtype == "status":
        data = msg.get("data") or {}
        abnormal = bool(data.get("abnormal"))
        st = device_state.setdefault(device, {"status": None, "ts": 0, "abnormal": False})
        st["ts"] = time.time()
        st["status"] = data
        st["abnormal"] = abnormal
        store.save_state(device, data, ts, abnormal)
        store.add_event(device, "status", payload=data, ts=ts,
                        abnormal=abnormal, reasons=data.get("reasons"))
        log.info("status '%s' abnormal=%s reasons=%s", device, abnormal, data.get("reasons"))
        await broadcast_device(device, short=False, abnormal=abnormal, data=data)
        return

    if mtype == "heartbeat":
        st = device_state.setdefault(device, {"status": None, "ts": 0, "abnormal": False})
        st["ts"] = time.time()
        state = msg.get("state", "ok")
        if state == "ok":
            # 心跳不入库，只刷新活跃时间；若此前异常则记一条恢复事件
            was_abnormal = bool(st.get("abnormal"))
            store.touch(device, ts)
            if was_abnormal:
                st["abnormal"] = False
                store.set_abnormal(device, False)
                store.add_event(device, "recover", {"via": "heartbeat"}, ts=ts)
                log.info("device '%s' recovered via heartbeat", device)
            await broadcast_device(device, short=True, abnormal=False, data=st.get("status"))
        else:
            st["abnormal"] = True
            store.save_state(device, None, ts, True)
            store.add_event(device, "heartbeat", payload=msg, ts=ts,
                            abnormal=True, reasons=msg.get("reasons"))
            await broadcast_device(device, short=True, abnormal=True, data=st.get("status"))
        return

    # 指令回执 -> 路由给请求的 controller
    if "id" in msg and "response" in msg:
        store.add_event(device, "response", payload=_slim_for_store(msg), direction="out")
        rid = msg.get("id")
        target_id = msg.get("controller")
        if target_id == "__http__" and rid in pending_http:
            fut = pending_http.pop(rid)
            if not fut.done():
                fut.set_result(raw)
            return
        sent = False
        for cid, cws in list(controllers.items()):
            if target_id and cid != target_id:
                continue
            try:
                await cws.send_json(msg)
                sent = True
            except Exception:
                controllers.pop(cid, None)
        if not sent and not controllers:
            log.warning("response for id=%s but no controller online", rid)
        return

    if mtype == "hello":
        log.info("device hello: %s", device)
        store.add_event(device, "hello", payload=msg, ts=ts)
        return

    store.add_event(device, mtype or "unknown", payload=msg, ts=ts)


async def broadcast_device(device: str, short: bool, abnormal: bool, data):
    st = device_state.get(device, {})
    for cid, cws in list(controllers.items()):
        try:
            await cws.send_json({"type": "device_status", "device": device,
                                 "short": short, "abnormal": abnormal,
                                 "ts": st.get("ts", time.time()), "data": data})
        except Exception:
            controllers.pop(cid, None)


async def handle_controller_msg(cid: str, ws, raw: str):
    try:
        msg = json.loads(raw)
    except json.JSONDecodeError:
        return
    mtype = msg.get("type")
    if mtype == "list_devices":
        await ws.send_json({
            "type": "devices",
            "devices": [{"name": d, "online": time.time() - device_last_seen.get(d, 0)} for d in devices],
        })
        return
    if mtype == "cmd" or (mtype is None and "cmd" in msg):
        await route_cmd(msg, cid, ws)
        return
    log.warning("unknown controller msg type: %s", mtype)


async def route_cmd(msg: dict, cid: str, controller_ws):
    device = msg.get("device", "")
    store.add_event(device or "-", "cmd", payload=msg, direction="in")
    ws = devices.get(device)
    if ws is None or ws.closed:
        await controller_ws.send_json({
            "type": "cmd_error", "id": msg.get("id", ""),
            "msg": f"device '{device}' offline", "devices_online": list(devices),
        })
        return
    payload = {"id": msg.get("id", str(uuid.uuid4())[:8]), "controller": cid,
               "payload": {"cmd": msg.get("cmd", ""), "params": msg.get("params", {})}}
    try:
        await ws.send_json(payload)
    except Exception as e:
        await controller_ws.send_json({"type": "cmd_error", "id": payload["id"], "msg": f"send failed: {e}"})


pending_http = {}


# ===== HTTP API =====

async def api_cmd(request):
    """HTTP 控制接口：POST {"device":"s20","cmd":"status.get","params":{}}
    若设备在线，等待其回执（最久 15s），HTTP 同步返回结果。"""
    if not check_token(request):
        return unauthorized(request)
    try:
        body = await request.json()
    except Exception:
        return web.json_response({"code": 400, "msg": "bad json"}, status=400)
    device = body.get("device", "")
    ws = devices.get(device)
    if ws is None or ws.closed:
        return web.json_response({"code": 404, "msg": f"device '{device}' offline",
                                  "devices_online": list(devices)}, status=404)
    rid = body.get("id", str(uuid.uuid4())[:8])
    store.add_event(device, "cmd", payload=body, direction="in")
    payload = {"id": rid, "controller": "__http__",
               "payload": {"cmd": body.get("cmd", ""), "params": body.get("params", {})}}
    fut = asyncio.get_event_loop().create_future()
    pending_http[rid] = fut
    await ws.send_json(payload)
    try:
        result = await asyncio.wait_for(fut, timeout=15)
        return web.json_response(json.loads(result))
    except asyncio.TimeoutError:
        return web.json_response({"code": 504, "msg": "device no response"}, status=504)
    finally:
        pending_http.pop(rid, None)


async def status(request):
    if not check_token(request):
        return unauthorized(request)
    out = []
    for d in devices:
        st = device_state.get(d, {})
        out.append({
            "name": d,
            "age": round(time.time() - device_last_seen.get(d, 0), 1),
            "abnormal": st.get("abnormal", False),
            "statusTs": st.get("ts", 0),
        })
    return web.json_response({"devices": out, "controllers": len(controllers), "db": store.stats()})


async def device_state_api(request):
    """GET /api/device/state?device=s20&limit=50 —— 最新状态 + 最近事件"""
    if not check_token(request):
        return unauthorized(request)
    q = request.rel_url.query
    d = q.get("device", "")
    limit = q.get("limit", HISTORY_MAX)
    st = store.get_state(d)
    if st is None:
        return web.json_response({"code": 404, "msg": f"no state for device '{d}'",
                                  "devices": list(devices)}, status=404)
    return web.json_response({
        "device": d,
        "online": d in devices,
        "abnormal": st["abnormal"],
        "ts": st["ts"],
        "lastSeen": st["lastSeen"],
        "status": st["status"],
        "history": list(reversed(store.get_events(d, limit))),   # 时间正序，便于阅读
        "db": store.stats(),
    })


async def events_api(request):
    """GET /api/events?device=s20&limit=50&before_id=123 —— 事件分页"""
    if not check_token(request):
        return unauthorized(request)
    q = request.rel_url.query
    d = q.get("device", "")
    if not d:
        return web.json_response({"code": 400, "msg": "missing device"}, status=400)
    limit = q.get("limit", HISTORY_MAX)
    before = q.get("before_id")
    try:
        before_i = int(before) if before else None
    except ValueError:
        before_i = None
    events = store.get_events(d, limit, before_i)
    return web.json_response({
        "device": d, "count": len(events), "events": events,
        "next_before_id": events[-1]["id"] if events else None,
    })


async def index(request):
    if not check_token(request):
        log.warning("index rejected from %s path=%s", client_ip(request), request.path)
        return unauthorized(request)
    idx = os.path.join(WEB_DIR, "index.html")
    if not os.path.exists(idx):
        return web.Response(text="web/index.html not found", status=404)
    resp = web.FileResponse(idx)
    resp.headers["Cache-Control"] = "no-store"
    resp.headers["X-Content-Type-Options"] = "nosniff"
    set_auth_cookie(resp, request)
    return resp


routes_app.add_routes([
    web.get("/ws", ws_handler),
    web.post("/api/cmd", api_cmd),
    web.get("/api/status", status),
    web.get("/api/device/state", device_state_api),
    web.get("/api/events", events_api),
    web.get("/", index),
    web.get("/index.html", index),
])


def build_ssl_context():
    cert = os.environ.get("RELAY_SSL_CERT", "")
    key = os.environ.get("RELAY_SSL_KEY", "")
    if not cert or not key:
        return None
    if not (os.path.exists(cert) and os.path.exists(key)):
        log.error("RELAY_SSL_CERT/KEY not found, fallback to plain http")
        return None
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.load_cert_chain(cert, key)
    try:
        ctx.minimum_version = ssl.TLSVersion.TLSv1_2
    except Exception:
        pass
    return ctx


def main():
    store.init()
    st = store.stats()
    # 重启恢复：从 DB 载入最新状态与异常标记
    for d in store.all_devices():
        full = store.get_state(d["name"])
        if full:
            device_state[d["name"]] = {"status": full["status"], "ts": full["ts"], "abnormal": full["abnormal"]}
    ssl_ctx = build_ssl_context()
    scheme = "wss/https" if ssl_ctx else "ws/http"
    log.info("relay listening %s:%d (%s) token=%s*** db=%s events=%d devices=%d",
             HOST, PORT, scheme, TOKEN[:4], st["dbPath"], st["events"], st["devices"])
    web.run_app(routes_app, host=HOST, port=PORT, ssl_context=ssl_ctx)


if __name__ == "__main__":
    main()
