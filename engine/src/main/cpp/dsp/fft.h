#pragma once

#include <vector>
#include <complex>

namespace winton::native {

/**
 * Radix-2 Cooley-Tukey FFT implementation for power-of-2 frame sizes.
 *
 * Algorithm:
 *   - Bit-reversal permutation
 *   - Staged butterfly computation (len = 2, 4, 8, ... N)
 *   - Returns magnitude spectrum (N/2 bins)
 *
 * Constraints:
 *   - Input size MUST be a power-of-2 (1, 2, 4, 8, ..., 2048, 4096, ...)
 *   - No validation; caller must ensure compliance
 *   - In-place processing; destroys input
 *
 * Complexity: O(N log N), typical ~10-20 µs for N=1024 @ 48kHz
 */
class FFT {
public:
    /**
     * Compute magnitude spectrum of real-valued input.
     *
     * @param samples Real-valued time-domain signal (must be power-of-2 size)
     * @return Magnitude spectrum [0..N/2] bins (Nyquist inclusive)
     */
    static std::vector<float> magnitudeSpectrum(const std::vector<float>& samples);

    /**
     * Compute magnitude spectrum with pre-allocated buffers (for reuse).
     *
     * @param samples Input samples
     * @param fftBuffer Pre-allocated complex buffer (will be resized to samples.size())
     * @param magnitudes Pre-allocated output buffer (will be resized to samples.size()/2 + 1)
     */
    static void magnitudeSpectrum(
        const std::vector<float>& samples,
        std::vector<std::complex<float>>& fftBuffer,
        std::vector<float>& magnitudes
    );

private:
    /**
     * Bit-reversal permutation (in-place).
     * Rearranges buffer[i] ↔ buffer[reverse(i)] for all i.
     */
    static void bitReverse(std::vector<std::complex<float>>& buffer);

    /**
     * Radix-2 butterfly stages.
     * Processes len = 2, 4, 8, ... up to buffer.size().
     */
    static void butterflyStages(std::vector<std::complex<float>>& buffer);
};

} // namespace winton::native

