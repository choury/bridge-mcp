// Package mcpserver 把 provider client 声明的能力映射为 MCP 工具：
// client 上线 → 按声明注册工具；下线 → 注销工具；调用 → 经 registry 派发。
package mcpserver

import (
	"bytes"
	"context"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/modelcontextprotocol/go-sdk/mcp"

	"bridge-mcp/internal/registry"
)

const ServerVersion = "1.0.0"

type Server struct {
	mcps *mcp.Server
	reg  *registry.Registry
	sep  string
}

// New 创建 MCP server 并挂接 registry 的上线/下线回调。
func New(reg *registry.Registry, toolSep string) *Server {
	if toolSep == "" {
		toolSep = "."
	}
	s := &Server{
		mcps: mcp.NewServer(&mcp.Implementation{Name: "bridge-mcp", Version: ServerVersion}, nil),
		reg:  reg,
		sep:  toolSep,
	}
	s.mcps.AddTool(&mcp.Tool{
		Name:        "bridge.clients",
		Description: "List provider clients currently connected to this bridge. Each entry has the client_id, a version (self-reported provider implementation identifier), an optional note (self-description of the client's machine/environment), remote address, connected time, and its capabilities. Call this first to discover which clients are online.",
		InputSchema: json.RawMessage(`{"type":"object"}`),
	}, s.handleClients)

	// 路径式文件搬运：内容走 bridge↔provider 通道，不经过模型上下文。
	// 始终注册；目标 client 不在线或未声明对应能力时，调用自然报错。
	s.mcps.AddTool(&mcp.Tool{
		Name:        "bridge.push",
		Description: "Transfer a file from this machine to a bridge client, by paths only. The client must provide the file.write_chunk capability.",
		InputSchema: json.RawMessage(`{
			"type": "object",
			"properties": {
				"client":      {"type": "string", "description": "target client id"},
				"local_path":  {"type": "string", "description": "path on this machine (the bridge host) to read"},
				"remote_path": {"type": "string", "description": "path on the client to write"},
				"offset":      {"type": "integer", "minimum": 0, "default": 0, "description": "0 starts a new transfer (target must not exist); N continues — the client requires its file to be exactly N bytes, else errors"}
			},
			"required": ["client", "local_path", "remote_path"]
		}`),
	}, s.handleTransferPush)
	s.mcps.AddTool(&mcp.Tool{
		Name:        "bridge.pull",
		Description: "Transfer a file from a bridge client to this machine, by paths only. The client must provide the file.read_chunk capability.",
		InputSchema: json.RawMessage(`{
			"type": "object",
			"properties": {
				"client":      {"type": "string", "description": "source client id"},
				"remote_path": {"type": "string", "description": "path on the client to read"},
				"local_path":  {"type": "string", "description": "path on this machine (the bridge host) to write"},
				"offset":      {"type": "integer", "minimum": 0, "default": 0, "description": "0 starts a new transfer (local file must not exist); N continues — requires the local file to be exactly N bytes, else errors"}
			},
			"required": ["client", "remote_path", "local_path"]
		}`),
	}, s.handleTransferPull)

	reg.OnConnect(s.registerClientTools)
	reg.OnDisconnect(s.removeClientTools)
	return s
}

// Run 在 stdio 上提供 MCP 服务，直到 stdin 关闭或 ctx 取消。
func (s *Server) Run(ctx context.Context) error {
	return s.mcps.Run(ctx, &mcp.StdioTransport{})
}

// HTTPHandler 返回 Streamable HTTP 传输的 MCP 端点（常驻模式挂 /mcp）。
// token 非空时要求 Authorization: Bearer <token>。
func (s *Server) HTTPHandler(token string) http.Handler {
	h := mcp.NewStreamableHTTPHandler(func(*http.Request) *mcp.Server { return s.mcps }, nil)
	if token == "" {
		return h
	}
	return bearerAuth(token, h)
}

func bearerAuth(token string, next http.Handler) http.Handler {
	want := sha256.Sum256([]byte(token))
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		got := strings.TrimSpace(strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer "))
		have := sha256.Sum256([]byte(got))
		if subtle.ConstantTimeCompare(want[:], have[:]) != 1 {
			w.Header().Set("WWW-Authenticate", `Bearer realm="bridge-mcp"`)
			http.Error(w, "unauthorized", http.StatusUnauthorized)
			return
		}
		next.ServeHTTP(w, r)
	})
}

func (s *Server) toolName(clientID, capability string) string {
	return clientID + s.sep + capability
}

func (s *Server) registerClientTools(info registry.ClientInfo) {
	for _, cap := range info.Capabilities {
		if cap.Hidden {
			continue
		}
		schema, err := normalizeInputSchema(cap.InputSchema)
		if err != nil {
			log.Printf("mcp: client %q capability %q skipped: %v", info.ClientID, cap.Name, err)
			continue
		}
		desc := cap.Description
		if desc == "" {
			desc = fmt.Sprintf("capability %q provided by bridge client %q", cap.Name, info.ClientID)
		}
		name := s.toolName(info.ClientID, cap.Name)
		clientID, capability := info.ClientID, cap.Name
		func() {
			// AddTool 对非法 schema/name 会 panic；单个坏能力只跳过，不拖垮 server
			defer func() {
				if r := recover(); r != nil {
					log.Printf("mcp: client %q capability %q skipped: %v", clientID, capability, r)
				}
			}()
			s.mcps.AddTool(&mcp.Tool{
				Name:        name,
				Description: desc,
				InputSchema: schema,
			}, func(ctx context.Context, req *mcp.CallToolRequest) (*mcp.CallToolResult, error) {
				return s.handleCall(ctx, clientID, capability, req)
			})
		}()
	}
	s.synthesizeFileTools(info)
}

// synthesizeFileTools client 只需实现分块原语（hidden 的 file.write_chunk/read_chunk/stat），
// bridge 替它封装模型可见的 file.push / file.pull；client 显式声明的同名能力优先。
func (s *Server) synthesizeFileTools(info registry.ClientInfo) {
	declared := make(map[string]bool)
	for _, cap := range info.Capabilities {
		declared[cap.Name] = true
	}
	if !declared["file.push"] && declared["file.write_chunk"] {
		clientID := info.ClientID
		s.mcps.AddTool(&mcp.Tool{
			Name:        s.toolName(clientID, "file.push"),
			Description: fmt.Sprintf("Write a UTF-8 text file on client %q (the model-to-remote Write semantic). Text content only — binary data must go through bridge.push.", clientID),
			InputSchema: json.RawMessage(`{
				"type": "object",
				"properties": {
					"path":    {"type": "string", "description": "path on the client to write"},
					"content": {"type": "string", "description": "UTF-8 text content"}
				},
				"required": ["path", "content"]
			}`),
		}, func(ctx context.Context, req *mcp.CallToolRequest) (*mcp.CallToolResult, error) {
			return s.handleSynthPush(ctx, clientID, req)
		})
	}
	if !declared["file.pull"] && declared["file.read_chunk"] {
		clientID := info.ClientID
		s.mcps.AddTool(&mcp.Tool{
			Name:        s.toolName(clientID, "file.pull"),
			Description: fmt.Sprintf("Read a UTF-8 text file from client %q into the model context, as plain text. Reads at most `limit` bytes from the head (default %d MiB); `size` and `truncated` tell the full size and whether more exists. Binary files are rejected — use bridge.pull to disk or exec for them.", clientID, filePullDefault>>20),
			InputSchema: json.RawMessage(`{
				"type": "object",
				"properties": {
					"path":  {"type": "string", "description": "path on the client to read"},
					"limit": {"type": "integer", "minimum": 1, "default": 6291456, "description": "max bytes to read from the head of the file"}
				},
				"required": ["path"]
			}`),
		}, func(ctx context.Context, req *mcp.CallToolRequest) (*mcp.CallToolResult, error) {
			return s.handleSynthPull(ctx, clientID, req)
		})
	}
}

func (s *Server) removeClientTools(info registry.ClientInfo) {
	names := make([]string, 0, len(info.Capabilities)+2)
	declared := make(map[string]bool)
	for _, cap := range info.Capabilities {
		declared[cap.Name] = true
		if !cap.Hidden {
			names = append(names, s.toolName(info.ClientID, cap.Name))
		}
	}
	if !declared["file.push"] && declared["file.write_chunk"] {
		names = append(names, s.toolName(info.ClientID, "file.push"))
	}
	if !declared["file.pull"] && declared["file.read_chunk"] {
		names = append(names, s.toolName(info.ClientID, "file.pull"))
	}
	s.mcps.RemoveTools(names...)
}

func (s *Server) handleCall(ctx context.Context, clientID, capability string, req *mcp.CallToolRequest) (*mcp.CallToolResult, error) {
	var args json.RawMessage
	if req.Params.Arguments != nil {
		b, err := json.Marshal(req.Params.Arguments)
		if err != nil {
			return errorResult(fmt.Sprintf("marshal arguments: %v", err)), nil
		}
		args = b
	}
	raw, err := s.reg.Call(ctx, clientID, capability, args)
	if err != nil {
		return errorResult(err.Error()), nil
	}
	return textResult(prettyJSON(raw)), nil
}

// clientView bridge.clients 的精简视图：能力只留 name，
// 完整 schema 已可通过 tools/list 获取。
type clientView struct {
	ClientID     string    `json:"client_id"`
	Version      string    `json:"version"`
	Note         string    `json:"note,omitempty"`
	RemoteAddr   string    `json:"remote_addr"`
	ConnectedAt  time.Time `json:"connected_at"`
	Capabilities []string  `json:"capabilities"`
}

func (s *Server) handleClients(_ context.Context, _ *mcp.CallToolRequest) (*mcp.CallToolResult, error) {
	clients := s.reg.Clients()
	if len(clients) == 0 {
		return textResult("no provider clients connected"), nil
	}
	out := make([]clientView, len(clients))
	for i, c := range clients {
		caps := make([]string, len(c.Capabilities))
		for j, cap := range c.Capabilities {
			caps[j] = cap.Name
		}
		out[i] = clientView{
			ClientID:     c.ClientID,
			Version:      c.Version,
			Note:         c.Note,
			RemoteAddr:   c.RemoteAddr,
			ConnectedAt:  c.ConnectedAt,
			Capabilities: caps,
		}
	}
	return textResult(prettyJSON(mustMarshal(out))), nil
}

// transferChunk 分块传输的单块大小；帧上限只需容纳一个块加头部
const transferChunk = 4 << 20

// handleTransferPush 把本机文件推到远端 client：bridge 分块读本地文件，
// 每块走一个二进制帧调用 file.write_chunk，模型只提供路径。
func (s *Server) handleTransferPush(ctx context.Context, req *mcp.CallToolRequest) (*mcp.CallToolResult, error) {
	var args struct {
		Client     string `json:"client"`
		LocalPath  string `json:"local_path"`
		RemotePath string `json:"remote_path"`
		Offset     int64  `json:"offset"`
	}
	if err := decodeArgs(req, &args); err != nil {
		return errorResult(err.Error()), nil
	}
	f, err := os.Open(args.LocalPath)
	if err != nil {
		return errorResult(fmt.Sprintf("read %s: %v", args.LocalPath, err)), nil
	}
	defer f.Close()

	fst, err := f.Stat()
	if err != nil {
		return errorResult(err.Error()), nil
	}
	if args.Offset > fst.Size() {
		return errorResult(fmt.Sprintf("offset %d exceeds local size %d", args.Offset, fst.Size())), nil
	}
	if _, err := f.Seek(args.Offset, io.SeekStart); err != nil {
		return errorResult(err.Error()), nil
	}
	off := args.Offset

	buf := make([]byte, transferChunk)
	for {
		n, rerr := f.Read(buf)
		if n > 0 {
			callArgs, _ := json.Marshal(map[string]any{
				"path": args.RemotePath, "offset": off,
			})
			if _, _, err := s.reg.CallRaw(ctx, args.Client, "file.write_chunk", callArgs, buf[:n]); err != nil {
				if off > args.Offset {
					return errorResult(fmt.Sprintf("%v (transferred up to offset %d; retry with offset=%d to continue)", err, off, off)), nil
				}
				return errorResult(err.Error()), nil
			}
			off += int64(n)
		}
		if rerr == io.EOF {
			break
		}
		if rerr != nil {
			return errorResult(fmt.Sprintf("read %s: %v", args.LocalPath, rerr)), nil
		}
	}
	if off == 0 { // 空文件：发一个零长块独占创建
		callArgs, _ := json.Marshal(map[string]any{"path": args.RemotePath, "offset": 0})
		if _, _, err := s.reg.CallRaw(ctx, args.Client, "file.write_chunk", callArgs, nil); err != nil {
			return errorResult(err.Error()), nil
		}
	}
	return textResult(fmt.Sprintf(`{"bytes_written": %d, "remote_path": %q}`, off-args.Offset, args.RemotePath)), nil
}

// handleTransferPull 把远端 client 的文件分块拉到本机，模型只提供路径。
func (s *Server) handleTransferPull(ctx context.Context, req *mcp.CallToolRequest) (*mcp.CallToolResult, error) {
	var args struct {
		Client     string `json:"client"`
		RemotePath string `json:"remote_path"`
		LocalPath  string `json:"local_path"`
		Offset     int64  `json:"offset"`
	}
	if err := decodeArgs(req, &args); err != nil {
		return errorResult(err.Error()), nil
	}
	flags := os.O_CREATE | os.O_WRONLY | os.O_EXCL
	if args.Offset > 0 {
		st, err := os.Stat(args.LocalPath)
		if err != nil {
			return errorResult(fmt.Sprintf("continue at offset %d: %v", args.Offset, err)), nil
		}
		if st.Size() != args.Offset {
			return errorResult(fmt.Sprintf("offset %d does not match local size %d", args.Offset, st.Size())), nil
		}
		flags = os.O_APPEND | os.O_WRONLY
	}
	out, err := os.OpenFile(args.LocalPath, flags, 0o644)
	if err != nil {
		return errorResult(fmt.Sprintf("write %s: %v", args.LocalPath, err)), nil
	}
	defer out.Close()

	total := args.Offset
	for {
		callArgs, _ := json.Marshal(map[string]any{
			"path": args.RemotePath, "offset": total, "length": transferChunk,
		})
		_, bin, err := s.reg.CallRaw(ctx, args.Client, "file.read_chunk", callArgs, nil)
		if err != nil {
			if total > args.Offset {
				return errorResult(fmt.Sprintf("%v (transferred up to offset %d; retry with offset=%d to continue)", err, total, total)), nil
			}
			return errorResult(err.Error()), nil
		}
		if bin == nil {
			return errorResult("provider returned non-binary result for file.read_chunk"), nil
		}
		res := bin
		if _, err := out.Write(res.Data); err != nil {
			return errorResult(fmt.Sprintf("write %s: %v", args.LocalPath, err)), nil
		}
		total += int64(len(res.Data))
		if res.Header.EOF {
			break
		}
		if len(res.Data) == 0 {
			return errorResult(fmt.Sprintf("provider returned an empty non-eof chunk at offset %d; aborting", total)), nil
		}
	}
	return textResult(fmt.Sprintf(`{"bytes_written": %d, "local_path": %q}`, total-args.Offset, args.LocalPath)), nil
}

// filePullDefault 合成 file.pull 默认读取上限（字节）
const filePullDefault = 6 << 20

func (s *Server) handleSynthPush(ctx context.Context, clientID string, req *mcp.CallToolRequest) (*mcp.CallToolResult, error) {
	var args struct {
		Path    string `json:"path"`
		Content string `json:"content"`
	}
	if err := decodeArgs(req, &args); err != nil {
		return errorResult(err.Error()), nil
	}
	if args.Path == "" {
		return errorResult("path must not be empty"), nil
	}
	callArgs, _ := json.Marshal(map[string]any{"path": args.Path, "offset": 0})
	if _, _, err := s.reg.CallRaw(ctx, clientID, "file.write_chunk", callArgs, []byte(args.Content)); err != nil {
		return errorResult(err.Error()), nil
	}
	return textResult(fmt.Sprintf(`{"bytes_written": %d}`, len(args.Content))), nil
}

func (s *Server) handleSynthPull(ctx context.Context, clientID string, req *mcp.CallToolRequest) (*mcp.CallToolResult, error) {
	var args struct {
		Path  string `json:"path"`
		Limit int64  `json:"limit"`
	}
	if err := decodeArgs(req, &args); err != nil {
		return errorResult(err.Error()), nil
	}
	if args.Path == "" {
		return errorResult("path must not be empty"), nil
	}
	if args.Limit <= 0 {
		args.Limit = filePullDefault
	}
	// 截断语义：最多读 limit 字节的头部；文件总大小取自 read_chunk 应答头，
	// 据此计算 truncated（文件不存在/是目录由首个 read_chunk 自然报错）
	var (
		buf   []byte
		total int64
	)
	for int64(len(buf)) < args.Limit {
		length := int64(transferChunk)
		if rem := args.Limit - int64(len(buf)); rem < length {
			length = rem
		}
		callArgs, _ := json.Marshal(map[string]any{"path": args.Path, "offset": int64(len(buf)), "length": length})
		_, bin, err := s.reg.CallRaw(ctx, clientID, "file.read_chunk", callArgs, nil)
		if err != nil {
			return errorResult(err.Error()), nil
		}
		if bin == nil {
			return errorResult("provider returned non-binary result for file.read_chunk"), nil
		}
		total = bin.Header.Size
		buf = append(buf, bin.Data...)
		if bin.Header.EOF {
			break
		}
	}
	if !utf8.Valid(buf) || bytes.IndexByte(buf, 0) >= 0 {
		return errorResult(fmt.Sprintf("%s is not a UTF-8 text file; use bridge.pull to transfer it to disk", args.Path)), nil
	}
	var out bytes.Buffer
	enc := json.NewEncoder(&out)
	enc.SetEscapeHTML(false)
	_ = enc.Encode(map[string]any{
		"size": total, "truncated": total > int64(len(buf)), "content": string(buf),
	})
	return textResult(strings.TrimRight(out.String(), "\n")), nil
}

func decodeArgs(req *mcp.CallToolRequest, v any) error {
	if req.Params.Arguments == nil {
		return errors.New("missing arguments")
	}
	b, err := json.Marshal(req.Params.Arguments)
	if err != nil {
		return err
	}
	return json.Unmarshal(b, v)
}

// normalizeInputSchema 要求 input_schema 是 type 为 object 的 JSON Schema；
// 缺省/缺 type 时补全为 object，其余情况拒绝该能力。
func normalizeInputSchema(raw json.RawMessage) (json.RawMessage, error) {
	if len(raw) == 0 {
		return json.RawMessage(`{"type":"object"}`), nil
	}
	var m map[string]any
	if err := json.Unmarshal(raw, &m); err != nil {
		return nil, fmt.Errorf("input_schema is not a JSON object: %w", err)
	}
	switch t, _ := m["type"].(string); t {
	case "":
		m["type"] = "object"
		return json.Marshal(m)
	case "object":
		return raw, nil
	default:
		return nil, fmt.Errorf("input_schema type must be %q, got %q", "object", t)
	}
}

func textResult(text string) *mcp.CallToolResult {
	return &mcp.CallToolResult{Content: []mcp.Content{&mcp.TextContent{Text: text}}}
}

func errorResult(msg string) *mcp.CallToolResult {
	return &mcp.CallToolResult{Content: []mcp.Content{&mcp.TextContent{Text: msg}}, IsError: true}
}

func prettyJSON(raw json.RawMessage) string {
	if len(raw) == 0 {
		return "ok"
	}
	var buf bytes.Buffer
	if err := json.Indent(&buf, raw, "", "  "); err != nil {
		return string(raw)
	}
	return buf.String()
}

func mustMarshal(v any) json.RawMessage {
	b, err := json.Marshal(v)
	if err != nil {
		return json.RawMessage(`[]`)
	}
	return b
}
