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
    @POST("recognizer/upload")
    fun ocrImage(
        @Part media: MultipartBody.Part,
        @Header("X-OCR-SECRET") apiKey: String
    ): Call<OcrResponse>
}

data class OcrResponse(
    val images: List<ImageResult>
)

data class ImageResult(
    val inferResult: String,
    val fields: List<Field>
)

data class Field(
    val inferText: String
)