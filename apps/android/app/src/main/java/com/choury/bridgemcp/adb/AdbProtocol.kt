package com.choury.bridgemcp.adb

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object AdbProtocol {
    const val CMD_SYNC = 0x434e5953
    const val CMD_CNXN = 0x4e584e43
    const val CMD_AUTH = 0x48545541
    const val CMD_OPEN = 0x4e45504f
    const val CMD_OKAY = 0x59414b4f
    const val CMD_CLSE = 0x45534c43
    const val CMD_WRTE = 0x45545257

    const val AUTH_TYPE_TOKEN = 1
    const val AUTH_TYPE_SIGNATURE = 2
    const val AUTH_TYPE_RSAPUBLICKEY = 3

    const val CONNECT_VERSION = 0x01000000
    const val MAX_PAYLOAD = 1048576

    const val V2_STDOUT = 1
    const val V2_STDERR = 2
    const val V2_EXIT = 3
    const val V2_CLOSE_STDIN = 4
    const val V2_WINDOW_SIZE_CHANGE = 5
}

data class AdbMessage(
    val command: Int,
    val arg0: Int,
    val arg1: Int,
    val dataLength: Int,
    val dataCheck: Int,
    val magic: Int,
    val payload: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as AdbMessage
        return command == other.command &&
                arg0 == other.arg0 &&
                arg1 == other.arg1 &&
                dataLength == other.dataLength &&
                dataCheck == other.dataCheck &&
                magic == other.magic &&
                payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = command
        result = 31 * result + arg0
        result = 31 * result + arg1
        result = 31 * result + dataLength
        result = 31 * result + dataCheck
        result = 31 * result + magic
        result = 31 * result + payload.contentHashCode()
        return result
    }

    companion object {
        fun read(input: InputStream): AdbMessage {
            val header = ByteArray(24)
            var read = 0
            while (read < 24) {
                val r = input.read(header, read, 24 - read)
                if (r < 0) throw EOFException("Unexpected EOF while reading ADB message header (read $read/24)")
                read += r
            }
            val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            val cmd = buf.getInt()
            val arg0 = buf.getInt()
            val arg1 = buf.getInt()
            val len = buf.getInt()
            val check = buf.getInt()
            val magic = buf.getInt()
            if (cmd xor -0x1 != magic) {
                throw IllegalStateException("Corrupt ADB packet: magic mismatch (cmd=0x${Integer.toHexString(cmd)}, magic=0x${Integer.toHexString(magic)})")
            }
            val payload = if (len > 0) {
                val p = ByteArray(len)
                var pRead = 0
                while (pRead < len) {
                    val r = input.read(p, pRead, len - pRead)
                    if (r < 0) throw EOFException("Unexpected EOF while reading ADB payload ($pRead/$len)")
                    pRead += r
                }
                p
            } else {
                ByteArray(0)
            }
            return AdbMessage(cmd, arg0, arg1, len, check, magic, payload)
        }

        fun write(output: OutputStream, command: Int, arg0: Int, arg1: Int, payload: ByteArray? = null) {
            val len = payload?.size ?: 0
            var check = 0
            if (payload != null) {
                for (b in payload) check += (b.toInt() and 0xff)
            }
            val buf = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            buf.putInt(command)
            buf.putInt(arg0)
            buf.putInt(arg1)
            buf.putInt(len)
            buf.putInt(check)
            buf.putInt(command xor -0x1)
            output.write(buf.array())
            if (payload != null && len > 0) {
                output.write(payload)
            }
            output.flush()
        }
    }
}
