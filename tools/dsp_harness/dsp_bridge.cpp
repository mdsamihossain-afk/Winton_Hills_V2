#include <iostream>
#include <vector>
#include <array>
#include <cstdarg>
#include <algorithm>
#include <chrono>
#include <cmath>
#include <complex>
#include <cstring>
#include <utility>
#include <atomic>
#include <condition_variable>
#include <cstddef>
#include <cstdint>
#include <mutex>
#include <thread>
#include <sstream>

// --- Logging Mock ---
// This satisfies the linker for __android_log_print but is a PURE no-op.
// It does not affect control flow (returns, breaks, etc.) in the calling code.
extern "C" {
    int __android_log_print(int prio, const char* tag, const char* fmt, ...) {
        return 0;
    }
}

// --- Private Access ---
// Bridge-only hack to allow testing of private members without modifying source files.
#define private public
#include "../../engine/src/main/cpp/native_processing_engine.h"
#include "../../engine/src/main/cpp/native_processing_engine.cpp"
#include "../../engine/src/main/cpp/dsp/biquad_filter.h"
#include "../../engine/src/main/cpp/dsp/biquad_filter.cpp"
#include "../../engine/src/main/cpp/dsp/biquad_cascade.h"
#include "../../engine/src/main/cpp/dsp/biquad_cascade.cpp"
#include "../../engine/src/main/cpp/dsp/fft.h"
#include "../../engine/src/main/cpp/dsp/fft.cpp"
#include "../../engine/src/main/cpp/dsp/analysis.h"
#include "../../engine/src/main/cpp/dsp/analysis.cpp"
#undef private

using namespace winton::native;

extern "C" {

// --- BiquadFilter Wrappers ---

BiquadFilter* winton_biquad_create() {
    return new BiquadFilter();
}

void winton_biquad_destroy(BiquadFilter* filter) {
    delete filter;
}

void winton_biquad_update(BiquadFilter* filter, float freq, float gain, float q, int sample_rate, int ramp_samples) {
    BandConfig config{freq, gain, q};
    filter->update(config, sample_rate, ramp_samples);
}

// EXPLICIT: Expose coefficients for Suite 2b isolation
void winton_biquad_get_coefficients(BiquadFilter* filter, double* out_b, double* out_a) {
    out_b[0] = filter->b0_; out_b[1] = filter->b1_; out_b[2] = filter->b2_;
    out_a[0] = 1.0;          out_a[1] = filter->a1_; out_a[2] = filter->a2_;
}

float winton_biquad_process_sample(BiquadFilter* filter, float sample) {
    return filter->process(sample);
}

void winton_biquad_reset(BiquadFilter* filter) {
    filter->reset();
}

// --- Feature Extraction Wrappers (Accessing private static methods) ---

float winton_engine_compute_rms(const float* samples, int count) {
    return Analysis::computeRms(samples, count);
}

float winton_engine_compute_spectral_centroid(const float* magnitudes, int magnitudes_count, int sample_rate) {
    // Wrap the raw array into the std::vector expected by the private method
    std::vector<float> mags(magnitudes, magnitudes + magnitudes_count);
    return Analysis::computeSpectralCentroid(mags, sample_rate);
}

float winton_engine_compute_zcr(const float* samples, int count) {
    return Analysis::computeZcr(samples, count);
}

void winton_engine_apply_hann_window(float* samples, int count) {
    std::vector<float> vec(samples, samples + count);
    Analysis::applyHannWindow(vec);
    std::copy(vec.begin(), vec.end(), samples);
}

void winton_engine_compute_mfcc(const float* samples, int count, int sample_rate, int n_mfcc, float* out_mfcc) {
    std::vector<std::complex<float>> fftBuffer;
    std::vector<float> magBuffer;
    auto mfcc = Analysis::computeMfcc(samples, count, sample_rate, n_mfcc, fftBuffer, magBuffer);
    std::copy(mfcc.begin(), mfcc.end(), out_mfcc);
}

// --- Bridge-specific FFT helper (to match internal behavior) ---
void winton_engine_get_fft_magnitudes(const float* samples, int count, float* out_magnitudes) {
    std::vector<float> vec(samples, samples + count);
    auto mags = FFT::magnitudeSpectrum(vec);
    std::copy(mags.begin(), mags.end(), out_magnitudes);
}

// --- NativeProcessingEngine Wrappers ---

NativeProcessingEngine* winton_engine_create() {
    return new NativeProcessingEngine();
}

void winton_engine_destroy(NativeProcessingEngine* engine) {
    engine->stop();
    delete engine;
}

void winton_engine_start(NativeProcessingEngine* engine, int sample_rate) {
    engine->start(sample_rate, 1024, false);
}

void winton_engine_set_policy(NativeProcessingEngine* engine, float freq, float gain, float q) {
    std::vector<BandConfig> bands = {{freq, gain, q}};
    engine->setPolicy(1, bands);
}

void winton_engine_set_bypass_mode(NativeProcessingEngine* engine, bool bypass) {
    engine->setBypassMode(bypass);
}

void winton_engine_process_frame(NativeProcessingEngine* engine, const float* samples, int count) {
    AnalysisFrame frame;
    frame.frameSize = count;
    frame.sampleRate = 48000;
    std::copy(samples, samples + count, frame.samples.begin());
    engine->processFrame(frame);
}

void winton_engine_get_latest_analysis(NativeProcessingEngine* engine, float* out_data) {
    auto analysis = engine->latestAnalysis();
    std::copy(analysis.begin(), analysis.end(), out_data);
}

}
