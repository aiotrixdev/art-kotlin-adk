package com.example.artlibrary.agentic

import AgentThread
import BaseWorkflow
import Socket

class Agent(
    val agentId: String,
    socket: Socket
) : BaseWorkflow(socket) {

    override fun channelName(): String = "agent_com_$agentId"

      override fun connect(): Agent {
        super.connect()
        return this
    }

    fun thread(): AgentThread = AgentThread(this)
}
