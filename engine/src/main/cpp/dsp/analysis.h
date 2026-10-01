#pragma once

#include <vector>
#include <complex>
#include <utility>

namespace winton::native {

/**
 * Modular analysis component for feature extraction.
 * Includes RMS, Spectral Centroid, ZCR, and MFCC.
 */
class Analysis {
public:
    /**
     * Compute Root Mean Square (RMS) loudness.
     */
    static float computeRms(const float* samples, int count);

    /**
     * Apply Hann window in-place.
     */
    static void applyHannWindow(std::vector<float>& samples);

    /**
     * Compute Spectral Centroid (frequency-weighted center of gravity).
     * @param magnitudes FFT magnitude spectrum (size N/2 + 1)
     */
    static float computeSpectralCentroid(const std::vector<float>& magnitudes, int sampleRate);

    /**
     * Compute Zero Crossing Rate (ZCR) with noise floor rejection.
     */
    static float computeZcr(const float* samples, int count);

    /**
     * Heuristic classification based on RMS and Centroid.
     * @return {classId, confidence}
     */
    static std::pair<int, float> classify(float rms, float centroid);

    /**
     * Compute Mel-Frequency Cepstral Coefficients (MFCC).
     * Pipeline: Pre-emphasis -> Hann -> FFT -> Mel Filterbank -> Log -> DCT-II.
     *
     * @param samples Input samples (power-of-2 size)
     * @param sampleRate Sample rate in Hz
     * @param nMfcc Number of coefficients to return (default 13)
     * @param fftBuffer Pre-allocated scratch buffer for FFT
     * @param magBuffer Pre-allocated scratch buffer for magnitudes
     * @return 13 coefficients
     */
    static std::vector<float> computeMfcc(
        const float* samples,
        int count,
        int sampleRate,
        int nMfcc,
        std::vector<std::complex<float>>& fftBuffer,
        std::vector<float>& magBuffer
    );

private:
    /**
     * Discrete Cosine Transform (Type II) with Orthonormal normalization.
     */
    static std::vector<float> computeDct(const std::vector<double>& input);
};

} // namespace winton::native
