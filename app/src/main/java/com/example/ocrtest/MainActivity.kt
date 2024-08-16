package com.example.ocrtest

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.net.ConnectivityManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.example.ocrtest.databinding.ActivityMainBinding
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONArray
import org.json.JSONObject
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.UUID

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraDevice: CameraDevice
    private lateinit var captureRequestBuilder: CaptureRequest.Builder
    private lateinit var cameraCaptureSessions: CameraCaptureSession
    private lateinit var backgroundHandler: Handler
    private lateinit var backgroundThread: HandlerThread

    private val secretKey: String by lazy {
        BuildConfig.OCR_SECRET_KEY
    }

    private val invokeUrl: String by lazy {
        BuildConfig.INVOKE_URL
    }

    private val textureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            openCamera()
        }

        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = false
    }

    private fun openCamera() {
        val manager = getSystemService(CAMERA_SERVICE) as CameraManager
        try {
            val cameraId = manager.cameraIdList[0]
            val characteristics = manager.getCameraCharacteristics(cameraId)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val imageDimension = map?.getOutputSizes(SurfaceTexture::class.java)?.get(0)
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA_PERMISSION)
                return
            }
            manager.openCamera(cameraId, stateCallback, null)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraDevice = camera
            createCameraPreview()
        }

        override fun onDisconnected(camera: CameraDevice) {
            cameraDevice.close()
        }

        override fun onError(camera: CameraDevice, error: Int) {
            cameraDevice.close()
            this@MainActivity.finish()
        }
    }

    private fun createCameraPreview() {
        try {
            val texture = binding.textureView.surfaceTexture!!
            texture.setDefaultBufferSize(binding.textureView.width, binding.textureView.height)
            val surface = Surface(texture)
            captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            captureRequestBuilder.addTarget(surface)

            cameraDevice.createCaptureSession(Collections.singletonList(surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (cameraDevice == null) return
                    cameraCaptureSessions = session
                    updatePreview()
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Toast.makeText(this@MainActivity, "Configuration change", Toast.LENGTH_SHORT).show()
                }
            }, null)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun updatePreview() {
        captureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        try {
            cameraCaptureSessions.setRepeatingRequest(captureRequestBuilder.build(), null, null)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 미리 준비된 이미지 파일을 지정하여 OCR 수행
        binding.btnOcr.setOnClickListener {
            performOcrWithLocalImage()
        }

        binding.textureView.surfaceTextureListener = textureListener
    }

    private fun getBitmapFromLocalFile(): Bitmap {
        val drawableId = R.drawable.medi4 // drawable 파일 이름이 medi3.jpg인 경우
        return BitmapFactory.decodeResource(resources, drawableId)
    }

    private fun performOcrWithLocalImage() {
        val bitmap = getBitmapFromLocalFile()
        performOcrWithBitmap(bitmap)
    }
    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetworkInfo = connectivityManager.activeNetworkInfo
        return activeNetworkInfo?.isConnected == true
    }

    private fun performOcrWithBitmap(bitmap: Bitmap) {
        if (!isNetworkAvailable()) {
            runOnUiThread {
                Toast.makeText(this, "No internet connection", Toast.LENGTH_SHORT).show()
                binding.tvOcr.text = "No internet connection"
            }
            return
        }

        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, stream)
        val byteArray = stream.toByteArray()

        Thread {
            try {
                Log.d("OCR_REQUEST", "Connecting to URL: $invokeUrl")
                val url = URL(invokeUrl)
                val con = url.openConnection() as HttpURLConnection
                con.useCaches = false
                con.doInput = true
                con.doOutput = true
                con.readTimeout = 30000
                con.requestMethod = "POST"
                val boundary = "----" + UUID.randomUUID().toString().replace("-", "")
                con.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                con.setRequestProperty("X-OCR-SECRET", secretKey)

                val json = JSONObject().apply {
                    put("version", "V2")
                    put("requestId", UUID.randomUUID().toString())
                    put("timestamp", System.currentTimeMillis())
                    put("lang", "ko")  // 언어를 지정합니다. 예: 한국어 "ko"
                    put("enableTableDetection", true)  // 표 인식을 활성화

                    val imageObject = JSONObject().apply {
                        put("format", "jpg")
                        put("name", "demo")
                    }

                    put("images", JSONArray().put(imageObject))
                }

                val postParams = json.toString()

                con.connect()
                Log.d("OCR_REQUEST", "Connected. Writing data...")
                DataOutputStream(con.outputStream).use { wr ->
                    writeMultiPart(wr, postParams, byteArray, boundary)
                    wr.close()
                }

                val responseCode = con.responseCode
                Log.d("OCR_REQUEST", "Response code: $responseCode")
                val response = StringBuffer()
                BufferedReader(InputStreamReader(if (responseCode == 200) con.inputStream else con.errorStream)).use { br ->
                    var inputLine: String?
                    while (br.readLine().also { inputLine = it } != null) {
                        response.append(inputLine)
                    }
                }

                runOnUiThread {
                    if (responseCode == 200) {
                        binding.tvOcr.text = "OCR 성공: ${response}"
                        Log.d("OCR_RESPONSE", "OCR 성공: ${response}")

                        // 응답을 기반으로 데이터를 분류하고 저장
                        processOcrResponse(response.toString())
                    } else {
                        binding.tvOcr.text = "OCR 실패: ${response}"
                        Log.e("OCR_RESPONSE", "OCR 실패: ${response}")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    binding.tvOcr.text = "OCR 에러: ${e.message}"
                    Log.e("OCR_REQUEST", "OCR 에러: ${e.message}", e)
                }
                e.printStackTrace()
            }
        }.start()
    }

    private fun processOcrResponse(response: String) {
        try {
            val jsonResponse = JSONObject(response)
            val images = jsonResponse.getJSONArray("images")
            val tables = images.getJSONObject(0).getJSONArray("tables")

            // 결과를 저장할 리스트
            val extractedData = mutableListOf<Map<String, String>>()

            var isProcessingDrugs = false

            for (tableIndex in 0 until tables.length()) {
                val cells = tables.getJSONObject(tableIndex).getJSONArray("cells")

                for (i in 0 until cells.length()) {
                    val cell = cells.getJSONObject(i)
                    val rowIndex = cell.getInt("rowIndex")
                    val columnIndex = cell.getInt("columnIndex")

                    // 셀 텍스트 결합
                    val cellTextLines = cell.getJSONArray("cellTextLines")
                    val cellTextBuilder = StringBuilder()

                    for (lineIndex in 0 until cellTextLines.length()) {
                        val line = cellTextLines.getJSONObject(lineIndex)
                        val cellWords = line.getJSONArray("cellWords")

                        for (wordIndex in 0 until cellWords.length()) {
                            val word = cellWords.getJSONObject(wordIndex)
                            cellTextBuilder.append(word.getString("inferText")).append(" ")
                        }
                    }

                    val cellText = cellTextBuilder.toString().trim()

                    // '처방 의약품의 명칭' 셀을 찾으면 플래그 설정
                    if ("처방의약품의명칭" in cellText.replace(" ", "")) {
                        isProcessingDrugs = true
                        continue
                    }

                    // 플래그가 설정된 이후의 셀들을 처리 (실제 약품 정보 추출)
                    if (isProcessingDrugs) {
                        // 명칭을 기준으로 데이터를 추출
                        if (columnIndex == 0) { // 첫 번째 열에서 명칭을 찾음
                            if (cellText.isEmpty()) {
                                // 명칭이 비어있으면 저장을 중지
                                isProcessingDrugs = false
                                break
                            }

                            // 명칭에서 숫자를 제거
                            val name = cellText.replace(Regex("\\d"), "")
                            val dosage = getNextCellValue(cells, rowIndex, columnIndex + 1)
                            val frequency = getNextCellValue(cells, rowIndex, columnIndex + 2)
                            val duration = getNextCellValue(cells, rowIndex, columnIndex + 3)
                            val method = getNextCellValue(cells, rowIndex, columnIndex + 4)

                            // 데이터 저장
                            extractedData.add(
                                mapOf(
                                    "명칭" to name,
                                    "1회 투약량" to dosage,
                                    "1일 투여횟수" to frequency,
                                    "총 투약일수" to duration,
                                    "용법" to method
                                )
                            )
                        }
                    }
                }
            }

            // 결과 출력 (예: 로그로 출력)
            extractedData.forEach { data ->
                Log.d("ExtractedData", "명칭: ${data["명칭"]}, 1회 투약량: ${data["1회 투약량"]}, 1일 투여횟수: ${data["1일 투여횟수"]}, 총 투약일수: ${data["총 투약일수"]}, 용법: ${data["용법"]}")
            }
        } catch (e: Exception) {
            Log.e("OCR_PROCESSING", "Error processing OCR response: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun getNextCellValue(cells: JSONArray, rowIndex: Int, columnIndex: Int): String {
        for (j in 0 until cells.length()) {
            val nextCell = cells.getJSONObject(j)
            if (nextCell.getInt("rowIndex") == rowIndex && nextCell.getInt("columnIndex") == columnIndex) {
                val cellTextLines = nextCell.getJSONArray("cellTextLines")
                val stringBuilder = StringBuilder()

                for (k in 0 until cellTextLines.length()) {
                    val line = cellTextLines.getJSONObject(k)
                    val cellWords = line.getJSONArray("cellWords")

                    for (l in 0 until cellWords.length()) {
                        val word = cellWords.getJSONObject(l)
                        stringBuilder.append(word.getString("inferText")).append(" ")
                    }
                }

                return stringBuilder.toString().trim()  // 공백 제거
            }
        }
        return ""
    }

    @Throws(IOException::class)
    private fun writeMultiPart(out: OutputStream, jsonMessage: String, byteArray: ByteArray, boundary: String) {
        val sb = StringBuilder()
        sb.append("--").append(boundary).append("\r\n")
        sb.append("Content-Disposition:form-data; name=\"message\"\r\n\r\n")
        sb.append(jsonMessage)
        sb.append("\r\n")

        out.write(sb.toString().toByteArray(Charsets.UTF_8))
        out.flush()

        out.write(("--$boundary\r\n").toByteArray(Charsets.UTF_8))
        val fileString = StringBuilder()
        fileString.append("Content-Disposition:form-data; name=\"file\"; filename=\"medi4.jpg\"\r\n")
        fileString.append("Content-Type: application/octet-stream\r\n\r\n")
        out.write(fileString.toString().toByteArray(Charsets.UTF_8))
        out.flush()

        out.write(byteArray)
        out.write("\r\n".toByteArray(Charsets.UTF_8))

        out.write(("--$boundary--\r\n").toByteArray(Charsets.UTF_8))
        out.flush()
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("Camera Background")
        backgroundThread.start()
        backgroundHandler = Handler(backgroundThread.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread.quitSafely()
        try {
            backgroundThread.join()
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
    }

    override fun onResume() {
        super.onResume()
        startBackgroundThread()
        if (binding.textureView.isAvailable) {
            openCamera()
        } else {
            binding.textureView.surfaceTextureListener = textureListener
        }
    }

    override fun onPause() {
        super.onPause()
        stopBackgroundThread()
        if (::cameraDevice.isInitialized) {
            cameraDevice.close()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults[0] == PackageManager.PERMISSION_DENIED) {
                Toast.makeText(this, "Sorry!!!, you can't use this app without granting permission", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    companion object {
        private const val REQUEST_CAMERA_PERMISSION = 200
    }
}