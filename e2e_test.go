//go:build e2e

// 端到端测试：bridge + provider 两个二进制 + 一个 MCP stdio client。
// 运行：go build -o bin/ ./cmd/... && go test -tags e2e -run TestBridgeE2E -v .
package main

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/modelcontextprotocol/go-sdk/mcp"
)

type lockedBuffer struct {
	mu  sync.Mutex
	buf bytes.Buffer
}

func (b *lockedBuffer) Write(p []byte) (int, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.buf.Write(p)
}

func (b *lockedBuffer) String() string {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.buf.String()
}

type harness struct {
	t           *testing.T
	ctx         context.Context
	bridge      *exec.Cmd
	bridgeStdin io.WriteCloser
	cs          *mcp.ClientSession
	wsPort      string
	listChanged chan struct{}
}

func freePort(t *testing.T) string {
	t.Helper()
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer l.Close()
	_, port, _ := net.SplitHostPort(l.Addr().String())
	return port
}

func startBridge(t *testing.T) *harness {
	t.Helper()
	h := &harness{
		t:           t,
		ctx:         context.Background(),
		wsPort:      freePort(t),
		listChanged: make(chan struct{}, 32),
	}
	h.bridge = exec.Command("./bin/bridge-mcp", "-addr", "127.0.0.1:"+h.wsPort)
	h.bridge.Stderr = os.Stderr
	stdin, err := h.bridge.StdinPipe()
	if err != nil {
		t.Fatal(err)
	}
	stdout, err := h.bridge.StdoutPipe()
	if err != nil {
		t.Fatal(err)
	}
	if err := h.bridge.Start(); err != nil {
		t.Fatal(err)
	}
	h.bridgeStdin = stdin

	client := mcp.NewClient(&mcp.Implementation{Name: "e2e-client", Version: "1.0"}, &mcp.ClientOptions{
		ToolListChangedHandler: func(context.Context, *mcp.ToolListChangedRequest) {
			h.listChanged <- struct{}{}
		},
	})
	cs, err := client.Connect(h.ctx, &mcp.IOTransport{Reader: stdout, Writer: stdin}, nil)
	if err != nil {
		t.Fatalf("mcp connect: %v", err)
	}
	h.cs = cs
	return h
}

func (h *harness) teardown() {
	_ = h.bridgeStdin.Close()
	_ = h.bridge.Wait()
}

func (h *harness) startProvider(id, note string) (*exec.Cmd, *lockedBuffer) {
	h.t.Helper()
	args := []string{"--server", "ws://127.0.0.1:" + h.wsPort + "/ws", "--id", id}
	if note != "" {
		args = append(args, "--note", note)
	}
	p := exec.Command("./bin/bridge-provider", args...)
	errBuf := &lockedBuffer{}
	p.Stderr, p.Stdout = errBuf, os.Stdout
	if err := p.Start(); err != nil {
		h.t.Fatal(err)
	}
	return p, errBuf
}

func (h *harness) waitListChanged() {
	h.t.Helper()
	select {
	case <-h.listChanged:
	case <-time.After(5 * time.Second):
		h.t.Fatal("timed out waiting for tools/list_changed")
	}
}

func (h *harness) toolNames() map[string]bool {
	h.t.Helper()
	res, err := h.cs.ListTools(h.ctx, nil)
	if err != nil {
		h.t.Fatal(err)
	}
	names := make(map[string]bool)
	for _, tool := range res.Tools {
		names[tool.Name] = true
	}
	return names
}

func (h *harness) call(name string, args map[string]any) map[string]any {
	h.t.Helper()
	res, err := h.cs.CallTool(h.ctx, &mcp.CallToolParams{Name: name, Arguments: args})
	if err != nil {
		h.t.Fatalf("call %s: %v", name, err)
	}
	if len(res.Content) == 0 {
		h.t.Fatalf("call %s: no content", name)
	}
	text, ok := res.Content[0].(*mcp.TextContent)
	if !ok {
		h.t.Fatalf("call %s: unexpected content type %T", name, res.Content[0])
	}
	if res.IsError {
		h.t.Fatalf("call %s: tool error: %s", name, text.Text)
	}
	var m map[string]any
	if err := json.Unmarshal([]byte(text.Text), &m); err != nil {
		h.t.Fatalf("call %s: result is not JSON: %q", name, text.Text)
	}
	return m
}

// callToolError 调用预期返回工具错误（IsError）的工具，返回错误文本
func (h *harness) callToolError(name string, args map[string]any) string {
	h.t.Helper()
	res, err := h.cs.CallTool(h.ctx, &mcp.CallToolParams{Name: name, Arguments: args})
	if err != nil {
		h.t.Fatal(err)
	}
	if !res.IsError {
		h.t.Fatalf("call %s: expected tool error, got %v", name, res.Content)
	}
	return res.Content[0].(*mcp.TextContent).Text
}

func TestBridgeE2E(t *testing.T) {
	h := startBridge(t)
	defer h.teardown()

	// 1. 初始只有 bridge 内置工具（静态注册；client 能力工具尚未出现）
	if names := h.toolNames(); len(names) != 3 || !names["bridge.clients"] || !names["bridge.push"] || !names["bridge.pull"] {
		t.Fatalf("initial tools = %v, want bridge.clients/push/pull", names)
	}

	// 2. provider 连入 → 能力工具出现
	p1, p1Err := h.startProvider("devbox", "办公室的 Debian 测试机")
	defer func() {
		if t.Failed() {
			t.Logf("devbox provider stderr:\n%s", p1Err.String())
		}
	}()
	h.waitListChanged()
	names := h.toolNames()
	for _, want := range []string{"bridge.clients", "devbox.exec", "devbox.file.push", "devbox.file.pull"} {
		if !names[want] {
			t.Fatalf("tools after connect = %v, missing %s", names, want)
		}
	}

	// 3. bridge.clients 返回 note 与能力
	clientsRes, err := h.cs.CallTool(h.ctx, &mcp.CallToolParams{Name: "bridge.clients"})
	if err != nil {
		t.Fatal(err)
	}
	clientsJSON := clientsRes.Content[0].(*mcp.TextContent).Text
	if !bytes.Contains([]byte(clientsJSON), []byte("办公室的 Debian 测试机")) {
		t.Fatalf("bridge.clients missing note: %s", clientsJSON)
	}

	// 4. exec：正常执行、非零退出码、超时、超限截断（保头/tail 保尾）
	out := h.call("devbox.exec", map[string]any{"command": "echo e2e-marker-$((41+1))"})
	if out["exit_code"].(float64) != 0 || !bytes.Contains([]byte(out["stdout"].(string)), []byte("e2e-marker-42")) {
		t.Fatalf("exec echo: %v", out)
	}
	out = h.call("devbox.exec", map[string]any{"command": "exit 3"})
	if out["exit_code"].(float64) != 3 {
		t.Fatalf("exec exit 3: %v", out)
	}
	out = h.call("devbox.exec", map[string]any{"command": "sleep 5", "timeout_ms": 1000})
	if out["timed_out"] != true {
		t.Fatalf("exec timeout: %v", out)
	}
	bigOut := `for k in $(seq 4 4 48); do printf 'x%.0s' $(seq 1 4096); printf "<M${k}K>"; done`
	out = h.call("devbox.exec", map[string]any{"command": bigOut})
	if out["exit_code"].(float64) != 0 || out["truncated"] != true {
		t.Fatalf("exec big head: %v", out)
	}
	if so := out["stdout"].(string); len(so) != 32<<10 || !strings.HasPrefix(so, strings.Repeat("x", 4096)+"<M4K>") {
		t.Fatalf("exec big head keeps first 32KB: len=%d", len(so))
	}
	out = h.call("devbox.exec", map[string]any{"command": bigOut, "tail": true})
	if out["exit_code"].(float64) != 0 || out["truncated"] != true {
		t.Fatalf("exec big tail: %v", out)
	}
	if so := out["stdout"].(string); len(so) != 32<<10 || !strings.HasSuffix(so, "<M48K>") {
		t.Fatalf("exec big tail keeps last 32KB: len=%d tail=%q", len(so), so[len(so)-12:])
	}

	// workdir 生效与失败路径
	out = h.call("devbox.exec", map[string]any{"command": "pwd", "workdir": "/tmp"})
	if !bytes.Contains([]byte(out["stdout"].(string)), []byte("/tmp")) {
		t.Fatalf("workdir not applied: %v", out)
	}
	if res, err := h.cs.CallTool(h.ctx, &mcp.CallToolParams{
		Name:      "devbox.exec",
		Arguments: map[string]any{"command": "pwd", "workdir": "/nonexistent-dir-e2e"},
	}); err != nil || !res.IsError {
		t.Fatalf("bad workdir should return tool error, got err=%v res=%v", err, res)
	}

	// 5. file.push / file.pull 纯文本往返
	// 大文本回归：默认 WS 读上限 32KB 曾未放大，大传输直接失败
	bigPath := filepath.Join(t.TempDir(), "big.txt")
	big := bytes.Repeat([]byte("桥bridge-42\n"), (1<<20)/10)
	h.call("devbox.file.push", map[string]any{"path": bigPath, "content": string(big)})
	if pulled2 := h.call("devbox.file.pull", map[string]any{"path": bigPath}); pulled2["size"].(float64) != float64(len(big)) {
		t.Fatalf("big file size mismatch: %v", pulled2["size"])
	}
	pushPath := filepath.Join(t.TempDir(), "roundtrip.txt")
	payload := []byte("hello bridge 你好")
	h.call("devbox.file.push", map[string]any{"path": pushPath, "content": string(payload)})
	pulled := h.call("devbox.file.pull", map[string]any{"path": pushPath})
	if pulled["content"].(string) != string(payload) {
		t.Fatalf("file roundtrip mismatch: %q vs %q", pulled["content"], payload)
	}
	if direct, err := os.ReadFile(pushPath); err != nil || !bytes.Equal(direct, payload) {
		t.Fatalf("pushed file on disk mismatch: %q %v", direct, err)
	}

	// 二进制文件被 file.pull 拒绝，指向 bridge.pull
	binRemote := filepath.Join(t.TempDir(), "bin.dat")
	binData := make([]byte, 4096)
	for i := range binData {
		binData[i] = byte(i)
	}
	binSrc := filepath.Join(t.TempDir(), "bin-src.dat")
	if err := os.WriteFile(binSrc, binData, 0o644); err != nil {
		t.Fatal(err)
	}
	h.call("bridge.push", map[string]any{"client": "devbox", "local_path": binSrc, "remote_path": binRemote})
	if msg := h.callToolError("devbox.file.pull", map[string]any{"path": binRemote}); !bytes.Contains([]byte(msg), []byte("not a UTF-8 text file")) {
		t.Fatalf("binary pull should be rejected: %q", msg)
	}

	// 5.5 合成 file.pull 截断语义：limit 读头部 + truncated 标记
	small := h.call("devbox.file.pull", map[string]any{"path": pushPath})
	if small["truncated"] != false || small["size"].(float64) != float64(len(payload)) {
		t.Fatalf("file.pull full read: %v", small)
	}
	part := h.call("devbox.file.pull", map[string]any{"path": bigPath, "limit": 65536})
	if part["truncated"] != true || part["size"].(float64) != float64(len(big)) {
		t.Fatalf("file.pull truncated read: size=%v truncated=%v", part["size"], part["truncated"])
	}
	if part["content"].(string) != string(big[:65536]) {
		t.Fatalf("file.pull truncated content mismatch")
	}

	// 5.7 file.replace：精确替换的完整错误契约
	repPath := filepath.Join(t.TempDir(), "replace.txt")
	h.call("devbox.file.push", map[string]any{"path": repPath, "content": "alpha beta gamma beta\n"})
	out = h.call("devbox.file.replace", map[string]any{"path": repPath, "old": "gamma", "new": "GAMMA"})
	if out["replaced"].(float64) != 1 {
		t.Fatalf("replace unique: %v", out)
	}
	if msg := h.callToolError("devbox.file.replace", map[string]any{"path": repPath, "old": "delta", "new": "x"}); !bytes.Contains([]byte(msg), []byte("not found")) {
		t.Fatalf("replace not-found: %q", msg)
	}
	if msg := h.callToolError("devbox.file.replace", map[string]any{"path": repPath, "old": "beta", "new": "x"}); !bytes.Contains([]byte(msg), []byte("occurs 2 times")) {
		t.Fatalf("replace ambiguous: %q", msg)
	}
	out = h.call("devbox.file.replace", map[string]any{"path": repPath, "old": "beta", "new": "BETA", "all": true})
	if out["replaced"].(float64) != 2 {
		t.Fatalf("replace all: %v", out)
	}
	rep := h.call("devbox.file.pull", map[string]any{"path": repPath})
	if rep["content"].(string) != "alpha BETA GAMMA BETA\n" {
		t.Fatalf("replace result content: %q", rep["content"])
	}

	// 6. 路径式搬运：bridge.push / bridge.pull，内容不经过模型。
	// 24MB 超过单帧上限 16MiB，验证二进制分块传输
	xferSrc := filepath.Join(t.TempDir(), "xfer.bin")
	xferData := make([]byte, 24<<20)
	for i := range xferData {
		xferData[i] = byte(i * 7)
	}
	if err := os.WriteFile(xferSrc, xferData, 0o644); err != nil {
		t.Fatal(err)
	}
	xferRemote := filepath.Join(t.TempDir(), "xfer-remote.bin")
	h.call("bridge.push", map[string]any{"client": "devbox", "local_path": xferSrc, "remote_path": xferRemote})
	xferBack := filepath.Join(t.TempDir(), "xfer-back.bin")
	h.call("bridge.pull", map[string]any{"client": "devbox", "remote_path": xferRemote, "local_path": xferBack})
	if back, err := os.ReadFile(xferBack); err != nil || !bytes.Equal(back, xferData) {
		t.Fatalf("path transfer roundtrip mismatch (err %v, %d bytes)", err, len(back))
	}

	// 续传：push 先传 8MB 前缀，offset=8MiB 续传剩余；pull 本地已有 12MB 前缀，offset=12MiB 续传
	prefPath := filepath.Join(t.TempDir(), "xfer-prefix.bin")
	if err := os.WriteFile(prefPath, xferData[:8<<20], 0o644); err != nil {
		t.Fatal(err)
	}
	resRemote := filepath.Join(t.TempDir(), "xfer-resume.bin")
	h.call("bridge.push", map[string]any{"client": "devbox", "local_path": prefPath, "remote_path": resRemote})
	out = h.call("bridge.push", map[string]any{"client": "devbox", "local_path": xferSrc, "remote_path": resRemote, "offset": 8 << 20})
	if out["bytes_written"].(float64) != float64(len(xferData)-8<<20) {
		t.Fatalf("push continuation: %v", out)
	}
	resBack := filepath.Join(t.TempDir(), "xfer-resume-back.bin")
	if err := os.WriteFile(resBack, xferData[:12<<20], 0o644); err != nil {
		t.Fatal(err)
	}
	out = h.call("bridge.pull", map[string]any{"client": "devbox", "remote_path": resRemote, "local_path": resBack, "offset": 12 << 20})
	if out["bytes_written"].(float64) != float64(len(xferData)-12<<20) {
		t.Fatalf("pull continuation: %v", out)
	}
	if back, err := os.ReadFile(resBack); err != nil || !bytes.Equal(back, xferData) {
		t.Fatalf("resume roundtrip mismatch (err %v, %d bytes)", err, len(back))
	}

	// offset 错位校验：与目标当前大小不符即报错（不制造空洞、不接错基底）
	if msg := h.callToolError("bridge.push", map[string]any{"client": "devbox", "local_path": xferSrc, "remote_path": resRemote, "offset": 4096}); !bytes.Contains([]byte(msg), []byte("does not match")) {
		t.Fatalf("push with wrong offset should fail: %q", msg)
	}
	if msg := h.callToolError("bridge.pull", map[string]any{"client": "devbox", "remote_path": resRemote, "local_path": resBack, "offset": 4096}); !bytes.Contains([]byte(msg), []byte("does not match")) {
		t.Fatalf("pull with wrong offset should fail: %q", msg)
	}

	// 覆盖保护：目标已存在即报错；删除/改名由 agent 显式经 exec 完成
	if msg := h.callToolError("bridge.push", map[string]any{"client": "devbox", "local_path": xferSrc, "remote_path": resRemote}); !bytes.Contains([]byte(msg), []byte("exists")) {
		t.Fatalf("push to existing remote should fail: %q", msg)
	}
	if msg := h.callToolError("bridge.pull", map[string]any{"client": "devbox", "remote_path": resRemote, "local_path": resBack}); !bytes.Contains([]byte(msg), []byte("exists")) {
		t.Fatalf("pull to existing local should fail: %q", msg)
	}
	if msg := h.callToolError("devbox.file.push", map[string]any{"path": resRemote, "content": "x"}); !bytes.Contains([]byte(msg), []byte("exists")) {
		t.Fatalf("file.push to existing should fail: %q", msg)
	}

	// 7. 同 client 串行：B 必须在 A 完成之后才开始
	var resA, resB map[string]any
	var wg sync.WaitGroup
	wg.Add(2)
	go func() {
		defer wg.Done()
		resA = h.call("devbox.exec", map[string]any{"command": "sleep 1; date +%s%3N"})
	}()
	time.Sleep(150 * time.Millisecond)
	go func() {
		defer wg.Done()
		resB = h.call("devbox.exec", map[string]any{"command": "date +%s%3N"})
	}()
	wg.Wait()
	if resA["exit_code"].(float64) != 0 || resB["exit_code"].(float64) != 0 {
		t.Fatalf("serial test exit codes: A=%v B=%v", resA, resB)
	}
	tsA, _ := resA["stdout"].(string) // A 的完成时间戳
	tsB, _ := resB["stdout"].(string) // B 的执行时间戳
	if tsB < tsA {
		t.Fatalf("same-client calls not serialized: B(%s) ran before A finished(%s)", tsB, tsA)
	}

	// 7. 不同 client 并行
	p2, _ := h.startProvider("devbox2", "")
	h.waitListChanged()
	start := time.Now()
	var wg2 sync.WaitGroup
	wg2.Add(2)
	go func() {
		defer wg2.Done()
		h.call("devbox.exec", map[string]any{"command": "sleep 1"})
	}()
	go func() {
		defer wg2.Done()
		h.call("devbox2.exec", map[string]any{"command": "sleep 1"})
	}()
	wg2.Wait()
	if elapsed := time.Since(start); elapsed > 1800*time.Millisecond {
		t.Fatalf("cross-client calls not parallel: took %s", elapsed)
	}

	// 8. 重复 id：拒绝新连接，旧连接不受影响
	p3, p3Err := h.startProvider("devbox", "")
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) && !bytes.Contains([]byte(p3Err.String()), []byte("duplicate_client_id")) {
		time.Sleep(50 * time.Millisecond)
	}
	if !bytes.Contains([]byte(p3Err.String()), []byte("duplicate_client_id")) {
		t.Fatalf("duplicate id not rejected; provider stderr: %s", p3Err.String())
	}
	_ = p3.Process.Kill()
	_ = p3.Wait()
	h.call("devbox.exec", map[string]any{"command": "echo still-alive"})

	// 9. 断线下线：工具消失、调用报 unknown tool（工具已注销，SDK 返回协议层错误）
	_ = p1.Process.Kill()
	_ = p1.Wait()
	h.waitListChanged()
	names = h.toolNames()
	if names["devbox.exec"] || names["devbox.file.pull"] {
		t.Fatalf("tools after disconnect = %v, devbox.* should be gone", names)
	}
	if !names["devbox2.exec"] {
		t.Fatalf("devbox2 should survive: %v", names)
	}
	if _, err := h.cs.CallTool(h.ctx, &mcp.CallToolParams{
		Name: "devbox.exec", Arguments: map[string]any{"command": "echo x"},
	}); err == nil || !bytes.Contains([]byte(err.Error()), []byte("unknown tool")) {
		t.Fatalf("call to offline client should fail with unknown tool, got err=%v", err)
	}

	_ = p2.Process.Kill()
	_ = p2.Wait()

	// 10. 全部 client 下线 → 动态工具消失，bridge 内置工具保留
	h.waitListChanged()
	if names = h.toolNames(); len(names) != 3 || !names["bridge.clients"] || !names["bridge.push"] || !names["bridge.pull"] {
		t.Fatalf("tools after all providers gone = %v, want bridge built-ins only", names)
	}
	// 无 client 时调用搬运工具 → 自然报错
	if msg := h.callToolError("bridge.push", map[string]any{"client": "devbox", "local_path": "/etc/hostname", "remote_path": "/tmp/x"}); !bytes.Contains([]byte(msg), []byte("not connected")) {
		t.Fatalf("bridge.push with no clients: %q", msg)
	}
}

// TestBridgeStreamableHTTP 常驻模式：/mcp 上的 Streamable HTTP + bearer 认证。
func TestBridgeStreamableHTTP(t *testing.T) {
	ctx := context.Background()
	port := freePort(t)
	base := "http://127.0.0.1:" + port

	bridge := exec.Command("./bin/bridge-mcp", "-addr", "127.0.0.1:"+port, "-mcp-http", "-token", "e2e-token")
	bridge.Stderr = os.Stderr
	if err := bridge.Start(); err != nil {
		t.Fatal(err)
	}
	defer func() { _ = bridge.Process.Kill(); _ = bridge.Wait() }()

	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		resp, err := http.Get(base + "/healthz")
		if err == nil {
			resp.Body.Close()
			break
		}
		time.Sleep(50 * time.Millisecond)
	}

	// 无 token → 401
	resp, err := http.Post(base+"/mcp", "application/json", strings.NewReader(
		`{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"x","version":"0"}}}`))
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("no-token POST /mcp: status %d, want 401", resp.StatusCode)
	}

	// provider 连入
	prov := exec.Command("./bin/bridge-provider", "--server", "ws://127.0.0.1:"+port+"/ws",
		"--id", "devbox", "--note", "http 模式测试", "--token", "e2e-token")
	prov.Stderr = os.Stderr
	if err := prov.Start(); err != nil {
		t.Fatal(err)
	}
	defer func() { _ = prov.Process.Kill(); _ = prov.Wait() }()

	// 带 token 的 MCP 客户端
	client := mcp.NewClient(&mcp.Implementation{Name: "e2e-http", Version: "1.0"}, nil)
	cs, err := client.Connect(ctx, &mcp.StreamableClientTransport{
		Endpoint:   base + "/mcp",
		HTTPClient: &http.Client{Transport: authTransport{token: "e2e-token", rt: http.DefaultTransport}},
	}, nil)
	if err != nil {
		t.Fatalf("mcp connect: %v", err)
	}

	// 等动态工具出现并调用
	sawTool := false
	for time.Now().Before(deadline) {
		res, err := cs.ListTools(ctx, nil)
		if err != nil {
			t.Fatal(err)
		}
		for _, tool := range res.Tools {
			if tool.Name == "devbox.exec" {
				sawTool = true
			}
		}
		if sawTool {
			break
		}
		time.Sleep(100 * time.Millisecond)
	}
	if !sawTool {
		t.Fatal("devbox.exec never appeared in tools/list")
	}
	callRes, err := cs.CallTool(ctx, &mcp.CallToolParams{
		Name: "devbox.exec", Arguments: map[string]any{"command": "echo http-mode"},
	})
	if err != nil {
		t.Fatal(err)
	}
	if text := callRes.Content[0].(*mcp.TextContent).Text; !strings.Contains(text, "http-mode") {
		t.Fatalf("exec result: %s", text)
	}

	// 错误 token 连不上
	bad := mcp.NewClient(&mcp.Implementation{Name: "e2e-bad", Version: "1.0"}, nil)
	if _, err := bad.Connect(ctx, &mcp.StreamableClientTransport{
		Endpoint:   base + "/mcp",
		HTTPClient: &http.Client{Transport: authTransport{token: "wrong", rt: http.DefaultTransport}},
		MaxRetries: 1,
	}, nil); err == nil {
		t.Fatal("bad token should fail to connect")
	}
}

type authTransport struct {
	token string
	rt    http.RoundTripper
}

func (a authTransport) RoundTrip(req *http.Request) (*http.Response, error) {
	req = req.Clone(req.Context())
	req.Header.Set("Authorization", "Bearer "+a.token)
	return a.rt.RoundTrip(req)
}
