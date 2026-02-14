package com.tontext.app

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.tontext.app.whatsnew.WhatsNewActivity
import com.tontext.app.whisper.WhisperTranscriber
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

private const val LOG_TAG = "SetupActivity"
private const val MODEL_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base-q5_1.bin"
private const val REQUEST_RECORD_AUDIO = 100

class SetupActivity : AppCompatActivity() {

    private lateinit var stepDownloadStatus: TextView
    private lateinit var downloadProgress: ProgressBar
    private lateinit var downloadProgressText: TextView
    private lateinit var stepPermissionStatus: TextView
    private lateinit var stepEnableStatus: TextView
    private lateinit var stepSelectStatus: TextView
    private lateinit var setupComplete: LinearLayout
    private lateinit var versionText: TextView

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = AppPreferences(this)
        val currentVersionCode = BuildConfig.VERSION_CODE
        val lastSeen = prefs.lastSeenVersionCode

        when {
            lastSeen == 0 -> {
                prefs.lastSeenVersionCode = currentVersionCode
            }
            lastSeen < currentVersionCode -> {
                startActivity(Intent(this, WhatsNewActivity::class.java))
            }
        }

        setContentView(R.layout.activity_setup)

        stepDownloadStatus = findViewById(R.id.stepDownloadStatus)
        downloadProgress = findViewById(R.id.downloadProgress)
        downloadProgressText = findViewById(R.id.downloadProgressText)
        stepPermissionStatus = findViewById(R.id.stepPermissionStatus)
        stepEnableStatus = findViewById(R.id.stepEnableStatus)
        stepSelectStatus = findViewById(R.id.stepSelectStatus)
        setupComplete = findViewById(R.id.setupComplete)
        versionText = findViewById(R.id.versionText)

        versionText.text = "v${BuildConfig.VERSION_NAME}"

        // Done button
        findViewById<TextView>(R.id.btnDone).setOnClickListener {
            finish()
        }

        // Step 1: Download model
        findViewById<LinearLayout>(R.id.stepDownload).setOnClickListener {
            if (!WhisperTranscriber.isModelDownloaded(this)) {
                downloadModel()
            }
        }

        // Step 2: Permission
        findViewById<LinearLayout>(R.id.stepPermission).setOnClickListener {
            requestMicPermission()
        }

        // Step 3: Enable keyboard
        findViewById<LinearLayout>(R.id.stepEnable).setOnClickListener {
            val intent = android.content.Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
            startActivity(intent)
        }

        // Step 4: Select keyboard
        findViewById<LinearLayout>(R.id.stepSelect).setOnClickListener {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        }

        // Clean up old model files from previous versions
        File(filesDir, "ggml-tiny.bin").delete()
        File(filesDir, "ggml-tiny-q5_1.bin").delete()

        // Auto-start model download if not yet done
        if (!WhisperTranscriber.isModelDownloaded(this)) {
            downloadModel()
        }
    }

    override fun onResume() {
        super.onResume()
        updateStepStatuses()
    }

    private fun updateStepStatuses() {
        // Step 1: Model
        if (WhisperTranscriber.isModelDownloaded(this)) {
            stepDownloadStatus.text = "\u2713"
            stepDownloadStatus.setTextColor(getColor(R.color.accent))
        }

        // Step 2: Permission
        if (hasMicPermission()) {
            stepPermissionStatus.text = "\u2713"
            stepPermissionStatus.setTextColor(getColor(R.color.accent))
        }

        // Step 3: IME enabled
        if (isImeEnabled()) {
            stepEnableStatus.text = "\u2713"
            stepEnableStatus.setTextColor(getColor(R.color.accent))
        }

        // Step 4: IME selected
        if (isImeSelected()) {
            stepSelectStatus.text = "\u2713"
            stepSelectStatus.setTextColor(getColor(R.color.accent))
        }

        // All steps done
        val allDone = WhisperTranscriber.isModelDownloaded(this)
                && hasMicPermission()
                && isImeEnabled()
                && isImeSelected()

        setupComplete.visibility = if (allDone) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun hasMicPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
    }

    private fun requestMicPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.RECORD_AUDIO),
            REQUEST_RECORD_AUDIO
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RECORD_AUDIO) {
            updateStepStatuses()
        }
    }

    private fun isImeEnabled(): Boolean {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        val imeId = ComponentName(this, TonTextIMEService::class.java).flattenToShortString()
        return imm.enabledInputMethodList.any {
            it.id.contains(packageName)
        }
    }

    private fun isImeSelected(): Boolean {
        val currentIme = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        return currentIme?.contains(packageName) == true
    }

    private fun downloadModel() {
        downloadProgress.visibility = android.view.View.VISIBLE
        downloadProgressText.visibility = android.view.View.VISIBLE

        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val modelFile = File(filesDir, "ggml-base-q5_1.bin")
                    val tmpFile = File(filesDir, "ggml-base-q5_1.bin.tmp")

                    val url = URL(MODEL_URL)
                    val connection = url.openConnection() as HttpURLConnection
                    connection.connectTimeout = 15000
                    connection.readTimeout = 15000
                    connection.connect()

                    val totalBytes = connection.contentLength.toLong()
                    var downloadedBytes = 0L

                    connection.inputStream.use { input ->
                        FileOutputStream(tmpFile).use { output ->
                            val buffer = ByteArray(8192)
                            var bytesRead: Int
                            while (input.read(buffer).also { bytesRead = it } != -1) {
                                output.write(buffer, 0, bytesRead)
                                downloadedBytes += bytesRead
                                val progress = if (totalBytes > 0) {
                                    (downloadedBytes * 100 / totalBytes).toInt()
                                } else 0
                                withContext(Dispatchers.Main) {
                                    downloadProgress.progress = progress
                                    val mbDown = downloadedBytes / (1024 * 1024)
                                    val mbTotal = totalBytes / (1024 * 1024)
                                    downloadProgressText.text = "$mbDown / $mbTotal MiB"
                                }
                            }
                        }
                    }

                    tmpFile.renameTo(modelFile)
                    Log.d(LOG_TAG, "Model downloaded: ${modelFile.absolutePath}")
                }

                updateStepStatuses()
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Model download failed", e)
                downloadProgressText.text = "Download failed. Tap to retry."
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
