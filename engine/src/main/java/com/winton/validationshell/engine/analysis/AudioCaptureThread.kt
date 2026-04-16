package com.winton.validationshell.engine.analysis

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log

/**
 * Background thread that captures audio from the microphone via [AudioRecord],
 * runs [FeatureExtractor] on each frame, and stores the latest analysis results.
 *
 * Thread lifecycle is managed by [start] / [stopAndJoin].
 * The caller polls [latestResult] at whatever rate it needs (e.g. 10 Hz).
 *
 * Design constraints:
 *  - Runs entirely in `:engine` — no Android UI dependency.
 *  - Uses the lowest-latency configuration the device supports.
 *  - Fails gracefully: if AudioRecord cannot be initialised, the thread
 *    never starts and [latestResult] returns a silent/zero frame.
 */
class AudioCaptureThread(
    private val sampleRate: Int = 44100,
    private val frameSize: Int = 1024 // samples per analysis frame (must be power of 2)
) : Thread("AudioCaptureThread") {

    companion object {
        private const val TAG = "AudioCaptureThread"
    }

    // --- Result container (read by polling thread) ---
    data class AnalysisResult(
        val rms: Float = 0f,
        val spectralCentroid: Float = 0f,
        val classification: Int = 0,        // 0=Silence, 1=Speech, 2=Music, 3=Mixed
        val confidence: Float = 0f,
        val captureLatencyMs: Float = -1f
    )

    @Volatile
    var latestResult: AnalysisResult = AnalysisResult()
        private set

    @Volatile
    private var running = false

    private var recorder: AudioRecord? = null

    /** A=true bypass raw signal, B=false processed signal when a chain exists. */
    @Volatile
    var bypassMode: Boolean = false

    /** Current processing chain; null means passthrough. */
    @Volatile
    var filterChain: BiquadFilterChain? = null

    /**
     * Start the capture thread. Call from engine start.
     * Safe to call if already running (no-op).
     */
    override fun start() {
        if (running) return
        running = true
        super.start()
    }

    /**
     * Signal the thread to stop and wait for it to finish.
     * Releases the [AudioRecord] resource.
     */
    fun stopAndJoin() {
        running = false
        try {
            join(2000) // wait up to 2 s
        } catch (e: InterruptedException) {
            Log.w(TAG, "Interrupted while joining capture thread", e)
        }
    }

    @SuppressLint("MissingPermission") // Permission is checked by the app layer before starting
    override fun run() {
        Log.i(TAG, "Capture thread started (sampleRate=$sampleRate, frameSize=$frameSize)")
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
        } catch (e: Exception) {
            Log.w(TAG, "Could not raise thread priority to audio", e)
        }

        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT

        // Validate sample rate: try requested, fall back to 44100 if unsupported
        var activeSampleRate = sampleRate
        var minBuf = AudioRecord.getMinBufferSize(activeSampleRate, channelConfig, audioFormat)
        if (minBuf <= 0 && activeSampleRate != 44100) {
            Log.w(TAG, "Sample rate $activeSampleRate unsupported, falling back to 44100")
            activeSampleRate = 44100
            minBuf = AudioRecord.getMinBufferSize(activeSampleRate, channelConfig, audioFormat)
        }
        if (minBuf <= 0) {
            Log.e(TAG, "getMinBufferSize returned $minBuf — cannot record")
            running = false
            return
        }

        // Use at least 2× the frame size, or the system minimum — whichever is larger
        val bufferSize = maxOf(minBuf, frameSize * 2 * 2) // *2 for 16-bit (2 bytes/sample)

        try {
            recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                activeSampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create AudioRecord", e)
            running = false
            return
        }

        val rec = recorder!!
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord not initialised (state=${rec.state})")
            rec.release()
            recorder = null
            running = false
            return
        }

        val shortBuffer = ShortArray(frameSize)
        val floatBuffer = FloatArray(frameSize)
        val processBuffer = FloatArray(frameSize)
        // Scratch buffer for windowed FFT input
        val windowedBuffer = FloatArray(frameSize)
        val fftReal = DoubleArray(frameSize)
        val fftImag = DoubleArray(frameSize)
        val fftMagnitudes = FloatArray(frameSize / 2)

        val outputMinBuffer = AudioTrack.getMinBufferSize(
            activeSampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT
        )
        val outputBufferSize = maxOf(outputMinBuffer, frameSize * 4 * 2)
        val outputTrack = if (outputMinBuffer > 0) {
            try {
                AudioTrack(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(activeSampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                    outputBufferSize,
                    AudioTrack.MODE_STREAM,
                    AudioManager.AUDIO_SESSION_ID_GENERATE
                ).also {
                    it.play()
                    Log.i(TAG, "AudioTrack started (rate=$activeSampleRate, bufferSize=$outputBufferSize)")
                }
            } catch (e: Exception) {
                Log.w(TAG, "AudioTrack init failed, output monitoring disabled", e)
                null
            }
        } else {
            Log.w(TAG, "AudioTrack min buffer unsupported ($outputMinBuffer), output monitoring disabled")
            null
        }

        try {
            rec.startRecording()
            Log.i(TAG, "AudioRecord started (rate=$activeSampleRate, bufferSize=$bufferSize, minBuf=$minBuf)")

            while (running) {
                val captureStartNs = System.nanoTime()

                // Read a full frame of 16-bit PCM
                val samplesRead = rec.read(shortBuffer, 0, frameSize)
                if (samplesRead <= 0) {
                    // ERROR or no data — yield and retry
                    sleep(5)
                    continue
                }

                // Convert Short → Float (normalise to -1.0 .. 1.0)
                for (i in 0 until samplesRead) {
                    floatBuffer[i] = shortBuffer[i] / 32768f
                }
                // Zero-fill remainder if partial read
                for (i in samplesRead until frameSize) {
                    floatBuffer[i] = 0f
                }

                // --- RMS ---
                val rms = FeatureExtractor.computeRms(floatBuffer, samplesRead)

                // --- Spectral Centroid via FFT magnitude ---
                // Copy + window
                System.arraycopy(floatBuffer, 0, windowedBuffer, 0, frameSize)
                FeatureExtractor.applyHannWindow(windowedBuffer)
                val magnitudes = fftMagnitudeSpectrum(windowedBuffer, fftReal, fftImag, fftMagnitudes)
                val centroid = FeatureExtractor.computeSpectralCentroid(magnitudes, activeSampleRate)

                // --- Classification ---
                val (classification, confidence) = FeatureExtractor.classify(rms, centroid)

                // --- Capture latency (time from read start to analysis done) ---
                val captureLatencyMs = (System.nanoTime() - captureStartNs) / 1_000_000f

                latestResult = AnalysisResult(
                    rms = rms,
                    spectralCentroid = centroid,
                    classification = classification,
                    confidence = confidence,
                    captureLatencyMs = captureLatencyMs
                )

                // Route audio to output as either bypass or processed signal.
                System.arraycopy(floatBuffer, 0, processBuffer, 0, samplesRead)
                val chain = filterChain
                if (!bypassMode && chain != null && !chain.isEmpty) {
                    chain.process(processBuffer, samplesRead)
                }
                outputTrack?.let { track ->
                    writeToTrackBlocking(track, processBuffer, samplesRead)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Capture loop error", e)
        } finally {
            try {
                rec.stop()
            } catch (e: Exception) {
                Log.w(TAG, "AudioRecord.stop() failed", e)
            }
            rec.release()
            recorder = null
            try {
                outputTrack?.stop()
            } catch (e: Exception) {
                Log.w(TAG, "AudioTrack.stop() failed", e)
            }
            try {
                outputTrack?.release()
            } catch (e: Exception) {
                Log.w(TAG, "AudioTrack.release() failed", e)
            }
            Log.i(TAG, "Capture thread stopped")
        }
    }

    // ========================================================================
    // IN-PLACE RADIX-2 FFT — O(N log N), works for power-of-2 frame sizes.
    // ========================================================================

    /**
     * Compute magnitude spectrum using an in-place radix-2 Cooley–Tukey FFT.
     * Returns N/2 magnitude bins.
     */
    private fun fftMagnitudeSpectrum(
        buffer: FloatArray,
        real: DoubleArray,
        imag: DoubleArray,
        magnitudes: FloatArray
    ): FloatArray {
        val n = buffer.size
        for (i in 0 until n) {
            real[i] = buffer[i].toDouble()
            imag[i] = 0.0
        }

        // Bit-reversal permutation
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                var tmp = real[i]; real[i] = real[j]; real[j] = tmp
                tmp = imag[i]; imag[i] = imag[j]; imag[j] = tmp
            }
        }

        // Cooley–Tukey butterfly
        var len = 2
        while (len <= n) {
            val angle = 2.0 * Math.PI / len
            val wReal = kotlin.math.cos(angle)
            val wImag = kotlin.math.sin(angle)
            var i = 0
            while (i < n) {
                var curReal = 1.0
                var curImag = 0.0
                for (k in 0 until len / 2) {
                    val tReal = curReal * real[i + k + len / 2] - curImag * imag[i + k + len / 2]
                    val tImag = curReal * imag[i + k + len / 2] + curImag * real[i + k + len / 2]
                    real[i + k + len / 2] = real[i + k] - tReal
                    imag[i + k + len / 2] = imag[i + k] - tImag
                    real[i + k] += tReal
                    imag[i + k] += tImag
                    val newCurReal = curReal * wReal - curImag * wImag
                    curImag = curReal * wImag + curImag * wReal
                    curReal = newCurReal
                }
                i += len
            }
            len = len shl 1
        }

        // Magnitude of first N/2 bins
        for (k in magnitudes.indices) {
            magnitudes[k] = kotlin.math.sqrt(real[k] * real[k] + imag[k] * imag[k]).toFloat()
        }
        return magnitudes
    }

    private fun writeToTrackBlocking(track: AudioTrack, buffer: FloatArray, size: Int) {
        var offset = 0
        while (running && offset < size) {
            val written = track.write(buffer, offset, size - offset, AudioTrack.WRITE_BLOCKING)
            if (written > 0) {
                offset += written
                continue
            }

            if (written == 0) {
                Log.w(TAG, "AudioTrack write returned 0, dropping frame (size=$size offset=$offset)")
            } else {
                Log.w(TAG, "AudioTrack write failed with code=$written (size=$size offset=$offset)")
            }
            break
        }
    }
}

