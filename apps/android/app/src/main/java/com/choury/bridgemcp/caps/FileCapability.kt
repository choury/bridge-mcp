package com.choury.bridgemcp.caps

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import com.choury.bridgemcp.BridgeApp
import com.choury.bridgemcp.CapHandler
import com.choury.bridgemcp.Capability
import com.choury.bridgemcp.CapResult
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

// 文件能力：file.op（list/stat/rename/rm）+ 分块原语（hidden）。bridge 依据 chunk 原语自动合成
// file.push / file.pull，静态 bridge.push / bridge.pull 也可用。
// 范围：授予「所有文件访问」后为整个共享存储；未授予时直接报错指引。
object FileCapability {

    private const val READ_CHUNK_MAX = 8L shl 20

    private val OP_SCHEMA = """{
        "type": "object",
        "properties": {
            "action": {"type": "string", "enum": ["list", "stat", "mkdir", "rename", "rm"], "description": "list: list a directory; stat: file or dir info; mkdir: create a directory (no error if it already exists, parents created only with recursive); rename: move within shared storage; rm: delete"},
            "path": {"type": "string", "description": "target path; for rename this is the source"},
            "to": {"type": "string", "description": "destination path, required for rename"},
            "recursive": {"type": "boolean", "default": false, "description": "rm: delete a directory tree instead of just files and empty dirs; mkdir: also create missing parent directories"}
        },
        "required": ["action", "path"]
    }"""

    private val WRITE_CHUNK_SCHEMA = """{
        "type": "object",
        "properties": {
            "path": {"type": "string", "description": "path to write"},
            "offset": {"type": "integer", "minimum": 0, "description": "0 creates the file (fails if exists); N>0 requires the file to be exactly N bytes (append continuation), else errors"}
        },
        "required": ["path", "offset"]
    }"""

    private val READ_CHUNK_SCHEMA = """{
        "type": "object",
        "properties": {
            "path": {"type": "string", "description": "path to read"},
            "offset": {"type": "integer", "minimum": 0, "description": "byte offset to read from"},
            "length": {"type": "integer", "minimum": 1, "maximum": 8388608, "default": 4194304, "description": "max bytes to read"}
        },
        "required": ["path", "offset"]
    }"""

    fun register(add: (Capability, CapHandler) -> Unit) {
        add(
            Capability(
                name = "file.op",
                description = "File operations on this phone's shared storage: list (list a directory), stat (existence/type/size/mtime), mkdir (create a directory), rename (move), rm (delete).",
                inputSchema = OP_SCHEMA,
            ),
            { args, _ -> op(args) },
        )
        add(
            Capability(
                name = "file.write_chunk",
                description = "Write one raw binary chunk at offset (bridge-internal chunked transfer).",
                inputSchema = WRITE_CHUNK_SCHEMA,
                hidden = true,
            ),
            { args, data -> writeChunk(args, data) },
        )
        add(
            Capability(
                name = "file.read_chunk",
                description = "Read one raw binary chunk (bridge-internal chunked transfer).",
                inputSchema = READ_CHUNK_SCHEMA,
                hidden = true,
            ),
            { args, _ -> readChunk(args) },
        )
    }

    private fun requireStorageAccess() {
        val granted = if (Build.VERSION.SDK_INT >= 30) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                BridgeApp.instance, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
        if (!granted) {
            throw IOException("all-files access not granted; grant it in the app first")
        }
    }

    private fun op(args: JSONObject): CapResult {
        requireStorageAccess()
        val path = args.optString("path")
        if (path.isBlank()) throw IOException("path must not be empty")
        return when (val action = args.optString("action")) {
            "list" -> list(args)
            "stat" -> stat(args)
            "mkdir" -> mkdir(args)
            "rename" -> rename(args)
            "rm" -> rm(args)
            else -> throw IOException("invalid action \"$action\"; expected list|stat|mkdir|rename|rm")
        }
    }

    private fun list(args: JSONObject): CapResult {
        val path = args.optString("path")
        val dir = File(path)
        if (!dir.isDirectory) throw IOException("$path is not a directory")
        val entries = JSONArray()
        for (f in dir.listFiles().orEmpty().sortedBy { it.name }) {
            entries.put(
                JSONObject()
                    .put("name", f.name)
                    .put("type", if (f.isDirectory) "dir" else "file")
                    .put("size", f.length())
                    .put("mtime", f.lastModified())
            )
        }
        return CapResult.Json(
            JSONObject().put("path", path).put("count", entries.length()).put("entries", entries)
        )
    }

    private fun stat(args: JSONObject): CapResult {
        val path = args.optString("path")
        val file = File(path)
        if (!file.exists()) {
            return CapResult.Json(JSONObject().put("path", path).put("exists", false))
        }
        return CapResult.Json(
            JSONObject()
                .put("path", path)
                .put("exists", true)
                .put("type", if (file.isDirectory) "dir" else "file")
                .put("size", file.length())
                .put("mtime", file.lastModified())
        )
    }

    private fun mkdir(args: JSONObject): CapResult {
        val path = args.optString("path")
        val file = File(path)
        val created = if (args.optBoolean("recursive", false)) file.mkdirs() else file.mkdir()
        if (!file.isDirectory && !created) throw IOException("mkdir failed: $path")
        return CapResult.Json(JSONObject().put("ok", true).put("path", path))
    }

    private fun rename(args: JSONObject): CapResult {
        val path = args.optString("path")
        val to = args.optString("to")
        if (to.isBlank()) throw IOException("rename requires \"to\"")
        if (!File(path).renameTo(File(to))) throw IOException("rename failed: $path -> $to")
        return CapResult.Json(JSONObject().put("ok", true).put("path", path).put("to", to))
    }

    private fun rm(args: JSONObject): CapResult {
        val path = args.optString("path")
        val file = File(path)
        if (args.optBoolean("recursive", false)) {
            file.walkBottomUp().forEach { if (!it.delete()) throw IOException("rm failed: ${it.path}") }
        } else if (!file.delete()) {
            throw IOException("rm failed: $path")
        }
        return CapResult.Json(JSONObject().put("ok", true).put("path", path))
    }

    // 追加式契约与 Go provider 一致：offset 0 独占创建（已存在即报错）；
    // offset>0 要求文件当前大小恰好等于 offset，续传点错位直接报错。
    private fun writeChunk(args: JSONObject, data: ByteArray?): CapResult {
        requireStorageAccess()
        val path = args.optString("path")
        if (path.isBlank()) throw IOException("path must not be empty")
        val offset = args.getLong("offset")
        val file = File(path)
        if (offset == 0L) {
            if (!file.createNewFile()) throw IOException("$path already exists")
            if (data != null) file.outputStream().use { it.write(data) }
        } else {
            RandomAccessFile(file, "rw").use { raf ->
                if (raf.length() != offset) {
                    throw IOException("offset $offset does not match file size ${raf.length()}")
                }
                if (data != null) {
                    raf.seek(offset)
                    raf.write(data)
                }
            }
        }
        return CapResult.Json(JSONObject().put("bytes_written", data?.size ?: 0))
    }

    private fun readChunk(args: JSONObject): CapResult {
        requireStorageAccess()
        val path = args.optString("path")
        if (path.isBlank()) throw IOException("path must not be empty")
        val offset = args.getLong("offset")
        var length = args.optLong("length", 0)
        if (length <= 0 || length > READ_CHUNK_MAX) length = READ_CHUNK_MAX

        val file = File(path)
        if (!file.isFile) throw IOException("$path is not a file")
        val size = file.length()
        val remaining = size - offset
        if (remaining <= 0) return CapResult.Bin(ByteArray(0), eof = true, size = size)

        val count = minOf(length, remaining).toInt()
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(offset)
            val buf = ByteArray(count)
            raf.readFully(buf)
            val eof = offset + count >= size
            return CapResult.Bin(buf, eof = eof, size = size)
        }
    }
}
