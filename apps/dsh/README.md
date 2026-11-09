# @choury/dsh-bridge-mcp

把当前 DSH 主机变成 bridge-mcp 的 **provider**：DSH 作为 client 主动连入 bridge，
声明本机能力，agent 侧即可跨机器调用。

```
Agent ──MCP──> bridge ──WebSocket──> 本插件（host 执行能力 + client 设置页）
```

## 插件行为

- 能力声明与 go 的 provider 参考实现一致（只声明原语，`file.push`/`file.pull` 由 bridge 合成）。
- 地址支持 `http(s)://` 与 `ws(s)://`；地址里的 `user:pass@` 转成 `Authorization: Basic`。
- 断线 / 停止会中止在途调用并杀掉 `exec` 进程组；重连后旧连接上的结果直接丢弃，绝不串到新连接。
- 配置与日志在 `$DSH_HOME/bridge-mcp/`（目录 0700、文件 0600），Token 明文保存。

## 设置页

DSH → 设置 → **Bridge MCP**：

- 服务器 / Client ID / 备注 / Token（连接中只读）；Client ID 默认取主机名原值
- 连接 / 停止，状态徽章，实时日志（300 行）
- 能力开关：`exec`、文件工具（分块原语 + 替换）；修改能力需先停止连接

## 安装

要求 DSH ≥ `0.2.2-rc.1`、Node ≥ 22.19。

从 npm 安装（推荐）：

```bash
dsh plugin --profile web add @choury/dsh-bridge-mcp
```

再把包名加入 `~/.dsh/profiles/web/package.json` 的 `dsh.profile.bundles`：

```json
{
  "dsh": {
    "profile": {
      "bundles": ["...", "@choury/dsh-bridge-mcp"]
    }
  }
}
```

也可以在 DSH 设置 → **Plugins** 里按包名安装并启用（会同时写入依赖和 bundles）。
重启 DSH 后设置页出现 **Bridge MCP**。

包自带 `dsh.bundle.patch`，进入 `dsh.profile.bundles` 后会自动插入 provider 行，
不需要再手写 `cordis.patch.yml`。

本地开发/离线安装：

```bash
dsh plugin --profile web add file:/path/to/bridge-mcp/apps/dsh
```

同样加入 `dsh.profile.bundles` 即可。若只想手动 patch，先确保包已在 profile 的
`node_modules` 中，再在 profile 的 `cordis.patch.yml` 写：

```yaml
- insert:
    - id: bridge-mcp
      name: '@choury/dsh-bridge-mcp'
```

## 目录结构

```
apps/dsh/
├── package.json          # dsh.bundle.patch + dsh.client
├── cordis.patch.yml      # bundle 补丁
├── src/host/             # Host 源码（ws 仅 devDependency）
│   ├── index.js          # 插件入口
│   ├── build.mjs         # 打包入口 + ws → lib/host.js
│   ├── bridge.js         # 连接、重连、调用串行
│   ├── capabilities.js   # 能力声明与分发
│   ├── exec.js           # exec 原语
│   ├── files.js          # 分块读写 + 精确替换
│   ├── protocol.js       # 二进制帧编解码
│   ├── config.js         # 配置与日志
│   └── rpc.js            # /api 设置页端点
├── src/client/           # 设置页（React.createElement）
└── lib/                  # 构建产物（host 内含 ws）
```

构建：`npm run build`

