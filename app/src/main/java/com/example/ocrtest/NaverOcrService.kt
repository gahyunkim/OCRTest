package com.example.ocrtest

import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Call
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part

interface NaverOcrService {
    @Multipart
    @POST("v1/recognizer/upload")
    fun ocrSpeech(
        @Part media: MultipartBody.Part,
        @Part("message") params: RequestBody,
        @Header("X-OCR-SECRET") apiKey: String
    ): Call<OcrResponse>
}

data class OcrResponse(
    val text: String
)