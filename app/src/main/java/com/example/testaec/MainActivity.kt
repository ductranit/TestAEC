package com.example.testaec

import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.os.Bundle
import android.os.Environment
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import android.view.Menu
import android.view.MenuItem
import android.widget.Button
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.testaec.databinding.ActivityMainBinding
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import kotlin.concurrent.thread
import android.Manifest
import android.content.ContentValues
import android.content.res.AssetFileDescriptor
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException

class MainActivity : AppCompatActivity() {

    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var binding: ActivityMainBinding

    private var isRecording = false
    private var audioRecord: AudioRecord? = null
    private val RECORD_REQUEST_CODE = 101

    // Audio recording settings
    private val SAMPLE_RATE = 44100
    private val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    private lateinit var recordButton: Button
    private lateinit var audioManager: AudioManager
    private var assetMediaPlayer: MediaPlayer? = null
    private val ASSET_AUDIO_FILENAME = "classroom-32941.mp3"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        val navController = findNavController(R.id.nav_host_fragment_content_main)
        appBarConfiguration = AppBarConfiguration(navController.graph)
        setupActionBarWithNavController(navController, appBarConfiguration)

        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        recordButton = binding.button
        recordButton.text = "start recording"
        recordButton.setOnClickListener { view ->
            if (!isRecording) {
                if (checkPermission()) {
                    startRecording()
                } else {
                    requestPermission()
                }
            } else {
                stopRecording()
            }
        }

        binding.btnPlayAudio.setOnClickListener {
            if (assetMediaPlayer?.isPlaying == true) {
                stopAssetPlayback()
            } else {
                // Optional: Consider stopping recorder playback if asset is started
                // if (mediaPlayer?.isPlaying == true) { stopPlayback() }
                startAssetPlayback(ASSET_AUDIO_FILENAME)
            }
        }
    }

    private fun startAssetPlayback(assetFileName: String) {
        // Release previous instance if any
        stopAssetPlayback()

        assetMediaPlayer = MediaPlayer()
        var afd: AssetFileDescriptor? = null
        try {
            afd = assets.openFd(assetFileName)
            assetMediaPlayer?.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            assetMediaPlayer?.prepare() // Use prepareAsync() for non-trivial files on UI thread
            assetMediaPlayer?.start()
            binding.btnPlayAudio.text = "Stop Asset Audio" // Update button text

            // Handle completion
            assetMediaPlayer?.setOnCompletionListener {
                stopAssetPlayback()
                Log.i("AssetPlayer", "Asset playback completed.")
            }

            // Handle errors
            assetMediaPlayer?.setOnErrorListener { mp, what, extra ->
                Log.e("AssetPlayer", "Asset playback error - what: $what extra: $extra")
                stopAssetPlayback() // Clean up on error
                true // Indicate error was handled
            }

        } catch (e: FileNotFoundException) {
            Log.e("AssetPlayer", "Asset file not found: $assetFileName", e)
            stopAssetPlayback() // Clean up
        } catch (e: IOException) {
            Log.e("AssetPlayer", "IOException setting data source or preparing asset: $assetFileName", e)
            stopAssetPlayback() // Clean up
        } catch (e: IllegalStateException) {
            Log.e("AssetPlayer", "IllegalStateException during asset playback setup", e)
            stopAssetPlayback() // Clean up
        } catch (e: SecurityException) {
            Log.e("AssetPlayer", "SecurityException reading asset: $assetFileName", e)
            stopAssetPlayback() // Clean up
        } finally {
            // Close the AssetFileDescriptor
            try {
                afd?.close()
            } catch (e: IOException) {
                Log.e("AssetPlayer", "Error closing AssetFileDescriptor", e)
            }
        }
    }

    private fun stopAssetPlayback() {
        assetMediaPlayer?.apply {
            try {
                if (isPlaying) {
                    stop()
                }
                release() // Release resources
            } catch (e: IllegalStateException) {
                Log.e("AssetPlayer", "IllegalStateException on stop/release", e)
                // Release might still be needed even if stop fails
                try { release() } catch (e: Exception) { /* Ignore inner exception */ }
            }
        }
        assetMediaPlayer = null
        binding.btnPlayAudio.text = "Play Asset Audio" // Reset button text
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Inflate the menu; this adds items to the action bar if it is present.
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        return when (item.itemId) {
            R.id.action_settings -> true
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        val navController = findNavController(R.id.nav_host_fragment_content_main)
        return navController.navigateUp(appBarConfiguration)
                || super.onSupportNavigateUp()
    }

    private fun checkPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.RECORD_AUDIO),
            RECORD_REQUEST_CODE
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == RECORD_REQUEST_CODE && grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startRecording()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startRecording() {
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT
        )

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            minBufferSize
        ).apply {
            // Enable Acoustic Echo Cancellation
            if (AcousticEchoCanceler.isAvailable()) {
                AcousticEchoCanceler.create(this.audioSessionId).apply {
                    enabled = true
                }
            }
        }

        val audioData = ByteArray(minBufferSize)
        val outputFile = File(cacheDir,
            "recording_${System.currentTimeMillis()}.wav")

        audioRecord?.startRecording()
        isRecording = true
        recordButton.text = "Stop Recording"

        thread {
            FileOutputStream(outputFile).use { fos ->
                // Write WAV header
                writeWavHeader(fos, minBufferSize)

                while (isRecording) {
                    val read = audioRecord?.read(audioData, 0, minBufferSize) ?: 0
                    if (read > 0) {
                        fos.write(audioData, 0, read)
                    }
                }

                // Update WAV header with final size
                updateWavHeader(outputFile)
            }

            // After recording is complete, copy to shared storage
            if (!isRecording) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    saveToSharedStorage(outputFile)
                } else {
                    saveToExternalStorage(outputFile)
                }
            }
        }
    }

    private fun stopRecording() {
        isRecording = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        recordButton.text = "Start Recording"
    }

    private fun writeWavHeader(out: FileOutputStream, bufferSize: Int) {
        val totalAudioLen = 0L // Will be updated later
        val totalDataLen = totalAudioLen + 36
        val channels = 1
        val byteRate = SAMPLE_RATE * channels * 2 // 2 bytes per sample

        val header = ByteArray(44).apply {
            set(0, 'R'.code.toByte()); set(1, 'I'.code.toByte())
            set(2, 'F'.code.toByte()); set(3, 'F'.code.toByte())
            set(4, (totalDataLen and 0xff).toByte())
            set(5, (totalDataLen shr 8 and 0xff).toByte())
            set(6, (totalDataLen shr 16 and 0xff).toByte())
            set(7, (totalDataLen shr 24 and 0xff).toByte())
            set(8, 'W'.code.toByte()); set(9, 'A'.code.toByte())
            set(10, 'V'.code.toByte()); set(11, 'E'.code.toByte())
            set(12, 'f'.code.toByte()); set(13, 'm'.code.toByte())
            set(14, 't'.code.toByte()); set(15, ' '.code.toByte())
            set(16, 16); set(17, 0); set(18, 0); set(19, 0) // Subchunk1Size
            set(20, 1); set(21, 0) // AudioFormat PCM = 1
            set(22, channels.toByte()); set(23, 0) // NumChannels
            set(24, (SAMPLE_RATE and 0xff).toByte())
            set(25, (SAMPLE_RATE shr 8 and 0xff).toByte())
            set(26, (SAMPLE_RATE shr 16 and 0xff).toByte())
            set(27, (SAMPLE_RATE shr 24 and 0xff).toByte())
            set(28, (byteRate and 0xff).toByte())
            set(29, (byteRate shr 8 and 0xff).toByte())
            set(30, (byteRate shr 16 and 0xff).toByte())
            set(31, (byteRate shr 24 and 0xff).toByte())
            set(32, 2); set(33, 0) // BlockAlign
            set(34, 16); set(35, 0) // BitsPerSample
            set(36, 'd'.code.toByte()); set(37, 'a'.code.toByte())
            set(38, 't'.code.toByte()); set(39, 'a'.code.toByte())
            set(40, (totalAudioLen and 0xff).toByte())
            set(41, (totalAudioLen shr 8 and 0xff).toByte())
            set(42, (totalAudioLen shr 16 and 0xff).toByte())
            set(43, (totalAudioLen shr 24 and 0xff).toByte())
        }
        out.write(header)
    }

    private fun saveToExternalStorage(tempFile: File) {
        val timestamp = System.currentTimeMillis()
        val fileName = "recording_$timestamp.wav"
        val recordingsDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
            ""
        )

        // Create Recordings directory if it doesn't exist
        if (!recordingsDir.exists()) {
            recordingsDir.mkdirs()
        }

        val destinationFile = File(recordingsDir, fileName)
        try {
            FileInputStream(tempFile).use { input ->
                FileOutputStream(destinationFile).use { output ->
                    input.copyTo(output)
                }
            }
            tempFile.delete()
        } catch (e: IOException) {
            e.printStackTrace()
            // Handle error (maybe show a toast to the user)
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun saveToSharedStorage(tempFile: File) {
        val timestamp = System.currentTimeMillis()
        val fileName = "recording_$timestamp.wav"

        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
            put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_RECORDINGS)
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }

        val resolver = contentResolver
        val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)

        uri?.let {
            resolver.openOutputStream(it)?.use { outputStream ->
                FileInputStream(tempFile).use { inputStream ->
                    inputStream.copyTo(outputStream)
                }
            }

            values.clear()
            values.put(MediaStore.Audio.Media.IS_PENDING, 0)
            resolver.update(it, values, null, null)
        }

        // Clean up temporary file
        tempFile.delete()
    }

    private fun updateWavHeader(file: File) {
        val fileSize = file.length()
        val totalAudioLen = fileSize - 44
        val totalDataLen = totalAudioLen + 36

        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(4)
            raf.write((totalDataLen and 0xff).toInt())
            raf.write((totalDataLen shr 8 and 0xff).toInt())
            raf.write((totalDataLen shr 16 and 0xff).toInt())
            raf.write((totalDataLen shr 24 and 0xff).toInt())
            raf.seek(40)
            raf.write((totalAudioLen and 0xff).toInt())
            raf.write((totalAudioLen shr 8 and 0xff).toInt())
            raf.write((totalAudioLen shr 16 and 0xff).toInt())
            raf.write((totalAudioLen shr 24 and 0xff).toInt())
        }
    }
}