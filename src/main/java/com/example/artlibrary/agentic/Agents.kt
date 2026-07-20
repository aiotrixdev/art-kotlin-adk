package com.example.artlibrary.agentic

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
}
