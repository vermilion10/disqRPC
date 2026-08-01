package com.github.vermilion10.disqrpc.util

import android.content.Context
import android.net.Uri
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

object ImageUploader {
    private const val CATBOX_URL = "https://catbox.moe/user/api.php"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun upload(uri: Uri, context: Context): String? {
        return try {
            val resolver = context.contentResolver
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
            val mime = resolver.getType(uri) ?: "image/png"
            val ext = when {
                mime.contains("png") -> "png"
                mime.contains("jpeg") || mime.contains("jpg") -> "jpg"
                mime.contains("gif") -> "gif"
                mime.contains("webp") -> "webp"
                else -> "png"
            }
            val fileBody = bytes.toRequestBody(mime.toMediaType())
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("reqtype", "fileupload")
                .addFormDataPart("userhash", "")
                .addFormDataPart("fileToUpload", "rpc_image.$ext", fileBody)
                .build()
            val request = Request.Builder().url(CATBOX_URL).post(body).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val url = response.body?.string()?.trim() ?: return null
                if (url.startsWith("http")) url else null
            }
        } catch (e: Exception) {
            Logger.e("Image upload failed: ${e.message}")
            null
        }
    }
}
