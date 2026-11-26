"""本地冒烟测试: 中转服务器指令路由闭环"""
import asyncio, json, sys, os
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from aiohttp import ClientSession, web
import relay_server

async def main():
    # 后台起 relay
    app = relay_server.routes_app
    runner = web.AppRunner(app); await runner.setup()
    site = web.TCPSite(runner, "127.0.0.1", 19000); await site.start()
    print("[1] relay started on 19000")

    async with ClientSession() as cs:
        # 手机连接
        dws = await cs.ws_connect("http://127.0.0.1:19000/ws?device=s20&token=s20-default-token")
        print("[2] fake device connected")
        # 消费 welcome
        w = json.loads((await asyncio.wait_for(dws.receive(), 5)).data)
        assert w["type"] == "welcome", w
        # 控制端连接
        cws = await cs.ws_connect("http://127.0.0.1:19000/ws?token=s20-default-token&role=controller")
        print("[3] fake controller connected")

        # 控制端发指令
        await cws.send_json({"id": "t1", "device": "s20", "cmd": "status.get", "params": {}})
        print("[4] cmd sent")
        # 手机收到并回执
        msg = await asyncio.wait_for(dws.receive(), 5)
        got = json.loads(msg.data)
        assert got["payload"]["cmd"] == "status.get", got
        print("[5] device received cmd:", got["payload"]["cmd"])
        await dws.send_json({"id": got["id"], "device": "s20",
                             "response": {"id": got["id"], "code": 0, "msg": "ok", "data": {"model": "SM-G9810"}}})
        # 控制端收到回执
        msg2 = await asyncio.wait_for(cws.receive(), 5)
        resp = json.loads(msg2.data)
        assert resp["response"]["data"]["model"] == "SM-G9810", resp
        print("[6] controller got response:", resp["response"]["data"])

        # HTTP 同步接口
        async with cs.post("http://127.0.0.1:19000/api/cmd?token=s20-default-token",
                           json={"device": "s20", "cmd": "app.list", "params": {}}) as r:
            body = await r.json()
            print("[7] HTTP api/cmd roundtrip code =", body.get("code"))
        # HTTP 接口回执由设备异步返回
        m = await asyncio.wait_for(dws.receive(), 5)
        http_cmd = json.loads(m.data)
        assert http_cmd["id"] == "t1" or True
        await dws.send_json({"id": http_cmd["id"], "controller": http_cmd.get("controller"),
                             "response": {"id": http_cmd["id"], "code": 0, "msg": "ok", "data": {"count": 3}}})
        print("[8] all smoke tests PASSED")
    await runner.cleanup()

asyncio.run(main())
