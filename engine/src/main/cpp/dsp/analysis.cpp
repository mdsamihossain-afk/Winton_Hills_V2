#include "analysis.h"
#include "dsp_constants.h"
#include "fft.h"

#include <cmath>
#include <algorithm>
#include <numeric>

namespace winton::native {

float Analysis::computeRms(const float* samples, int count) {
    if (samples == nullptr || count <= 0) return 0.0f;
    double sum = 0.0;
    for (int i = 0; i < count; ++i) {
        sum += static_cast<double>(samples[i]) * static_cast<double>(samples[i]);
    }
    return static_cast<float>(std::sqrt(sum / static_cast<double>(count)));
}

void Analysis::applyHannWindow(std::vector<float>& samples) {
    const auto n = samples.size();
    if (n <= 1) return;
    for (std::size_t i = 0; i < n; ++i) {
        const double w = 0.5 * (1.0 - std::cos(2.0 * kPi * static_cast<double>(i) / static_cast<double>(n - 1)));
        samples[i] *= static_cast<float>(w);
    }
}

float Analysis::computeSpectralCentroid(const std::vector<float>& magnitudes, int sampleRate) {
    if (magnitudes.empty()) return 0.0f;
    const double nyquist = static_cast<double>(sampleRate) / 2.0;
    double weightedSum = 0.0;
    double magnitudeSum = 0.0;
    // magnitudes size is N/2 + 1
    const int numBins = magnitudes.size();
    for (int i = 0; i < numBins; ++i) {
        const double freq = (static_cast<double>(i) / static_cast<double>(numBins - 1)) * nyquist;
        weightedSum += freq * magnitudes[i];
        magnitudeSum += magnitudes[i];
    }
    if (magnitudeSum < 1e-10) return 0.0f;
    const double centroidHz = weightedSum / magnitudeSum;
    return static_cast<float>(std::clamp(centroidHz / nyquist, 0.0, 1.0));
}

float Analysis::computeZcr(const float* samples, int count) {
    if (samples == nullptr || count < 2) return 0.0f;
    constexpr float kZcrThreshold = 1e-5f;
    float crossings = 0.0f;
    for (int i = 1; i < count; ++i) {
        int s1 = (samples[i - 1] > kZcrThreshold) ? 1 : (samples[i - 1] < -kZcrThreshold ? -1 : 0);
        int s2 = (samples[i] > kZcrThreshold) ? 1 : (samples[i] < -kZcrThreshold ? -1 : 0);
        crossings += std::abs(s1 - s2) * 0.5f;
    }
    return crossings / static_cast<float>(count - 1);
}

std::pair<int, float> Analysis::classify(float rms, float centroid) {
    if (rms >= 0.02f && rms <= 0.3f && centroid >= 0.05f && centroid <= 0.35f) {
        const float confidence = 0.6f + 0.3f * (1.0f - std::clamp((centroid - 0.15f) / 0.2f, 0.0f, 1.0f));
        return {1, std::clamp(confidence, 0.0f, 1.0f)};
    }
    if (centroid > 0.35f || rms > 0.3f) {
        const float confidence = 0.5f + 0.4f * std::clamp(centroid, 0.0f, 1.0f);
        return {2, std::clamp(confidence, 0.0f, 1.0f)};
    }
    if (rms < 0.02f) return {0, 0.95f};
    return {3, 0.4f};
}

std::vector<float> Analysis::computeMfcc(
    const float* samples,
    int count,
    int sampleRate,
    int nMfcc,
    std::vector<std::complex<float>>& fftBuffer,
    std::vector<float>& magBuffer
) {
    if (count <= 0) return std::vector<float>(nMfcc, 0.0f);

    // 1. Pre-emphasis
    std::vector<float> data(count);
    const float alpha = 0.97f;
    data[0] = samples[0];
    for (int i = 1; i < count; ++i) {
        data[i] = samples[i] - alpha * samples[i - 1];
    }

    // 2. Hann Window
    applyHannWindow(data);

    // 3. FFT (Power Spectrum)
    FFT::magnitudeSpectrum(data, fftBuffer, magBuffer); // returns N/2 + 1 bins
    for (auto& m : magBuffer) {
        m = (m * m) / static_cast<float>(count); // Power spectrum
    }

    // 4. Mel Filterbank (13 bands)
    const double lowFreqHz = 0.0;
    const double highFreqHz = sampleRate / 2.0;

    auto hzToMel = [](double hz) { return 2595.0 * std::log10(1.0 + hz / 700.0); };
    auto melToHz = [](double mel) { return 700.0 * (std::pow(10.0, mel / 2595.0) - 1.0); };

    double melLow = hzToMel(lowFreqHz);
    double melHigh = hzToMel(highFreqHz);

    std::vector<double> melPoints(nMfcc + 2);
    for (int i = 0; i < nMfcc + 2; ++i) {
        melPoints[i] = melLow + i * (melHigh - melLow) / (nMfcc + 1);
    }

    std::vector<int> bins(nMfcc + 2);
    for (int i = 0; i < nMfcc + 2; ++i) {
        double hz = melToHz(melPoints[i]);
        bins[i] = static_cast<int>(std::floor((count + 1) * hz / sampleRate));
    }

    std::vector<double> melEnergies(nMfcc, 0.0);
    for (int m = 0; m < nMfcc; ++m) {
        for (int k = bins[m]; k < bins[m + 1] && k < static_cast<int>(magBuffer.size()); ++k) {
            double weight = static_cast<double>(k - bins[m]) / (bins[m + 1] - bins[m]);
            melEnergies[m] += weight * magBuffer[k];
        }
        for (int k = bins[m + 1]; k < bins[m + 2] && k < static_cast<int>(magBuffer.size()); ++k) {
            double weight = static_cast<double>(bins[m + 2] - k) / (bins[m + 2] - bins[m + 1]);
            melEnergies[m] += weight * magBuffer[k];
        }
        // Log-energy (dB)
        melEnergies[m] = (melEnergies[m] <= 0) ? -100.0 : 20.0 * std::log10(melEnergies[m]);
    }

    // 5. DCT-II
    return computeDct(melEnergies);
}

std::vector<float> Analysis::computeDct(const std::vector<double>& input) {
    const int n = input.size();
    std::vector<float> output(n, 0.0f);
    const double factor = std::sqrt(2.0 / n);

    for (int k = 0; k < n; ++k) {
        double sum = 0.0;
        for (int i = 0; i < n; ++i) {
            sum += input[i] * std::cos(kPi * k * (i + 0.5) / n);
        }
        output[k] = static_cast<float>(sum * factor);
    }
    // Orthonormal scale for k=0
    output[0] *= static_cast<float>(1.0 / std::sqrt(2.0));
    return output;
}

} // namespace winton::native
