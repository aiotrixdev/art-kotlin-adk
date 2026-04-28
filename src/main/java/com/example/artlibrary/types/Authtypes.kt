package com.example.artlibrary.types

data class AdkConfig(
    val uri: String,
    val authToken: String,
    val root: String,
    val getCredentials: (() -> CredentialStore)? = null
)

data class CredentialStore(
    val environment: String,
    val projectKey: String,
    val orgTitle: String,
    val clientID: String,
    val clientSecret: String,
    val config: AdkConfig? = null,
    val accessToken: String? = null
)

data class AuthenticationConfig(
    var environment: String,
    var projectKey: String,
    var orgTitle: String,
    var clientID: String,
    var clientSecret: String,
    var config: AdkConfig? = null,
    var accessToken: String? = null,
    var getCredentials: (() -> CredentialStore)? = null
)

data class AuthData(
    val accessToken: String,
    val refreshToken: String
)

data class ConnectConfig(
    val restoreConnection: Boolean? = null
)
