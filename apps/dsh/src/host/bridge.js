/**
 * bridge 连接器：hello/welcome、1s→30s 退避重连、调用串行、二进制应答。
 */
import WebSocket from 'ws'

import { describeCapabilities, executeCapability } from './capabilities.js'
import { decodeBinary, encodeBinary } from './protocol.js'

const BACKOFF_MIN_MS = 1000
const BACKOFF_MAX_MS = 30000

/** http(s)→ws(s)，userinfo→Basic 头（与 Go provider 一致）。 */
export function parseServer(server) {
  const url = new URL(server)
  let headers
  if (url.username !== '' || url.password !== '') {
    const credentials = `${decodeURIComponent(url.username)}:${decodeURIComponent(url.password)}`
    headers = { Authorization: `Basic ${Buffer.from(credentials, 'utf8').toString('base64')}` }
    url.username = ''
    url.password = ''
  }
  if (url.protocol === 'http:') url.protocol = 'ws:'
  else if (url.protocol === 'https:') url.protocol = 'wss:'
  if (url.protocol !== 'ws:' && url.protocol !== 'wss:') {
    throw new Error(`unsupported protocol: ${url.protocol}`)
  }
  return { url: url.toString(), headers }
}

export class BridgeLink {
  constructor(options) {
    this.getConfig = options.getConfig
    this.log = options.log
    this.onOnline = options.onOnline
    /** @type {WebSocket|null} */
    this.socket = null
    /** @type {NodeJS.Timeout|null} */
    this.retryTimer = null
    this.attempt = 0
    this.shouldReconnect = false
    this.disposed = false
    /** 每次连接换代：旧代的在途调用一律丢弃，结果绝不发到新连接。 */
    this.generation = 0
    /** 当前代在途调用的 AbortController（断线/停止时整批中止）。 */
    this.controllers = new Set()
    this.queue = Promise.resolve()
    this.status = {
      kind: 'offline',
      detail: '未连接',
      since: new Date().toISOString(),
      connectedAt: null,
      version: null,
    }
  }

  setStatus(kind, detail, extra = {}) {
    this.status = {
      ...this.status,
      kind,
      detail,
      since: new Date().toISOString(),
      ...extra,
    }
  }

  snapshot() {
    return { ...this.status }
  }

  connect() {
    if (this.disposed) return
    this.shouldReconnect = true
    this.attempt = 0
    this.open()
  }

  disconnect() {
    this.shouldReconnect = false
    this.clearRetry()
    this.retireCalls()
    const socket = this.socket
    this.socket = null
    if (socket) {
      try {
        socket.close(1000, 'client stop')
      } catch {
      }
    }
    this.setStatus('offline', '已停止', { connectedAt: null })
    this.log('info', '已停止连接')
  }

  dispose() {
    this.disposed = true
    this.disconnect()
  }

  clearRetry() {
    if (this.retryTimer) {
      clearTimeout(this.retryTimer)
      this.retryTimer = null
    }
  }

  /**
   * 进入新的一代：中止在途调用、丢弃排队调用。
   * 队列重置后，新连接不会被旧连接上未完成的 exec 堵住。
   */
  retireCalls() {
    this.generation += 1
    const controllers = [...this.controllers]
    this.controllers.clear()
    for (const controller of controllers) {
      try {
        controller.abort()
      } catch {
        // 已结束
      }
    }
    this.queue = Promise.resolve()
  }

  /** 结果只允许回到发起调用时的那条连接。 */
  isCurrent(socket, generation) {
    return !this.disposed && generation === this.generation && socket === this.socket
  }

  open() {
    if (this.disposed) return
    this.retireCalls()
    this.clearRetry()
    const config = this.getConfig()

    if (!config.server) {
      this.setStatus('failed', '服务器地址为空')
      this.log('error', '服务器地址为空，无法连接')
      return
    }

    const previous = this.socket
    this.socket = null
    if (previous) {
      try {
        previous.close(1000, 'reconnect')
      } catch {
      }
    }

    let target
    try {
      target = parseServer(config.server)
    } catch (cause) {
      this.fail(`服务器地址无效：${cause.message}`)
      return
    }

    this.setStatus('connecting', `连接 ${config.server}`)
    this.log('info', `连接 ${config.server}（client_id=${config.clientId}）`)

    let socket
    try {
      socket = new WebSocket(target.url, {
        headers: target.headers,
        handshakeTimeout: 10000,
        maxPayload: 32 << 20,
      })
    } catch (cause) {
      this.fail(`无法建立 WebSocket：${cause.message}`)
      return
    }
    this.socket = socket

    socket.on('open', () => {
      if (this.socket !== socket) return
      const capabilities = describeCapabilities(config.capabilities)
      const hello = {
        type: 'hello',
        client_id: config.clientId,
        version: PLUGIN_VERSION,
        capabilities,
        ...(config.note ? { note: config.note } : {}),
        ...(config.token ? { auth: config.token } : {}),
      }
      try {
        socket.send(JSON.stringify(hello))
      } catch (cause) {
        this.fail(`发送 hello 失败：${cause.message}`)
        return
      }
      this.log('info', `已发送 hello：${capabilities.length} 项能力`)
    })

    socket.on('message', (data, isBinary) => {
      if (this.socket !== socket) return
      if (!isBinary) {
        this.handleText(data.toString(), socket)
        return
      }
      try {
        const { header, data: payload } = decodeBinary(data)
        this.enqueue(header, payload, socket)
      } catch (cause) {
        this.log('error', `无法解析二进制帧：${cause.message}`)
      }
    })

    socket.on('error', () => {})

    socket.on('close', (code, reason) => {
      if (this.socket !== socket) return
      this.socket = null
      this.retireCalls()
      if (!this.shouldReconnect) {
        this.setStatus('offline', '已停止', { connectedAt: null })
        return
      }
      this.scheduleReconnect(code, reason?.toString?.() ?? '')
    })
  }

  fail(message) {
    this.shouldReconnect = false
    this.retireCalls()
    this.clearRetry()
    const socket = this.socket
    this.socket = null
    if (socket) {
      try {
        socket.close(1000, 'failed')
      } catch {
      }
    }
    this.setStatus('failed', message, { connectedAt: null })
    this.log('error', message)
  }

  handleText(text, socket) {
    let message
    try {
      message = JSON.parse(text)
    } catch {
      this.log('warn', '收到无法解析的文本帧，已忽略')
      return
    }
    if (message?.type === 'welcome') {
      this.attempt = 0
      try {
        this.onOnline?.()
      } catch {
        // 持久化失败不影响连接
      }
      const version = typeof message.server_version === 'string' ? message.server_version : null
      this.setStatus('online', `已连接${version ? ` bridge ${version}` : ''}`, {
        connectedAt: new Date().toISOString(),
        version,
      })
      this.log('info', `bridge 握手成功${version ? `（server ${version}）` : ''}`)
      return
    }
    if (message?.type === 'error') {
      const code = message.code || 'error'
      this.fail(`bridge 拒绝连接：${code}${message.message ? `（${message.message}）` : ''}`)
      return
    }
    if (message?.type === 'call') {
      this.enqueue(message, undefined, socket)
      return
    }
    this.log('warn', `收到未知消息类型：${message?.type ?? '(none)'}`)
  }

  /**
   * 调用串行排队。
   * 入队时记住连接代号与来源 socket：断线/重连后旧代调用直接作废，
   * 不会把结果发到新连接上（bridge 新连接会复用 c1、c2… 编号）。
   */
  enqueue(call, data, socket) {
    const generation = this.generation
    const source = socket ?? this.socket
    this.queue = this.queue
      .then(() => {
        if (this.disposed || generation !== this.generation) return
        return this.dispatch(call, data, source, generation)
      })
      .catch(cause => this.log('error', `调用处理异常：${cause.message}`))
  }

  async dispatch(call, data, socket, generation) {
    if (!call || call.type !== 'call' || typeof call.id !== 'string') return
    if (!socket || !this.isCurrent(socket, generation)) return
    const capability = call.capability
    const startedAt = Date.now()
    const controller = new AbortController()
    this.controllers.add(controller)
    try {
      const result = await executeCapability(capability, call.arguments ?? {}, data, {
        signal: controller.signal,
      })
      if (!this.isCurrent(socket, generation)) {
        this.log('warn', `${capability} 已完成，但连接已更换，丢弃结果`)
        return
      }
      if (result && result.binary === true) {
        this.sendBinary(
          { type: 'result', id: call.id, ok: true, eof: result.eof === true, size: result.size },
          result.data,
          socket,
        )
      } else {
        this.sendJson({ type: 'result', id: call.id, ok: true, result }, socket)
      }
      this.log('info', `${capability} 完成（${Date.now() - startedAt}ms）`)
    } catch (cause) {
      if (!this.isCurrent(socket, generation)) return
      const message = cause instanceof Error ? cause.message : String(cause)
      this.sendJson({ type: 'result', id: call.id, ok: false, error: message }, socket)
      this.log('error', `${capability} 失败：${message}`)
    } finally {
      this.controllers.delete(controller)
    }
  }

  sendJson(message, socket = this.socket) {
    if (!socket || socket !== this.socket || socket.readyState !== WebSocket.OPEN) return
    try {
      socket.send(JSON.stringify(message), error => {
        if (error) this.log('error', `发送应答失败：${error.message}`)
      })
    } catch (cause) {
      this.log('error', `发送应答失败：${cause.message}`)
    }
  }

  sendBinary(header, data, socket = this.socket) {
    if (!socket || socket !== this.socket || socket.readyState !== WebSocket.OPEN) return
    try {
      socket.send(encodeBinary(header, data), error => {
        if (error) this.log('error', `发送二进制应答失败：${error.message}`)
      })
    } catch (cause) {
      this.log('error', `发送二进制应答失败：${cause.message}`)
    }
  }

  scheduleReconnect(code, reason) {
    this.attempt += 1
    const delay = Math.min(BACKOFF_MIN_MS * 2 ** (this.attempt - 1), BACKOFF_MAX_MS)
    const seconds = Math.round(delay / 100) / 10
    const detail = `连接断开（${code}${reason ? ` ${reason}` : ''}），${seconds}s 后重连`
    this.setStatus('reconnecting', detail, { connectedAt: null })
    this.log('warn', detail)
    this.clearRetry()
    this.retryTimer = setTimeout(() => {
      this.retryTimer = null
      if (this.shouldReconnect && !this.disposed) this.open()
    }, delay)
    this.retryTimer.unref?.()
  }
}
