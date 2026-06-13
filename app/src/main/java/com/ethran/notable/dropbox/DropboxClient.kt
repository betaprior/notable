package com.ethran.notable.dropbox

import com.ethran.notable.utils.AppResult
import com.ethran.notable.utils.DomainError
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * Dropbox HTTP API v2 client using OkHttp.
 * Handles OAuth2 PKCE flow and file operations.
 */
class DropboxClient(
    private var accessToken: String,
    private val refreshToken: String,
    private val onTokenRefreshed: (newAccessToken: String) -> Unit = {}
) {
    private val TAG = "DropboxClient"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Upload file content to Dropbox.
     * @param path Dropbox path (e.g. "/notable/file.xopp")
     * @param content File bytes
     * @return rev string on success
     */
    fun upload(path: String, content: ByteArray): AppResult<String, DomainError> {
        return executeWithRetry {
            val apiArg = json.encodeToString(UploadArg(path = path))
            val request = Request.Builder()
                .url("https://content.dropboxapi.com/2/files/upload")
                .post(content.toRequestBody("application/octet-stream".toMediaType()))
                .header("Authorization", "Bearer $accessToken")
                .header("Dropbox-API-Arg", apiArg)
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body.string()
                    val metadata = json.decodeFromString<FileMetadata>(body)
                    AppResult.Success(metadata.rev)
                } else {
                    val errBody = response.body.string()
                    Log.e(TAG,"Upload failed: ${response.code} $errBody")
                    AppResult.Error(DomainError.SyncError("Upload failed (${response.code}): $errBody"))
                }
            }
        }
    }

    /**
     * Download file content from Dropbox.
     * @param path Dropbox path
     * @return Pair of (content bytes, rev string)
     */
    fun download(path: String): AppResult<Pair<ByteArray, String>, DomainError> {
        return executeWithRetry {
            val apiArg = """{"path": "${path.replace("\"", "\\\"")}"}"""
            val request = Request.Builder()
                .url("https://content.dropboxapi.com/2/files/download")
                .post(ByteArray(0).toRequestBody())
                .header("Authorization", "Bearer $accessToken")
                .header("Dropbox-API-Arg", apiArg)
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val apiResult = response.header("Dropbox-API-Result") ?: "{}"
                    val metadata = json.decodeFromString<FileMetadata>(apiResult)
                    val bytes = response.body.bytes()
                    AppResult.Success(bytes to metadata.rev)
                } else {
                    val body = response.body.string()
                    Log.e(TAG,"Download failed: ${response.code} $body")
                    if (response.code == 409 && body.contains("not_found")) {
                        AppResult.Error(DomainError.NotFound("File not found: $path"))
                    } else {
                        AppResult.Error(DomainError.SyncError("Download failed (${response.code}): $body"))
                    }
                }
            }
        }
    }

    /**
     * Get file metadata (including rev) without downloading content.
     */
    fun getMetadata(path: String): AppResult<FileMetadata, DomainError> {
        return executeWithRetry {
            val body = json.encodeToString(PathArg(path))
                .toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("https://api.dropboxapi.com/2/files/get_metadata")
                .post(body)
                .header("Authorization", "Bearer $accessToken")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val metadata = json.decodeFromString<FileMetadata>(response.body.string())
                    AppResult.Success(metadata)
                } else {
                    val body = response.body.string()
                    Log.e(TAG,"get_metadata failed: ${response.code} $body")
                    if (response.code == 409 && body.contains("not_found")) {
                        AppResult.Error(DomainError.NotFound("File not found: $path"))
                    } else {
                        AppResult.Error(DomainError.SyncError("get_metadata failed (${response.code}): $body"))
                    }
                }
            }
        }
    }

    /**
     * List files in a Dropbox folder.
     */
    fun listFolder(path: String): AppResult<List<FileMetadata>, DomainError> {
        return executeWithRetry {
            val body = json.encodeToString(ListFolderArg(path = path))
                .toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("https://api.dropboxapi.com/2/files/list_folder")
                .post(body)
                .header("Authorization", "Bearer $accessToken")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val result = json.decodeFromString<ListFolderResult>(response.body.string())
                    AppResult.Success(result.entries.filter { it.tag == "file" })
                } else {
                    val body = response.body.string()
                    Log.e(TAG,"list_folder failed: ${response.code} $body")
                    if (response.code == 409 && body.contains("not_found")) {
                        AppResult.Error(DomainError.NotFound("Folder not found: $path"))
                    } else {
                        AppResult.Error(DomainError.SyncError("list_folder failed (${response.code}): $body"))
                    }
                }
            }
        }
    }

    /**
     * Get current account info to verify connection.
     */
    fun getAccountInfo(): AppResult<String, DomainError> {
        return executeWithRetry {
            val request = Request.Builder()
                .url("https://api.dropboxapi.com/2/users/get_current_account")
                .post(ByteArray(0).toRequestBody("application/json".toMediaType()))
                .header("Authorization", "Bearer $accessToken")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body.string()
                    val account = json.decodeFromString<AccountInfo>(body)
                    AppResult.Success(account.name.displayName)
                } else {
                    AppResult.Error(DomainError.SyncAuthError)
                }
            }
        }
    }

    /**
     * Refresh the access token using the refresh token.
     */
    private fun refreshAccessToken(): Boolean {
        if (refreshToken.isBlank()) return false
        try {
            val formBody = "grant_type=refresh_token&refresh_token=$refreshToken&client_id=$DROPBOX_APP_KEY"
                .toRequestBody("application/x-www-form-urlencoded".toMediaType())
            val request = Request.Builder()
                .url("https://api.dropboxapi.com/oauth2/token")
                .post(formBody)
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val tokenResponse = json.decodeFromString<TokenResponse>(response.body.string())
                    accessToken = tokenResponse.accessToken
                    onTokenRefreshed(accessToken)
                    Log.i(TAG,"Access token refreshed successfully")
                    return true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG,"Token refresh failed: ${e.message}")
        }
        return false
    }

    /**
     * Execute a request, retrying once with token refresh on 401.
     */
    private fun <T> executeWithRetry(block: () -> AppResult<T, DomainError>): AppResult<T, DomainError> {
        return try {
            val result = block()
            if (result is AppResult.Error && result.error == DomainError.SyncAuthError) {
                if (refreshAccessToken()) {
                    block()
                } else {
                    result
                }
            } else {
                result
            }
        } catch (e: Exception) {
            AppResult.Error(DomainError.NetworkError(e.message ?: "Dropbox request failed"))
        }
    }

    // --- DTOs ---

    @Serializable
    private data class UploadArg(
        val path: String,
        val mode: String = "overwrite",
        val autorename: Boolean = false,
        val mute: Boolean = true
    )

    @Serializable
    private data class PathArg(val path: String)

    @Serializable
    private data class ListFolderArg(
        val path: String,
        val recursive: Boolean = false,
        val include_deleted: Boolean = false
    )

    @Serializable
    data class ListFolderResult(
        val entries: List<FileMetadata> = emptyList(),
        val cursor: String = "",
        val has_more: Boolean = false
    )

    @Serializable
    data class FileMetadata(
        val name: String = "",
        val path_lower: String = "",
        val path_display: String = "",
        val rev: String = "",
        val size: Long = 0,
        val client_modified: String = "",
        val server_modified: String = "",
        @kotlinx.serialization.SerialName(".tag")
        val tag: String = ""
    )

    @Serializable
    private data class TokenResponse(
        @kotlinx.serialization.SerialName("access_token")
        val accessToken: String,
        @kotlinx.serialization.SerialName("token_type")
        val tokenType: String = "",
        @kotlinx.serialization.SerialName("expires_in")
        val expiresIn: Int = 0
    )

    @Serializable
    private data class AccountInfo(
        val name: AccountName
    )

    @Serializable
    private data class AccountName(
        @kotlinx.serialization.SerialName("display_name")
        val displayName: String
    )

    companion object {
        /**
         * Exchange an authorization code for tokens (PKCE flow).
         */
        fun exchangeCodeForToken(
            code: String,
            codeVerifier: String
        ): AppResult<Pair<String, String>, DomainError> {
            val client = OkHttpClient()
            val json = Json { ignoreUnknownKeys = true }

            val formBody = listOf(
                "code=$code",
                "grant_type=authorization_code",
                "client_id=$DROPBOX_APP_KEY",
                "code_verifier=$codeVerifier"
            ).joinToString("&")
                .toRequestBody("application/x-www-form-urlencoded".toMediaType())

            val request = Request.Builder()
                .url("https://api.dropboxapi.com/oauth2/token")
                .post(formBody)
                .build()

            return try {
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body.string()
                        @Serializable
                        data class FullTokenResponse(
                            @kotlinx.serialization.SerialName("access_token")
                            val accessToken: String,
                            @kotlinx.serialization.SerialName("refresh_token")
                            val refreshToken: String = ""
                        )
                        val tokenResponse = json.decodeFromString<FullTokenResponse>(body)
                        AppResult.Success(tokenResponse.accessToken to tokenResponse.refreshToken)
                    } else {
                        AppResult.Error(DomainError.SyncAuthError)
                    }
                }
            } catch (e: Exception) {
                AppResult.Error(DomainError.NetworkError(e.message ?: "Token exchange failed"))
            }
        }

        /**
         * Generate a PKCE code verifier (random 43-128 char string).
         */
        fun generateCodeVerifier(): String {
            val bytes = ByteArray(32)
            SecureRandom().nextBytes(bytes)
            return android.util.Base64.encodeToString(bytes, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
        }

        /**
         * Generate the code challenge from a code verifier (S256).
         */
        fun generateCodeChallenge(verifier: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray())
            return android.util.Base64.encodeToString(digest, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
        }

        /**
         * Build the Dropbox OAuth2 authorization URL.
         */
        /**
         * Build the Dropbox OAuth2 authorization URL.
         * No redirect_uri — Dropbox will display the auth code on screen
         * for the user to paste back into the app.
         */
        fun buildAuthUrl(codeChallenge: String): String {
            return "https://www.dropbox.com/oauth2/authorize" +
                "?client_id=$DROPBOX_APP_KEY" +
                "&response_type=code" +
                "&code_challenge=$codeChallenge" +
                "&code_challenge_method=S256" +
                "&token_access_type=offline"
        }
    }
}
