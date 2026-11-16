package com.example.artlibrary.websockets

import android.os.Build
import androidx.annotation.RequiresApi
import com.example.artlibrary.types.ConnectorField
import com.example.artlibrary.types.ConnectorProfile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

data class ConnectorProfileResponse(
    val status: String,
    val error: String? = null,
    val connector_id: String? = null,
    val provider: String? = null,
    val allowed_fields: List<ConnectorField>? = null,
    val metadata: Map<String, String>? = null
)

/** A connector-scoped profile for the currently authenticated ART user. */
class Connector(
    val connectorId: String,
    private val socket: Socket
) {
    private var profilePromise = CompletableDeferred<ConnectorProfile>()

    init {
        require(connectorId.isNotEmpty()) {
            "connectorId must be a non-empty string"
        }
    }

    /**
     * Starts the existence/schema lookup immediately.
     * Call this after construction to initialize the profile.
     */
    fun initializeProfile(scope: CoroutineScope) {
        if (profilePromise.isCompleted) return

        scope.launch(Dispatchers.IO) {
            try {
                val response = request("describe")
                profilePromise.complete(toProfile(response))
            } catch (error: Throwable) {
                profilePromise.completeExceptionally(error)
            }
        }
    }

    /** Resolve the connector, its allowed metadata fields, and current values. */
    suspend fun profile(): ConnectorProfile {
        return profilePromise.await()
    }

    /**
     * Partially update the authenticated user's metadata for this connector.
     * The server repeats all validation before persisting the merged profile.
     */
    suspend fun updateProfile(
        metadata: Map<String, String>
    ): ConnectorProfile {

        val profile = profilePromise.await()

        val allowed = profile.allowedFields.associateBy { it.key }

        for ((key, _) in metadata) {
            if (!allowed.containsKey(key)) {
                throw IllegalArgumentException(
                    "Metadata field \"$key\" is not allowed for connector $connectorId"
                )
            }
        }

        val response = request("update", metadata)
        val updated = toProfile(response)

        profilePromise = CompletableDeferred(updated)

        return updated
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private suspend fun request(
        action: String,
        metadata: Map<String, String>? = null
    ): ConnectorProfileResponse {

        socket.waitForConnection()

        val payload = mutableMapOf<String, Any?>(
            "action" to action,
            "connector_id" to connectorId
        )

        if (metadata != null) {
            payload["metadata"] = metadata
        }

        val response = socket.pushForSecureLine(
            event = "connector-profile",
            data = payload,
            listen = true
        )

        val responseData = when (response) {
            is Map<*, *> -> response["data"] ?: response
            else -> response
        }

        return responseData.toConnectorProfileResponse()
    }

    private fun toProfile(
        response: ConnectorProfileResponse
    ): ConnectorProfile {

        if (response.status != "successful") {
            throw IllegalStateException(
                response.error
                    ?: "Connector $connectorId could not be resolved"
            )
        }

        return ConnectorProfile(
            connectorId = response.connector_id ?: connectorId,
            provider = response.provider ?: "",
            allowedFields = response.allowed_fields ?: emptyList(),
            metadata = response.metadata ?: emptyMap()
        )
    }

    private fun Any?.toConnectorProfileResponse(): ConnectorProfileResponse {
        if (this is ConnectorProfileResponse) return this

        val map = this as? Map<*, *>
            ?: throw IllegalStateException(
                "Invalid connector profile response"
            )

        val fields = (map["allowed_fields"] as? List<*>)
            ?.mapNotNull { item ->
                when (item) {
                    is ConnectorField -> item

                    is Map<*, *> -> ConnectorField(
                        key = item["key"]?.toString()
                            ?: return@mapNotNull null,
                        label = item["label"]?.toString() ?: "",
                        type = item["type"]?.toString() ?: "string",
                        placeholder = item["placeholder"]?.toString(),
                        required = item["required"] as? Boolean,
                        description = item["description"]?.toString()
                    )

                    else -> null
                }
            }

        val metadataMap = (map["metadata"] as? Map<*, *>)
            ?.entries
            ?.mapNotNull { (key, value) ->
                if (key is String && value is String) {
                    key to value
                } else {
                    null
                }
            }
            ?.toMap()

        return ConnectorProfileResponse(
            status = map["status"]?.toString() ?: "unsuccessful",
            error = map["error"]?.toString(),
            connector_id = map["connector_id"]?.toString(),
            provider = map["provider"]?.toString(),
            allowed_fields = fields,
            metadata = metadataMap
        )
    }
}