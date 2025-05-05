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
import android.widget.Toast // Added for potential feedback
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
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.lang.UnsupportedOperationException // Added for AudioRecord check

class MainActivity : AppCompatActivity() {

    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var binding: ActivityMainBinding

    private var isRecording = false
    private var audioRecord: AudioRecord? = null
    private val RECORD_REQUEST_CODE = 101

    // --- Audio recording settings ---
    private val SAMPLE_RATE = 44100
    // ***** MODIFIED: Use STEREO configuration *****
    private val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_STEREO
    private val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    // ********************************************
    private val BITS_PER_SAMPLE: Short = 16 // Explicitly define for header calculation

    private lateinit var recordButton: Button
    private lateinit var audioManager: AudioManager
    private var assetExoPlayer: ExoPlayer? = null
    private val ASSET_AUDIO_FILENAME = "classroom-32941.mp3"
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    private var outputFile: File? = null // Keep track of the current file

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
        recordButton.text = "Start Recording"
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
                // Check if recording is active *or* if audioRecord was successfully initialized previously
                // Playing asset audio might be desired even if not currently recording,
                // as long as an audio session was available (needed for AEC).
                val currentAudioSessionId = audioRecord?.audioSessionId
                if (currentAudioSessionId != null && currentAudioSessionId != 0) {
                    startAssetPlayback(ASSET_AUDIO_FILENAME, currentAudioSessionId)
                } else {
                    Log.e("AssetPlayer", "Cannot play asset: AudioRecord session ID not available. Start recording first.")
                    Toast.makeText(this, "Start recording first to get audio session", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    @OptIn(UnstableApi::class) private fun startAssetPlayback(assetFileName: String, audioSessionId: Int) {
        // Release previous instance if any
        stopAssetPlayback()
        Log.i("AssetPlayer", "Starting playback for $assetFileName with session ID: $audioSessionId")

        try {
            // Initialize ExoPlayer
            assetExoPlayer = ExoPlayer.Builder(this).build().apply {
                // Set audio session ID
                // Important: Ensure the audioSessionId is valid (> 0)
                if (audioSessionId > 0) {
                    try {
                        setAudioSessionId(audioSessionId)
                        Log.i("AssetPlayer", "Successfully set audio session ID $audioSessionId for ExoPlayer")
                    } catch (e: Exception) {
                        Log.e("AssetPlayer", "Failed to set audio session ID $audioSessionId for ExoPlayer", e)
                        // Proceed without session ID if setting fails? Or stop? Decide based on requirements.
                    }
                } else {
                    Log.w("AssetPlayer", "Invalid audio session ID ($audioSessionId), cannot link ExoPlayer.")
                }

                // Create MediaSource from asset
                val afd: AssetFileDescriptor = assets.openFd(assetFileName)
                // Using AssetDataSource directly is slightly simpler if only playing from assets
                val assetDataSource = AssetDataSource(this@MainActivity)
                assetDataSource.open(androidx.media3.datasource.DataSpec(Uri.parse("asset:///$assetFileName")))

                val mediaSource: MediaSource = ProgressiveMediaSource.Factory(
                    { assetDataSource } // Provide the AssetDataSource instance here
                ).createMediaSource(MediaItem.fromUri(assetDataSource.uri!!)) // Use URI from opened datasource


                // Set media source and prepare
                setMediaSource(mediaSource)
                prepare()
                play()

                // Update button text
                binding.btnPlayAudio.text = "Stop Asset Audio"

                // Handle completion
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

                // AssetFileDescriptor should be closed after the source is created/used,
                // but ExoPlayer's sources often manage this internally.
                // Closing it here *might* be too early. Test carefully. Let ExoPlayer manage it.
                // afd.close() // Possibly remove this explicit close
            }
        } catch (e: FileNotFoundException) {
            Log.e("AssetPlayer", "Asset file not found: $assetFileName", e)
            stopAssetPlayback()
        } catch (e: IOException) {
            Log.e("AssetPlayer", "IOException setting data source or preparing asset: $assetFileName", e)
            stopAssetPlayback()
        } catch (e: IllegalStateException) {
            Log.e("AssetPlayer", "IllegalStateException during asset playback setup", e)
            stopAssetPlayback()
        } catch (e: SecurityException) {
            Log.e("AssetPlayer", "SecurityException reading asset: $assetFileName", e)
            stopAssetPlayback()
        } catch (e: Exception) { // Catch generic exceptions
            Log.e("AssetPlayer", "Unexpected error during asset playback setup", e)
            stopAssetPlayback()
        }
    }


    private fun stopAssetPlayback() {
        assetExoPlayer?.apply {
            try {
                if (isPlaying) {
                    stop()
                }
                release()
            } catch (e: IllegalStateException) {
                Log.e("AssetPlayer", "IllegalStateException on stop/release", e)
                // Attempt release again if stop failed
                try { release() } catch (re: Exception) { Log.e("AssetPlayer", "Error on secondary release", re) }
            }
        }
        assetExoPlayer = null
        binding.btnPlayAudio.text = "Play Asset Audio"
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
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
        if (requestCode == RECORD_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.i("Permission", "Audio permission granted.")
                startRecording()
            } else {
                Log.w("Permission", "Audio permission denied.")
                Toast.makeText(this, "Audio permission is required to record", Toast.LENGTH_SHORT).show()
            }
        }
    }

    @SuppressLint("MissingPermission") // Permission is checked before calling
    private fun startRecording() {
        // Check if already recording
        if (isRecording) {
            Log.w("Recording", "Already recording, ignoring start request.")
            return
        }

        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION // Keep for potential AEC benefits
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)

        var minBufferSize: Int
        try {
            minBufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                CHANNEL_CONFIG, // Using STEREO config
                AUDIO_FORMAT
            )
            // Handle cases where configuration is not supported or buffer size is invalid
            if (minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
                Log.e("Recording", "Invalid AudioRecord parameter.")
                Toast.makeText(this, "Audio recording parameters invalid", Toast.LENGTH_SHORT).show()
                return
            }
            if (minBufferSize == AudioRecord.ERROR) {
                Log.e("Recording", "Unable to query buffer size, configuration likely not supported.")
                Toast.makeText(this, "Stereo recording may not be supported on this device", Toast.LENGTH_LONG).show()
                // Optional: Fallback to MONO here if desired
                return
            }
            Log.d("Recording", "Calculated min buffer size: $minBufferSize bytes")
            // Ensure buffer size is reasonable, increase if needed (e.g., minBufferSize * 2)
            if (minBufferSize < 4096) { // Example: ensure at least a certain size
                minBufferSize *= 2
                Log.d("Recording", "Increased buffer size to: $minBufferSize bytes")
            }

        } catch (e: Exception) {
            Log.e("Recording", "Exception getting min buffer size", e)
            Toast.makeText(this, "Failed to get audio buffer size", Toast.LENGTH_SHORT).show()
            return
        }


        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.CAMCORDER, // Keep or try MIC if stereo issues with VOICE_COMM
                SAMPLE_RATE,
                CHANNEL_CONFIG, // Using STEREO config
                AUDIO_FORMAT,
                minBufferSize // Use calculated (and potentially increased) buffer size
            ).apply {
                // Check state after creation
                if (this.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e("Recording", "AudioRecord failed to initialize. State: ${this.state}")
                    Toast.makeText(this@MainActivity, "Failed to initialize audio recorder. Stereo might not be supported.", Toast.LENGTH_LONG).show()
                    release() // Release the failed recorder object
                    audioRecord = null // Ensure it's null
                    return@startRecording // Exit the startRecording function
                }

                Log.d("Recording", "AudioRecord initialized successfully. Session ID: ${this.audioSessionId}, Channels: ${this.channelCount}, Format: ${this.audioFormat}, Sample Rate: ${this.sampleRate}")

                // Enable Acoustic Echo Cancellation (May or may not work well with stereo)
                // Check availability *before* creating
                if (AcousticEchoCanceler.isAvailable()) {
                    try {
                        aec = AcousticEchoCanceler.create(this.audioSessionId).apply {
                            enabled = true // Attempt to enable
                            Log.i("Recording", "AEC available and attempted to enable. Enabled: $enabled")
                        }
                        if (aec == null) Log.w("Recording", "AEC is available but create() returned null.")
                    } catch (e: RuntimeException) {
                        Log.e("Recording", "Error creating or enabling AEC", e)
                        aec = null // Ensure it's null if creation failed
                    } catch (e: UnsupportedOperationException) {
                        Log.w("Recording", "AEC not supported on this device/configuration.", e)
                        aec = null
                    }
                } else {
                    Log.w("Recording", "AEC not available on this device.")
                    aec = null
                }

                // Enable Noise Suppressor (May or may not work well with stereo)
                // Check availability *before* creating
                if (NoiseSuppressor.isAvailable()) {
                    try {
                        ns = NoiseSuppressor.create(this.audioSessionId).apply {
                            enabled = true // Attempt to enable
                            Log.i("Recording", "NS available and attempted to enable. Enabled: $enabled")
                        }
                        if (ns == null) Log.w("Recording", "NS is available but create() returned null.")
                    } catch (e: RuntimeException) {
                        Log.e("Recording", "Error creating or enabling NS", e)
                        ns = null // Ensure it's null if creation failed
                    } catch (e: UnsupportedOperationException) {
                        Log.w("Recording", "NS not supported on this device/configuration.", e)
                        ns = null
                    }
                } else {
                    Log.w("Recording", "NS not available on this device.")
                    ns = null
                }
            }
        } catch (e: IllegalArgumentException) {
            Log.e("Recording", "IllegalArgumentException initializing AudioRecord. Check parameters.", e)
            Toast.makeText(this, "Invalid recording parameters.", Toast.LENGTH_SHORT).show()
            return
        } catch (e: UnsupportedOperationException) {
            // This can happen if the combination of source, sample rate, channel config, format is not supported
            Log.e("Recording", "UnsupportedOperationException initializing AudioRecord. Stereo/Config not supported?", e)
            Toast.makeText(this, "Stereo recording configuration not supported on this device.", Toast.LENGTH_LONG).show()
            audioRecord?.release() // Clean up potential partial object
            audioRecord = null
            return
        } catch (e: SecurityException) {
            Log.e("Recording", "SecurityException initializing AudioRecord. Missing permission?", e)
            Toast.makeText(this, "Recording permission error.", Toast.LENGTH_SHORT).show()
            // Consider re-requesting permission or guiding user
            return
        }

        // If audioRecord is still null after try-catch and checks, exit.
        if (audioRecord == null) {
            Log.e("Recording", "AudioRecord is null after initialization attempt, cannot start recording.")
            return
        }


        val audioData = ByteArray(minBufferSize)
        // Store the file reference to be accessible in stopRecording and saving functions
        outputFile = File(cacheDir, "recording_${System.currentTimeMillis()}.raw") // Save raw first
        Log.d("Recording", "Temporary output file: ${outputFile?.absolutePath}")


        try {
            audioRecord?.startRecording()
            // Double check state after starting
            if (audioRecord?.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                Log.e("Recording", "AudioRecord failed to start recording. State: ${audioRecord?.recordingState}")
                Toast.makeText(this, "Failed to start recording.", Toast.LENGTH_SHORT).show()
                releaseAudioRecordResources() // Clean up thoroughly
                return
            }
        } catch (e: IllegalStateException) {
            Log.e("Recording", "IllegalStateException on startRecording.", e)
            Toast.makeText(this, "Failed to start recorder.", Toast.LENGTH_SHORT).show()
            releaseAudioRecordResources() // Clean up thoroughly
            return
        }

        isRecording = true
        recordButton.text = "Stop Recording"
        Log.i("Recording", "Recording started...")

        // Start the background thread for reading and writing
        thread {
            var fileOutputStream: FileOutputStream? = null
            try {
                fileOutputStream = FileOutputStream(outputFile)
                // Write WAV header placeholders
                writeWavHeader(fileOutputStream, CHANNEL_CONFIG, AUDIO_FORMAT)

                Log.d("RecordingThread", "Starting read loop.")
                while (isRecording) {
                    // Check audioRecord state periodically within the loop
                    if (audioRecord == null || audioRecord?.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                        Log.w("RecordingThread", "AudioRecord state invalid (${audioRecord?.recordingState}), stopping thread.")
                        break // Exit loop if recorder state becomes invalid
                    }

                    val readResult = audioRecord?.read(audioData, 0, minBufferSize) ?: AudioRecord.ERROR_INVALID_OPERATION

                    if (readResult > 0) {
                        try {
                            fileOutputStream.write(audioData, 0, readResult)
                        } catch (e: IOException) {
                            Log.e("RecordingThread", "IOException writing to file", e)
                            isRecording = false // Stop recording on write error
                            break // Exit loop
                        }
                    } else {
                        // Handle read errors
                        handleAudioRecordReadError(readResult)
                        if (readResult == AudioRecord.ERROR_INVALID_OPERATION || readResult == AudioRecord.ERROR_BAD_VALUE || readResult == AudioRecord.ERROR_DEAD_OBJECT) {
                            isRecording = false // Stop recording on critical errors
                            break
                        }
                        // ERROR or ERROR_TIMEOUT might be recoverable, add short delay?
                        // Thread.sleep(10)
                    }
                }
                Log.d("RecordingThread", "Exited read loop. isRecording=$isRecording")

            } catch (e: IOException) {
                Log.e("RecordingThread", "IOException opening FileOutputStream", e)
                isRecording = false // Ensure recording stops
            } catch (e: Exception) { // Catch any other unexpected errors in the thread
                Log.e("RecordingThread", "Unexpected error in recording thread", e)
                isRecording = false
            } finally {
                try {
                    fileOutputStream?.close()
                    Log.d("RecordingThread", "FileOutputStream closed.")
                } catch (e: IOException) {
                    Log.e("RecordingThread", "IOException closing FileOutputStream", e)
                }

                // Update header and save only if recording stopped successfully and outputFile is valid
                val finalOutputFile = outputFile // Capture value for safety
                if (!isRecording && finalOutputFile != null && finalOutputFile.exists() && finalOutputFile.length() > 44) {
                    try {
                        Log.d("RecordingThread", "Updating WAV header for ${finalOutputFile.name}")
                        updateWavHeader(finalOutputFile, CHANNEL_CONFIG, AUDIO_FORMAT)
                        // Move saving logic here to ensure it happens after the file is closed and header updated
                        Log.d("RecordingThread", "Saving final WAV file...")
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { // Use Q for scoped storage properly
                            saveToSharedStorage(finalOutputFile)
                        } else {
                            saveToExternalStorage(finalOutputFile)
                        }
                        Log.i("Recording", "Recording saved successfully.")
                    } catch (e: IOException) {
                        Log.e("RecordingThread", "Error updating header or saving file", e)
                        runOnUiThread { Toast.makeText(this@MainActivity, "Error saving recording", Toast.LENGTH_SHORT).show() }
                        // Optionally delete the corrupt file
                        // finalOutputFile.delete()
                    }
                } else {
                    Log.w("RecordingThread", "Skipping header update/save. isRecording=$isRecording, outputFile=$finalOutputFile")
                    // Delete the potentially empty or corrupt file if saving shouldn't happen
                    finalOutputFile?.delete()
                }
            }
        }
    }

    private fun handleAudioRecordReadError(errorCode: Int) {
        when (errorCode) {
            AudioRecord.ERROR_INVALID_OPERATION -> Log.e("RecordingThread", "Read error: ERROR_INVALID_OPERATION")
            AudioRecord.ERROR_BAD_VALUE -> Log.e("RecordingThread", "Read error: ERROR_BAD_VALUE")
            AudioRecord.ERROR_DEAD_OBJECT -> Log.e("RecordingThread", "Read error: ERROR_DEAD_OBJECT (Recorder died)")
            AudioRecord.ERROR -> Log.w("RecordingThread", "Read error: ERROR (Unspecified)")
            // AudioRecord.ERROR_TIMEOUT -> Log.v("RecordingThread", "Read error: ERROR_TIMEOUT") // Less severe, maybe ignore or log verbosely
            else -> Log.w("RecordingThread", "Read returned unexpected code: $errorCode")
        }
    }


    private fun stopRecording() {
        if (!isRecording) {
            Log.w("Recording", "Stop called but not currently recording.")
            // Ensure resources are released if stop is called unexpectedly
            releaseAudioRecordResources()
            runOnUiThread { // Ensure UI updates are on the main thread
                recordButton.text = "Start Recording"
            }
            return
        }

        isRecording = false // Signal the recording thread to stop
        Log.i("Recording", "Stop recording requested. Waiting for thread to finish...")

        // Note: The actual stop/release and file operations now happen
        //       at the end of the recording thread to ensure file is complete.
        //       We only update UI immediately.

        // Release AudioRecord and effects - MUST happen after the thread loop finishes reading
        // Moved the core release logic to a separate function for clarity
        releaseAudioRecordResources()

        runOnUiThread { // Ensure UI updates are on the main thread
            recordButton.text = "Start Recording"
            Toast.makeText(this, "Recording stopped.", Toast.LENGTH_SHORT).show()
        }
        Log.i("Recording", "Stop recording processing finished on main thread.")
    }

    // Helper function to release AudioRecord and effects safely
    private fun releaseAudioRecordResources() {
        if (audioRecord != null) {
            // Check state before stopping
            if (audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                try {
                    audioRecord?.stop()
                    Log.d("Recording", "AudioRecord stopped.")
                } catch (e: IllegalStateException) {
                    Log.e("Recording", "IllegalStateException stopping AudioRecord.", e)
                }
            }
            // Check state before releasing
            if (audioRecord?.state == AudioRecord.STATE_INITIALIZED) {
                try {
                    audioRecord?.release()
                    Log.d("Recording", "AudioRecord released.")
                } catch (e: Exception) { // Catch potential exceptions on release
                    Log.e("Recording", "Exception releasing AudioRecord", e)
                }
            }
            audioRecord = null
        } else {
            Log.d("Recording", "AudioRecord was already null, no need to release.")
        }

        // Release effects safely
        try {
            aec?.release()
            Log.d("Recording", "AEC released.")
        } catch (e: IllegalStateException) {
            Log.e("Recording", "IllegalStateException releasing AEC.", e)
        } catch (e: Exception) {
            Log.e("Recording", "Exception releasing AEC", e)
        }
        aec = null

        try {
            ns?.release()
            Log.d("Recording", "NS released.")
        } catch (e: IllegalStateException) {
            Log.e("Recording", "IllegalStateException releasing NS.", e)
        } catch (e: Exception) {
            Log.e("Recording", "Exception releasing NS", e)
        }
        ns = null

        // Reset AudioManager mode if necessary (optional, depends on app behavior)
        // audioManager.mode = AudioManager.MODE_NORMAL
    }


    // ***** MODIFIED: writeWavHeader now accepts channelConfig and audioFormat *****
    private fun writeWavHeader(out: FileOutputStream, channelConfig: Int, audioFormat: Int) {
        val channels = when (channelConfig) {
            AudioFormat.CHANNEL_IN_STEREO -> 2
            AudioFormat.CHANNEL_IN_MONO -> 1
            else -> 1 // Default to mono for unknown configs
        }
        val bitsPerSample = when (audioFormat) {
            AudioFormat.ENCODING_PCM_16BIT -> 16
            AudioFormat.ENCODING_PCM_8BIT -> 8
            else -> 16 // Default to 16-bit
        }
        val byteRate = SAMPLE_RATE * channels * (bitsPerSample / 8)
        val blockAlign = (channels * (bitsPerSample / 8)).toShort()

        val totalAudioLen = 0L // Placeholder - Will be updated later
        val totalDataLen = totalAudioLen + 36 // 36 bytes for header before data

        Log.d("WavHeader", "Writing header: channels=$channels, bitsPerSample=$bitsPerSample, byteRate=$byteRate, blockAlign=$blockAlign")

        val header = ByteArray(44)
        header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte(); header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte() // ChunkID
        header[4] = (totalDataLen and 0xff).toByte(); header[5] = (totalDataLen shr 8 and 0xff).toByte(); header[6] = (totalDataLen shr 16 and 0xff).toByte(); header[7] = (totalDataLen shr 24 and 0xff).toByte() // ChunkSize
        header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte(); header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte() // Format
        header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte(); header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte() // Subchunk1ID
        header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0 // Subchunk1Size (16 for PCM)
        header[20] = 1; header[21] = 0 // AudioFormat (1 for PCM)
        // ***** MODIFIED: Write correct number of channels *****
        header[22] = channels.toByte(); header[23] = 0
        // *******************************************************
        header[24] = (SAMPLE_RATE and 0xff).toByte(); header[25] = (SAMPLE_RATE shr 8 and 0xff).toByte(); header[26] = (SAMPLE_RATE shr 16 and 0xff).toByte(); header[27] = (SAMPLE_RATE shr 24 and 0xff).toByte() // SampleRate
        header[28] = (byteRate and 0xff).toByte(); header[29] = (byteRate shr 8 and 0xff).toByte(); header[30] = (byteRate shr 16 and 0xff).toByte(); header[31] = (byteRate shr 24 and 0xff).toByte() // ByteRate
        // ***** MODIFIED: Write correct block align *****
        header[32] = (blockAlign.toInt() and 0xff).toByte(); header[33] = (blockAlign.toInt() shr 8 and 0xff).toByte()
        // **********************************************
        header[34] = (bitsPerSample and 0xff).toByte(); header[35] = (bitsPerSample.toInt() shr 8 and 0xff).toByte() // BitsPerSample
        header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte(); header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte() // Subchunk2ID
        header[40] = (totalAudioLen and 0xff).toByte(); header[41] = (totalAudioLen shr 8 and 0xff).toByte(); header[42] = (totalAudioLen shr 16 and 0xff).toByte(); header[43] = (totalAudioLen shr 24 and 0xff).toByte() // Subchunk2Size

        out.write(header, 0, 44)
    }

    // ***** ADDED: updateWavHeader now also accepts channelConfig and audioFormat *****
    // Although not strictly necessary for the update logic itself (only sizes change),
    // passing them makes the function signature consistent with writeWavHeader.
    private fun updateWavHeader(file: File, channelConfig: Int, audioFormat: Int) {
        val fileSize = file.length()
        if (fileSize < 44) {
            Log.e("WavHeader", "File size (${fileSize}b) is too small to be a valid WAV, cannot update header.")
            return // Prevent errors on empty/corrupt files
        }
        val totalAudioLen = fileSize - 44 // Size of data chunk
        val totalDataLen = fileSize - 8    // Total size minus RIFF and WAVE equals ChunkSize

        Log.d("WavHeader", "Updating header for ${file.name}: fileSize=$fileSize, totalAudioLen=$totalAudioLen, totalDataLen=$totalDataLen")

        try {
            RandomAccessFile(file, "rw").use { raf ->
                raf.seek(4) // Seek to ChunkSize field
                raf.write(byteArrayOf(
                    (totalDataLen and 0xff).toByte(),
                    (totalDataLen shr 8 and 0xff).toByte(),
                    (totalDataLen shr 16 and 0xff).toByte(),
                    (totalDataLen shr 24 and 0xff).toByte()
                ))

                raf.seek(40) // Seek to Subchunk2Size field (data size)
                raf.write(byteArrayOf(
                    (totalAudioLen and 0xff).toByte(),
                    (totalAudioLen shr 8 and 0xff).toByte(),
                    (totalAudioLen shr 16 and 0xff).toByte(),
                    (totalAudioLen shr 24 and 0xff).toByte()
                ))
                Log.d("WavHeader", "Header updated successfully.")
            }
        } catch(e: IOException) {
            Log.e("WavHeader", "IOException updating WAV header for ${file.name}", e)
        }
    }


    // --- Saving functions (modified slightly for robustness) ---

    private fun saveToExternalStorage(tempFile: File) {
        val timestamp = System.currentTimeMillis()
        val fileName = "recording_stereo_$timestamp.wav" // Indicate stereo in filename
        val recordingsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)

        if (Environment.getExternalStorageState() != Environment.MEDIA_MOUNTED) {
            Log.e("SaveStorage", "External storage not mounted or readable.")
            runOnUiThread { Toast.makeText(this, "External storage unavailable", Toast.LENGTH_SHORT).show() }
            tempFile.delete() // Clean up temp file if can't save
            return
        }

        // Create Recordings directory if it doesn't exist
        if (!recordingsDir.exists()) {
            if (!recordingsDir.mkdirs()) {
                Log.e("SaveStorage", "Failed to create recordings directory.")
                runOnUiThread { Toast.makeText(this, "Failed to create directory", Toast.LENGTH_SHORT).show() }
                tempFile.delete()
                return
            }
        }

        val destinationFile = File(recordingsDir, fileName)
        Log.d("SaveStorage", "Attempting to save to: ${destinationFile.absolutePath}")
        try {
            FileInputStream(tempFile).use { input ->
                FileOutputStream(destinationFile).use { output ->
                    val copied = input.copyTo(output)
                    Log.d("SaveStorage", "Copied $copied bytes to public storage.")
                }
            }
            tempFile.delete() // Delete cache file on success
            Log.i("SaveStorage", "Successfully saved to ${destinationFile.absolutePath}")
            runOnUiThread { Toast.makeText(this, "Saved to Music/$fileName", Toast.LENGTH_LONG).show() }

        } catch (e: IOException) {
            Log.e("SaveStorage", "Error saving file to public storage", e)
            runOnUiThread { Toast.makeText(this, "Error saving file", Toast.LENGTH_SHORT).show() }
            // Keep tempFile for debugging? Or delete?
            // destinationFile.delete() // Delete partially written file if copy failed
            tempFile.delete() // Delete cache file even on failure to avoid buildup
        }
    }

    @RequiresApi(Build.VERSION_CODES.S) // Changed to Q as RELATIVE_PATH requires API 29
    private fun saveToSharedStorage(tempFile: File) {
        val timestamp = System.currentTimeMillis()
        val fileName = "recording_stereo_$timestamp.wav" // Indicate stereo in filename

        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
            // RELATIVE_PATH requires API 29 (Q)
            put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_RECORDINGS)
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }

        val resolver = contentResolver
        var uri: Uri? = null

        try {
            uri = resolver.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)

            if (uri == null) {
                Log.e("SaveStorage", "Failed to create MediaStore entry.")
                throw IOException("MediaStore insert returned null URI")
            }

            Log.d("SaveStorage", "MediaStore URI created: $uri")
            resolver.openOutputStream(uri)?.use { outputStream ->
                FileInputStream(tempFile).use { inputStream ->
                    val copied = inputStream.copyTo(outputStream)
                    Log.d("SaveStorage", "Copied $copied bytes via MediaStore.")
                }
            } ?: throw IOException("Failed to open output stream for URI: $uri")

            values.clear()
            values.put(MediaStore.Audio.Media.IS_PENDING, 0)
            val updatedRows = resolver.update(uri, values, null, null)
            if (updatedRows > 0) {
                Log.i("SaveStorage", "Successfully saved to MediaStore: $uri")
                runOnUiThread { Toast.makeText(this, "Saved to Recordings/$fileName", Toast.LENGTH_LONG).show() }
            } else {
                Log.w("SaveStorage", "Failed to finalize MediaStore entry (set IS_PENDING to 0).")
                // File might still be usable, but flagged as pending.
                runOnUiThread { Toast.makeText(this, "File saved but might be pending", Toast.LENGTH_SHORT).show() }
            }
        } catch (e: IOException) {
            Log.e("SaveStorage", "Error saving file via MediaStore", e)
            runOnUiThread { Toast.makeText(this, "Error saving file", Toast.LENGTH_SHORT).show() }
            // Clean up MediaStore entry if possible if saving failed
            uri?.let { failedUri ->
                try {
                    resolver.delete(failedUri, null, null)
                    Log.d("SaveStorage", "Deleted pending MediaStore entry due to error.")
                } catch (deleteEx: Exception) {
                    Log.e("SaveStorage", "Error deleting pending MediaStore entry", deleteEx)
                }
            }
        } finally {
            // Clean up temporary file regardless of success or failure
            if (tempFile.exists()) {
                if (tempFile.delete()) {
                    Log.d("SaveStorage", "Temporary file deleted: ${tempFile.name}")
                } else {
                    Log.w("SaveStorage", "Failed to delete temporary file: ${tempFile.name}")
                }
            }
        }
    }

    // --- Lifecycle ---
    override fun onDestroy() {
        super.onDestroy()
        Log.d("Lifecycle", "onDestroy called.")
        // Ensure resources are released if the activity is destroyed
        if (isRecording) {
            isRecording = false // Signal thread to stop if somehow still running
        }
        releaseAudioRecordResources() // Release recorder and effects
        stopAssetPlayback() // Release player
    }

    override fun onPause() {
        super.onPause()
        Log.d("Lifecycle", "onPause called.")
        // Consider stopping recording when the app is paused,
        // unless background recording is a feature.
        if (isRecording) {
            Log.w("Lifecycle", "Recording active during onPause - stopping.")
            stopRecording()
        }
        // Pause asset playback if desired
        // if (assetExoPlayer?.isPlaying == true) {
        //    assetExoPlayer?.pause()
        //    binding.btnPlayAudio.text = "Resume Asset Audio" // Update UI
        // }
        // Or fully stop playback:
        stopAssetPlayback()
    }
}