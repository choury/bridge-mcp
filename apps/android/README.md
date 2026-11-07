# bridge-mcp-android

bridge-mcp 的 Android provider：手机作为 client 连入 [bridge-mcp](../bridge-mcp) 的 WebSocket 端点，把**定位、共享存储文件、通知、设备信息、截屏**暴露为 MCP 工具，agent 即可跨端调用。

架构与 bridge-mcp 的 Go provider（`cmd/bridge-provider`）一致：前台服务持有 WebSocket 连接（OkHttp，120s ping 保活）；首次连接失败即报错停止、由用户修正后重试，上线后断线才按 1s→30s 指数退避重连（握手成功即重置，网络恢复即时打断退避）；开机自启（`BOOT_COMPLETED`）；调用按 bridge 侧 FIFO 串行处理。

## 能力面

| 工具 | 说明 |
|---|---|
| `phone.location.get {max_age_ms?}` | 单次定位；`max_age_ms` 内的缓存位置直接复用，否则等下一个 fix（30s 超时）。agent 调用时 app 在后台，需授予「始终允许」（`ACCESS_BACKGROUND_LOCATION`）——FGS location 类型与开机自启冲突（Android 14 禁止后台启动），故不走该路 |
| `phone.file.list {path}` | 列举共享存储目录（name/type/size/mtime） |
| `phone.file.push {path, content}` / `phone.file.pull {path, limit?}` | 由 bridge 依据 hidden 的 chunk 原语合成，纯文本读写 |
| `bridge.push / bridge.pull` | 二进制文件搬运，内容不过模型、无大小上限（4MiB 分块走 hidden 原语） |
| `phone.notification.post {id?, title, body, silent?}` / `phone.notification.cancel {id}` | agent → 手机方向发/撤本地通知 |
| `phone.notifications.list` | 手机 → agent 方向：当前通知快照（key/package/title/text/post_time/ongoing/actions），对齐 OpenClaw |
| `phone.notifications.actions {key, action, replyText?}` | 对指定通知 `open` / `dismiss` / `reply`（reply 经 RemoteInput 填充内联回复） |
| `phone.device.status / info / permissions / health / apps` | 电量/热状态/存储/网络、机型系统版本、权限授予状态、内存压力/电池健康/doze、应用列表，返回结构对齐 OpenClaw 同名命令 |
| `phone.screen.capture {format?}` | MediaProjection 抓单帧存盘，返回 `{path, size, width, height}`，用 `bridge.pull` 拉回查看 |

注意：bridge 协议没有 client→server 推送通道（服务端收到非 `result` 消息即断连），因此 `notifications.list` 是拉取式快照，无法实时转发。

## 权限与授权入口

App 内每项能力都有对应的授权按钮；未授权时相应调用直接报错并提示。

- **定位 / 后台定位 / 通知**：运行时权限（后台定位在 Android 11+ 多数不弹框，被拒后请到应用详情把位置权限设为「始终允许」）
- **所有文件**（`MANAGE_EXTERNAL_STORAGE`）：跳系统设置授予；未授予时文件能力直接报错
- **通知使用权**（`NotificationListenerService`）：跳系统设置授予；`notifications.list` 在已授权但未绑定时会主动 `requestRebind`
- **投屏授权**：每次会话需点一次系统弹窗（MediaProjection 机制限制，无法后台静默触发），授权在服务存活期内有效

## 构建与运行

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

要求 compileSdk 37（本仓库 SDK 37 以次版本 `android-37.0` 提供，构建脚本已用 `compileSdkMinor = 0` 声明）。

release 打包：设置 `APP_KEYSTORE`（keystore 路径）即自动签名出 `app-release.apk`，配套 `APP_KEYSTORE_PASS` / `APP_KEY_ALIAS` / `APP_KEY_PASS`（key 密码缺省沿用 keystore 密码）；未设置则产出未签名包。

App 内填写：服务器（如 `http://10.0.2.2:18765/ws`，模拟器经 `10.0.2.2` 访问宿主机）、Client ID、Token，点「连接」。宿主机侧：

```bash
bin/bridge-mcp --addr :18765 --mcp-http --token <token>
curl http://127.0.0.1:18765/healthz   # → ok
```

## 安全提示

- 界面随系统语言双语（`values/` 英文 + `values-zh/` 中文）；**MCP 报错与 App 内日志固定英文**，保证 agent 侧可稳定解析
- `android:usesCleartextTraffic="true"`：局域网 http 明文通信所需；跨不可信网络请套 TLS（https）或隧道
- `QUERY_ALL_PACKAGES`：`device.apps` 的 `includeNonLaunchable` 全量列表需要；仅侧载分发，不上 Google Play
- `MANAGE_EXTERNAL_STORAGE`：文件能力面向整个共享存储，授予即代表信任接入的 agent
- `--token` 仅做准入认证，语义与 bridge 一致

## 端到端验证记录（Medium_Phone_API_34 模拟器 + 本机 bridge --mcp-http）

- 连入注册 14 项能力，`bridge.clients` 可见；`am force-stop` 后下线
- `location.get`：`geo fix 116.397 39.917` 注入后返回精确坐标（±5m）
- `notification.post` → 通知栏可见；`notifications.list` 读到该通知；`actions dismiss` 后消失
- `file.push`（39B 中文文本）落盘 `/sdcard/Download`，`file.pull` 读回一致；`bridge.push/pull` 3MB 二进制 1.2s，md5 一致；对已存在文件 push 正确报错（独占创建契约）
- `device.*` 五项返回合理数据（电池/热状态/存储/网络/内存/应用列表）
- `screen.capture` 出 1080×2400 PNG，`bridge.pull` 拉回图像正确（内容即 app 界面）
- 重启模拟器：`BOOT_COMPLETED` 自动拉起前台服务并重连上线
