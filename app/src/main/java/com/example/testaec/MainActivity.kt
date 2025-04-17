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
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.audiofx.NoiseSuppressor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.AssetDataSource
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.AudioTrackSink
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.Logging
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnectionFactory
import org.webrtc.audio.JavaAudioDeviceModule
import org.webrtc.voiceengine.WebRtcAudioUtils
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.Channels

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
    private var assetExoPlayer: ExoPlayer? = null
    private val ASSET_AUDIO_FILENAME = "classroom-32941.mp3"
//    private var aec: AcousticEchoCanceler? = null
//    private var ns: NoiseSuppressor? = null


    // WebRTC components
    private lateinit var peerConnectionFactory: PeerConnectionFactory
    private var audioDeviceModule: JavaAudioDeviceModule? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var audioSink: CustomAudioSink? = null
    private val TAG = "WebRTC"

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
            if (assetExoPlayer?.isPlaying == true) {
                stopAssetPlayback()
            } else {
                val currentAudioSessionId = audioRecord?.audioSessionId
                if (currentAudioSessionId != null && currentAudioSessionId != AudioManager.ERROR) {
                    startAssetPlayback(ASSET_AUDIO_FILENAME, currentAudioSessionId)
                } else {
                    Log.e("AssetPlayer", "Cannot play asset: AudioRecord not ready or has invalid session ID.")
                }
            }
        }

        initializeWebRTC()
    }

    private fun initializeWebRTC() {
        // Enable WebRTC logging for debugging

        // Initialize EglBase (required for video, but safe to include)
        val eglBase = EglBase.create()
        Log.d("WebRTC", "EglBase initialized")

        // Create JavaAudioDeviceModule with default settings
        audioDeviceModule = JavaAudioDeviceModule.builder(applicationContext)
            .setAudioRecordErrorCallback(object : JavaAudioDeviceModule.AudioRecordErrorCallback {
                override fun onWebRtcAudioRecordInitError(errorMessage: String?) {
                    Log.e(TAG, "ADM Record Init Error: $errorMessage")
                }
                override fun onWebRtcAudioRecordStartError(errorCode: JavaAudioDeviceModule.AudioRecordStartErrorCode?, errorMessage: String?) {
                    Log.e(TAG, "ADM Record Start Error: $errorCode - $errorMessage")
                }
                override fun onWebRtcAudioRecordError(errorMessage: String?) {
                    Log.e(TAG, "ADM Record Error: $errorMessage")
                }
            })
            .setAudioTrackErrorCallback(object : JavaAudioDeviceModule.AudioTrackErrorCallback {
                override fun onWebRtcAudioTrackInitError(errorMessage: String?) {
                    Log.e(TAG, "ADM Track Init Error: $errorMessage")
                }
                override fun onWebRtcAudioTrackStartError(errorCode: JavaAudioDeviceModule.AudioTrackStartErrorCode?, errorMessage: String?) {
                    Log.e(TAG, "ADM Track Start Error: $errorCode - $errorMessage")
                }
                override fun onWebRtcAudioTrackError(errorMessage: String?) {
                    Log.e(TAG, "ADM Track Error: $errorMessage")
                }
            })
            .setSamplesReadyCallback(object: JavaAudioDeviceModule.SamplesReadyCallback {
                override fun onWebRtcAudioRecordSamplesReady(samples: JavaAudioDeviceModule.AudioSamples?) {
                    Log.e(TAG, "onWebRtcAudioRecordSamplesReady: $samples")
                }

            })
            .setUseHardwareNoiseSuppressor(false)
            .setSampleRate(SAMPLE_RATE)
            .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .createAudioDeviceModule()
        Log.d(TAG, "JavaAudioDeviceModule created: $audioDeviceModule")

        val factoryOptions = PeerConnectionFactory.Options()
        val initOption = PeerConnectionFactory.InitializationOptions.builder(this)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(initOption)
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(factoryOptions)
            .setAudioDeviceModule(audioDeviceModule)
            .createPeerConnectionFactory()

        Logging.enableLogToDebugOutput(Logging.Severity.LS_VERBOSE)
        Log.d("WebRTC", "WebRTC logging enabled")
    }

    @OptIn(UnstableApi::class)
    private fun startAssetPlayback(assetFileName: String, audioSessionId: Int) {
        stopAssetPlayback()

        try {
            assetExoPlayer = ExoPlayer.Builder(this).build().apply {
                setAudioSessionId(audioSessionId)
                val afd: AssetFileDescriptor = assets.openFd(assetFileName)
                val dataSource = AssetDataSource(this@MainActivity)
                dataSource.open(
                    androidx.media3.datasource.DataSpec(
                        Uri.parse("asset:///$assetFileName"),
                        afd.startOffset,
                        afd.length
                    )
                )
                val mediaSource: MediaSource = ProgressiveMediaSource.Factory(
                    DefaultDataSource.Factory(this@MainActivity)
                ).createMediaSource(MediaItem.fromUri("asset:///$assetFileName"))

                setMediaSource(mediaSource)
                prepare()
                play()

                binding.btnPlayAudio.text = "Stop Asset Audio"

                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_ENDED) {
                            stopAssetPlayback()
                            Log.i("AssetPlayer", "Asset playback completed.")
                        }
                    }

                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        Log.e("AssetPlayer", "Asset playback error: ${error.message}")
                        stopAssetPlayback()
                    }
                })

                afd.close()
            }
        } catch (e: Exception) {
            Log.e("AssetPlayer", "Error playing asset: $assetFileName", e)
            stopAssetPlayback()
        }
    }

    private fun stopAssetPlayback() {
        assetExoPlayer?.apply {
            try {
                if (isPlaying) stop()
                release()
            } catch (e: Exception) {
                Log.e("AssetPlayer", "Error stopping/releasing player", e)
            }
        }
        assetExoPlayer = null
        binding.btnPlayAudio.text = "Play Asset Audio"
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

    @SuppressLint("MissingPermission", "NewApi")
    private fun startRecording() {
//        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

//        // Request audio focus
//        val audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
//            .setAudioAttributes(
//                AudioAttributes.Builder()
//                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
//                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
//                    .build()
//            )
//            .build()
//        val focusResult = audioManager.requestAudioFocus(audioFocusRequest)
//        if (focusResult != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
//            Log.e("WebRTC", "Audio focus request failed")
//            return
//        }
//        Log.d("WebRTC", "Audio focus granted")

        // Create audio source with constraints for noise suppression, AGC, and echo cancellation
        val audioConstraints = MediaConstraints()
        audioConstraints.mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
        audioConstraints.mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
//        audioConstraints.mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
//        audioConstraints.mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
//        audioConstraints.mandatory.add(MediaConstraints.KeyValuePair("googAudioMirroring", "false"))

        audioSource = peerConnectionFactory.createAudioSource(audioConstraints)
        if (audioSource == null) {
            Log.e(TAG, "Failed to create AudioSource")
            return
        }

        audioTrack = peerConnectionFactory.createAudioTrack("audio_track", audioSource)
        if (audioTrack == null) {
            Log.e(TAG, "Failed to create AudioTrack")
        }

        Log.d(TAG, "AudioTrack created: $audioTrack, AudioSource: $audioSource")

        val outputFile = File(cacheDir, "recording_${System.currentTimeMillis()}.wav")
        audioSink = CustomAudioSink(outputFile, SAMPLE_RATE) { data ->
            Log.d(TAG, "Audio data received: ${data} bytes")
        }
        Log.d(TAG, "CustomAudioSink created: $audioSink")

        audioTrack?.addSink(audioSink)
        Log.d(TAG, "Sink added to AudioTrack")

        audioTrack?.setEnabled(true)
        Log.d(TAG, "AudioTrack enabled. Current state: ${audioTrack?.state()}")

        isRecording = true
        recordButton.text = "Stop Recording"

        thread {
            while (isRecording) {
                Thread.sleep(100)

//                Log.d(TAG, "AudioTrack state: ${audioTrack?.state()}")
            }

            audioSink?.close()
            updateWavHeader(outputFile)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                saveToSharedStorage(outputFile)
            } else {
                saveToExternalStorage(outputFile)
            }
        }
    }

    private fun stopRecording() {
        isRecording = false
        audioTrack?.setEnabled(false)
        audioTrack?.removeSink(audioSink)
        audioTrack?.dispose()
        audioSource?.dispose()
        audioSink?.close()
        audioTrack = null
        audioSource = null
        audioSink = null
        recordButton.text = "Start Recording"
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

class CustomAudioSink(
    private val outputFile: File,
    private val sampleRate: Int,
    private val onAudioData: (ByteBuffer) -> Unit
) : AudioTrackSink {
    private var fileOutputStream: FileOutputStream? = null

    init {
        // Initialize FileOutputStream and write WAV header
        fileOutputStream = FileOutputStream(outputFile)
        writeWavHeader(fileOutputStream!!, sampleRate)
    }

    fun close() {
        fileOutputStream?.close()
        fileOutputStream = null
    }

    private fun writeWavHeader(out: FileOutputStream, SAMPLE_RATE: Int) {
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

    override fun onData(
        audioData: ByteBuffer?,
        p1: Int,
        p2: Int,
        p3: Int,
        p4: Int,
        p5: Long
    ) {
        Log.d("WebRTC_Sink", ">>> onData called! Buffer null: ${audioData == null}")
        audioData?.let {
            // Write audio data to file
            val channel = Channels.newChannel(fileOutputStream)
            channel.write(it)
//            fileOutputStream?.write(it)
            // Pass data to callback for further processing if needed
            onAudioData(it)
        }
    }
}