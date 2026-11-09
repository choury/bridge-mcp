/**
 * 配置与日志；配置在 `$DSH_HOME/bridge-mcp/config.json`（0600）。
 */
import { chmodSync, existsSync, mkdirSync, readFileSync, renameSync, writeFileSync } from 'node:fs'
import { homedir, hostname } from 'node:os'
import { join } from 'node:path'

const CLIENT_ID_RE = /^[a-zA-Z0-9_-]{1,32}$/
const MAX_NOTE = 500
const MAX_LOGS = 300

/** 默认 client id：主机名原值。 */
function defaultClientId(name = hostname()) {
  const value = String(name).trim()
  return value === '' ? 'dsh-host' : value
}

function defaultConfig() {
  return {
    server: 'http://127.0.0.1:8765/ws',
    clientId: defaultClientId(),
    note: '',
    token: '',
    enabled: false,
    capabilities: { exec: true, file: true },
  }
}

function asBoolean(value, fallback) {
  if (typeof value === 'boolean') return value
  if (value === 'true' || value === 1) return true
  if (value === 'false' || value === 0) return false
  return fallback
}

/** 校验并归一化配置；错误信息直接给 UI。 */
function normalizeConfig(input = {}, base = defaultConfig()) {
  const merged = {
    ...base,
    ...input,
    capabilities: { ...base.capabilities, ...(input.capabilities ?? {}) },
  }

  const server = typeof merged.server === 'string' ? merged.server.trim() : ''
  if (server !== '') {
    let url
    try {
      url = new URL(server)
    } catch {
      throw new Error('服务器地址不是合法 URL，应形如 http://host:8765/ws')
    }
    if (!['http:', 'https:', 'ws:', 'wss:'].includes(url.protocol)) {
      throw new Error('服务器地址必须是 http://、https://、ws:// 或 wss://')
    }
  }

  const clientId = typeof merged.clientId === 'string' ? merged.clientId.trim() : ''
  if (!CLIENT_ID_RE.test(clientId)) {
    throw new Error('Client ID 只能包含字母、数字、下划线和短横线，长度 1-32')
  }

  const note = typeof merged.note === 'string' ? merged.note.trim() : ''
  if ([...note].length > MAX_NOTE) throw new Error(`备注最多 ${MAX_NOTE} 个字符`)

  const token = typeof merged.token === 'string' ? merged.token.trim() : ''

  return {
    server,
    clientId,
    note,
    token,
    enabled: asBoolean(merged.enabled, false),
    capabilities: {
      exec: asBoolean(merged.capabilities.exec, true),
      file: asBoolean(merged.capabilities.file, true),
    },
  }
}

function timestamp() {
  const now = new Date()
  const pad = n => String(n).padStart(2, '0')
  return `${pad(now.getHours())}:${pad(now.getMinutes())}:${pad(now.getSeconds())}`
}

export class Store {
  constructor(options = {}) {
    const home = options.home || process.env.DSH_HOME || join(homedir(), '.dsh')
    this.directory = join(home, 'bridge-mcp')
    this.file = join(this.directory, 'config.json')
    this._config = defaultConfig()
    this._logs = []
    this.load()
  }

  get config() {
    return this._config
  }

  load() {
    try {
      if (!existsSync(this.file)) return
      this._config = normalizeConfig(JSON.parse(readFileSync(this.file, 'utf8')), defaultConfig())
    } catch (cause) {
      this.log('warn', `读取配置失败，使用默认值：${cause.message}`)
    }
  }

  save(patch = {}) {
    const next = normalizeConfig(patch, this._config)
    this.persist(next)
    this._config = next
    return next
  }

  persist(config) {
    if (!existsSync(this.directory)) mkdirSync(this.directory, { recursive: true, mode: 0o700 })
    const temp = `${this.file}.tmp-${process.pid}`
    writeFileSync(temp, `${JSON.stringify(config, null, 2)}\n`, { mode: 0o600 })
    renameSync(temp, this.file)
    try {
      chmodSync(this.file, 0o600)
    } catch {
      // 某些文件系统不支持 chmod
    }
  }

  log(level, message) {
    this._logs.push(`[${timestamp()}] ${level.toUpperCase()} ${message}`)
    if (this._logs.length > MAX_LOGS) this._logs.splice(0, this._logs.length - MAX_LOGS)
  }

  logs() {
    return [...this._logs]
  }

  clearLogs() {
    this._logs.length = 0
  }
}
