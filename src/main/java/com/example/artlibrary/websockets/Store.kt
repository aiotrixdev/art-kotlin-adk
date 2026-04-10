package com.example.artlibrary.websockets

import com.example.artlibrary.types.ChannelConfig
import java.util.concurrent.ConcurrentHashMap

class ConnectionStore private constructor() {

    private val channels: MutableMap<String, ChannelConfig> = ConcurrentHashMap()
    private val publicKeys: MutableMap<String, String> = ConcurrentHashMap()

    companion object {
        @Volatile
        private var instance: ConnectionStore? = null

        fun getInstance(): ConnectionStore {
            return instance ?: synchronized(this) {
                instance ?: ConnectionStore().also { instance = it }
            }
        }
    }

    fun addChannel(channel: String, channelConfig: ChannelConfig) {
        channels[channel] = channelConfig
    }

    fun getChannel(channel: String): ChannelConfig? {
        return channels[channel]
    }

    fun deleteChannel(channel: String): Boolean {
        return channels.remove(channel) != null
    }

    fun addKey(username: String, key: String) {
        publicKeys[username] = key
    }

    fun getKey(username: String): String? {
        return publicKeys[username]
    }
}
