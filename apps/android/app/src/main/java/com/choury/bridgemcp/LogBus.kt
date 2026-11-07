package com.choury.bridgemcp

import android.text.format.DateFormat
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.util.Date

// 服务与能力处理器向 UI 广播日志的通道；replay 保证 Activity 重开后能看到历史。
object LogBus {
    private val _flow = MutableSharedFlow<String>(
        replay = 300, extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val flow: SharedFlow<String> = _flow

    fun log(message: String) {
        _flow.tryEmit("${DateFormat.format("HH:mm:ss", Date())}  $message")
    }
}
