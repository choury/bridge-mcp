/**
 * exec：按平台选 shell 执行，超时杀整个进程组/进程树，输出各截 32KB（缺省 30s，1s–10min）。
 */
import { spawn, spawnSync } from 'node:child_process'
import { existsSync, statSync } from 'node:fs'
import { basename } from 'node:path'

const MAX_STREAM = 32 * 1024

const IS_WIN = process.platform === 'win32'

function selectShell() {
  if (!IS_WIN) {
    const file = ['/bin/bash', '/usr/bin/bash', '/bin/sh'].find(candidate => existsSync(candidate)) || '/bin/sh'
    return { file, args: ['-lc'], label: `${basename(file)} -lc (login shell)` }
  }
  return {
    file: process.env.ComSpec || 'cmd.exe',
    args: ['/d', '/s', '/c'],
    // cmd /c 对以引号开头且多于两个引号的命令会剥掉首尾引号，`"C:\Program Files\x.exe" a "b"` 被拆坏；call 前缀规避
    transform: command => (command.startsWith('"') ? `call ${command}` : command),
    // cmd 的 &、>、| 不能被 Node 当普通参数转义，必须原样拼接
    verbatim: true,
    label: 'cmd.exe (Windows cmd syntax)',
  }
}

const SHELL = selectShell()

/** 能力描述用：告知 agent 实际面对的 shell 与语法。 */
export const SHELL_LABEL = SHELL.label

const STRICT_UTF8 = new TextDecoder('utf-8', { fatal: true, ignoreBOM: true })

let oemDecoder
/** cmd 内建命令往管道写的是 OEM 代码页（chcp 改不了）；映射到 TextDecoder 标签，探测失败返回 null。 */
function getOemDecoder() {
  if (oemDecoder !== undefined) return oemDecoder
  oemDecoder = null
  if (IS_WIN) {
    try {
      const probe = spawnSync(process.env.ComSpec || 'cmd.exe', ['/d', '/c', 'chcp'], { timeout: 3000, windowsHide: true })
      // 输出本身是 OEM 编码，但代码页编号是 ASCII，用 latin1 取数字即可
      const match = /(\d+)\s*$/m.exec(probe.stdout.toString('latin1'))
      const labels = { 866: 'ibm866', 874: 'windows-874', 932: 'shift_jis', 936: 'gb18030', 949: 'euc-kr', 950: 'big5' }
      const label = match && labels[Number(match[1])]
      if (label) oemDecoder = new TextDecoder(label)
    } catch {
      // 探测失败就按 UTF-8 容错解码
    }
  }
  return oemDecoder
}

/** 剥掉截断制造的不完整序列：开头的孤儿续字节（≤3）、结尾缺字节的起始序列；否则合法 UTF-8 流会被误判成 OEM。 */
function trimCharBoundary(buffer) {
  let start = 0
  let end = buffer.length
  while (start < 3 && start < end && (buffer[start] & 0xc0) === 0x80) start++
  for (let i = 1; i <= 3 && i <= end - start; i++) {
    const byte = buffer[end - i]
    if ((byte & 0xc0) === 0x80) continue
    const need = byte >= 0xf0 ? 4 : byte >= 0xe0 ? 3 : byte >= 0xc0 ? 2 : 1
    if (i < need) end -= i
    break
  }
  return buffer.subarray(start, end)
}

/** 输出解码：现代工具走管道就是 UTF-8，先严格解；解不动说明是遗留工具的 OEM 字节，退 OEM。 */
function decodeOutput(buffer) {
  const trimmed = trimCharBoundary(buffer)
  try {
    return STRICT_UTF8.decode(trimmed)
  } catch {
    const fallback = getOemDecoder()
    return fallback ? fallback.decode(trimmed) : trimmed.toString('utf8')
  }
}

/** 按保头/保尾累积一条流，并记录是否截断。 */
function createSink(keepTail) {
  /** @type {Buffer[]} */
  let chunks = []
  let kept = 0
  let total = 0
  return {
    push(chunk) {
      total += chunk.length
      if (!keepTail) {
        if (kept >= MAX_STREAM) return
        const room = MAX_STREAM - kept
        const part = chunk.length > room ? chunk.subarray(0, room) : chunk
        chunks.push(part)
        kept += part.length
        return
      }
      chunks.push(chunk)
      kept += chunk.length
      while (kept > MAX_STREAM && chunks.length > 0) {
        const excess = kept - MAX_STREAM
        const first = chunks[0]
        if (first.length <= excess) {
          chunks.shift()
          kept -= first.length
        } else {
          chunks[0] = first.subarray(excess)
          kept -= excess
        }
      }
    },
    read() {
      return {
        text: decodeOutput(Buffer.concat(chunks)),
        truncated: total > kept,
      }
    },
  }
}

/** 杀掉整个进程组/进程树；child 自身已退出时组内可能仍有持有管道的后台孤儿（`xxx &`），仍须击杀。 */
function killGroup(child) {
  if (!child) return
  if (IS_WIN) {
    // Windows 没有进程组，taskkill /T 按进程树击杀；root 已死则无法遍历树，孤儿由 grace timer 收敛
    if (typeof child.pid === 'number' && child.pid > 0 && child.exitCode === null) {
      // error 必须消费：未监听的 spawn error 会抛 uncaughtException
      spawn('taskkill', ['/pid', String(child.pid), '/T', '/F'], { stdio: 'ignore', windowsHide: true }).on('error', () => {})
    }
    return
  }
  if (typeof child.pid === 'number' && child.pid > 0) {
    try {
      process.kill(-child.pid, 'SIGKILL')
      return
    } catch {
      // 没有独立进程组（或已消失）时退回单进程 kill
    }
  }
  if (child.exitCode === null) {
    try {
      child.kill('SIGKILL')
    } catch {
      // 进程已经没了
    }
  }
}

/** 执行一次命令；options.signal 用于断线/停止时中止整个进程组。 */
export async function runExec(args = {}, options = {}) {
  const { signal } = options
  if (signal?.aborted) throw new Error('exec aborted')
  const command = typeof args.command === 'string' ? args.command : ''
  if (command.trim() === '') throw new Error('command must be a non-empty string')

  let cwd
  if (args.workdir !== undefined && args.workdir !== null && args.workdir !== '') {
    cwd = String(args.workdir)
    let stat
    try {
      stat = statSync(cwd)
    } catch {
      throw new Error(`workdir does not exist: ${cwd}`)
    }
    if (!stat.isDirectory()) throw new Error(`workdir is not a directory: ${cwd}`)
  }

  // 与 Go 对齐：缺省 30s；显式给值时收敛到 [1s, 10min]
  let timeoutMs = 30000
  if (Number.isFinite(args.timeout_ms)) {
    timeoutMs = Math.floor(args.timeout_ms)
    if (timeoutMs < 1000) timeoutMs = 1000
    if (timeoutMs > 10 * 60 * 1000) timeoutMs = 10 * 60 * 1000
  }
  const keepTail = args.tail === true

  return await new Promise((resolve, reject) => {
    let child
    try {
      child = spawn(SHELL.file, [...SHELL.args, SHELL.transform ? SHELL.transform(command) : command], {
        cwd,
        detached: !IS_WIN,
        env: process.env,
        stdio: ['ignore', 'pipe', 'pipe'],
        ...(SHELL.verbatim ? { windowsVerbatimArguments: true } : {}),
        ...(IS_WIN ? { windowsHide: true } : {}),
      })
    } catch (cause) {
      reject(new Error(`failed to start ${SHELL.file}: ${cause.message}`))
      return
    }

    const stdout = createSink(keepTail)
    const stderr = createSink(keepTail)
    let timedOut = false
    let settled = false
    /** @type {NodeJS.Timeout | undefined} */
    let timer
    /** @type {NodeJS.Timeout | undefined} */
    let graceTimer

    function finish() {
      if (timer) clearTimeout(timer)
      if (graceTimer) clearTimeout(graceTimer)
      signal?.removeEventListener('abort', onAbort)
    }

    timer = setTimeout(() => {
      timedOut = true
      teardown()
    }, timeoutMs)

    child.stdout?.on('data', chunk => stdout.push(chunk))
    child.stderr?.on('data', chunk => stderr.push(chunk))

    const snapshot = () => {
      const out = stdout.read()
      const err = stderr.read()
      return {
        exit_code: child.exitCode ?? -1,
        timed_out: timedOut,
        stdout: out.text,
        stderr: err.text,
        truncated: out.truncated || err.truncated,
      }
    }

    /**
     * 断线/超时收敛：杀掉进程组后给一个短 grace，等 close 自然到达；
     * 孙进程逃出进程组且继续持有 stdout/stderr 时 close 永远不来，
     * grace 到点销毁流强制收敛。
     */
    const teardown = () => {
      if (settled) return
      killGroup(child)
      if (graceTimer) return
      graceTimer = setTimeout(() => {
        if (settled) return
        settled = true
        finish()
        try {
          child.stdout?.destroy?.()
        } catch {
          // 流已关闭
        }
        try {
          child.stderr?.destroy?.()
        } catch {
          // 流已关闭
        }
        resolve(snapshot())
      }, 2000)
      graceTimer.unref?.()
    }

    const onAbort = () => teardown()

    child.on('error', cause => {
      if (settled) return
      settled = true
      finish()
      reject(new Error(`failed to run command: ${cause.message}`))
    })

    child.on('close', code => {
      if (settled) return
      settled = true
      finish()
      const out = stdout.read()
      const err = stderr.read()
      resolve({
        exit_code: typeof code === 'number' ? code : -1,
        timed_out: timedOut,
        stdout: out.text,
        stderr: err.text,
        truncated: out.truncated || err.truncated,
      })
    })

    signal?.addEventListener('abort', onAbort, { once: true })
    if (signal?.aborted) onAbort()
  })
}
