package com.example.artlibrary.agentic

import android.os.Build
import androidx.annotation.RequiresApi
import com.example.artlibrary.storage.FileRef
import com.example.artlibrary.storage.ListFilesResult
import com.example.artlibrary.storage.ListOptions
import com.example.artlibrary.storage.Storage
import com.example.artlibrary.storage.UploadOptions
import com.example.artlibrary.websockets.Socket

class Orchestrator(
    val orchestratorId: String,
    socket: Socket
) : BaseWorkflow(socket) {

    override fun channelName(): String = "orch_com_$orchestratorId"

    override fun connect(): Orchestrator {
        super.connect()
        return this
    }

    suspend fun thread(threadId: String? = null): OrchestratorThread {
        val sub = getSubscription()
        return OrchestratorThread(sub, threadId)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun upload(file: ByteArray, opts: UploadOptions = UploadOptions()): FileRef =
        Storage().upload(file, opts.copy(configId = orchestratorId))

    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun listFiles(opts: ListOptions = ListOptions()): ListFilesResult =
        Storage().listFiles(opts.copy(configId = orchestratorId))
}
