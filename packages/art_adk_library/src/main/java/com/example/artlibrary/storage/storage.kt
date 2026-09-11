package com.example.artlibrary.storage

import android.os.Build
import androidx.annotation.RequiresApi
import com.example.artlibrary.auth.Auth
import com.example.artlibrary.config.Constant
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** Phase-tagged storage failure. Mirrors the JS `UploadError`. */
class UploadError(
    message: String,
    val phase: String,
    val status: Int? = null,
    cause: Throwable? = null,
) : Exception(message, cause)

data class UploadOptions(
    val filename: String? = null,
    val contentType: String? = null,   // JS reads this off the Blob; on Android pass it explicitly
    val configType: String? = null,
    val configId: String? = null,
    val scopes: List<String>? = null,
    val ttlSeconds: Long? = null,
    val timeoutMs: Long? = null,
    val onProgress: ((Float) -> Unit)? = null,
)

data class FileRef(
    val fileId: String,
    val name: String,
    val readUrl: String,
    val size: Long,
    val contentType: String,
)

data class ListOptions(
    val configType: String? = null,
    val configId: String? = null,
    val page: Int? = null,
    val limit: Int? = null,
)

data class StorageFile(
    val fileId: String,
    val name: String,
    val configType: String,
    val configId: String,
    val size: Long,
    val contentType: String,
    val status: String,
    val createdAt: String,
    val expiresAt: String? = null,
    val readUrl: String? = null,
)

data class ListFilesResult(val files: List<StorageFile>, val total: Int)

@RequiresApi(Build.VERSION_CODES.O)
class Storage {

    private val http = OkHttpClient()
    private val gson = Gson()
    private val json = "application/json".toMediaType()

    suspend fun upload(file: ByteArray, opts: UploadOptions = UploadOptions()): FileRef {
        if (file.isEmpty()) throw UploadError("file is empty", "validate")

        val auth = try {
            Auth.getInstance()
        } catch (e: Exception) {
            throw UploadError("call connect() before upload()", "validate")
        }

        val fileName = opts.filename ?: "upload.bin"
        val contentType = opts.contentType ?: "application/octet-stream"
        val configId = opts.configId ?: auth.getCredentials().projectKey

        val init = storageCall(
            "signed-url", "POST", "/upload/signed-url",
            body = mapOf(
                "config_type" to (opts.configType ?: "media"),
                "config_id" to configId,
                "file_name" to fileName,
                "file_size" to file.size,
                "content_type" to contentType,
                "scopes" to (opts.scopes ?: emptyList<String>()),
                "ttl_seconds" to opts.ttlSeconds,
            ),
            timeoutMs = opts.timeoutMs,
        )
        val fileId = init["file_id"]?.toString()
        val uploadUrl = init["upload_url"]?.toString()
        if (fileId.isNullOrEmpty() || uploadUrl.isNullOrEmpty())
            throw UploadError("signed-url missing file_id/upload_url", "signed-url")

        putToStorage(uploadUrl, file, contentType, opts)

        val done = storageCall("confirm", "POST", "/upload/confirm/$fileId", timeoutMs = opts.timeoutMs)
        val doneFile = done["file"] as? Map<*, *>
        return FileRef(
            fileId = fileId,
            name = fileName,
            readUrl = done["read_url"]?.toString() ?: "",
            size = longOf(doneFile?.get("file_size")) ?: file.size.toLong(),
            contentType = contentType,
        )
    }

    suspend fun listFiles(opts: ListOptions = ListOptions()): ListFilesResult {
        val params = buildList {
            opts.configType?.let { add("config_type" to it) }
            opts.configId?.let { add("config_id" to it) }
            opts.page?.let { add("page" to it.toString()) }
            opts.limit?.let { add("limit" to it.toString()) }
        }
        val q = params.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }
        val data = storageCall("list", "GET", "/files" + if (q.isNotEmpty()) "?$q" else "")
        val files = (data["files"] as? List<*>)
            ?.mapNotNull { (it as? Map<*, *>)?.let(::toStorageFile) } ?: emptyList()
        return ListFilesResult(files, intOf(data["total"]) ?: 0)
    }

    suspend fun getFile(fileId: String, timeoutMs: Long? = null): StorageFile {
        val data = storageCall("get", "GET", "/file/${enc(fileId)}", timeoutMs = timeoutMs)
        return toStorageFile(data["file"] as? Map<*, *>, data["read_url"]?.toString())
    }

    suspend fun deleteFile(fileId: String, hard: Boolean = false, timeoutMs: Long? = null) {
        val path = "/file/${enc(fileId)}" + if (hard) "/hard" else ""
        storageCall("delete", "DELETE", path, timeoutMs = timeoutMs)
    }

    // ---- internals ----

    private fun toStorageFile(f: Map<*, *>?, readUrl: String? = null): StorageFile = StorageFile(
        fileId = f?.get("id")?.toString() ?: "",
        name = f?.get("original_name")?.toString() ?: "",
        configType = f?.get("config_type")?.toString() ?: "",
        configId = f?.get("config_id")?.toString() ?: "",
        size = longOf(f?.get("file_size")) ?: 0L,
        contentType = f?.get("content_type")?.toString() ?: "",
        status = f?.get("status")?.toString() ?: "",
        createdAt = f?.get("created_at")?.toString() ?: "",
        expiresAt = f?.get("expires_at")?.toString(),
        readUrl = readUrl ?: f?.get("read_url")?.toString(),
    )

    private suspend fun storageCall(
        op: String,
        method: String,
        path: String,
        body: Any? = null,
        timeoutMs: Long? = null,
    ): Map<*, *> = withContext(Dispatchers.IO) {
        val creds = try {
            Auth.getInstance().getCredentials()
        } catch (e: Exception) {
            throw UploadError("call connect() before using storage", "validate")
        }
        // Constant.BASE_URL is "https://<uri>" in the Kotlin core (no "/ws" suffix).
        val baseUrl = "${Constant.BASE_URL.removeSuffix("/ws")}/api/${enc(creds.orgTitle)}/storage"

        try {
            val auth = Auth.getInstance()
            auth.authenticate()
            val token = auth.getAuthData().accessToken

            val reqBody: RequestBody? = when {
                body != null -> gson.toJson(body).toRequestBody(json)
                method.equals("POST", true) || method.equals("PUT", true) ||
                        method.equals("PATCH", true) -> ByteArray(0).toRequestBody(null)
                else -> null
            }

            val request = Request.Builder()
                .url("$baseUrl$path")
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/json")
                .header("X-Org", creds.orgTitle)
                .header("Environment", creds.environment)
                .header("ProjectKey", creds.projectKey)
                .apply { if (body != null) header("Content-Type", "application/json") }
                .method(method.uppercase(), reqBody)
                .build()

            val client = if (timeoutMs != null)
                http.newBuilder().callTimeout(timeoutMs, TimeUnit.MILLISECONDS).build() else http

            client.newCall(request).execute().use { res ->
                if (!res.isSuccessful) throw IOException("HTTP ${res.code} ${res.message}")
                val text = res.body?.string().orEmpty()
                val root = if (text.isBlank()) emptyMap<Any?, Any?>()
                else gson.fromJson(text, Map::class.java) ?: emptyMap<Any?, Any?>()
                (root["data"] as? Map<*, *>) ?: emptyMap<Any?, Any?>()
            }
        } catch (e: UploadError) {
            throw e
        } catch (e: Exception) {
            throw UploadError("storage $op failed: ${e.message ?: e}", op, cause = e)
        }
    }

    private suspend fun putToStorage(
        url: String, file: ByteArray, contentType: String, opts: UploadOptions,
    ) = withContext(Dispatchers.IO) {
        val timeoutMs = opts.timeoutMs ?: 60_000L
        opts.onProgress?.invoke(0f)
        val client = http.newBuilder().callTimeout(timeoutMs, TimeUnit.MILLISECONDS).build()
        val request = Request.Builder()
            .url(url)
            .header("x-ms-blob-type", "BlockBlob")
            .header("Content-Type", contentType)
            .put(file.toRequestBody(contentType.toMediaTypeOrNull()))
            .build()
        try {
            client.newCall(request).execute().use { res ->
                if (!res.isSuccessful)
                    throw UploadError("storage PUT failed (${res.code})", "put", res.code)
                opts.onProgress?.invoke(1f)
            }
        } catch (e: UploadError) {
            throw e
        } catch (e: Exception) {
            throw UploadError("storage PUT failed (network/timeout)", "put", cause = e)
        }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
    private fun intOf(v: Any?): Int? = (v as? Number)?.toInt()
    private fun longOf(v: Any?): Long? = (v as? Number)?.toLong()
}