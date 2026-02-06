package com.winton.validationshell.engine

import android.util.Log

class Engine {
    fun startAudioEngine(conservativeMode: Boolean): Boolean {
        Log.d("EngineStub", "startAudioEngine(conservativeMode=$conservativeMode)")
        return true
    }

    fun stopAudioEngine() {
        Log.d("EngineStub", "stopAudioEngine()")
    }

    fun getAnalysisData(): FloatArray {
        // Mock data: [rms, spectralCentroid, classification, confidence]
        // classification: 1: Speech, 2: Music, 3: Mixed
        return floatArrayOf(
            (0.1 + Math.random() * 0.4).toFloat(), // RMS
            (0.2 + Math.random() * 0.5).toFloat(), // Spectral Centroid
            1.0f,                                   // Classification (Speech)
            0.95f                                  // Confidence
        )
    }

    fun setPolicy(policyId: Int) {
        Log.d("EngineStub", "setPolicy(policyId=$policyId)")
    }

    fun setAutoPolicy(enabled: Boolean) {
        Log.d("EngineStub", "setAutoPolicy(enabled=$enabled)")
    }

    fun healthCheck(): String {
        return "Engine Stub Ready"
    }
}
