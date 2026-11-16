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
    val orchestratorId: String, socket: Socket
) : BaseWorkflow(socket) {

    init {
        validateExecutionReference(orchestratorId)
    }

    override fun channelName(): String {
        return "orch_com_$orchestratorId"
    }

    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun thread(thread_id: String? = null): OrchestratorThread {
        val sub = getSubscription()

        // Bypass channelConfig.orchestratorEnabled gate: this code path is
        // entered only via adk.orchestrator(id), which is itself the opt-in.
        return sub.threadUnchecked(thread_id)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun upload(file: ByteArray, opts: UploadOptions = UploadOptions()): FileRef =
        Storage().upload(file, opts.copy(configId = orchestratorId))

    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun listFiles(opts: ListOptions = ListOptions()): ListFilesResult =
        Storage().listFiles(opts.copy(configId = orchestratorId))
}
