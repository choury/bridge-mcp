package registry

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"sync"
	"sync/atomic"
	"time"

	"github.com/coder/websocket"

	"bridge-mcp/internal/protocol"
)

// client 一个已注册的 provider 连接。dispatch 队列 + 单 worker 保证同一 client
// 的调用严格串行；不同 client 各自独立，天然并行。
type client struct {
	reg          *Registry
	info         ClientInfo
	conn         *websocket.Conn
	ctx          context.Context
	cancel       context.CancelFunc
	lastActive   atomic.Int64 // 最近一次任一方向数据帧的时间，心跳据此判定空闲
	dispatch     chan *pendingCall
	pending      sync.Map // call id → chan resultMsg
	pendingCount atomic.Int32
	dead         chan struct{}
	deadOnce     sync.Once
	booted       atomic.Bool
	seq          atomic.Int64
	writeMu      sync.Mutex
}

// resultMsg 一次调用的应答：JSON 结果或二进制结果二选一。
type resultMsg struct {
	json *protocol.Result
	bin  *protocol.BinResult
}

type callOutcome struct {
	result json.RawMessage
	bin    *protocol.BinResult
	err    error
}

type pendingCall struct {
	capability string
	args       json.RawMessage
	data       []byte // 非空时以二进制帧发送
	deadline   time.Time
	canceled   atomic.Bool
	done       chan callOutcome // 容量 1，finish 非阻塞
}

func (pc *pendingCall) finish(o callOutcome) {
	select {
	case pc.done <- o:
	default:
	}
}

// Call 把一次能力调用派发给 clientID，阻塞至结果、超时或 ctx 取消。
// 超时从入队起算，包含排队等待时间。
func (r *Registry) Call(ctx context.Context, clientID, capability string, args json.RawMessage) (json.RawMessage, error) {
	out, err := r.dispatch(ctx, clientID, &pendingCall{
		capability: capability, args: args,
		deadline: time.Now().Add(r.cfg.CallTimeout), done: make(chan callOutcome, 1),
	})
	if err != nil {
		return nil, err
	}
	return out.result, out.err
}

// CallRaw 同 Call，但可附带二进制载荷（走 Binary 帧）。应答类型由能力自身决定：
// JSON 结果（如 write_chunk 的 bytes_written）或二进制结果（如 read_chunk 的数据块），二选一。
func (r *Registry) CallRaw(ctx context.Context, clientID, capability string, args json.RawMessage, data []byte) (json.RawMessage, *protocol.BinResult, error) {
	out, err := r.dispatch(ctx, clientID, &pendingCall{
		capability: capability, args: args, data: data,
		deadline: time.Now().Add(r.cfg.CallTimeout), done: make(chan callOutcome, 1),
	})
	if err != nil {
		return nil, nil, err
	}
	if out.err != nil {
		return nil, nil, out.err
	}
	return out.result, out.bin, nil
}

// dispatch 入队并等待结果，Call 与 CallBinary 共用。
func (r *Registry) dispatch(ctx context.Context, clientID string, pc *pendingCall) (callOutcome, error) {
	cl := r.get(clientID)
	if cl == nil {
		return callOutcome{}, fmt.Errorf("client %q is not connected (use bridge.clients to list online clients)", clientID)
	}

	qTimer := time.NewTimer(time.Until(pc.deadline))
	defer qTimer.Stop()
	select {
	case cl.dispatch <- pc:
	case <-cl.dead:
		return callOutcome{}, fmt.Errorf("client %q disconnected", clientID)
	case <-ctx.Done():
		return callOutcome{}, ctx.Err()
	case <-qTimer.C:
		return callOutcome{}, fmt.Errorf("call to %s.%s timed out after %s (queued behind other calls)", clientID, pc.capability, r.cfg.CallTimeout)
	}

	wTimer := time.NewTimer(time.Until(pc.deadline))
	defer wTimer.Stop()
	select {
	case out := <-pc.done:
		return out, nil
	case <-ctx.Done():
		pc.canceled.Store(true)
		return callOutcome{}, ctx.Err()
	case <-wTimer.C:
		pc.canceled.Store(true)
		return callOutcome{}, fmt.Errorf("call to %s.%s timed out after %s", clientID, pc.capability, r.cfg.CallTimeout)
	}
}

// worker 单 goroutine 顺序消费派发队列：前一调用完成（或超时）后才发出下一个。
func (cl *client) worker() {
	for {
		select {
		case <-cl.dead:
			return
		case pc := <-cl.dispatch:
			if pc.canceled.Load() {
				continue
			}
			cl.execOne(pc)
		}
	}
}

func (cl *client) execOne(pc *pendingCall) {
	remaining := time.Until(pc.deadline)
	if remaining <= 0 {
		pc.finish(callOutcome{err: fmt.Errorf("call to %s.%s timed out after %s", cl.info.ClientID, pc.capability, cl.reg.cfg.CallTimeout)})
		return
	}
	id := fmt.Sprintf("c%d", cl.seq.Add(1))
	ch := make(chan resultMsg, 1)
	cl.pending.Store(id, ch)
	cl.pendingCount.Add(1)
	defer func() {
		cl.pending.Delete(id)
		cl.pendingCount.Add(-1)
	}()

	var werr error
	if pc.data != nil {
		var msg []byte
		msg, werr = protocol.EncodeBinaryMessage(protocol.Call{
			Type: protocol.TypeCall, ID: id, Capability: pc.capability, Arguments: pc.args,
		}, pc.data)
		if werr == nil {
			werr = cl.writeRaw(websocket.MessageBinary, msg)
		}
	} else {
		werr = cl.writeJSON(cl.ctx, protocol.Call{
			Type: protocol.TypeCall, ID: id, Capability: pc.capability, Arguments: pc.args,
		})
	}
	if werr != nil {
		pc.finish(callOutcome{err: fmt.Errorf("write to client: %w", werr)})
		log.Printf("ws: client %q write error: %v", cl.info.ClientID, werr)
		cl.kill()
		return
	}

	wctx, cancel := context.WithTimeout(cl.ctx, remaining)
	defer cancel()
	select {
	case rm := <-ch:
		switch {
		case rm.json != nil:
			if rm.json.OK {
				pc.finish(callOutcome{result: rm.json.Result})
			} else {
				pc.finish(callOutcome{err: fmt.Errorf("provider error: %s", rm.json.Error)})
			}
		case rm.bin != nil:
			if rm.bin.Header.OK {
				pc.finish(callOutcome{bin: rm.bin})
			} else {
				pc.finish(callOutcome{err: fmt.Errorf("provider error: %s", rm.bin.Header.Error)})
			}
		}
	case <-cl.dead:
		pc.finish(callOutcome{err: fmt.Errorf("client %q disconnected", cl.info.ClientID)})
	case <-wctx.Done():
		pc.finish(callOutcome{err: fmt.Errorf("call to %s.%s timed out after %s", cl.info.ClientID, pc.capability, cl.reg.cfg.CallTimeout)})
	}
}

func (cl *client) readLoop() {
	for {
		msgType, data, err := cl.conn.Read(cl.ctx)
		if err != nil {
			log.Printf("ws: client %q read error: %v", cl.info.ClientID, err)
			cl.kill()
			return
		}
		cl.lastActive.Store(time.Now().UnixNano())
		if len(data) > cl.reg.cfg.MaxBody {
			log.Printf("ws: client %q sent %d bytes, exceeding limit %d; disconnecting", cl.info.ClientID, len(data), cl.reg.cfg.MaxBody)
			cl.kill()
			return
		}
		if msgType == websocket.MessageBinary {
			var hdr protocol.BinResultHeader
			payload, err := protocol.DecodeBinaryMessage(data, &hdr)
			if err != nil || hdr.Type != protocol.TypeResult {
				log.Printf("ws: client %q sent malformed binary message; disconnecting", cl.info.ClientID)
				cl.kill()
				return
			}
			if ch, ok := cl.pending.LoadAndDelete(hdr.ID); ok {
				ch.(chan resultMsg) <- resultMsg{bin: &protocol.BinResult{Header: hdr, Data: payload}}
			}
			continue
		}
		var env protocol.Envelope
		if err := json.Unmarshal(data, &env); err != nil {
			log.Printf("ws: client %q sent malformed message; disconnecting", cl.info.ClientID)
			cl.kill()
			return
		}
		switch env.Type {
		case protocol.TypeResult:
			var res protocol.Result
			if err := json.Unmarshal(data, &res); err != nil {
				cl.kill()
				return
			}
			if ch, ok := cl.pending.LoadAndDelete(res.ID); ok {
				ch.(chan resultMsg) <- resultMsg{json: &res}
			}
		default:
			log.Printf("ws: client %q sent unexpected %q; disconnecting", cl.info.ClientID, env.Type)
			cl.kill()
			return
		}
	}
}

// heartbeat 空闲探活：连接静默达到 IdlePing 且无在途调用时 ping 一次，pong 在
// PongWait 内未到即判死。半开连接（对端已死但 TCP 层无感，典型如设备重启、
// NAT 静默丢弃）无法靠读循环发现，只能由服务端主动探测回收。
func (cl *client) heartbeat() {
	t := time.NewTicker(cl.reg.cfg.PongWait)
	defer t.Stop()
	for {
		select {
		case <-cl.dead:
			return
		case <-t.C:
		}
		if cl.pendingCount.Load() > 0 {
			continue
		}
		idle := time.Since(time.Unix(0, cl.lastActive.Load()))
		if idle < cl.reg.cfg.IdlePing {
			continue
		}
		pctx, cancel := context.WithTimeout(cl.ctx, cl.reg.cfg.PongWait)
		err := cl.conn.Ping(pctx)
		cancel()
		if err != nil {
			log.Printf("ws: client %q idle %s with no pong within %s; disconnecting",
				cl.info.ClientID, idle.Round(time.Second), cl.reg.cfg.PongWait)
			cl.kill()
			return
		}
		cl.lastActive.Store(time.Now().UnixNano())
	}
}

// kill 恰好执行一次：注销表项、关闭连接、终止派发，并触发下线回调。
func (cl *client) kill() {
	cl.deadOnce.Do(func() {
		close(cl.dead)
		cl.cancel()
		_ = cl.conn.CloseNow()
		cl.reg.unregister(cl)
		if cl.booted.Load() {
			log.Printf("ws: client %q disconnected", cl.info.ClientID)
			_, discHooks := cl.reg.hooks()
			for _, f := range discHooks {
				f(cl.info)
			}
		}
	})
}

func (cl *client) writeJSON(ctx context.Context, v any) error {
	data, err := json.Marshal(v)
	if err != nil {
		return err
	}
	return cl.writeRaw(websocket.MessageText, data)
}

// writeRaw 持写锁发出一条消息；大块数据按体量放宽写超时（每 MiB 加 10s），
// 慢链路上 4MiB 块不至于误杀。
func (cl *client) writeRaw(msgType websocket.MessageType, data []byte) error {
	timeout := writeTimeout
	if extra := len(data) / (1 << 20); extra > 0 {
		timeout += time.Duration(extra) * 10 * time.Second
	}
	wctx, cancel := context.WithTimeout(cl.ctx, timeout)
	defer cancel()
	cl.writeMu.Lock()
	defer cl.writeMu.Unlock()
	err := cl.conn.Write(wctx, msgType, data)
	if err == nil {
		cl.lastActive.Store(time.Now().UnixNano())
	}
	return err
}

func writeRaw(conn *websocket.Conn, ctx context.Context, v any) error {
	data, err := json.Marshal(v)
	if err != nil {
		return err
	}
	wctx, cancel := context.WithTimeout(ctx, writeTimeout)
	defer cancel()
	return conn.Write(wctx, websocket.MessageText, data)
}
