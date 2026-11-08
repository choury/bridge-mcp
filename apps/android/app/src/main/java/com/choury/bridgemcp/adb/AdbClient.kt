package com.choury.bridgemcp.adb

import com.choury.bridgemcp.BridgeApp
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class ExecResult(
    val exitCode: Int,
    val timedOut: Boolean,
    val stdout: String,
    val stderr: String,
    val truncated: Boolean
)

object AdbClient {

    private const val DEFAULT_CONNECT_TIMEOUT_MS = 5_000
    private const val DEFAULT_AUTH_TIMEOUT_MS = 10_000L
    private const val MAX_OUTPUT_BYTES = 32 shl 10

    /**
     * Pre-authorize with adbd on localhost.
     * If the device has not authorized this app yet, adbd will prompt the user
     * with the "Allow USB debugging?" dialog. We wait up to authTimeoutMs (default 90s)
     * for user authorization. Once authorized, a verification command ("echo ok") is run.
     */
    fun preAuthorize(port: Int, host: String = "127.0.0.1", authTimeoutMs: Long = 90_000L) {
        val key = AdbKey.loadOrGenerate(BridgeApp.instance)
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), DEFAULT_CONNECT_TIMEOUT_MS)
            socket.soTimeout = authTimeoutMs.toInt()

            val session = handshake(socket, key, authTimeoutMs)
            // Verify shell access
            val result = session.exec("echo ok", timeoutMs = 15_000L, tail = false)
            if (result.timedOut) {
                throw IOException("adb pre-auth test command timed out")
            }
            if (result.stdout.trim() != "ok") {
                throw IOException("adb pre-auth test command failed: ${result.stdout} ${result.stderr}".trim())
            }
        }
    }

    /**
     * Runs a shell command on localhost via ADB and returns the result.
     */
    fun exec(
        port: Int,
        command: String,
        timeoutMs: Long,
        tail: Boolean,
        host: String = "127.0.0.1"
    ): ExecResult {
        val key = AdbKey.loadOrGenerate(BridgeApp.instance)
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), DEFAULT_CONNECT_TIMEOUT_MS)
            socket.soTimeout = DEFAULT_AUTH_TIMEOUT_MS.toInt()

            val session = handshake(socket, key, DEFAULT_AUTH_TIMEOUT_MS)
            return session.exec(command, timeoutMs, tail)
        }
    }

    private fun handshake(socket: Socket, key: AdbKey, authTimeoutMs: Long): AdbSession {
        val input = socket.getInputStream()
        val output = socket.getOutputStream()

        // 1. Send CNXN
        val ident = "host::bridge-mcp\u0000".toByteArray(Charsets.UTF_8)
        AdbMessage.write(output, AdbProtocol.CMD_CNXN, AdbProtocol.CONNECT_VERSION, AdbProtocol.MAX_PAYLOAD, ident)

        // 2. Handle incoming AUTH or CNXN
        var message = AdbMessage.read(input)
        if (message.command == AdbProtocol.CMD_AUTH) {
            if (message.arg0 != AdbProtocol.AUTH_TYPE_TOKEN) {
                throw IOException("Unsupported ADB auth challenge type: ${message.arg0}")
            }

            // Sign token with our persisted key
            val signature = key.sign(message.payload)
            AdbMessage.write(output, AdbProtocol.CMD_AUTH, AdbProtocol.AUTH_TYPE_SIGNATURE, 0, signature)

            message = AdbMessage.read(input)
            if (message.command == AdbProtocol.CMD_AUTH) {
                // Signature not yet in /data/misc/adb/adb_keys; send public key to prompt dialog
                AdbMessage.write(output, AdbProtocol.CMD_AUTH, AdbProtocol.AUTH_TYPE_RSAPUBLICKEY, 0, key.publicKeyPayload)
                // Block until user taps Allow or timeout occurs
                socket.soTimeout = authTimeoutMs.toInt()
                message = try {
                    AdbMessage.read(input)
                } catch (e: SocketTimeoutException) {
                    throw IOException("adb handshake timed out; authorize in the device dialog within ${authTimeoutMs / 1000}s", e)
                }
            }
        }

        if (message.command != AdbProtocol.CMD_CNXN) {
            throw IOException("ADB connection failed: received cmd=0x${Integer.toHexString(message.command)}")
        }

        val banner = String(message.payload, Charsets.UTF_8)
        val features = parseFeatures(banner)
        return AdbSession(socket, features)
    }

    private fun parseFeatures(banner: String): Set<String> {
        val featurePart = banner.substringAfter("features=", "")
        if (featurePart.isEmpty()) return emptySet()
        val list = featurePart.substringBefore(";").split(",")
        return list.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    internal class AdbSession(
        private val socket: Socket,
        private val features: Set<String>
    ) : Closeable {
        private val input = socket.getInputStream()
        private val output = socket.getOutputStream()

        fun exec(command: String, timeoutMs: Long, tail: Boolean): ExecResult {
            val useV2 = features.contains("shell_v2")
            val destination = if (useV2) {
                "shell,v2,raw:$command\u0000"
            } else {
                "shell:$command\u0000"
            }

            val localId = 1
            AdbMessage.write(output, AdbProtocol.CMD_OPEN, localId, 0, destination.toByteArray(Charsets.UTF_8))

            val deadlineMs = System.currentTimeMillis() + timeoutMs
            var remoteId = 0
            var openAcked = false
            val stdoutStream = ByteArrayOutputStream()
            val stderrStream = ByteArrayOutputStream()
            var exitCode = -1
            var timedOut = false

            val v2Buffer = ByteArrayOutputStream()

            try {
                while (true) {
                    val remainingMs = (deadlineMs - System.currentTimeMillis()).coerceAtLeast(1)
                    socket.soTimeout = remainingMs.toInt().coerceAtLeast(10)

                    val msg = AdbMessage.read(input)
                    when (msg.command) {
                        AdbProtocol.CMD_OKAY -> {
                            remoteId = msg.arg0
                            openAcked = true
                        }
                        AdbProtocol.CMD_WRTE -> {
                            if (!openAcked) {
                                remoteId = msg.arg0
                                openAcked = true
                            }
                            if (msg.payload.isNotEmpty()) {
                                if (useV2) {
                                    v2Buffer.write(msg.payload)
                                    val data = v2Buffer.toByteArray()
                                    var offset = 0
                                    while (offset + 5 <= data.size) {
                                        val id = data[offset].toInt() and 0xff
                                        val len = ByteBuffer.wrap(data, offset + 1, 4).order(ByteOrder.LITTLE_ENDIAN).int
                                        if (len < 0 || offset + 5 + len > data.size) {
                                            break // wait for rest of packet
                                        }
                                        val payload = data.copyOfRange(offset + 5, offset + 5 + len)
                                        when (id) {
                                            AdbProtocol.V2_STDOUT -> stdoutStream.write(payload)
                                            AdbProtocol.V2_STDERR -> stderrStream.write(payload)
                                            AdbProtocol.V2_EXIT -> {
                                                exitCode = if (payload.isNotEmpty()) payload[0].toInt() and 0xff else 0
                                            }
                                        }
                                        offset += 5 + len
                                    }
                                    if (offset > 0) {
                                        val remaining = data.copyOfRange(offset, data.size)
                                        v2Buffer.reset()
                                        v2Buffer.write(remaining)
                                    }
                                } else {
                                    stdoutStream.write(msg.payload)
                                }
                            }
                            // Flow control: acknowledge received data
                            AdbMessage.write(output, AdbProtocol.CMD_OKAY, localId, remoteId)
                        }
                        AdbProtocol.CMD_CLSE -> {
                            AdbMessage.write(output, AdbProtocol.CMD_CLSE, localId, remoteId)
                            if (!useV2 && exitCode == -1) {
                                exitCode = 0
                            }
                            break
                        }
                    }
                }
            } catch (e: SocketTimeoutException) {
                timedOut = true
                exitCode = -1
            } finally {
                close()
            }

            val (stdoutStr, outTrunc) = cap(stdoutStream.toByteArray(), tail)
            val (stderrStr, errTrunc) = cap(stderrStream.toByteArray(), tail)

            return ExecResult(
                exitCode = exitCode,
                timedOut = timedOut,
                stdout = stdoutStr,
                stderr = stderrStr,
                truncated = outTrunc || errTrunc
            )
        }

        private fun cap(data: ByteArray, tail: Boolean): Pair<String, Boolean> {
            return if (data.size <= MAX_OUTPUT_BYTES) {
                String(data, Charsets.UTF_8) to false
            } else {
                val cut = if (tail) {
                    data.copyOfRange(data.size - MAX_OUTPUT_BYTES, data.size)
                } else {
                    data.copyOf(MAX_OUTPUT_BYTES)
                }
                String(cut, Charsets.UTF_8) to true
            }
        }

        override fun close() {
            try {
                socket.close()
            } catch (_: Exception) {}
        }
    }
}
