package com.example.artlibrary.agentic

import com.example.artlibrary.websockets.Socket
import com.example.artlibrary.websockets.Subscription
import kotlinx.coroutines.*

// agentic/channelEntity.kt
abstract class BaseWorkflow(protected val socket: Socket) {

    protected var subscription: Subscription? = null
    private var subscribeJob: Deferred<Subscription>? = null

    protected abstract fun channelName(): String

    open fun connect(): BaseWorkflow {
        if (subscribeJob == null) {
            subscribeJob = CoroutineScope(Dispatchers.Default).async {
                val sub = socket.subscribe(channelName()) as Subscription
                subscription = sub
                sub
            }
        }
        return this
    }

    /** @internal */
    suspend fun getSubscription(): Subscription {
        subscription?.let { return it }
        if (subscribeJob == null) connect()
        val job = subscribeJob!!
        return try {
            job.await()
        } catch (e: Exception) {
            if (subscribeJob === job) subscribeJob = null
            throw e
        }
    }
}