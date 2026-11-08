package com.choury.bridgemcp.caps

import com.choury.bridgemcp.Capability
import com.choury.bridgemcp.CapHandler
import com.choury.bridgemcp.CapResult
import com.choury.bridgemcp.adb.AdbClient
import org.json.JSONObject
import java.io.IOException

// exec：经本机 adbd 以 shell uid 执行命令
// schema 对齐 Go provider（cmd/bridge-provider），无 workdir，命令原样透传。
object ExecCapability {

    private const val DEFAULT_TIMEOUT = 30_000L
    private const val MAX_TIMEOUT = 600_000L
    private const val AUTH_TIMEOUT = 90_000L

    private val EXEC_SCHEMA = """{
        "type": "object",
        "properties": {
            "command": {"type": "string", "description": "shell command line to execute (/system/bin/sh -c, as the shell user)"},
            "timeout_ms": {"type": "integer", "minimum": 1000, "maximum": 600000, "default": 30000, "description": "execution timeout in milliseconds"},
            "tail": {"type": "boolean", "default": false, "description": "keep the last 32KB of stdout/stderr instead of the first 32KB"}
        },
        "required": ["command"]
    }"""

    fun register(add: (Capability, CapHandler) -> Unit, port: Int) {
        add(
            Capability(
                name = "exec",
                description = "Run a shell command on this device as the shell user via adb (adbd on localhost). Returns exit_code, timed_out, stdout, stderr, truncated.",
                inputSchema = EXEC_SCHEMA,
            ),
            { args, _ -> exec(args, port) },
        )
    }

    // 预授权：与本机 adbd 完成 ADB 协议握手，若需授权则在 90s 内等待用户在弹窗确认，确认后密钥持久化。
    fun preAuthorize(port: Int) {
        AdbClient.preAuthorize(port = port, authTimeoutMs = AUTH_TIMEOUT)
    }

    private fun exec(args: JSONObject, port: Int): CapResult {
        val command = args.optString("command")
        if (command.isBlank()) throw IOException("command must not be empty")
        val timeoutMs = args.optLong("timeout_ms", DEFAULT_TIMEOUT).coerceIn(1_000L, MAX_TIMEOUT)
        val tail = args.optBoolean("tail", false)

        val result = AdbClient.exec(
            port = port,
            command = command,
            timeoutMs = timeoutMs,
            tail = tail
        )

        return CapResult.Json(
            JSONObject()
                .put("exit_code", result.exitCode)
                .put("timed_out", result.timedOut)
                .put("stdout", result.stdout)
                .put("stderr", result.stderr)
                .put("truncated", result.truncated)
        )
    }
}
