package com.choury.bridgemcp

import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer

// bridge-mcp WebSocket 协议（与 bridge-mcp/internal/protocol 对应）：
// 文本帧承载 JSON 控制消息，二进制帧为 [4字节大端 headerLen][header JSON][raw 数据]。
object Protocol {
    const val TYPE_HELLO = "hello"
    const val TYPE_WELCOME = "welcome"
    const val TYPE_ERROR = "error"
    const val TYPE_CALL = "call"
    const val TYPE_RESULT = "result"

    fun hello(clientId: String, version: String, note: String, auth: String, capabilities: List<Capability>): JSONObject {
        val caps = JSONArray()
        for (c in capabilities) {
            val o = JSONObject()
            o.put("name", c.name)
            if (c.description.isNotEmpty()) o.put("description", c.description)
            if (c.inputSchema != null) o.put("input_schema", JSONObject(c.inputSchema))
            if (c.hidden) o.put("hidden", true)
            caps.put(o)
        }
        return JSONObject()
            .put("type", TYPE_HELLO)
            .put("client_id", clientId)
            .put("version", version)
            .put("note", note)
            .put("auth", auth)
            .put("capabilities", caps)
    }

    fun resultOk(id: String, value: JSONObject): JSONObject =
        JSONObject().put("type", TYPE_RESULT).put("id", id).put("ok", true).put("result", value)

    fun resultError(id: String, message: String): JSONObject =
        JSONObject().put("type", TYPE_RESULT).put("id", id).put("ok", false).put("error", message)

    fun binResultHeader(id: String, eof: Boolean, size: Long): JSONObject =
        JSONObject().put("type", TYPE_RESULT).put("id", id).put("ok", true)
            .put("eof", eof).put("size", size)

    fun encodeBinary(header: JSONObject, data: ByteArray): ByteArray {
        val hdr = header.toString().toByteArray(Charsets.UTF_8)
        val out = ByteArray(4 + hdr.size + data.size)
        ByteBuffer.wrap(out).putInt(hdr.size)
        System.arraycopy(hdr, 0, out, 4, hdr.size)
        System.arraycopy(data, 0, out, 4 + hdr.size, data.size)
        return out
    }

    fun decodeBinary(message: ByteArray): Pair<JSONObject, ByteArray> {
        if (message.size < 4) throw IllegalArgumentException("binary message too short")
        val headerLen = ByteBuffer.wrap(message).int
        if (headerLen < 0 || 4L + headerLen > message.size) {
            throw IllegalArgumentException("binary message header length out of range")
        }
        val header = JSONObject(String(message, 4, headerLen, Charsets.UTF_8))
        return header to message.copyOfRange(4 + headerLen, message.size)
    }
}

// 声明给 bridge 的一项能力；inputSchema 为 JSON Schema 文本，hidden 能力不注册为模型工具。
class Capability(
    val name: String,
    val description: String,
    val inputSchema: String? = null,
    val hidden: Boolean = false,
)

// 能力调用的返回：JSON 走文本帧 result；Bin 走二进制帧（read_chunk 场景，eof/size 随头返回）。
sealed class CapResult {
    class Json(val value: JSONObject) : CapResult()
    class Bin(val data: ByteArray, val eof: Boolean, val size: Long) : CapResult()
}

// data 非空表示二进制帧调用（bridge.push 推 4MiB 块给 file.write_chunk 的场景）。
typealias CapHandler = suspend (args: JSONObject, data: ByteArray?) -> CapResult
