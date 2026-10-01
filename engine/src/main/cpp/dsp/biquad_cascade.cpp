#include "biquad_cascade.h"

#include <algorithm>

namespace winton::native {

void BiquadCascade::updateBands(const std::vector<BandConfig>& bands, int sampleRate, int rampSamples) {
    const auto maxLen = std::max(filters_.size(), bands.size());
    if (filters_.size() < maxLen) {
        filters_.resize(maxLen);
    }

    for (std::size_t i = 0; i < maxLen; ++i) {
        const BandConfig target = i < bands.size() ? bands[i] : BandConfig{};
        filters_[i].update(target, sampleRate, rampSamples);
    }
}

void BiquadCascade::process(float* samples, int count) {
    for (auto& filter : filters_) {
        filter.process(samples, count);
    }
}

void BiquadCascade::reset() {
    for (auto& filter : filters_) {
        filter.reset();
    }
}

} // namespace winton::native
