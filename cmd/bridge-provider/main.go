// provider 是参考实现：连入 bridge server，声明 exec / file.push / file.pull
// 三项能力并在本机执行。断线后自动重连。
package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"log"
	"os"
	"os/exec"
	"os/signal"
	"path/filepath"
	"syscall"
	"time"
	"unicode/utf8"

	"github.com/coder/websocket"

	"bridge-mcp/internal/protocol"
)

const (
	outputCap    = 32 << 10 // exec 输出捕获上限（stdout/stderr 各自）
	execMaxWait  = 10 * time.Minute
	readChunkMax = 8 << 20 // read_chunk 单次读取上限
)

// capHandler 能力处理器：args 为 JSON 参数，data 为二进制帧载荷（可空）。
// 返回 value 走 JSON 结果；返回 bin 走二进制帧结果（分块传输）。
type capHandler func(ctx context.Context, args json.RawMessage, data []byte) (capResult, error)

type capResult struct {
	value any
	bin   *binOut
}

type binOut struct {
	data []byte
	eof  bool
	size int64 // 数据来源的文件总大小，随二进制应答头返回
}

func main() {
	server := flag.String("server", "ws://127.0.0.1:8765/ws", "bridge server WebSocket 地址")
	id := flag.String("id", "", "client id，缺省用 hostname；需匹配 ^[a-zA-Z0-9_-]{1,32}$")
	note := flag.String("note", "", "可选的自我介绍，展示给 agent（≤500 字符）")
	token := flag.String("token", os.Getenv("BRIDGE_TOKEN"), "server 要求的 auth token")
	flag.Parse()
	if *id == "" {
		*id, _ = os.Hostname()
		log.Printf("provider: --id not given, using hostname %q", *id)
	}

	caps := []protocol.Capability{
		{
			Name:        "exec",
			Description: "Run a shell command on this host via a login shell (bash -lc, full user PATH). Returns exit_code, timed_out, stdout, stderr, truncated.",
			InputSchema: json.RawMessage(`{
				"type": "object",
				"properties": {
					"command":    {"type": "string", "description": "shell command line to execute"},
					"workdir":    {"type": "string", "description": "working directory to chdir into before execution; the call fails if it does not exist"},
				"timeout_ms": {"type": "integer", "minimum": 1000, "maximum": 600000, "default": 30000, "description": "execution timeout in milliseconds"},
				"tail":       {"type": "boolean", "default": false, "description": "keep the last 32KB of stdout/stderr instead of the first 32KB"}
			},
				"required": ["command"]
			}`),
		},
		{
			Name:        "file.replace",
			Description: "Replace exact text in a UTF-8 file on this host: `old` must occur exactly once and is replaced by `new`; errors (without modifying) when absent or when it occurs more than once, unless all=true replaces every occurrence.",
			InputSchema: json.RawMessage(`{
				"type": "object",
				"properties": {
					"path": {"type": "string", "description": "path of the text file to modify"},
					"old":  {"type": "string", "minLength": 1, "description": "exact text to find"},
					"new":  {"type": "string", "description": "replacement text"},
					"all":  {"type": "boolean", "default": false, "description": "replace every occurrence instead of requiring uniqueness"}
				},
				"required": ["path", "old", "new"]
			}`),
		},
		{
			Name:        "file.write_chunk",
			Description: "Write one raw binary chunk at offset (bridge-internal chunked transfer).",
			Hidden:      true,
			InputSchema: json.RawMessage(`{
				"type": "object",
				"properties": {
					"path":   {"type": "string", "description": "path to write"},
					"offset": {"type": "integer", "minimum": 0, "description": "0 creates the file (fails if exists); N>0 requires the file to be exactly N bytes (append continuation), else errors"}
				},
				"required": ["path", "offset"]
			}`),
		},
		{
			Name:        "file.read_chunk",
			Description: "Read one raw binary chunk (bridge-internal chunked transfer).",
			Hidden:      true,
			InputSchema: json.RawMessage(`{
				"type": "object",
				"properties": {
					"path":   {"type": "string", "description": "path to read"},
					"offset": {"type": "integer", "minimum": 0, "description": "byte offset to read from"},
					"length": {"type": "integer", "minimum": 1, "maximum": 8388608, "default": 4194304, "description": "max bytes to read"}
				},
				"required": ["path", "offset"]
			}`),
		},
	}

	handlers := map[string]capHandler{
		"exec": func(ctx context.Context, raw json.RawMessage, _ []byte) (capResult, error) {
			v, err := runExec(ctx, raw)
			return capResult{value: v}, err
		},
		"file.replace":     replaceFile,
		"file.write_chunk": writeChunk,
		"file.read_chunk":  readChunk,
	}

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt)
	defer stop()

	backoff := time.Second
	for {
		welcomed, err := runOnce(ctx, *server, protocol.Hello{
			Type: protocol.TypeHello, ClientID: *id, Note: *note, Auth: *token, Capabilities: caps,
		}, handlers)
		if welcomed {
			backoff = time.Second
		}
		if ctx.Err() != nil {
			return
		}
		if err != nil {
			log.Printf("provider: %v; reconnecting in %s", err, backoff)
		}
		select {
		case <-ctx.Done():
			return
		case <-time.After(backoff):
		}
		if backoff *= 2; backoff > 30*time.Second {
			backoff = 30 * time.Second
		}
	}
}

// runOnce 完成一次连接生命周期；welcomed 表示握手成功（可用于重置退避）。
func runOnce(ctx context.Context, server string, hello protocol.Hello, handlers map[string]capHandler) (welcomed bool, err error) {
	dctx, cancel := context.WithTimeout(ctx, 10*time.Second)
	conn, _, err := websocket.Dial(dctx, server, nil)
	cancel()
	if err != nil {
		return false, fmt.Errorf("dial %s: %w", server, err)
	}
	// 默认读上限 32KB，放大以容纳 push 进来的调用帧（含二进制块）
	conn.SetReadLimit(32 << 20)
	baseCtx, cancelBase := context.WithCancel(ctx)
	defer cancelBase()
	defer conn.CloseNow()

	if err := writeJSON(conn, baseCtx, hello); err != nil {
		return false, fmt.Errorf("send hello: %w", err)
	}
	// 等待 welcome
	rctx, cancelRead := context.WithTimeout(baseCtx, 10*time.Second)
	_, data, err := conn.Read(rctx)
	cancelRead()
	if err != nil {
		return false, fmt.Errorf("await welcome: %w", err)
	}
	var env protocol.Envelope
	if err := json.Unmarshal(data, &env); err != nil {
		return false, fmt.Errorf("bad message from server: %w", err)
	}
	switch env.Type {
	case protocol.TypeWelcome:
	case protocol.TypeError:
		var em protocol.ErrorMsg
		_ = json.Unmarshal(data, &em)
		return false, fmt.Errorf("server rejected: [%s] %s", em.Code, em.Message)
	default:
		return false, fmt.Errorf("unexpected first message %q from server", env.Type)
	}
	log.Printf("provider: %s: registered as %q with %d capabilities", server, hello.ClientID, len(hello.Capabilities))

	// handle 执行一次能力调用并回发结果；data 非空表示二进制帧调用
	handle := func(call protocol.Call, data []byte) error {
		var (
			cr      capResult
			callErr error
		)
		h, ok := handlers[call.Capability]
		if !ok {
			callErr = fmt.Errorf("unknown capability %q", call.Capability)
		} else {
			cr, callErr = h(baseCtx, call.Arguments, data)
		}
		if callErr != nil {
			return writeJSON(conn, baseCtx, protocol.Result{Type: protocol.TypeResult, ID: call.ID, Error: callErr.Error()})
		}
		if cr.bin != nil {
			msg, err := protocol.EncodeBinaryMessage(
				protocol.BinResultHeader{Type: protocol.TypeResult, ID: call.ID, OK: true, EOF: cr.bin.eof, Size: cr.bin.size}, cr.bin.data)
			if err != nil {
				return err
			}
			return writeRaw(conn, baseCtx, websocket.MessageBinary, msg)
		}
		return writeJSON(conn, baseCtx, protocol.Result{Type: protocol.TypeResult, ID: call.ID, OK: true, Result: mustMarshal(cr.value)})
	}

	for {
		msgType, data, err := conn.Read(baseCtx)
		if err != nil {
			return true, fmt.Errorf("connection lost: %w", err)
		}
		if msgType == websocket.MessageBinary {
			var call protocol.Call
			payload, derr := protocol.DecodeBinaryMessage(data, &call)
			if derr != nil || call.Type != protocol.TypeCall {
				continue
			}
			if err := handle(call, payload); err != nil {
				return true, fmt.Errorf("send result: %w", err)
			}
			continue
		}
		if err := json.Unmarshal(data, &env); err != nil {
			continue
		}
		switch env.Type {
		case protocol.TypeCall:
			var call protocol.Call
			if err := json.Unmarshal(data, &call); err != nil {
				continue
			}
			if err := handle(call, nil); err != nil {
				return true, fmt.Errorf("send result: %w", err)
			}
		}
	}
}

func runExec(ctx context.Context, raw json.RawMessage) (any, error) {
	var args struct {
		Command   string `json:"command"`
		Workdir   string `json:"workdir"`
		TimeoutMS *int64 `json:"timeout_ms"`
		Tail      bool   `json:"tail"`
	}
	if err := json.Unmarshal(raw, &args); err != nil {
		return nil, fmt.Errorf("decode arguments: %w", err)
	}
	if args.Command == "" {
		return nil, errors.New("command must not be empty")
	}
	timeout := 30 * time.Second
	if args.TimeoutMS != nil {
		timeout = time.Duration(*args.TimeoutMS) * time.Millisecond
		if timeout < time.Second {
			timeout = time.Second
		}
		if timeout > execMaxWait {
			timeout = execMaxWait
		}
	}

	// login shell 加载用户 profile（PATH、nvm 等）；无 bash 的环境退回 /bin/sh
	shell := "bash"
	if _, err := exec.LookPath("bash"); err != nil {
		shell = "/bin/sh"
	}
	ectx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	cmd := exec.CommandContext(ectx, shell, "-lc", args.Command)
	if args.Workdir != "" {
		cmd.Dir = args.Workdir
	}
	// 独立进程组，超时时整组击杀：只杀 sh 会留下持有 stdout 管道的孤儿，
	// 导致 Wait 阻塞到孤儿自然退出
	cmd.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}
	cmd.Cancel = func() error {
		if cmd.Process != nil {
			_ = syscall.Kill(-cmd.Process.Pid, syscall.SIGKILL)
			return cmd.Process.Kill()
		}
		return os.ErrProcessDone
	}
	stdout, stderr := &cappedBuffer{max: outputCap, tail: args.Tail}, &cappedBuffer{max: outputCap, tail: args.Tail}
	cmd.Stdout, cmd.Stderr = stdout, stderr
	runErr := cmd.Run()

	exitCode := -1
	if cmd.ProcessState != nil {
		exitCode = cmd.ProcessState.ExitCode()
	}
	return map[string]any{
		"exit_code": exitCode,
		"timed_out": ectx.Err() == context.DeadlineExceeded,
		"stdout":    stdout.String(),
		"stderr":    stderr.String(),
		"truncated": stdout.truncated || stderr.truncated,
	}, spawnErr(runErr)
}

// spawnErr 把"命令以非零码退出"从错误中剔除（这属于正常结果），只保留启动失败类错误。
func spawnErr(err error) error {
	if err == nil {
		return nil
	}
	var ee *exec.ExitError
	if errors.As(err, &ee) {
		return nil
	}
	return err
}

func replaceFile(_ context.Context, raw json.RawMessage, _ []byte) (capResult, error) {
	var args struct {
		Path string `json:"path"`
		Old  string `json:"old"`
		New  string `json:"new"`
		All  bool   `json:"all"`
	}
	if err := json.Unmarshal(raw, &args); err != nil {
		return capResult{}, fmt.Errorf("decode arguments: %w", err)
	}
	if args.Path == "" || args.Old == "" {
		return capResult{}, errors.New("path and old must not be empty")
	}
	data, err := os.ReadFile(args.Path)
	if err != nil {
		return capResult{}, err
	}
	if !utf8.Valid(data) {
		return capResult{}, errors.New("not a UTF-8 text file; binary files must go through bridge.pull/push")
	}
	n := bytes.Count(data, []byte(args.Old))
	if n == 0 {
		return capResult{}, fmt.Errorf("old not found in %s", args.Path)
	}
	if n > 1 && !args.All {
		return capResult{}, fmt.Errorf("old occurs %d times in %s; pass all=true to replace every occurrence", n, args.Path)
	}
	out := bytes.Replace(data, []byte(args.Old), []byte(args.New), n)

	// 原子写回：同目录临时文件 + rename，保留原权限
	st, err := os.Stat(args.Path)
	if err != nil {
		return capResult{}, err
	}
	tmp, err := os.CreateTemp(filepath.Dir(args.Path), filepath.Base(args.Path)+".replace-*")
	if err != nil {
		return capResult{}, err
	}
	if _, err := tmp.Write(out); err != nil {
		tmp.Close()
		os.Remove(tmp.Name())
		return capResult{}, err
	}
	if err := tmp.Chmod(st.Mode().Perm()); err != nil {
		tmp.Close()
		os.Remove(tmp.Name())
		return capResult{}, err
	}
	if err := tmp.Close(); err != nil {
		os.Remove(tmp.Name())
		return capResult{}, err
	}
	if err := os.Rename(tmp.Name(), args.Path); err != nil {
		os.Remove(tmp.Name())
		return capResult{}, err
	}
	return capResult{value: map[string]any{"replaced": n, "size": len(out)}}, nil
}

func writeChunk(_ context.Context, raw json.RawMessage, data []byte) (capResult, error) {
	var args struct {
		Path   string `json:"path"`
		Offset int64  `json:"offset"`
	}
	if err := json.Unmarshal(raw, &args); err != nil {
		return capResult{}, fmt.Errorf("decode arguments: %w", err)
	}
	if args.Path == "" {
		return capResult{}, errors.New("path must not be empty")
	}
	// 追加式契约：offset 0 独占创建（已存在即报错）；offset>0 要求文件当前
	// 大小恰好等于 offset——续传点错位直接报错，绝不制造空洞或覆盖基底
	var (
		f   *os.File
		err error
	)
	if args.Offset == 0 {
		f, err = os.OpenFile(args.Path, os.O_CREATE|os.O_WRONLY|os.O_EXCL, 0o644)
	} else {
		f, err = os.OpenFile(args.Path, os.O_WRONLY, 0)
		if err == nil {
			st, serr := f.Stat()
			if serr != nil {
				f.Close()
				return capResult{}, serr
			}
			if st.Size() != args.Offset {
				f.Close()
				return capResult{}, fmt.Errorf("offset %d does not match file size %d", args.Offset, st.Size())
			}
		}
	}
	if err != nil {
		return capResult{}, err
	}
	defer f.Close()
	if len(data) > 0 {
		if _, err := f.WriteAt(data, args.Offset); err != nil {
			return capResult{}, err
		}
	}
	return capResult{value: map[string]any{"bytes_written": len(data)}}, nil
}

func readChunk(_ context.Context, raw json.RawMessage, _ []byte) (capResult, error) {
	var args struct {
		Path   string `json:"path"`
		Offset int64  `json:"offset"`
		Length int64  `json:"length"`
	}
	if err := json.Unmarshal(raw, &args); err != nil {
		return capResult{}, fmt.Errorf("decode arguments: %w", err)
	}
	if args.Path == "" {
		return capResult{}, errors.New("path must not be empty")
	}
	if args.Length <= 0 || args.Length > readChunkMax {
		args.Length = readChunkMax
	}
	st, err := os.Stat(args.Path)
	if err != nil {
		return capResult{}, err
	}
	if st.IsDir() {
		return capResult{}, fmt.Errorf("%s is a directory", args.Path)
	}
	remaining := st.Size() - args.Offset
	if remaining <= 0 {
		return capResult{bin: &binOut{eof: true}}, nil
	}
	if args.Length > remaining {
		args.Length = remaining
	}
	f, err := os.Open(args.Path)
	if err != nil {
		return capResult{}, err
	}
	defer f.Close()
	buf := make([]byte, args.Length)
	n, rerr := f.ReadAt(buf, args.Offset)
	if rerr != nil && rerr != io.EOF {
		return capResult{}, rerr
	}
	eof := args.Offset+int64(n) >= st.Size()
	return capResult{bin: &binOut{data: buf[:n], eof: eof, size: st.Size()}}, nil
}

// cappedBuffer 捕获输出到 max 字节为止，进程继续运行但超出部分丢弃；tail 为 true 保尾，否则保头。
type cappedBuffer struct {
	buf       bytes.Buffer
	max       int
	tail      bool
	truncated bool
}

func (b *cappedBuffer) Write(p []byte) (int, error) {
	b.buf.Write(p)
	if over := b.buf.Len() - b.max; over > 0 {
		b.truncated = true
		if b.tail {
			b.buf.Next(over)
		} else {
			b.buf.Truncate(b.max)
		}
	}
	// 必须报告消费了全部输入：短写会让 io.Copy 终止并关闭管道，子进程 SIGPIPE
	return len(p), nil
}

func (b *cappedBuffer) String() string { return b.buf.String() }

func writeJSON(conn *websocket.Conn, ctx context.Context, v any) error {
	data, err := json.Marshal(v)
	if err != nil {
		return err
	}
	return writeRaw(conn, ctx, websocket.MessageText, data)
}

func writeRaw(conn *websocket.Conn, ctx context.Context, msgType websocket.MessageType, data []byte) error {
	timeout := 10 * time.Second
	if extra := len(data) / (1 << 20); extra > 0 {
		timeout += time.Duration(extra) * 10 * time.Second
	}
	wctx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	return conn.Write(wctx, msgType, data)
}

func mustMarshal(v any) json.RawMessage {
	b, err := json.Marshal(v)
	if err != nil {
		return json.RawMessage(`{}`)
	}
	return b
}
