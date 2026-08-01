package com.github.vermilion10.disqrpc.data.remote

import okhttp3.ResponseBody
import retrofit2.http.GET
import retrofit2.http.Query

interface PlayStoreService {
    @GET("store/apps/details")
    suspend fun getAppDetails(
        @Query("id") packageName: String,
        @Query("hl") language: String = "en"
    ): ResponseBody
}
