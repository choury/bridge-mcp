/**
 * 二进制帧：[4 字节大端 headerLen][header JSON][raw data]。
 */
export function encodeBinary(header, data) {
  const hdr = Buffer.from(JSON.stringify(header), 'utf8')
  const body = data === undefined || data === null ? Buffer.alloc(0) : Buffer.from(data)
  const frame = Buffer.allocUnsafe(4 + hdr.length + body.length)
  frame.writeUInt32BE(hdr.length, 0)
  hdr.copy(frame, 4)
  body.copy(frame, 4 + hdr.length)
  return frame
}

export function decodeBinary(frame) {
  const buf = Buffer.from(frame)
  if (buf.length < 4) throw new Error('binary frame too short')
  const headerLength = buf.readUInt32BE(0)
  if (headerLength + 4 > buf.length) throw new Error('binary frame header length out of range')
  let header
  try {
    header = JSON.parse(buf.subarray(4, 4 + headerLength).toString('utf8'))
  } catch (cause) {
    throw new Error(`binary frame header is not JSON: ${cause.message}`)
  }
  return { header, data: buf.subarray(4 + headerLength) }
}
