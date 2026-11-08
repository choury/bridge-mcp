package com.choury.bridgemcp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

// 一次连接的生命周期：连入 → hello → welcome → 串行处理 call。
// bridge 对同一 client 严格 FIFO 派发，这里顺序 await 每个 handler 即可对齐。
class BridgeConnection(
    private val serverUrl: String,
    private val helloMessage: JSONObject,
    private val handlers: Map<String, CapHandler>,
    private val onWelcomed: () -> Unit,
    private val onDown: () -> Unit,
) : WebSocketListener() {

    class ServerRejected(code: String, message: String) :
        IOException("server rejected: [$code] $message")

    // WebSocket 握手被 HTTP 层拒绝（401/403/404/407/502…）：状态文案只留状态码，原始细节留在 cause
    class HttpRejected(code: Int, cause: Throwable) : IOException("HTTP($code)", cause)

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val opened = CompletableDeferred<Unit>()
    private val textMessages = Channel<String>(Channel.UNLIMITED)
    private val binaryMessages = Channel<Pair<JSONObject, ByteArray>>(Channel.UNLIMITED)
    private var ws: WebSocket? = null

    override fun onOpen(webSocket: WebSocket, response: Response) {
        opened.complete(Unit)
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        textMessages.trySend(text)
    }

    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
        try {
            binaryMessages.trySend(Protocol.decodeBinary(bytes.toByteArray()))
        } catch (e: Exception) {
            LogBus.log("dropping malformed binary frame: ${e.message}")
        }
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        webSocket.close(1000, null)
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        textMessages.close()
        binaryMessages.close()
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        opened.completeExceptionally(
            if (response != null && response.code != 101) HttpRejected(response.code, t) else t
        )
        textMessages.close()
        binaryMessages.close()
    }

    // 阻塞至连接结束（断线/关闭/异常）；成功握手后回调 onWelcomed。
    suspend fun serve() {
        // URL userinfo（https://user:pass@host/…）转 Basic 认证头
        val url = serverUrl.toHttpUrl()
        val request = Request.Builder().url(url)
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) {
            request.header("Authorization", Credentials.basic(url.username, url.password))
        }
        val ws = client.newWebSocket(request.build(), this)
        this.ws = ws
        try {
            opened.await()
            ws.send(helloMessage.toString())

            val first = withTimeout(10_000) { textMessages.receive() }
            val envelope = JSONObject(first)
            when (envelope.optString("type")) {
                Protocol.TYPE_WELCOME -> {}
                Protocol.TYPE_ERROR -> throw ServerRejected(
                    envelope.optString("code"), envelope.optString("message")
                )
                else -> throw IOException("unexpected first message: ${envelope.optString("type")}")
            }
            onWelcomed()

            coroutineScope {
                val binaryJob = launch {
                    for ((header, payload) in binaryMessages) handleCall(header, payload)
                }
                for (text in textMessages) {
                    val envelope = JSONObject(text)
                    if (envelope.optString("type") == Protocol.TYPE_CALL) handleCall(envelope, null)
                }
                binaryJob.cancel()
            }
            onDown()
        } finally {
            ws.cancel()
        }
    }

    fun shutdown() {
        textMessages.close()
        binaryMessages.close()
        ws?.close(1000, null)
        ws?.cancel()
    }

    private suspend fun handleCall(call: JSONObject, data: ByteArray?) {
        val id = call.optString("id")
        val capability = call.optString("capability")
        val args = call.optJSONObject("arguments") ?: JSONObject()
        val startedAt = System.currentTimeMillis()
        val result = try {
            val handler = handlers[capability]
                ?: throw IllegalArgumentException("unknown capability \"$capability\"")
            handler(args, data)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LogBus.log("$capability ✗ ${System.currentTimeMillis() - startedAt}ms：${e.message}")
            ws?.send(Protocol.resultError(id, e.message ?: e.toString()).toString())
            return
        }
        when (result) {
            is CapResult.Json -> {
                ws?.send(Protocol.resultOk(id, result.value).toString())
                LogBus.log("$capability ✓ ${System.currentTimeMillis() - startedAt}ms")
            }
            is CapResult.Bin -> {
                ws?.send(
                    Protocol.encodeBinary(Protocol.binResultHeader(id, result.eof, result.size), result.data)
                        .toByteString()
                )
                LogBus.log("$capability ✓ ${System.currentTimeMillis() - startedAt}ms (${result.data.size} bytes)")
            }
        }
    }
}
