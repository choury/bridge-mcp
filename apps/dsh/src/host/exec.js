/**
 * exec：login shell 执行，超时杀进程组，输出各截 32KB（缺省 30s，1s–10min）。
 */
import { spawn } from 'node:child_process'
import { existsSync, statSync } from 'node:fs'

const MAX_STREAM = 32 * 1024

const SHELL = ['/bin/bash', '/usr/bin/bash', '/bin/sh'].find(candidate => existsSync(candidate)) || '/bin/sh'

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
        text: Buffer.concat(chunks).toString('utf8'),
        truncated: total > kept,
      }
    },
  }
}

/** 杀掉整个进程组。 */
function killGroup(child) {
  if (!child || child.exitCode !== null) return
  if (typeof child.pid === 'number' && child.pid > 0) {
    try {
      process.kill(-child.pid, 'SIGKILL')
      return
    } catch {
      // 没有独立进程组（或已消失）时退回单进程 kill
    }
  }
  try {
    child.kill('SIGKILL')
  } catch {
    // 进程已经没了
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
      child = spawn(SHELL, ['-lc', command], {
        cwd,
        detached: process.platform !== 'win32',
        env: process.env,
        stdio: ['ignore', 'pipe', 'pipe'],
      })
    } catch (cause) {
      reject(new Error(`failed to start ${SHELL}: ${cause.message}`))
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
      killGroup(child)
    }, timeoutMs)

    child.stdout?.on('data', chunk => stdout.push(chunk))
    child.stderr?.on('data', chunk => stderr.push(chunk))

    const snapshot = () => {
      const out = stdout.read()
      const err = stderr.read()
      return {
        exit_code: -1,
        timed_out: timedOut,
        stdout: out.text,
        stderr: err.text,
        truncated: out.truncated || err.truncated,
      }
    }

    /**
     * 断线/停止：杀掉进程组；再给一个短 grace，防止孙进程逃出进程组
     * 且继续持有 stdout/stderr，导致 close 永远不来、调用永不收敛。
     */
    const onAbort = () => {
      if (settled) return
      killGroup(child)
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
