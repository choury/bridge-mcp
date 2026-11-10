/**
 * 能力声明与分发；只声明原语，file.push/file.pull 由 bridge 合成。
 */
import { SHELL_LABEL, runExec } from './exec.js'
import { readChunk, replaceText, writeChunk } from './files.js'

const execCapability = {
  name: 'exec',
  description: `Run a shell command on this DSH host via ${SHELL_LABEL}. Returns exit code, stdout, stderr and truncation flags. stdout/stderr are each capped at 32KB (head by default; tail:true keeps the tail).`,
  input_schema: {
    type: 'object',
    properties: {
      command: { type: 'string', description: 'shell command to run' },
      workdir: { type: 'string', description: 'working directory; must exist' },
      timeout_ms: { type: 'integer', minimum: 1000, maximum: 600000, default: 30000, description: 'kill the whole process group/tree after this many milliseconds' },
      tail: { type: 'boolean', default: false, description: 'keep the last 32KB instead of the first 32KB of each stream' },
    },
    required: ['command'],
  },
}

const replaceCapability = {
  name: 'file.replace',
  description: 'Replace one exact occurrence of old text with new text in a UTF-8 file (atomic write). The match is byte-exact, so mind CRLF line endings on Windows. Errors when old is missing, matches several places (unless all:true), or the file is not UTF-8.',
  input_schema: {
    type: 'object',
    properties: {
      path: { type: 'string', description: 'file path on this host' },
      old: { type: 'string', minLength: 1, description: 'exact text to find' },
      new: { type: 'string', description: 'replacement text' },
      all: { type: 'boolean', default: false, description: 'replace every occurrence' },
    },
    required: ['path', 'old', 'new'],
  },
}

const writeChunkCapability = {
  name: 'file.write_chunk',
  description: 'Append one binary chunk at offset. offset 0 creates the file exclusively (fails if it exists); offset N requires the file to be exactly N bytes.',
  hidden: true,
  input_schema: {
    type: 'object',
    properties: {
      path: { type: 'string', description: 'file path on this host' },
      offset: { type: 'integer', minimum: 0, description: 'byte offset to append at' },
    },
    required: ['path', 'offset'],
  },
}

const readChunkCapability = {
  name: 'file.read_chunk',
  description: 'Read one binary chunk (at most 8MiB) from a file. Replies with a binary frame carrying eof and the total file size.',
  hidden: true,
  input_schema: {
    type: 'object',
    properties: {
      path: { type: 'string', description: 'file path on this host' },
      offset: { type: 'integer', minimum: 0, description: 'byte offset to read from' },
      length: { type: 'integer', minimum: 1, maximum: 8388608, default: 4194304, description: 'maximum bytes to read' },
    },
    required: ['path', 'offset'],
  },
}

/** 按开关生成 hello 里声明的能力。 */
export function describeCapabilities(enabled = {}) {
  const capabilities = []
  if (enabled.file) {
    capabilities.push(replaceCapability, writeChunkCapability, readChunkCapability)
  }
  if (enabled.exec) capabilities.push(execCapability)
  return capabilities
}

/** 返回 `{binary:true, ...}` 表示应答应走二进制帧。 */
export async function executeCapability(capability, args = {}, data = undefined, options = {}) {
  switch (capability) {
    case 'exec':
      return await runExec(args, options)
    case 'file.replace':
      return await replaceText(args)
    case 'file.write_chunk':
      return await writeChunk(args, data)
    case 'file.read_chunk': {
      const chunk = await readChunk(args)
      return { binary: true, data: chunk.data, eof: chunk.eof, size: chunk.size }
    }
    default:
      throw new Error(`unknown capability: ${capability}`)
  }
}
