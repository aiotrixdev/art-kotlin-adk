package com.example.artlibrary.agentic

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
}
