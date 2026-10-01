#include <iostream>
#include <vector>
#include <complex>
#include <cmath>
#include <iomanip>
#include <algorithm>
#include <chrono>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

namespace baseline {
    void bitReverse(std::vector<std::complex<float>>& buffer) {
        const std::size_t n = buffer.size();
        std::size_t j = 0;
        for (std::size_t i = 1; i < n; ++i) {
            std::size_t bit = n >> 1;
            while (j & bit) { j ^= bit; bit >>= 1; }
            j ^= bit;
            if (i < j) std::swap(buffer[i], buffer[j]);
        }
    }

    void butterflyStages(std::vector<std::complex<float>>& buffer) {
        const std::size_t n = buffer.size();
        for (std::size_t len = 2; len <= n; len <<= 1) {
            const float angle = -2.0f * static_cast<float>(M_PI) / static_cast<float>(len);
            const std::complex<float> wlen(std::cos(angle), std::sin(angle));
            for (std::size_t i = 0; i < n; i += len) {
                std::complex<float> w(1.0f, 0.0f);
                for (std::size_t k = 0; k < len / 2; ++k) {
                    const std::complex<float> u = buffer[i + k];
                    const std::complex<float> v = buffer[i + k + len / 2] * w;
                    buffer[i + k] = u + v;
                    buffer[i + k + len / 2] = u - v;
                    w *= wlen;
                }
            }
        }
    }

    std::vector<float> compute(const std::vector<float>& samples) {
        std::vector<std::complex<float>> buffer(samples.size());
        for(size_t i=0; i<samples.size(); ++i) buffer[i] = {samples[i], 0.0f};
        bitReverse(buffer);
        butterflyStages(buffer);
        std::vector<float> magnitudes(samples.size()/2);
        for(size_t i=0; i<magnitudes.size(); ++i) magnitudes[i] = std::abs(buffer[i]);
        return magnitudes;
    }
}

namespace double_prec_oracle {
    void bitReverse(std::vector<std::complex<double>>& buffer) {
        const std::size_t n = buffer.size();
        std::size_t j = 0;
        for (std::size_t i = 1; i < n; ++i) {
            std::size_t bit = n >> 1;
            while (j & bit) { j ^= bit; bit >>= 1; }
            j ^= bit;
            if (i < j) std::swap(buffer[i], buffer[j]);
        }
    }

    void butterflyStages(std::vector<std::complex<double>>& buffer) {
        const std::size_t n = buffer.size();
        for (std::size_t len = 2; len <= n; len <<= 1) {
            for (std::size_t i = 0; i < n; i += len) {
                for (std::size_t k = 0; k < len / 2; ++k) {
                    double angle = -2.0 * M_PI * k / len;
                    std::complex<double> w(std::cos(angle), std::sin(angle));
                    const std::complex<double> u = buffer[i + k];
                    const std::complex<double> v = buffer[i + k + len / 2] * w;
                    buffer[i + k] = u + v;
                    buffer[i + k + len / 2] = u - v;
                }
            }
        }
    }

    std::vector<double> compute(const std::vector<float>& samples) {
        std::vector<std::complex<double>> buffer(samples.size());
        for(size_t i=0; i<samples.size(); ++i) buffer[i] = {static_cast<double>(samples[i]), 0.0};
        bitReverse(buffer);
        butterflyStages(buffer);
        std::vector<double> magnitudes(samples.size()/2);
        for(size_t i=0; i<magnitudes.size(); ++i) magnitudes[i] = std::abs(buffer[i]);
        return magnitudes;
    }
}

namespace improved_twiddles {
    void butterflyStages(std::vector<std::complex<float>>& buffer) {
        const std::size_t n = buffer.size();
        for (std::size_t len = 2; len <= n; len <<= 1) {
            for (std::size_t i = 0; i < n; i += len) {
                for (std::size_t k = 0; k < len / 2; ++k) {
                    double angle = -2.0 * M_PI * k / len;
                    std::complex<float> w(static_cast<float>(std::cos(angle)), static_cast<float>(std::sin(angle)));
                    const std::complex<float> u = buffer[i + k];
                    const std::complex<float> v = buffer[i + k + len / 2] * w;
                    buffer[i + k] = u + v;
                    buffer[i + k + len / 2] = u - v;
                }
            }
        }
    }

    std::vector<float> compute(const std::vector<float>& samples) {
        std::vector<std::complex<float>> buffer(samples.size());
        for(size_t i=0; i<samples.size(); ++i) buffer[i] = {samples[i], 0.0f};
        baseline::bitReverse(buffer);
        butterflyStages(buffer);
        std::vector<float> magnitudes(samples.size()/2);
        for(size_t i=0; i<magnitudes.size(); ++i) magnitudes[i] = std::abs(buffer[i]);
        return magnitudes;
    }
}

namespace double_intermediates {
    void butterflyStages(std::vector<std::complex<float>>& buffer) {
        const std::size_t n = buffer.size();
        for (std::size_t len = 2; len <= n; len <<= 1) {
            for (std::size_t i = 0; i < n; i += len) {
                for (std::size_t k = 0; k < len / 2; ++k) {
                    double angle = -2.0 * M_PI * k / len;
                    std::complex<double> w(std::cos(angle), std::sin(angle));
                    const std::complex<double> u(buffer[i + k].real(), buffer[i + k].imag());
                    const std::complex<double> v = std::complex<double>(buffer[i + k + len / 2].real(), buffer[i + k + len / 2].imag()) * w;
                    buffer[i + k] = {static_cast<float>(u.real() + v.real()), static_cast<float>(u.imag() + v.imag())};
                    buffer[i + k + len / 2] = {static_cast<float>(u.real() - v.real()), static_cast<float>(u.imag() - v.imag())};
                }
            }
        }
    }

    std::vector<float> compute(const std::vector<float>& samples) {
        std::vector<std::complex<float>> buffer(samples.size());
        for(size_t i=0; i<samples.size(); ++i) buffer[i] = {samples[i], 0.0f};
        baseline::bitReverse(buffer);
        butterflyStages(buffer);
        std::vector<float> magnitudes(samples.size()/2);
        for(size_t i=0; i<magnitudes.size(); ++i) magnitudes[i] = std::abs(buffer[i]);
        return magnitudes;
    }
}

void run_audit(int n) {
    std::vector<float> input(n);
    for(int i=0; i<n; ++i) input[i] = static_cast<float>(rand()) / RAND_MAX * 2.0f - 1.0f;

    auto ground_truth = double_prec_oracle::compute(input);
    auto base_res = baseline::compute(input);
    auto improved_res = improved_twiddles::compute(input);
    auto intermediate_res = double_intermediates::compute(input);

    auto get_max_err = [&](const std::vector<float>& res) {
        double max_err = 0;
        for(size_t i=0; i<res.size(); ++i) {
            max_err = std::max(max_err, std::abs(static_cast<double>(res[i]) - ground_truth[i]));
        }
        return max_err;
    };

    std::cout << "Audit N=" << n << std::endl;
    std::cout << "  Baseline Max Error:     " << std::scientific << get_max_err(base_res) << std::endl;
    std::cout << "  Improved Twiddle Error: " << std::scientific << get_max_err(improved_res) << std::endl;
    std::cout << "  Double Interm Error:    " << std::scientific << get_max_err(intermediate_res) << std::endl;
    std::cout << std::endl;
}

int main() {
    srand(42);
    run_audit(1024);
    run_audit(2048);
    run_audit(4096);
    return 0;
}
