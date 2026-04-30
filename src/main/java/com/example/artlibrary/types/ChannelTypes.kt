package com.example.artlibrary.types

data class ChannelConfig(
    val channelName: String,
    val channelNamespace: String,
    val channelType: String,
    val presenceUsers: List<String> = emptyList(),
    val snapshot: Any?,
    val subscriptionID: String? = null
)
