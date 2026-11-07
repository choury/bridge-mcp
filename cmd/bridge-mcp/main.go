// bridge 是 MCP server 入口：stdin/stdout 上提供 MCP 服务，
// 同时监听 WebSocket 端口接受 provider client 连入。
package main

import (
	"context"
	"errors"
	"flag"
	"log"
	"net"
	"net/http"
	"os"
	"os/signal"
	"strconv"
	"syscall"
	"time"

	"bridge-mcp/internal/mcpserver"
	"bridge-mcp/internal/registry"
)

func main() {
	addr := flag.String("addr", envOr("BRIDGE_ADDR", ":8765"), "provider client WebSocket 监听地址")
	token := flag.String("token", os.Getenv("BRIDGE_TOKEN"), "要求 client 在 hello 中携带的 token（空 = 不鉴权）")
	callTimeout := flag.Duration("call-timeout", envDur("BRIDGE_CALL_TIMEOUT", 120*time.Second), "单次调用整体超时（含排队）")
	maxBody := flag.Int("max-body", envInt("BRIDGE_MAX_BODY", 16<<20), "单条 WS 消息字节上限（双方向）")
	toolSep := flag.String("tool-sep", envOr("BRIDGE_TOOL_SEP", "."), "工具名分隔符：client id 与能力名之间的连接符")
	mcpHTTP := flag.Bool("mcp-http", false, "常驻模式：以 Streamable HTTP 在 /mcp 提供 MCP 服务，不使用 stdio")
	flag.Parse()

	reg := registry.New(registry.Config{
		Token:       *token,
		CallTimeout: *callTimeout,
		MaxBody:     *maxBody,
	})
	srv := mcpserver.New(reg, *toolSep)

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	ln, err := net.Listen("tcp", *addr)
	if err != nil {
		log.Fatalf("bridge: listen %s: %v", *addr, err)
	}
	httpSrv := &http.Server{Handler: reg.Handler()}
	go func() {
		if err := httpSrv.Serve(ln); err != nil && !errors.Is(err, http.ErrServerClosed) {
			log.Printf("bridge: provider endpoint stopped: %v", err)
			stop()
		}
	}()
	if *mcpHTTP {
		reg.Mount("/mcp", srv.HTTPHandler(*token))
		auth := "no auth"
		if *token != "" {
			auth = "bearer auth (--token)"
		}
		log.Printf("bridge: mcp endpoint http://%s/mcp (%s); provider endpoint ws://%s/ws", ln.Addr(), auth, ln.Addr())
		// 常驻模式由服务管理器托管生命周期，等待信号退出
		<-ctx.Done()
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
		defer cancel()
		_ = httpSrv.Shutdown(shutdownCtx)
		return
	}

	log.Printf("bridge: provider endpoint ws://%s/ws  (health: http://%s/healthz)", ln.Addr(), ln.Addr())

	// stdout 承载 MCP stdio 协议，所有日志走 stderr（log 包默认）
	runErr := srv.Run(ctx)
	stop()

	shutdownCtx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	_ = httpSrv.Shutdown(shutdownCtx)

	if runErr != nil && ctx.Err() == nil {
		log.Fatalf("bridge: mcp server: %v", runErr)
	}
}

func envOr(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}

func envDur(key string, def time.Duration) time.Duration {
	if v := os.Getenv(key); v != "" {
		d, err := time.ParseDuration(v)
		if err != nil {
			log.Fatalf("bridge: invalid %s=%q: %v", key, v, err)
		}
		return d
	}
	return def
}

func envInt(key string, def int) int {
	if v := os.Getenv(key); v != "" {
		n, err := strconv.Atoi(v)
		if err != nil {
			log.Fatalf("bridge: invalid %s=%q: %v", key, v, err)
		}
		return n
	}
	return def
}
