#pragma once

#include <vector>
#include "biquad_filter.h"

namespace winton::native {

/**
 * Ordered serial chain of biquad filters built from policy or AutoEq bands.
 * Supports smooth coefficient ramping to prevent pops during policy switches.
 */
class BiquadCascade {
public:
    /**
     * Updates the filter chain with new bands, ramping coefficients to avoid pops.
     * If newBands is shorter than current filters, extra filters are ramped to neutral.
     * If newBands is longer, new filters are added (starting at neutral and ramping to target).
     */
    void updateBands(const std::vector<BandConfig>& bands, int sampleRate, int rampSamples = 50);

    /**
     * Process a buffer of audio samples through the entire serial chain.
     */
    void process(float* samples, int count);

    /**
     * Reset internal state for all filters in the cascade.
     */
    void reset();

    /**
     * Returns true if the cascade has no active filters.
     */
    [[nodiscard]] bool empty() const { return filters_.empty(); }

private:
    std::vector<BiquadFilter> filters_;
};

} // namespace winton::native
