# bridge-mcp

能力桥接 MCP server：provider client（设备/机器）通过 WebSocket 连入并声明自己的能力，
server 把这些能力动态暴露为 MCP 工具，agent即可跨机器调用。

```
┌────────┐  MCP (stdio 或    ┌─────────────┐  WebSocket（JSON + 二进制帧）  ┌───────────────┐
│ Agent  │  HTTP /mcp)       │ bridge      │ <----------------------------> │ provider      │
│        │ <---------------> │ (cmd/bridge)│      ws://host:8765/ws         │ (cmd/provider)│
└────────┘                   └─────────────┘                                └───────────────┘
                             client 上线 → 注册工具                          声明能力并实际执行
                             client 下线 → 注销工具                          参考实现：exec + 读写分块原语
```

设计分层：**能力协议只负责"声明与调用"**，组合语义（工具合成、分块、续传、覆盖保护、上下文限制）全部上移到 bridge。provider 实现者需要理解的协议面缩到最小。

## 构建

```bash
go build -o bin/ ./cmd/...
```

## 运行

bridge 有两种模式：

- **stdio 模式**（默认）：进程生命周期跟随 agent 会话。provider 断线自动重连（退避 1s→30s），会话拉起后最多一个退避间隔内重新挂上。
- **常驻模式**（`--mcp-http`）：以 Streamable HTTP 在同一端口的 `/mcp` 提供 MCP 服务，进程交给 systemd 托管，provider 随时可连。MCP 面暴露端口，应配 `--token`（Bearer 认证）。

**bridge**（stdio 模式，agent 所在机器）：

```bash
bin/bridge --addr :8765            # 或用环境变量 BRIDGE_ADDR 等
```

| 参数 | 环境变量 | 默认 | 说明 |
|---|---|---|---|
| `--addr` | `BRIDGE_ADDR` | `:8765` | provider 连接的 WS 监听地址（端点 `/ws`；常驻模式同端口 `/mcp`） |
| `--token` | `BRIDGE_TOKEN` | 空 | 非空时 client 必须在 hello 中携带相同 token |
| `--call-timeout` | `BRIDGE_CALL_TIMEOUT` | `120s` | 单次调用整体超时（含排队） |
| `--max-body` | `BRIDGE_MAX_BODY` | `16777216` | 单条 WS 消息（文本或二进制帧）字节上限，双方向 |
| `--tool-sep` | `BRIDGE_TOOL_SEP` | `.` | 工具名中 client id 与能力名的分隔符 |
| `--mcp-http` | — | `false` | 常驻模式：`/mcp` 提供 MCP，不使用 stdio |

**provider**（被控机器；参考实现 = exec + 两个分块原语）：

```bash
bin/provider --server ws://bridge-host:8765/ws --id devbox \
  --note "办公室的 Debian 13 测试机" --token secret
```

| 参数 | 默认 | 说明 |
|---|---|---|
| `--server` | `ws://127.0.0.1:8765/ws` | bridge 地址 |
| `--id` | （必填） | client id，`^[a-zA-Z0-9_-]{1,32}$` |
| `--note` | 空 | 自我介绍（≤500 字符），agent 通过 `bridge.clients` 可见 |
| `--token` | `$BRIDGE_TOKEN` | 与 bridge 一致的 token |

### 参考能力（provider 只实现原语，模型可见的文件工具由 bridge 合成）

- `exec {command, workdir?, timeout_ms?, tail?}` → `{exit_code, timed_out, stdout, stderr, truncated}`：经 `bash -lc`（login shell，完整用户 PATH；无 bash 退回 `/bin/sh`）执行；`workdir` 不存在报错；超时整进程组击杀（不留持有管道的孤儿）；stdout/stderr 各截断 32KB（保头，`tail: true` 保尾；超限进程继续运行不终止）
- `file.replace {path, old, new, all?}`（可见，直接注册为模型工具）：**精确文本替换**。`old` 恰好出现一处时替换为 `new`；未命中或多处直接报错且不修改文件（`all: true` 才批量）；非 UTF-8 文件拒绝。原子写回（同目录临时文件 + rename，保留权限）——替换语义放在数据所在地，只传片段
- `file.write_chunk {path, offset}` + 二进制载荷（hidden）——**唯一写原语**。追加式契约：offset 0 独占创建（已存在即报错）；offset>0 要求目标文件当前大小恰好等于 offset，否则报错
- `file.read_chunk {path, offset, length≤8MiB}`（hidden）——**唯一读原语**。应答为二进制帧，header 携带 `eof` 与 `size`（文件总大小）

合成规则：声明了 `write_chunk` 而未显式声明 `file.push` 时，bridge 替它注册 `<client>.file.push {path, content}`（**纯文本**，UTF-8 原样）；声明了 `read_chunk` 而未声明 `file.pull` 时，合成 `<client>.file.pull {path, limit?}`——最多读 `limit` 字节头部（默认 6MiB），返回 `{size, truncated, content}`；**非法 UTF-8 或含 NUL 的文件直接拒绝**并指向 bridge.pull。client 显式声明的同名能力优先于合成。

### 覆盖语义

所有写路径在 `offset: 0`（默认）时都是独占创建——**目标已存在即报错，绝不静默覆盖**。需要替换文件时由 agent 显式决策：先 `exec` 删除或改名（`rm` / `mv`，动作留痕），再写入。`offset: N` 续传是唯一例外，且接收端强校验 N 与自身文件大小一致。

## MCP 工具面

**动态工具** `<client_id>.<capability>`：如 `devbox.exec`、`devbox.file.pull`。描述与 schema 来自 client 声明（hidden 能力除外），client 上下线时动态增删（`tools/list_changed`）。

**静态工具**（bridge 自有，始终注册；目标不在线或未声明所需原语时调用自然报错）：

- `bridge.clients`：在线 client 清单（id、note、地址、连接时间、能力）。不确定谁在线先调这个。
- `bridge.push {client, local_path, remote_path, offset?}` / `bridge.pull {client, remote_path, local_path, offset?}`：**路径式搬运**。4MiB 二进制块流式传输，bridge 自己读写本机文件，**内容不经过模型上下文，文件大小无上限**。`offset: 0` 全新传输（目标已存在报错）；`offset: N` 续传——接收端要求自己的文件恰好 N 字节，不符即报错。中途失败的错误信息携带 `transferred up to offset N; retry with offset=N to continue`。耗时受 agent 侧 MCP `timeoutMs` 约束。

工具选择的语义分工：

| 场景 | 用什么 | 内容过模型？ | 限制 |
|---|---|---|---|
| 搬运文件 / 任意二进制（cp/scp 语义） | `bridge.push` / `bridge.pull` | 否 | 无 |
| 模型读/写文本（Read/Write 语义） | `<client>.file.pull`（`limit` 截断）/ `<client>.file.push` | 是 | 默认 6MiB，可调 |
| 模型改文本（Edit 语义，片段级） | `<client>.file.replace`（精确匹配，错误契约显式） | 只传 old/new 片段 | 单行可用 exec sed |
| 按需读大文件片段（grep/tail/sed） | `<client>.exec` | 输出各截断 32KB（`tail:true` 保尾） | — |
| 超大文件 / 慢链路（超 MCP 超时预算） | `<client>.exec` + curl/rsync | 否 | — |
| 查看远端图片 | `bridge.pull` 落盘 → agent 侧 Read（视觉通道） | 视觉输入 | — |

## 并发语义

- **同一 client 严格串行**：调用按 FIFO 排队，前一个完成（或超时）才发下一个。
- **不同 client 之间并行**，互不阻塞。
- 超时从请求入队起算（含排队等待）。

## WebSocket 协议

文本帧承载 JSON 控制消息，二进制帧承载大块数据。client 连入后 10 秒内必须首发 `hello`；同 id 重复连入被拒绝（`duplicate_client_id`），不影响已有连接。

**文本帧（JSON）**：

```jsonc
// client → server
{"type":"hello","client_id":"devbox","note":"...","auth":"...","capabilities":[
  {"name":"exec","description":"...","input_schema":{"type":"object",...}},
  {"name":"file.write_chunk","hidden":true,"input_schema":{...}}
]}
{"type":"result","id":"c1","ok":true,"result":{...}}
{"type":"result","id":"c1","ok":false,"error":"..."}

// server → client
{"type":"welcome","server_version":"1.0.0"}
{"type":"error","code":"duplicate_client_id|bad_handshake|unauthorized","message":"..."}   // 随后断开
{"type":"call","id":"c1","capability":"exec","arguments":{...}}
```

**二进制帧**：`[4字节大端 headerLen][header JSON][raw 数据]`，header 的 `type` 与文本消息同构：

```
server → client  header = Call（同上，载荷为块数据）           // 如 file.write_chunk 的 4MiB 块
client → server  header = {"type":"result","id":"c1","ok":true,"eof":true,"size":25165824}
                 载荷为数据块                                  // 如 file.read_chunk 的应答
```

规则：

- `client_id`：`^[a-zA-Z0-9_-]{1,32}$`；能力名：`^[a-zA-Z0-9_.-]{1,64}$`；`note` ≤500 字符；同一 hello 内能力名不得重复
- `input_schema` 必须是 `type:"object"` 的 JSON Schema（缺失 type 时 server 补全；不合法的能力被跳过并记日志，不影响其余能力）
- `hidden: true` 的能力不注册为模型工具，仅供 bridge 内部调用（分块原语即此用法）
- 应答类型（JSON 或二进制）由能力自身决定；调用按 id 路由，id 每连接内单调递增

## 常驻部署（systemd user service）

```ini
# ~/.config/systemd/user/bridge-mcp.service
[Unit]
Description=bridge-mcp (provider capability bridge, MCP over Streamable HTTP)
After=network-online.target

[Service]
EnvironmentFile=%h/.config/bridge-mcp.env   # BRIDGE_TOKEN=...
ExecStart=%h/.local/bin/bridge-mcp --addr :8765 --mcp-http
Restart=on-failure
RestartSec=2

[Install]
WantedBy=default.target
```

```bash
systemctl --user daemon-reload && systemctl --user enable --now bridge-mcp
# 完全无登录会话时保持运行（如无头机器）：
loginctl enable-linger $USER
```

验证：`curl http://127.0.0.1:8765/healthz` 返回 ok；`POST /mcp` 无 token 返回 401。provider 需带 `--token` 连入。

注意事项：

- **升级 bridge 二进制并重启服务后，已连接的 MCP 会话会失效**（Streamable HTTP 有状态模式的会话存在进程内），agent 侧需重启会话恢复。provider 不受影响，自动重连。未采用无状态模式，因为那会失去 `tools/list_changed` 推送——动态工具上下线将无法即时通知 agent。
- provider 二进制更新后 `systemctl --user restart bridge-provider` 即可（先 mv 再 restart，避免 ETXTBSY）。
- WS 读上限已按 `--max-body` 显式设置（coder/websocket 默认仅 32KB，不放大则大文件传输失败）。

## 端到端测试

```bash
go build -o bin/ ./cmd/...
go test -tags e2e -v .
```

覆盖：动态工具注册/注销、exec（正常/非零退出/超时/workdir 生效与失败/串行时序）、纯文本文件往返、file.replace（唯一命中/未命中/多命中/all 批量）、二进制拒绝、limit 截断、1MB 大文本（读上限回归）、24MB 分块搬运（超单帧上限）、offset 续传与错位校验、覆盖保护、跨 client 并行、重复 id 拒绝、断线下线，以及 Streamable HTTP 模式（401/握手/动态工具/调用）。

## 安全提示

- `--token` 仅做准入认证；WS 上为明文 JSON 与二进制帧，跨不可信网络请套 TLS（反向代理 wss）或走隧道。
- provider 参考实现对本机无任何路径/命令限制，只在与 bridge 相同信任域的机器上运行。
