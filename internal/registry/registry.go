// Package registry 维护 provider client 注册表，承载 WS 握手、调用路由与
// 并发模型：同一 client 的调用严格串行，不同 client 之间并行。
package registry

import (
	"context"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"net/http"
	"regexp"
	"sort"
	"sync"
	"time"

	"github.com/coder/websocket"

	"bridge-mcp/internal/protocol"
)

var (
	clientIDRe = regexp.MustCompile(`^[a-zA-Z0-9_-]{1,32}$`)
	capNameRe  = regexp.MustCompile(`^[a-zA-Z0-9_.-]{1,64}$`)
)

const (
	maxNoteLen   = 500
	writeTimeout = 10 * time.Second
)

type Config struct {
	Token       string        // 非空时要求 hello.auth 匹配
	CallTimeout time.Duration // 单次调用从入队到完成的整体超时
	MaxBody     int           // 单条 WS 消息上限（字节），双方向都受此约束
	HelloWait   time.Duration // 连入后等待 hello 的时限
	PingEvery   time.Duration // 协议层保活间隔
}

func (c *Config) fillDefaults() {
	if c.CallTimeout <= 0 {
		c.CallTimeout = 120 * time.Second
	}
	if c.MaxBody <= 0 {
		c.MaxBody = 16 << 20
	}
	if c.HelloWait <= 0 {
		c.HelloWait = 10 * time.Second
	}
	if c.PingEvery <= 0 {
		c.PingEvery = 30 * time.Second
	}
}

// ClientInfo client 的对外快照。
type ClientInfo struct {
	ClientID     string                `json:"client_id"`
	Note         string                `json:"note,omitempty"`
	RemoteAddr   string                `json:"remote_addr"`
	ConnectedAt  time.Time             `json:"connected_at"`
	Capabilities []protocol.Capability `json:"capabilities"`
}

type Registry struct {
	cfg Config
	mux *http.ServeMux

	mu     sync.RWMutex
	m      map[string]*client
	onConn []func(ClientInfo)
	onDisc []func(ClientInfo)
}

func New(cfg Config) *Registry {
	cfg.fillDefaults()
	r := &Registry{cfg: cfg, m: make(map[string]*client), mux: http.NewServeMux()}
	r.mux.HandleFunc("/ws", r.serveWS)
	r.mux.HandleFunc("/healthz", func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte("ok"))
	})
	return r
}

// Mount 在与 /ws 同一个端口上挂载额外端点（如 /mcp）。
func (r *Registry) Mount(path string, h http.Handler) {
	r.mux.Handle(path, h)
}

// Handler 暴露 WS 端点 /ws 与健康检查 /healthz。
func (r *Registry) Handler() http.Handler {
	return r.mux
}

// OnConnect 注册 client 上线回调（握手完成、工具可注册时触发）。
func (r *Registry) OnConnect(f func(ClientInfo)) {
	r.mu.Lock()
	r.onConn = append(r.onConn, f)
	r.mu.Unlock()
}

// OnDisconnect 注册 client 下线回调（连接断开、工具应注销时触发，恰好一次）。
func (r *Registry) OnDisconnect(f func(ClientInfo)) {
	r.mu.Lock()
	r.onDisc = append(r.onDisc, f)
	r.mu.Unlock()
}

// Clients 返回按 client_id 排序的在线快照。
func (r *Registry) Clients() []ClientInfo {
	r.mu.RLock()
	defer r.mu.RUnlock()
	out := make([]ClientInfo, 0, len(r.m))
	for _, cl := range r.m {
		out = append(out, cl.info)
	}
	sort.Slice(out, func(i, j int) bool { return out[i].ClientID < out[j].ClientID })
	return out
}

func (r *Registry) get(id string) *client {
	r.mu.RLock()
	defer r.mu.RUnlock()
	return r.m[id]
}

func (r *Registry) register(cl *client) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	if _, exists := r.m[cl.info.ClientID]; exists {
		return fmt.Errorf("client id %q already connected", cl.info.ClientID)
	}
	r.m[cl.info.ClientID] = cl
	return nil
}

// unregister 仅在表项仍是该实例时删除，防止误删后来者。
func (r *Registry) unregister(cl *client) {
	r.mu.Lock()
	if r.m[cl.info.ClientID] == cl {
		delete(r.m, cl.info.ClientID)
	}
	r.mu.Unlock()
}

func (r *Registry) hooks() (conn []func(ClientInfo), disc []func(ClientInfo)) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	conn = append(conn, r.onConn...)
	disc = append(disc, r.onDisc...)
	return conn, disc
}

func (r *Registry) serveWS(w http.ResponseWriter, req *http.Request) {
	conn, err := websocket.Accept(w, req, nil)
	if err != nil {
		return
	}
	// coder/websocket 默认读上限仅 32KB，必须显式放大到消息上限，否则大文件传输直接失败
	conn.SetReadLimit(int64(r.cfg.MaxBody))
	baseCtx, cancelBase := context.WithCancel(context.Background())
	defer cancelBase()

	hello, reject, err := r.handshake(conn, req, baseCtx)
	if err != nil {
		if reject != nil {
			_ = writeRaw(conn, baseCtx, reject)
		}
		log.Printf("ws: rejected connection from %s: %v", req.RemoteAddr, err)
		_ = conn.Close(websocket.StatusNormalClosure, rejectCode(reject))
		return
	}

	ctx, cancel := context.WithCancel(baseCtx)
	cl := &client{
		reg:      r,
		info:     ClientInfo{ClientID: hello.ClientID, Note: hello.Note, RemoteAddr: req.RemoteAddr, ConnectedAt: time.Now(), Capabilities: hello.Capabilities},
		conn:     conn,
		ctx:      ctx,
		cancel:   cancel,
		dispatch: make(chan *pendingCall, 64),
		dead:     make(chan struct{}),
	}
	if err := r.register(cl); err != nil {
		_ = writeRaw(conn, baseCtx, protocol.ErrorMsg{
			Type: protocol.TypeError, Code: protocol.CodeDuplicateClientID, Message: err.Error(),
		})
		log.Printf("ws: %v", err)
		_ = conn.Close(websocket.StatusNormalClosure, "duplicate client id")
		return
	}
	if err := cl.writeJSON(ctx, protocol.Welcome{Type: protocol.TypeWelcome, ServerVersion: "1.0.0"}); err != nil {
		r.unregister(cl)
		_ = conn.CloseNow()
		return
	}

	cl.booted.Store(true)
	log.Printf("ws: client %q connected from %s (%d capabilities)", hello.ClientID, req.RemoteAddr, len(hello.Capabilities))
	connHooks, _ := r.hooks()
	for _, f := range connHooks {
		f(cl.info)
	}

	go cl.worker()
	go cl.pinger()
	cl.readLoop()
}

// handshake 读取并校验首条 hello；返回拒绝时要发送的 error 消息。
func (r *Registry) handshake(conn *websocket.Conn, req *http.Request, baseCtx context.Context) (*protocol.Hello, *protocol.ErrorMsg, error) {
	hctx, cancel := context.WithTimeout(baseCtx, r.cfg.HelloWait)
	defer cancel()
	_, data, err := conn.Read(hctx)
	if err != nil {
		return nil, nil, fmt.Errorf("no hello within %s: %w", r.cfg.HelloWait, err)
	}
	if len(data) > r.cfg.MaxBody {
		return nil, nil, errors.New("hello exceeds max body size")
	}
	var env protocol.Envelope
	if err := json.Unmarshal(data, &env); err != nil || env.Type != protocol.TypeHello {
		return nil, badHandshake("first message must be hello"), errors.New("first message is not hello")
	}
	var hello protocol.Hello
	if err := json.Unmarshal(data, &hello); err != nil {
		return nil, badHandshake("malformed hello"), err
	}
	if !clientIDRe.MatchString(hello.ClientID) {
		return nil, badHandshake("invalid client_id: must match ^[a-zA-Z0-9_-]{1,32}$"), fmt.Errorf("invalid client_id from %s", req.RemoteAddr)
	}
	if len([]rune(hello.Note)) > maxNoteLen {
		return nil, badHandshake(fmt.Sprintf("note exceeds %d characters", maxNoteLen)), errors.New("note too long")
	}
	if !r.authOK(hello.Auth) {
		return nil, &protocol.ErrorMsg{Type: protocol.TypeError, Code: protocol.CodeUnauthorized, Message: "invalid auth token"}, errors.New("auth failed")
	}
	seen := make(map[string]bool)
	for _, cap := range hello.Capabilities {
		if !capNameRe.MatchString(cap.Name) {
			return nil, badHandshake(fmt.Sprintf("invalid capability name %q", cap.Name)), fmt.Errorf("invalid capability name %q", cap.Name)
		}
		if seen[cap.Name] {
			return nil, badHandshake(fmt.Sprintf("duplicate capability name %q", cap.Name)), fmt.Errorf("duplicate capability %q", cap.Name)
		}
		seen[cap.Name] = true
	}
	return &hello, nil, nil
}

func (r *Registry) authOK(got string) bool {
	if r.cfg.Token == "" {
		return true
	}
	want := sha256.Sum256([]byte(r.cfg.Token))
	have := sha256.Sum256([]byte(got))
	return subtle.ConstantTimeCompare(want[:], have[:]) == 1
}

func badHandshake(msg string) *protocol.ErrorMsg {
	return &protocol.ErrorMsg{Type: protocol.TypeError, Code: protocol.CodeBadHandshake, Message: msg}
}

func rejectCode(msg *protocol.ErrorMsg) string {
	if msg == nil {
		return protocol.CodeBadHandshake
	}
	return msg.Code
}
