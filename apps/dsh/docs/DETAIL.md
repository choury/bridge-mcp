# Bridge MCP

Turns this host into a [bridge-mcp](https://github.com/choury/bridge-mcp) provider so agents can call this machine's capabilities.

## Usage

1. Open **Settings → Bridge MCP**
2. Fill in the server URL (e.g. `http://127.0.0.1:8765/ws`), Client ID and token
3. Click **Connect**; tools such as `<client_id>.exec` appear on the agent side

## Capabilities

- `exec`: login shell, process-group kill on timeout/disconnect/stop, 32KB cap per stream
- Files: `file.replace` plus chunk primitives (`file.push` / `file.pull` are synthesized by bridge)

Configuration and logs live in `~/.dsh/bridge-mcp/`. See the repository README for protocol, overwrite rules and security.
