# Bridge MCP

把本机作为 provider 接入 [bridge-mcp](https://github.com/choury/bridge-mcp)，让 agent 跨机器调用本机能力。

## 使用

1. 打开 **设置 → Bridge MCP**
2. 填服务器（如 `http://127.0.0.1:8765/ws`）、Client ID、Token
3. 点 **连接**；上线后 agent 侧出现 `<client_id>.exec` 等工具

## 能力

- `exec`：login shell 执行（Windows 为 cmd.exe；输出按 UTF-8 严格解码、失败退 OEM 代码页），超时/断线/停止杀进程组（Windows 为进程树），输出各截断 32KB
- 文件：`file.replace` + 分块原语（`file.push` / `file.pull` 由 bridge 合成）

配置与日志在 `~/.dsh/bridge-mcp/`。协议、覆盖规则与安全说明见仓库 README。
