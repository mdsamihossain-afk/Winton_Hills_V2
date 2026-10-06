#include "fft.h"
#include "dsp_constants.h"

#include <cmath>
#include <algorithm>

namespace winton::native {

std::vector<float> FFT::magnitudeSpectrum(const std::vector<float>& samples) {
    std::vector<std::complex<float>> buffer;
    std::vector<float> magnitudes;
    magnitudeSpectrum(samples, buffer, magnitudes);
    return magnitudes;
}

void FFT::magnitudeSpectrum(
    const std::vector<float>& samples,
    std::vector<std::complex<float>>& fftBuffer,
    std::vector<float>& magnitudes) {

    const std::size_t n = samples.size();
    if (n == 0) {
        magnitudes.clear();
        fftBuffer.clear();
        return;
    }

    // Step 1: Convert real input to complex (imag = 0)
    fftBuffer.resize(n);
    for (std::size_t i = 0; i < n; ++i) {
        fftBuffer[i] = std::complex<float>(samples[i], 0.0f);
    }

    // Step 2: Bit-reversal permutation
    bitReverse(fftBuffer);

    // Step 3: Cooley-Tukey butterfly stages
    butterflyStages(fftBuffer);

    // Step 4: Extract magnitude spectrum [0..N/2]
    magnitudes.resize(n / 2 + 1);
    for (std::size_t k = 0; k < magnitudes.size(); ++k) {
        magnitudes[k] = std::abs(fftBuffer[k]);
    }
}

void FFT::bitReverse(std::vector<std::complex<float>>& buffer) {
    const std::size_t n = buffer.size();

    std::size_t j = 0;
    for (std::size_t i = 1; i < n; ++i) {
        // Bit-reversal logic: iterate j through bit-reversed addresses
        std::size_t bit = n >> 1;
        while (j & bit) {
            j ^= bit;
            bit >>= 1;
        }
        j ^= bit;

        // Swap if i < j (avoid redundant swaps)
        if (i < j) {
            std::swap(buffer[i], buffer[j]);
        }
    }
}

void FFT::butterflyStages(std::vector<std::complex<float>>& buffer) {
    const std::size_t n = buffer.size();

    // Staged computation: len = 2, 4, 8, ..., n
    for (std::size_t len = 2; len <= n; len <<= 1) {
        // Process all butterfly groups
        for (std::size_t i = 0; i < n; i += len) {
            // Within this group, iterate over butterfly pairs
            for (std::size_t k = 0; k < len / 2; ++k) {
                // Compute twiddle directly from double-precision angle to avoid accumulation error.
                // This improves cross-architecture reproducibility and reduces error for large N.
                const double angle = -2.0 * kPi * static_cast<double>(k) / static_cast<double>(len);
                const std::complex<float> w(
                    static_cast<float>(std::cos(angle)),
                    static_cast<float>(std::sin(angle))
                );

                // Butterfly operation:
                // U = buffer[i + k]
                // V = W * buffer[i + k + len/2]
                // buffer[i + k] = U + V
                // buffer[i + k + len/2] = U - V

                const std::complex<float> u = buffer[i + k];
                const std::complex<float> v = buffer[i + k + len / 2] * w;

                buffer[i + k] = u + v;
                buffer[i + k + len / 2] = u - v;
            }
        }
    }
}

} // namespace winton::native

