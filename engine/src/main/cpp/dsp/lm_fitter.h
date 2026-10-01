#pragma once

#include <vector>
#include <string>
#include <Eigen/Dense>

namespace winton::native {

// Generic Functor for Eigen Levenberg-Marquardt
template<typename _Scalar, int NX = Eigen::Dynamic, int NY = Eigen::Dynamic>
struct Functor
{
    typedef _Scalar Scalar;
    enum {
        InputsAtCompileTime = NX,
        ValuesAtCompileTime = NY
    };
    typedef Eigen::Matrix<Scalar, InputsAtCompileTime, 1> InputType;
    typedef Eigen::Matrix<Scalar, ValuesAtCompileTime, 1> ValueType;
    typedef Eigen::Matrix<Scalar, ValuesAtCompileTime, InputsAtCompileTime> JacobianType;
    typedef Eigen::ColPivHouseholderQR<JacobianType> QRSolver;

    const int m_inputs, m_values;

    Functor() : m_inputs(NX), m_values(NY) {}
    Functor(int inputs, int values) : m_inputs(inputs), m_values(values) {}

    int inputs() const { return m_inputs; }
    int values() const { return m_values; }
};

class LmFitter {
public:
    struct FitResult {
        float frequencyHz;
        float gainDb;
        float q;
        double error;
    };

    static FitResult fitSingleBand(
        const std::vector<double>& freqs,
        const std::vector<double>& targetDb,
        int sampleRate,
        const FitResult& initialGuess = {1000.0f, 0.0f, 1.0f, 0.0}
    );

    static std::vector<FitResult> fitMultiBand(
        const std::vector<double>& freqs,
        const std::vector<double>& targetDb,
        int sampleRate,
        int numBands
    );

    static std::vector<FitResult> fitMultiBandWithGuess(
        const std::vector<double>& freqs,
        const std::vector<double>& targetDb,
        int sampleRate,
        const std::vector<FitResult>& initialGuess
    );

    static bool runToyCase();
    static bool runMultiBandTest();

    struct PeakingEqResidual : Functor<double> {
        const std::vector<double>& freqs;
        const std::vector<double>& targetDb;
        int sampleRate;
        int numBands;

        PeakingEqResidual(const std::vector<double>& f, const std::vector<double>& t, int sr, int bands)
            : Functor<double>(3 * bands, f.size()), freqs(f), targetDb(t), sampleRate(sr), numBands(bands) {}

        int operator()(const Eigen::VectorXd& x, Eigen::VectorXd& fvec) const;
    };
};

} // namespace winton::native
