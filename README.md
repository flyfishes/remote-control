# S20 Remote（远程控制）

用手机（已 root 的三星 S20 / SM-G9810，Android 13）作为被控端，通过 **网页控制台** 远程查看状态、收发短信、管理应用与文件、截屏、开关网络、熄屏/唤醒/重启。支持两种链路：

```
                     ┌──────────────────────────────┐
   浏览器 ── HTTPS ──►│  手机内置 HTTPS 服务 :8999    │   （局域网直连）
                     │  NanoHTTPD + 自签证书(TLS1.2+)│
                     └──────────────┬───────────────┘
                                    │ wss:// (可选)
                     ┌──────────────▼───────────────┐
   浏览器 ── HTTPS ──►│  中转服务器 relay :9000      │   （公网/跨网段）
                     │  aiohttp + SQLite 事件库      │
                     └──────────────────────────────┘
```

- **直连模式**：浏览器访问 `https://<手机IP或DDNS域名>:8999/?token=…`
- **中转模式**：手机主动连 `wss://<服务器>:<端口>/ws`，浏览器访问中转首页
- 全链路 **token 校验**：校验失败只返回「未授权」，不返回静态页面

---

## 目录结构

```
s20-remote/
├── README.md
├── source/                    # 源码
│   ├── app/                   # Android App（Gradle 工程，Kotlin）
│   ├── server/                # 中转服务器（Python / aiohttp）
│   │   ├── relay_server.py
│   │   └── relay/             # store.py（SQLite）、web/index.html、nginx/systemd 配置
│   └── magisk/                # Magisk 模块（priv-app 安装 + 开机脚本 + 热点 helper）
├── deploy/                    # 部署产物（可直接拷贝到目标机）
│   ├── app/
│   │   ├── S20Remote-1.3.0.apk        # 普通安装包（可无 root 使用基础功能）
│   │   └── s20remote-magisk.zip       # Magisk 模块（priv-app + 开机自启 + 热点 helper）
│   └── server/
│       ├── relay-deploy/              # 可直接上传服务器的目录（含 install.sh、certs/）
│       └── s20relay-deploy.zip        # 上述目录的压缩包
├── docs/
│   ├── app-home.png           # App 首页截图
│   └── web-console.png        # 网页控制台截图
└── tools/                     # 本地开发工具（JDK17 / Gradle / adb，不随部署发布）
```

---

## 功能与依赖

| 功能 | 说明 | root | 其他依赖 |
|---|---|---|---|
| 状态/电量/存储/网络/DDNS/上报 | `status.get` `storage.get` 等 | 否 | — |
| 短信 收/发/删/检索 | 按地址、关键字、时间段过滤 | 否 | SEND_SMS / READ_SMS |
| 文件管理 | 列表/读/写/删/改/复制/详情 | 是（全盘） | 无 root 时仅 `/sdcard` + 应用私有目录，需「所有文件访问」 |
| 应用管理 | 列表/安装/卸载/启动/停止/进程 | 是 | 列表可无 root（PackageManager） |
| 截屏 | 缩放 + JPEG 压缩后回传 | 是 | — |
| 熄屏 | `keyevent 223`（回退 26） | 是 | 无 root 可用「设备管理员 lockNow」 |
| 唤醒 / 自动解锁 | 点亮 → 上滑 → 输入 PIN | 是 | PIN 存 DE 区，接口不回显 |
| 重启 | `su -c reboot` | 是 | — |
| WiFi / 蓝牙 开关 | 读状态走公共 API；写操作 root 优先 | 是 | 无 root：WiFi≤9、蓝牙≤12 可用系统 API |
| 移动数据 / 定位 开关 | `svc data` / `settings put` | 是 | 无公共 API |
| 热点管理 | SSID/密码/频段、客户端列表 | 是 | 仅三星机型（SemWifiManager helper） |
| 开关自动恢复 | 5 项开关被关自动打开，独立线程 | 是 | 不依赖中转连接 |
| 流量监控 | 短信查询 / 本地命令 / 阈值告警 | 部分 | 本地命令模式需要 root |
| HTTPS 证书 | 自签（EC P-256）生成、导入、指纹 | 否 | — |
| 中转 / SQLite 事件库 | 心跳不落库，其余事件全量入库 | 否 | — |

### 能力模型（v1.2.0 起）

所有指令执行前先判定能力，失败返回结构化错误，不会崩溃、不会误报：

```json
{"ok": false, "errCode": "NEED_ROOT", "feature": "screen.capture",
 "hint": "该功能需要 root（Magisk 授权）才能执行"}
```

`errCode` 取值：`NEED_ROOT`（需 root）、`NEED_PERM`（需运行时权限/设备管理员）、`NOT_SUPPORTED`（机型/安装方式不支持）、`DEVICE_ERROR`（执行异常，已被捕获）。

`cap.get` 返回能力矩阵（`recheck=1` 强制重测）：

```json
{
  "root": true, "privileged": true, "samsung": true,
  "manufacturer": "samsung", "model": "SM-G9810",
  "androidVersion": "13", "sdkInt": 33,
  "deviceAdmin": false, "manageStorage": true, "simulateNoRoot": false,
  "perms": {"sms": {"granted": true}, "location": {"granted": true}, "...": {}},
  "features": {"hotspot": "ok", "screenshot": "ok", "reboot": "ok", "unlock": "ok",
               "screenOff": "ok", "switchSet": "ok", "files": "ok", "apps": "ok", "dataSet": "ok"}
}
```

`features` 取值：`ok` 完整可用 / `degraded` 降级可用 / `no` 不可用。网页与 App 首页据此把按钮置灰并显示原因。

调试：`config.set {"simulateNoRoot": true}` 可强制按「无 root」执行，用于验证降级路径（默认 false）。

---

## 安装（手机端）

### 方式 A：普通 APK（最小依赖，基础功能可用）

```bash
adb install -r deploy/app/S20Remote-1.3.0.apk
# 或直接把 APK 拷到手机点击安装
```

- 无 root 时：状态、短信、证书、DDNS、中转、文件（限 /sdcard）、应用列表可用；
- 首次打开请在系统设置授予短信/定位/通知权限，并允许「所有文件访问」。

### 方式 B：Magisk 模块（推荐，完整能力）

1. 将 `deploy/app/s20remote-magisk.zip` 传到手机，在 Magisk App 中「从本地安装」；
2. 重启手机（模块安装后需重启生效）；
3. 模块内容：
   - `system/priv-app/S20Remote/S20Remote.apk` —— 特权安装，热点 helper 可用；
   - `service.sh` —— 开机等待解锁后启动服务（Direct Boot 兼容），可从 DE 区配置读取 PIN；
   - `system/etc/s20apcfg.dex` —— 热点配置 helper（以 uid 1000 运行）。

> 注意：用 `adb install` 覆盖安装后，系统优先运行 `/data/app` 的普通安装副本（不再享有 priv-app 特权），需要完整能力请卸载普通副本、只保留模块版本。

### 首次配置

打开 App「远程控制」首页：

1. **基础与网络**：设备名（中转标识）、访问 Token、HTTPS 端口（默认 8999）；
2. **HTTPS 证书**：默认自签（EC P-256，SAN 含 DDNS 域名/设备名/内网 IP/localhost），可重新生成或导入自有证书；
3. **中转服务器**（可选）：填 `wss://<域名或IP>:<端口>/ws` 并启用；
4. **唤醒与解锁**：锁屏 PIN（保存时自动同步到 Magisk 开机脚本）；
5. **上报与告警**：心跳间隔、电量/温度/存储阈值；
6. **开关自动恢复**：WiFi/热点/数据/蓝牙/定位 各自独立开关 + 检查间隔（默认 300 秒）。

App 首页还提供：启动/停止服务（合一按钮）、重启设备（5 秒倒计时）、更新 DDNS、检测 Root、能力与权限面板、前台空闲 30 秒自动熄屏开关、实时日志。

---

## 部署（中转服务器）

服务器要求：Linux + Python 3.9+；公网可选域名与证书。

### 方式 1：一键脚本（systemd）

```bash
scp -r deploy/server/relay-deploy user@server:/tmp/s20relay
ssh user@server
cd /tmp/s20relay && sudo ./install.sh          # 创建 s20relay 用户、venv、systemd 服务
sudo vim /etc/systemd/system/s20relay.service.d/override.conf   # 设置 RELAY_TOKEN
sudo systemctl restart s20relay
```

`install.sh` 会：创建 `s20relay` 系统用户 → 复制代码到 `/opt/s20relay` → 建 venv 装 `requirements.txt` → 安装 `s20relay.service`（含 `RELAY_HOST/RELAY_PORT/RELAY_TOKEN/RELAY_DB` 的 drop-in override）→ 启动服务。
SQLite 数据库默认 `/var/lib/s20relay/s20relay.db`（WAL 模式）。

### 方式 2：直接运行（调试）

```bash
cd source/server && pip install -r relay/requirements.txt
RELAY_TOKEN=your-token RELAY_PORT=9000 python relay_server.py
# TLS（不借助 nginx）：
RELAY_SSL_CERT=certs/relay.crt RELAY_SSL_KEY=certs/relay.key RELAY_TOKEN=your-token python relay_server.py
```

- 未设置 `RELAY_SSL_CERT/KEY` 时监听明文 `ws/http`（适合 nginx 反代终止 TLS）；
- `deploy/server/relay-deploy/start-relay-tls.bat` 为 Windows 本地带证书启动脚本；
- `deploy/server/relay-deploy/start-relay.sh` 为 Linux 后台启动脚本（支持 sqlite 3.30 运行时 + 后台 + 错误日志，默认 `LOG_LEVEL=WARNING`，只把警告/错误写入 `error.log`）。

### 日志级别

`LOG_LEVEL` 控制中转服务输出阈值：`DEBUG / INFO / WARNING / ERROR / CRITICAL`（不区分大小写，非法值回退 `INFO`，默认 `INFO`）。只输出该级别及以上的日志。

```bash
LOG_LEVEL=WARNING RELAY_TOKEN=xxx python relay_server.py   # 只输出警告与错误
LOG_LEVEL=INFO bash start-relay.sh                          # 后台启动时改为 INFO 级别
```

`start-relay.sh` 默认 `LOG_LEVEL=WARNING`，配合 `error.log` 使用——正常的 INFO 启动日志不会写入；systemd 部署可在 `override.conf` 里加 `Environment=LOG_LEVEL=INFO`（默认），用 `journalctl -u s20relay -f` 查看。

### 数据库后端：SQLite 3 / MySQL 8

存储层在 `relay/store.py`，按环境变量路由到 `store_sqlite.py` 或 `store_mysql.py`；9 个公开函数签名一致，业务代码与前端无需改动。

| 模式 | 启用方式 | 依赖 |
|---|---|---|
| SQLite 3（默认） | `RELAY_DB_BACKEND=sqlite`（可省略），`RELAY_DB=/path/s20relay.db` | 无 |
| MySQL 8 | `RELAY_DB_BACKEND=mysql` + `RELAY_DB_HOST/PORT/USER/PASS/NAME` | `pip install PyMySQL` |

未设置 `RELAY_DB_BACKEND` 时，只要出现任一 `RELAY_DB_HOST/PORT/USER/PASS/NAME` 就自动切到 MySQL，旧部署不受影响。

```bash
pip install PyMySQL
RELAY_DB_BACKEND=mysql RELAY_DB_HOST=127.0.0.1 RELAY_DB_PORT=3306 \
RELAY_DB_USER=s20relay RELAY_DB_PASS=xxx RELAY_DB_NAME=s20relay \
RELAY_TOKEN=your-token python relay_server.py
```

- 首次启动自动建库建表建索引（账号需 `CREATE`/`INDEX` 权限），表为 InnoDB + `utf8mb4_bin`，时间戳用 `DOUBLE`，JSON 用 `TEXT/MEDIUMTEXT`；
- 主 upsert 用 `ON DUPLICATE KEY UPDATE`，连接带 `ping(reconnect=True)` 与断线重试，`charset=utf8mb4` 保证中文 payload 正确；
- `GET /api/status` 的 `db.dbPath` 在 MySQL 模式返回 `mysql://host:port/db`，`dbSize` 取 `information_schema` 的表空间估算；
- 一键脚本传 `RELAY_DB_BACKEND=mysql RELAY_DB_HOST=...` 等变量即可自动装 PyMySQL 并写入 systemd override；
- 两个后端互不迁移历史数据（切换 = 新库从头记录，设备状态由下一次上报重建）。

### 方式 3：nginx 反向代理（推荐公网）

`source/server/relay/nginx-s20.conf` 已给出可用配置要点：

```
location /ws {
    proxy_pass http://127.0.0.1:9000;
    proxy_http_version 1.1;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection "upgrade";
    proxy_read_timeout 3600s;
}
location / { proxy_pass http://127.0.0.1:9000; }
```

配合 `RELAY_HOST=127.0.0.1` 只监听本机，由 nginx 终止 443。

手机端中转地址填 `wss://<域名>/ws`；浏览器访问 `https://<域名>/?token=…`。

---

## API

### 认证

三种方式任一（优先级从高到低）：

1. 请求头 `X-Token: <token>`
2. 查询参数 `?token=<token>`
3. Cookie `s20tk`（直连）/ `relaytk`（中转）

校验失败：HTTP 401，直连返回「未授权」页面，中转返回 JSON `{"code":401,...}`。仅 `/cert`（取证书 PEM）与 `/ping` 免鉴权。

### 直连 HTTP 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/` `/?token=` | 网页控制台（失败 401 未授权页） |
| GET | `/ping` | 连通性探测：`{"code":0,"tls":true,"time":…}` |
| POST | `/cmd` | 执行指令，见下 |
| GET | `/file?path=` | 下载文件（支持 Range） |
| POST | `/file?path=` | 上传文件（raw body 或 multipart） |
| GET | `/log?n=200` | 最近日志（文本） |
| GET | `/cert` | 下载当前证书 PEM |

`POST /cmd` 请求/响应：

```json
// 请求
{"id": "任意字符串", "cmd": "status.get", "params": {}}
// 响应
{"id": "…", "code": 0, "msg": "ok", "data": { … }}
// 失败：code=500，data 内可能带 errCode/hint
```

### 指令一览

| 指令 | 主要参数 | 说明 |
|---|---|---|
| `cmd.list` | — | 列出全部指令 |
| `cap.get` | `recheck` | 能力/权限矩阵 |
| `status.get` | — | 电量/温度/网络/IP/系统信息 |
| `storage.get` | — | 内部存储总量/剩余/告警 |
| `device.info` | — | 机型、Android 版本、uptime |
| `device.reboot` | — | 重启（需 root） |
| `device.screenOff` | — | 熄屏（root 或设备管理员） |
| `device.wakeUp` | — | 点亮并在锁屏时按 PIN 解锁（需 root） |
| `screen.capture` | `max`(默认720) `quality`(默认70) | 截屏，返回 base64 + 尺寸/耗时 |
| `media.photo` | `facing`(back/front) `max`(默认1080) `quality`(默认80) `save` | **拍照**（v1.3.0）：Camera2 后台直接拍摄（无需亮屏），返回 base64 JPEG；缺权限时 root 自动 `pm grant`+`appops allow`，仍不可用则降级拉起系统相机 App（返回 `mode=cameraApp`）；另存 `/sdcard/DCIM/S20Remote` |
| `media.record` | `seconds`(1~86400，默认15) | **录音**（v1.3.0，异步）：立即返回，后台录制 AAC/m4a（最长 24 小时）；另存 `/sdcard/Music/S20Remote`。屏幕锁定时自动解锁并将 App 置于前台 |
| `media.recordStop` | — | 手动停止录音，返回最近一次录音结果（含文件路径/大小） |
| `media.recordStatus` | — | 录音状态：录制中返回已录时长/字节数；结束后返回最近一次结果。文件可经「文件」模块下载（中转已支持 /file 流式下载） |
| `net.ips` / `net.publicIp` | `refresh` | 内网/公网 IP |
| `switch.get` | — | WiFi/数据/蓝牙/定位/热点状态 |
| `wifi.set` / `bluetooth.set` / `location.set` | `on` | 开关（root 优先，部分版本可无 root） |
| `data.get` / `data.set` | `on` | 移动数据（读公共 API，写需 root） |
| `hotspot.get` | — | 热点状态、SSID、密码、频段、客户端 |
| `hotspot.set` | `on` `ssid` `pass` `band` | 开关热点（需 root + 三星 helper） |
| `hotspot.config` | `ssid` `pass` `band` | 仅改配置 |
| `hotspot.probe` | — | 热点类/方法诊断 |
| `sms.list` | `limit` `offset` `address` `keyword` `startTs` `endTs` `order` | 短信检索 |
| `sms.send` | `address` `text` | 发送短信 |
| `sms.delete` | `id` | 删除短信 |
| `app.list` | `system` `keyword` | 应用列表 |
| `app.install` / `app.uninstall` / `app.start` / `app.stop` | `path` / `pkg`(+`activity`) | 应用管理（多为 root）。`app.start` 返回 `ok` `method`（`am start`/`monkey`/`startActivity`）`component` `output` `errCode` `hint` |
| `app.running` | — | 进程列表（需 root） |
| `file.list` | `path` `hidden` | 目录列表 |
| `file.read` / `file.write` | `path` `offset` `length` / `dataB64` `append` | 读/写（2MB 内嵌） |
| `file.mkdir` / `file.delete` / `file.move` / `file.copy` / `file.info` | `path` / `src` `dst` | 文件操作 |
| `config.get` / `config.set` | 键值 | 读写配置（含校验） |
| `cert.info` / `cert.regen` / `cert.import` / `cert.pem` | `pem` | 证书管理 |
| `guard.get` / `guard.check` | — | 开关自动恢复状态/手动触发 |
| `traffic.check` | — | 立即查询流量 |
| `ddns.get` / `ddns.update` | — | DDNS 状态/立即更新 |
| `log.get` / `log.clear` | `n` | 日志 |
| `service.restart` | — | 重启服务 |

配置项（`config.set` 可用键）：`token` `httpPort` `deviceName` `relayEnabled` `relayUrl` `relayInsecureTls` `tlsEnabled` `tlsMode` `ddnsProvider/User/Pass/Host/IpType/IntervalMin` `heartbeatSec` `batteryWarnPct` `tempWarnC` `cpuWarnC` `storageWarnGb` `unlockEnabled` `unlockPin` `autoRestoreWifi/Hotspot/Data/Bluetooth/Location` `guardSec` `traffic*` `logEnabled` `logFile` `appIdleScreenOff` `simulateNoRoot`。

### 中转 HTTP / WebSocket

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/` `?token=` | 控制台页面（401 校验页面） |
| GET | `/api/status` | 设备列表与在线状态 |
| GET | `/api/device/state?device=` | 最新状态 + 事件历史（`history` 默认 50） |
| GET | `/api/events?device=&before_id=` | 分页事件 |
| WS | `/ws?token=&role=device\|controller&device=` | 设备通道 / 控制器通道 |

WS 消息：设备上行 `{"type":"hello\|heartbeat\|status\|response",...}`，控制器下行 `{"id","device","cmd","params"}`，回包 `{"id","code","msg","data"}`。

SQLite（`relay/store.py`）：`device_state(device, last_seen, last_status…)`、`events(id, device, kind, direction, ts, payload)`。**心跳不落库**（仅更新时间），异常心跳、recover、上线/下线、指令与回执全部入库；截屏类回执入库前会剔除 base64 图片只留摘要。

---

## 构建

```bash
cd source/app
JAVA_HOME=<jdk17> ../tools/gradle/gradle-8.7/bin/gradle assembleRelease --offline
# 产物：app/build/outputs/apk/release/app-release.apk（versionCode 19 / 1.3.0）
```

- JDK 17 + Gradle 8.7 + Android SDK 33；Kotlin + AppCompat + BouncyCastle（自签证书）
- 网页控制台源码在 `source/server/relay/web/index.html`，构建前需同步三份副本：
  `source/server/relay/web/index.html`、`source/app/app/src/main/assets/index.html`、`deploy/server/relay-deploy/relay/web/index.html`

---

## 兼容性

- minSdk 28（Android 9），targetSdk 33，已在 Android 13 / SM-G9810 上完整验证；
- 已处理：Android 14+ 前台服务类型（`dataSync` + 权限声明）、Android 15 `BOOT_COMPLETED` 启动限制（失败转 AlarmManager 重试）、Android 12+ 蓝牙权限、Android 13+ 通知/附近 WiFi 权限、Android 10+ 分区存储；
- 解锁滑动坐标按屏幕尺寸实时计算，锁屏判定用 `KeyguardManager`（全机型）；
- 非 root 时自动降级并在 UI 标明原因，不会出现「显示正常但数据是假的」；
- 热点配置依赖三星 `SemWifiManager`（helper dex），非三星机型返回 `NOT_SUPPORTED`。

## 常见问题

| 现象 | 处理 |
|---|---|
| 浏览器提示证书不安全 | 自签证书属预期，导入 `https://<手机>:8999/cert` 或在浏览器选择继续访问 |
| 中转连不上 | 检查手机 `relayUrl` 是否为 `wss://…/ws`；服务器是否设置 TLS 或 nginx 反代；token 是否一致 |
| 页面 401 | token 不匹配，用 `?token=<配置的token>` 打开一次即可写入 Cookie |
| 端口被占用 | 服务会自动重试 3 次；仍失败时通知栏提示，改端口后重启服务 |
| 熄屏后点击无响应 | Doze 下 WiFi 可能被压制，先点「唤醒」 |
| 无 root 提示 NEED_ROOT | 属设计行为；短信/状态/文件(/sdcard) 等不依赖 root 的功能仍可用 |
| 重启后屏幕不熄 | 可能开启了开发者选项「不锁定屏幕」或充电保持唤醒；App 内可开启「前台空闲 30 秒自动熄屏」兜底（v1.2.1 起开机自启即生效，不依赖界面是否显示） |
| 网页点「启」没反应 | v1.2.1 已修：改由 root `am start -n <pkg>/<activity>` 启动（旧版走 `startActivity` 被 Android 10+ 后台启动限制静默拦截）。仍失败时日志会给出 `[DEVICE_ERROR]` 与具体原因 |
| 拍照报 CAMERA_DISABLED / disabled by policy | 相机被设备上的管控软件通过 Knox/MDM 策略全局禁用（系统相机 App 也打不开）；需处理管控端策略，录音功能不受影响 |
| 录音文件没有声音 | 已修复：Samsung 把麦克风/相机 appops 钉死为「仅在使用中允许」，后台采集会被系统静音。现在拍照/录音前 App 会自动解锁屏幕（锁定时点亮+上滑+输 PIN，约 5-10 秒）并把自身置于前台，采集结束自动完成；解锁失败（未配置 PIN/PIN 不对）会返回明确错误。注意：解锁+录音总耗时可能超过中转 15 秒命令超时，锁定状态下建议选 8 秒以内录音 |
| 熄屏后短信/文件（CE 区）异常 | 熄屏会重新上锁，FBE 的 CE 存储随之不可用；需要长期保持解锁请关闭自动熄屏或延长系统屏幕超时 |

## 更新记录

### v1.3.0（versionCode 19）

- 新增**媒体模块**：web 控制台新增「媒体」标签（屏幕与配置之间），提供远程拍照与录音。
  - `media.photo`：Camera2 后台直接拍一帧 JPEG（无需人工操作、无需亮屏），支持前/后摄与清晰度/质量参数；缺 `CAMERA` 权限时 root 下自动 `pm grant` + `appops set CAMERA allow`（Android 11+ 后台访问相机的放行手段）；仍不可用降级为拉起系统相机 App 人工拍摄。本地另存 `/sdcard/DCIM/S20Remote`。
  - `media.record`：MediaRecorder AAC/m4a 录音，**异步模式**——命令立即返回、后台独立线程录制，`seconds` 默认 15、范围 1~86400（最长 24 小时）；`media.recordStop` 手动停止、`media.recordStatus` 轮询进度；完成后文件保存在 `/sdcard/Music/S20Remote`，网页端可点「下载」经中转 /file 流式取回。缺 `RECORD_AUDIO` 权限处理同上。
  - App 首页启动时自动申请 `CAMERA`/`RECORD_AUDIO` 运行时权限；能力面板（网页+App）新增相机/麦克风权限与 photo/record 功能状态。
  - 已知限制：录音 ≤10 秒（中转 15s 命令超时约束）；若设备装有管控软件（如 Knox MDM 策略禁止相机），Camera2 与系统相机均会返回 `CAMERA_DISABLED ... disabled by policy`，此时录音不受影响。
    > v1.3.0 后续调整：录音改为异步模式（命令立即返回 + 后台录制 + 状态轮询），时长上限提升到 24 小时，不再受中转 15s 超时约束。
- 修复录音无声：Samsung 设备 RECORD_AUDIO/CAMERA appops 被钉死为 foreground（后台采集被系统静音，dumpsys 显示 `rec ... silenced`）。拍照/录音命令现在会先自动解锁屏幕（锁定时点亮→上滑→输 PIN）再把 App 置于前台（`MainActivity`），采集完成后 App 留在前台，由空闲熄屏机制自然恢复；实测锁屏态远程录音 `not silenced`。

### 中转存储层（2026-11-20）

- 新增 MySQL 8 后端：存储层拆为 `store_sqlite.py` / `store_mysql.py`，`store.py` 按 `RELAY_DB_BACKEND` 路由；上层业务与前端零改动。
- SQLite 模式行为完全不变；MySQL 模式需可选依赖 PyMySQL，首次启动自动建表建索引，支持断线重连。

### 日志级别 LOG_LEVEL（2026-11-20）

- 中转服务新增 `LOG_LEVEL`（DEBUG/INFO/WARNING/ERROR/CRITICAL，默认 INFO，非法值回退 INFO），按阈值过滤输出；install.sh / systemd / bat / start-relay.sh 全部暴露该变量。
- `start-relay.sh` 默认 `LOG_LEVEL=WARNING`，`error.log` 只记警告与错误，无需再靠 shell 文本过滤。

### v1.2.1（versionCode 18）

- 修复：网页「应用」页点击「启」无效。root 下统一走 `am start -n pkg/activity`（先用 PackageManager 解析 LAUNCHER，失败再降级 monkey），并回传 `ok/method/component/output`；包不存在、无启动入口、权限被拒分别给出明确 `hint`。前端日志按真实结果着色，不再无条件打印「✓」。
- 修复：开机自启后屏幕常亮。原「空闲熄屏」判断挂在首页 2 秒刷新里（`onResume` 启动），而开机脚本只拉起服务、不显示界面，判断永不执行。现抽为进程级定时器 `core/IdleScreenOff`：`Application.onCreate` 即按开关 arm 30 秒；首页触摸/按键复位计时；退到后台或重启倒计时弹窗期间不触发。
- 默认参数：开关自动恢复检查间隔默认 300 秒（`guardSec`）。

### v1.2.0（versionCode 17）

- 能力模型与降级：root / 非 root 双路径，接口统一返回 `errCode`（`NEED_ROOT` / `NEED_PERM` / `NOT_SUPPORTED` / `DEVICE_ERROR`）+ `hint`；
- 首页改版：状态卡、功能卡（启停合一 / 重启倒计时 / DDNS / Root 检测）、能力与权限卡、9 个配置分组、日志卡；
- 网页新增「屏幕」模块（截图/熄屏/唤醒）、能力面板；
- 工程拆分源码 / 部署 / 文档三部分。


## 安全说明

- Token 是唯一凭据：泄露等于把手机控制权交出，请使用长随机串；
- 自签证书默认忽略校验（`relayInsecureTls`），中间人风险自担，公网建议用可信证书；
- 锁屏 PIN 明文存于设备保护（DE）区，供开机解锁使用；持有 token 者可点亮并解锁屏幕；
- 建议仅在受控网络使用，并定期轮换 token 与证书。
