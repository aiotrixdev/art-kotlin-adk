package com.example.artlibrary.agentic

import android.os.Build
import androidx.annotation.RequiresApi
import com.example.artlibrary.storage.FileRef
import com.example.artlibrary.storage.ListFilesResult
import com.example.artlibrary.storage.ListOptions
import com.example.artlibrary.storage.Storage
import com.example.artlibrary.storage.UploadOptions
import com.example.artlibrary.websockets.Socket

class Agent(
    val agentId: String,
    socket: Socket
) : BaseWorkflow(socket) {

    override fun channelName(): String = "agent_com_$agentId"

    override fun connect(): Agent {
        super.connect()
        return this
    }

    fun thread(threadId: String? = null): AgentThread = AgentThread(this, threadId)

    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun upload(file: ByteArray, opts: UploadOptions = UploadOptions()): FileRef =
        Storage().upload(file, opts.copy(configId = agentId))

    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun listFiles(opts: ListOptions = ListOptions()): ListFilesResult =
        Storage().listFiles(opts.copy(configId = agentId))
}
