package com.example.ocrtest

import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Call
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Url

interface NaverOcrService {
    @Multipart
    @POST
    fun ocrImage(
        @Url url: String,
        @Part file: MultipartBody.Part,
        @Header("Content-Type") contentType: String,
        @Header("X-OCR-SECRET") apiKey: String
    ): Call<OcrResponse>
}

data class OcrResponse(
    val images: List<ImageResult>
)

data class ImageResult(
    val fields: List<FieldResult>
)

data class FieldResult(
    val inferText: String
)