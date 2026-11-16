package com.example.artlibrary.agentic

import android.os.Build
import androidx.annotation.RequiresApi
import com.example.artlibrary.websockets.Socket
import com.example.artlibrary.websockets.Subscription
import kotlinx.coroutines.*

// agentic/channelEntity.kt
abstract class BaseWorkflow(protected val socket: Socket) {

    protected var subscription: Subscription? = null
    private var subscribeJob: Deferred<Subscription>? = null

    protected abstract fun channelName(): String

    @RequiresApi(Build.VERSION_CODES.O)
    open fun connect(): BaseWorkflow {
        if (subscribeJob == null) {
            subscribeJob = CoroutineScope(Dispatchers.Default).async {
                try {
                    val sub = socket.subscribe(channelName()) as Subscription
                    subscription = sub
                    sub
                } catch (error: Throwable) {
                    subscribeJob = null
                    throw error
                }
            }
        }
        return this
    }

    /** @internal */
    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun getSubscription(): Subscription {
        socket.waitForConnection()
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