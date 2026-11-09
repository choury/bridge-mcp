/**
 * 文件原语：分块读写 + 精确替换（语义对齐 Go provider）。
 */
import { promises as fsp } from 'node:fs'
import { basename, dirname, join } from 'node:path'
import { randomBytes } from 'node:crypto'

const MAX_CHUNK = 8 * 1024 * 1024

function requirePath(value) {
  if (typeof value !== 'string' || value === '') throw new Error('path must not be empty')
  return value
}

function requireOffset(value) {
  if (value === undefined || value === null) return 0
  if (!Number.isSafeInteger(value) || value < 0) throw new Error('offset must be a non-negative integer')
  return value
}

/** 读取一段；offset 到末尾时返回空块 + eof。 */
export async function readChunk(args = {}) {
  const path = requirePath(args.path)
  const offset = requireOffset(args.offset)
  let length = Number.isSafeInteger(args.length) && args.length > 0 ? args.length : MAX_CHUNK
  if (length > MAX_CHUNK) length = MAX_CHUNK

  const stat = await fsp.stat(path)
  if (stat.isDirectory()) throw new Error(`${path} is a directory`)
  const remaining = stat.size - offset
  if (remaining <= 0) return { data: Buffer.alloc(0), eof: true, size: stat.size }

  const want = Math.min(length, remaining)
  const handle = await fsp.open(path, 'r')
  try {
    const buffer = Buffer.alloc(want)
    let read = 0
    while (read < want) {
      const { bytesRead } = await handle.read(buffer, read, want - read, offset + read)
      if (bytesRead === 0) break
      read += bytesRead
    }
    return { data: buffer.subarray(0, read), eof: offset + read >= stat.size, size: stat.size }
  } finally {
    await handle.close()
  }
}

/** 追加式写入：offset 0 独占创建，offset N 要求大小恰好为 N。 */
export async function writeChunk(args = {}, data) {
  const path = requirePath(args.path)
  const offset = requireOffset(args.offset)
  const payload = data === undefined || data === null ? Buffer.alloc(0) : Buffer.from(data)

  if (offset === 0) {
    try {
      // wx：目标已存在时抛 EEXIST，绝不静默覆盖
      await fsp.writeFile(path, payload, { flag: 'wx', mode: 0o644 })
    } catch (cause) {
      if (cause.code === 'EEXIST') throw new Error(`${path} already exists; refusing to overwrite`)
      throw cause
    }
  } else {
    const handle = await fsp.open(path, 'r+')
    try {
      const stat = await handle.stat()
      if (stat.size !== offset) {
        throw new Error(`offset ${offset} does not match file size ${stat.size}`)
      }
      if (payload.length > 0) await handle.write(payload, 0, payload.length, offset)
    } finally {
      await handle.close()
    }
  }
  return { bytes_written: payload.length }
}

/** 临时文件 + rename 原子写回，保留权限位。 */
async function atomicWrite(path, text, mode) {
  const temp = join(dirname(path), `.${basename(path)}.replace-${randomBytes(6).toString('hex')}`)
  const targetMode = mode & 0o7777
  try {
    await fsp.writeFile(temp, text, { mode: targetMode })
    // writeFile 的 mode 会被 umask 削掉，显式 chmod 才能像 Go 实现一样保留权限位
    await fsp.chmod(temp, targetMode)
    await fsp.rename(temp, path)
  } catch (cause) {
    await fsp.unlink(temp).catch(() => {})
    throw cause
  }
}

/** 精确替换：old 唯一命中，或 all:true 全替换。 */
export async function replaceText(args = {}) {
  const path = requirePath(args.path)
  const oldText = args.old
  if (typeof oldText !== 'string' || oldText === '') {
    throw new Error('path and old must not be empty')
  }
  const newText = args.new
  if (typeof newText !== 'string') throw new Error('new must be a string')

  const handle = await fsp.open(path, 'r')
  let raw
  let mode
  try {
    const stat = await handle.stat()
    if (!stat.isFile()) throw new Error(`${path} is not a regular file`)
    mode = stat.mode
    raw = await handle.readFile()
  } finally {
    await handle.close()
  }

  let text
  try {
    // ignoreBOM: true 保留文件开头的 BOM，与 Go 的 bytes.Replace 行为一致
    text = new TextDecoder('utf-8', { fatal: true, ignoreBOM: true }).decode(raw)
  } catch {
    throw new Error('not a UTF-8 text file; binary files must go through bridge.pull/push')
  }

  const occurrences = text.split(oldText).length - 1
  if (occurrences === 0) throw new Error(`old not found in ${path}`)
  if (occurrences > 1 && args.all !== true) {
    throw new Error(`old occurs ${occurrences} times in ${path}; pass all=true to replace every occurrence`)
  }

  const output = args.all === true
    ? text.split(oldText).join(newText)
    : text.slice(0, text.indexOf(oldText)) + newText + text.slice(text.indexOf(oldText) + oldText.length)

  await atomicWrite(path, output, mode)
  return { replaced: occurrences, size: Buffer.byteLength(output) }
}
