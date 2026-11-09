/**
 * 设置页 RPC：独占 `POST /api/dsh-bridge-mcp`（shared /api 只允许一个 interceptor，
 * 所以用 exact Fetch route），信封与鉴权由 DSH Connection 处理。
 */
const RPC_PATH = '/api/dsh-bridge-mcp'
const RPC_METHOD = 'dsh-bridge-mcp'

function failure(code, message) {
  return { ok: false, error: { code, message, details: {} } }
}

function envelope(rpcId, result) {
  return Response.json({ type: 'server-response', rpcId, result })
}

function isRecord(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}

/** 注册端点；返回 disposer。 */
export function registerRpc(ctx, store, link) {
  const state = () => ({
    config: store.config,
    status: link.snapshot(),
    logs: store.logs(),
  })

  const dispatch = async (action, input) => {
    switch (action) {
      case 'state':
        return { ok: true, value: state() }
      case 'save':
        store.save(input)
        return { ok: true, value: state() }
      case 'connect': {
        const config = store.save({ ...input })
        if (!config.server) throw new Error('请先填写服务器地址')
        store.log('info', '开始连接（来自设置页）')
        link.connect()
        return { ok: true, value: state() }
      }
      case 'disconnect':
        link.disconnect()
        store.save({ enabled: false })
        return { ok: true, value: state() }
      case 'clear-logs':
        store.clearLogs()
        return { ok: true, value: state() }
      default:
        return failure('not-found', `unknown action: ${action}`)
    }
  }

  const handle = async request => {
    if (request.method !== 'POST') return new Response('method not allowed', { status: 405 })
    const contentType = request.headers.get('content-type')?.split(';', 1)[0]?.trim().toLowerCase()
    if (contentType !== 'application/json') {
      return new Response('content type must be application/json', { status: 415 })
    }
    let message
    try {
      message = await request.json()
    } catch {
      return new Response('body is not JSON', { status: 400 })
    }

    const rpcId = typeof message?.rpcId === 'string' ? message.rpcId : 'invalid-request'
    if (message?.type !== 'client-request' || typeof message.rpcId !== 'string') {
      return envelope(rpcId, failure('gateway/bad-request', 'invalid client-request envelope'))
    }
    if (message.method !== RPC_METHOD) {
      return envelope(rpcId, failure(
        'gateway/bad-request',
        `method ${JSON.stringify(message.method)} does not match endpoint ${JSON.stringify(RPC_METHOD)}`,
      ))
    }
    const call = message.payload
    if (!isRecord(call) || typeof call.action !== 'string') {
      return envelope(rpcId, failure('bad-request', 'payload.action must be a string'))
    }

    try {
      return envelope(rpcId, await dispatch(call.action, isRecord(call.payload) ? call.payload : {}))
    } catch (cause) {
      return envelope(rpcId, failure('bad-request', cause instanceof Error ? cause.message : String(cause)))
    }
  }

  const dispose = ctx.connection.fetch.register({
    path: RPC_PATH,
    methods: ['POST'],
    requestBody: 'buffered',
    fetch: handle,
  })
  return () => {
    try {
      dispose?.()
    } catch {
      // 已释放
    }
  }
}
