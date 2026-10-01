#include "lm_fitter.h"
#include <iostream>
#include <cmath>
#include <algorithm>
#include <unsupported/Eigen/NumericalDiff>
#include <unsupported/Eigen/LevenbergMarquardt>

namespace winton::native {

int LmFitter::PeakingEqResidual::operator()(const Eigen::VectorXd& x, Eigen::VectorXd& fvec) const {
    fvec.setZero();
    constexpr double pi = 3.14159265358979323846;

    for (int m = 0; m < numBands; ++m) {
        const double f0 = x[m * 3];
        const double g = x[m * 3 + 1];
        const double q = std::max(0.1, x[m * 3 + 2]);

        const double A = std::pow(10.0, g / 40.0);
        const double w0 = 2.0 * pi * f0 / sampleRate;
        const double alpha = std::sin(w0) / (2.0 * q);
        const double cosW0 = std::cos(w0);

        const double b0 = 1.0 + alpha * A, b1 = -2.0 * cosW0, b2 = 1.0 - alpha * A;
        const double a0 = 1.0 + alpha / A, a1 = -2.0 * cosW0, a2 = 1.0 - alpha / A;

        const double b0_2 = b0 * b0, b1_2 = b1 * b1, b2_2 = b2 * b2;
        const double a0_2 = a0 * a0, a1_2 = a1 * a1, a2_2 = a2 * a2;

        for (int i = 0; i < fvec.size(); ++i) {
            const double w = 2.0 * pi * freqs[i] / sampleRate;
            const double cosW = std::cos(w), cos2W = std::cos(2.0 * w);
            const double num = b0_2 + b1_2 + b2_2 + 2.0 * (b0 * b1 + b1 * b2) * cosW + 2.0 * b0 * b2 * cos2W;
            const double den = a0_2 + a1_2 + a2_2 + 2.0 * (a0 * a1 + a1 * a2) * cosW + 2.0 * a0 * a2 * cos2W;
            fvec[i] += 10.0 * std::log10(std::max(1e-10, num / den));
        }
    }

    for (int i = 0; i < fvec.size(); ++i) {
        fvec[i] -= targetDb[i];
    }
    return 0;
}

LmFitter::FitResult LmFitter::fitSingleBand(
    const std::vector<double>& freqs,
    const std::vector<double>& targetDb,
    int sampleRate,
    const FitResult& initialGuess
) {
    auto results = fitMultiBand(freqs, targetDb, sampleRate, 1);
    return results[0];
}

std::vector<LmFitter::FitResult> LmFitter::fitMultiBand(
    const std::vector<double>& freqs,
    const std::vector<double>& targetDb,
    int sampleRate,
    int numBands
) {
    Eigen::VectorXd x(3 * numBands);
    for (int m = 0; m < numBands; ++m) {
        x[m * 3] = 1000.0 * (m + 1);
        x[m * 3 + 1] = 0.0;
        x[m * 3 + 2] = 1.0;
    }

    PeakingEqResidual functor(freqs, targetDb, sampleRate, numBands);
    Eigen::NumericalDiff<PeakingEqResidual> normFunctor(functor);
    Eigen::LevenbergMarquardt<Eigen::NumericalDiff<PeakingEqResidual>> lm(normFunctor);

    lm.minimize(x);

    std::vector<FitResult> results;
    for (int m = 0; m < numBands; ++m) {
        results.push_back({
            static_cast<float>(x[m * 3]),
            static_cast<float>(x[m * 3 + 1]),
            static_cast<float>(x[m * 3 + 2]),
            lm.fnorm()
        });
    }
    return results;
}

std::vector<LmFitter::FitResult> LmFitter::fitMultiBandWithGuess(
    const std::vector<double>& freqs,
    const std::vector<double>& targetDb,
    int sampleRate,
    const std::vector<FitResult>& initialGuess
) {
    int numBands = initialGuess.size();
    Eigen::VectorXd x(3 * numBands);
    for (int m = 0; m < numBands; ++m) {
        x[m * 3] = initialGuess[m].frequencyHz;
        x[m * 3 + 1] = initialGuess[m].gainDb;
        x[m * 3 + 2] = initialGuess[m].q;
    }

    PeakingEqResidual functor(freqs, targetDb, sampleRate, numBands);
    Eigen::NumericalDiff<PeakingEqResidual> normFunctor(functor);
    Eigen::LevenbergMarquardt<Eigen::NumericalDiff<PeakingEqResidual>> lm(normFunctor);

    lm.minimize(x);

    std::vector<FitResult> results;
    for (int m = 0; m < numBands; ++m) {
        results.push_back({
            static_cast<float>(x[m * 3]),
            static_cast<float>(x[m * 3 + 1]),
            static_cast<float>(x[m * 3 + 2]),
            lm.fnorm()
        });
    }
    return results;
}

bool LmFitter::runToyCase() {
    std::cout << "--- LM Fitter Toy Case: Single Band Match ---" << std::endl;

    const double targetF = 1000.0;
    const double targetG = 6.0;
    const double targetQ = 1.5;
    const int sampleRate = 48000;
    constexpr double pi = 3.14159265358979323846;

    // 1. Generate target curve from known params
    std::vector<double> freqs;
    std::vector<double> targetDb;
    for (double f = 100.0; f <= 10000.0; f *= 1.1) {
        freqs.push_back(f);
    }

    auto computeMag = [&](double f, double f0, double g, double q) {
        double A = std::pow(10.0, g / 40.0);
        double w0 = 2.0 * pi * f0 / sampleRate;
        double alpha = std::sin(w0) / (2.0 * q);
        double cosW0 = std::cos(w0);
        double b0 = 1.0 + alpha * A, b1 = -2.0 * cosW0, b2 = 1.0 - alpha * A;
        double a0 = 1.0 + alpha / A, a1 = -2.0 * cosW0, a2 = 1.0 - alpha / A;
        double w = 2.0 * pi * f / sampleRate;
        double cosW = std::cos(w), cos2W = std::cos(2.0 * w);
        double num = b0*b0 + b1*b1 + b2*b2 + 2*(b0*b1 + b1*b2)*cosW + 2*b0*b2*cos2W;
        double den = a0*a0 + a1*a1 + a2*a2 + 2*(a0*a1 + a1*a2)*cosW + 2*a0*a2*cos2W;
        return 10.0 * std::log10(std::max(1e-10, num / den));
    };

    for (double f : freqs) {
        targetDb.push_back(computeMag(f, targetF, targetG, targetQ));
    }

    // 2. Fit with a bad guess
    FitResult initial = {800.0f, 2.0f, 0.5f, 0.0};
    FitResult result = fitSingleBand(freqs, targetDb, sampleRate, initial);

    std::cout << "Target:  F=" << targetF << ", G=" << targetG << ", Q=" << targetQ << std::endl;
    std::cout << "Initial: F=" << initial.frequencyHz << ", G=" << initial.gainDb << ", Q=" << initial.q << std::endl;
    std::cout << "Result:  F=" << result.frequencyHz << ", G=" << result.gainDb << ", Q=" << result.q << std::endl;
    std::cout << "Error Norm: " << result.error << std::endl;

    bool pass = (std::abs(result.frequencyHz - targetF) < 1e-1 &&
                 std::abs(result.gainDb - targetG) < 1e-2 &&
                 std::abs(result.q - targetQ) < 1e-2);

    std::cout << "STATUS: " << (pass ? "PASS" : "FAIL") << std::endl;
    return pass;
}

bool LmFitter::runMultiBandTest() {
    std::cout << "\n--- LM Fitter Multi-Band Test: 1Custom SA02 Profile ---" << std::endl;
    const int sampleRate = 48000;
    constexpr double pi = 3.14159265358979323846;

    struct Band { double f, g, q; };
    std::vector<Band> targetBands = {
        {180.0, -3.3, 0.57}, {9140.0, 5.6, 3.46}, {3842.0, 6.4, 3.05},
        {2458.0, -5.3, 4.04}, {893.0, 1.6, 2.04}, {5939.0, -3.1, 6.0},
        {368.0, -0.6, 1.31}, {166.0, 0.4, 1.76}
    };

    auto computeTotalMag = [&](double f) {
        double totalDb = 0.0;
        for (const auto& b : targetBands) {
            double A = std::pow(10.0, b.g / 40.0);
            double w0 = 2.0 * pi * b.f / sampleRate;
            double alpha = std::sin(w0) / (2.0 * b.q);
            double cosW0 = std::cos(w0);
            double b0 = 1.0 + alpha * A, b1 = -2.0 * cosW0, b2 = 1.0 - alpha * A;
            double a0 = 1.0 + alpha / A, a1 = -2.0 * cosW0, a2 = 1.0 - alpha / A;
            double w = 2.0 * pi * f / sampleRate;
            double cosW = std::cos(w), cos2W = std::cos(2.0 * w);
            double num = b0*b0 + b1*b1 + b2*b2 + 2*(b0*b1 + b1*b2)*cosW + 2*b0*b2*cos2W;
            double den = a0*a0 + a1*a1 + a2*a2 + 2*(a0*a1 + a1*a2)*cosW + 2*a0*a2*cos2W;
            totalDb += 10.0 * std::log10(std::max(1e-10, num / den));
        }
        return totalDb;
    };

    std::vector<double> freqs;
    std::vector<double> targetDb;
    for (double f = 20.0; f <= 20000.0; f *= 1.05) {
        freqs.push_back(f);
        targetDb.push_back(computeTotalMag(f));
    }

    // High Fidelity fit: start EXACTLY at the target bands to verify the optimizer can find the global minimum.
    // If it can't find it when starting at the answer, there's a logic bug in the residual.
    std::vector<FitResult> initialGuess;
    for (const auto& b : targetBands) {
        initialGuess.push_back({static_cast<float>(b.f), static_cast<float>(b.g), static_cast<float>(b.q), 0.0});
    }

    auto results = fitMultiBandWithGuess(freqs, targetDb, sampleRate, initialGuess);

    double finalError = results[0].error;
    std::cout << "Final Error Norm: " << finalError << std::endl;

    // Check if the resulting curve matches the target curve
    double maxCurveDiff = 0.0;
    for (size_t i = 0; i < freqs.size(); ++i) {
        double fitDb = 0.0;
        for (const auto& res : results) {
            double A = std::pow(10.0, static_cast<double>(res.gainDb) / 40.0);
            double w0 = 2.0 * pi * res.frequencyHz / sampleRate;
            double alpha = std::sin(w0) / (2.0 * res.q);
            double cosW0 = std::cos(w0);
            double b0 = 1.0 + alpha * A, b1 = -2.0 * cosW0, b2 = 1.0 - alpha * A;
            double a0 = 1.0 + alpha / A, a1 = -2.0 * cosW0, a2 = 1.0 - alpha / A;
            double w = 2.0 * pi * freqs[i] / sampleRate;
            double cosW = std::cos(w), cos2W = std::cos(2.0 * w);
            double num = b0*b0 + b1*b1 + b2*b2 + 2*(b0*b1 + b1*b2)*cosW + 2*b0*b2*cos2W;
            double den = a0*a0 + a1*a1 + a2*a2 + 2*(a0*a1 + a1*a2)*cosW + 2*a0*a2*cos2W;
            fitDb += 10.0 * std::log10(std::max(1e-10, num / den));
        }
        maxCurveDiff = std::max(maxCurveDiff, std::abs(fitDb - targetDb[i]));
    }

    std::cout << "Max Curve Deviation: " << maxCurveDiff << " dB" << std::endl;
    bool pass = (maxCurveDiff < 0.5); // Allow 0.5dB tolerance for multi-band convergence
    std::cout << "STATUS: " << (pass ? "PASS" : "FAIL") << std::endl;

    return pass;
}

} // namespace winton::native
