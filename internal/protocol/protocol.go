// Package protocol 定义 bridge server 与 provider client 之间 WebSocket 连接上的
// JSON 消息格式，以及大块数据的二进制帧格式。
package protocol

import (
	"encoding/binary"
	"encoding/json"
	"errors"
)

// 消息类型。server → client: welcome / error / call；client → server: hello / result。
const (
	TypeWelcome = "welcome"
	TypeError   = "error"
	TypeCall    = "call"

	TypeHello  = "hello"
	TypeResult = "result"
)

// 错误码。
const (
	CodeDuplicateClientID = "duplicate_client_id"
	CodeBadHandshake      = "bad_handshake"
	CodeUnauthorized      = "unauthorized"
)

// Envelope 所有消息共用的类型信封，先解出 Type 再解具体消息。
type Envelope struct {
	Type string `json:"type"`
}

// Capability provider 声明的一项能力，server 据此注册 MCP 工具。
type Capability struct {
	Name        string          `json:"name"`
	Description string          `json:"description,omitempty"`
	InputSchema json.RawMessage `json:"input_schema,omitempty"`
	// Hidden 能力不注册为模型可见的 MCP 工具，仅供 bridge 内部调用
	// （如分块传输的 write_chunk/read_chunk）。
	Hidden bool `json:"hidden,omitempty"`
}

// Hello client 连入后的首条消息，声明身份与全部能力。
type Hello struct {
	Type         string       `json:"type"`
	ClientID     string       `json:"client_id"`
	Note         string       `json:"note,omitempty"`
	Auth         string       `json:"auth,omitempty"`
	Capabilities []Capability `json:"capabilities"`
}

// Welcome server 对 hello 的应答。
type Welcome struct {
	Type          string `json:"type"`
	ServerVersion string `json:"server_version"`
}

// ErrorMsg server 拒绝连接或报告协议错误时发送，随后关闭连接。
type ErrorMsg struct {
	Type    string `json:"type"`
	Code    string `json:"code"`
	Message string `json:"message"`
}

// Call server 转发给 client 的能力调用请求。
type Call struct {
	Type       string          `json:"type"`
	ID         string          `json:"id"`
	Capability string          `json:"capability"`
	Arguments  json.RawMessage `json:"arguments,omitempty"`
}

// Result client 对 Call 的应答，按 ID 关联。
type Result struct {
	Type   string          `json:"type"`
	ID     string          `json:"id"`
	OK     bool            `json:"ok"`
	Result json.RawMessage `json:"result,omitempty"`
	Error  string          `json:"error,omitempty"`
}

// 二进制帧：承载大块数据。
// 布局：[4字节大端 headerLen][header JSON][raw data]。
// header 的 type 语义与文本消息一致：server→client 为 call，client→server 为 result。

type BinResultHeader struct {
	Type  string `json:"type"`
	ID    string `json:"id"`
	OK    bool   `json:"ok"`
	EOF   bool   `json:"eof,omitempty"`
	Size  int64  `json:"size,omitempty"` // 数据来源的文件总大小（read_chunk 提供）
	Error string `json:"error,omitempty"`
}

// BinResult 二进制结果：header + 原始数据。
type BinResult struct {
	Header BinResultHeader
	Data   []byte
}

func EncodeBinaryMessage(header any, data []byte) ([]byte, error) {
	hdr, err := json.Marshal(header)
	if err != nil {
		return nil, err
	}
	buf := make([]byte, 4+len(hdr)+len(data))
	binary.BigEndian.PutUint32(buf[:4], uint32(len(hdr)))
	copy(buf[4:], hdr)
	copy(buf[4+len(hdr):], data)
	return buf, nil
}

func DecodeBinaryMessage(msg []byte, header any) (data []byte, err error) {
	if len(msg) < 4 {
		return nil, errors.New("binary message too short")
	}
	n := binary.BigEndian.Uint32(msg[:4])
	if uint64(n)+4 > uint64(len(msg)) {
		return nil, errors.New("binary message header length out of range")
	}
	if err := json.Unmarshal(msg[4:4+n], header); err != nil {
		return nil, err
	}
	return msg[4+n:], nil
}
