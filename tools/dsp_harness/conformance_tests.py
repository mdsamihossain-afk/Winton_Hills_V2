import ctypes
import numpy as np
import os
import sys
from dsp_harness import BiquadFilterOracle, FeatureExtractorOracle, SignalGenerator, \
    TOLERANCE_MAGNITUDE_DB, TOLERANCE_PHASE_DEG, TOLERANCE_MFCC

class DspBridge:
    def __init__(self, lib_path):
        self.lib = ctypes.CDLL(lib_path)

        # Biquad
        self.lib.winton_biquad_create.restype = ctypes.c_void_p
        self.lib.winton_biquad_destroy.argtypes = [ctypes.c_void_p]
        self.lib.winton_biquad_update.argtypes = [ctypes.c_void_p, ctypes.c_float, ctypes.c_float, ctypes.c_float, ctypes.c_int, ctypes.c_int]
        self.lib.winton_biquad_get_coefficients.argtypes = [ctypes.c_void_p, ctypes.POINTER(ctypes.c_double), ctypes.POINTER(ctypes.c_double)]
        self.lib.winton_biquad_process_sample.argtypes = [ctypes.c_void_p, ctypes.c_float]
        self.lib.winton_biquad_process_sample.restype = ctypes.c_float
        self.lib.winton_biquad_reset.argtypes = [ctypes.c_void_p]

        # Features
        self.lib.winton_engine_compute_rms.argtypes = [ctypes.POINTER(ctypes.c_float), ctypes.c_int]
        self.lib.winton_engine_compute_rms.restype = ctypes.c_float
        self.lib.winton_engine_compute_spectral_centroid.argtypes = [ctypes.POINTER(ctypes.c_float), ctypes.c_int, ctypes.c_int]
        self.lib.winton_engine_compute_spectral_centroid.restype = ctypes.c_float
        self.lib.winton_engine_compute_zcr.argtypes = [ctypes.POINTER(ctypes.c_float), ctypes.c_int]
        self.lib.winton_engine_compute_zcr.restype = ctypes.c_float
        self.lib.winton_engine_apply_hann_window.argtypes = [ctypes.POINTER(ctypes.c_float), ctypes.c_int]
        self.lib.winton_engine_get_fft_magnitudes.argtypes = [ctypes.POINTER(ctypes.c_float), ctypes.c_int, ctypes.POINTER(ctypes.c_float)]
        self.lib.winton_engine_compute_mfcc.argtypes = [ctypes.POINTER(ctypes.c_float), ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.POINTER(ctypes.c_float)]

        # Engine Core
        self.lib.winton_engine_create.restype = ctypes.c_void_p
        self.lib.winton_engine_destroy.argtypes = [ctypes.c_void_p]
        self.lib.winton_engine_start.argtypes = [ctypes.c_void_p, ctypes.c_int]
        self.lib.winton_engine_set_policy.argtypes = [ctypes.c_void_p, ctypes.c_float, ctypes.c_float, ctypes.c_float]
        self.lib.winton_engine_process_frame.argtypes = [ctypes.c_void_p, ctypes.POINTER(ctypes.c_float), ctypes.c_int]
        self.lib.winton_engine_get_latest_analysis.argtypes = [ctypes.c_void_p, ctypes.POINTER(ctypes.c_float)]

    def create_biquad(self):
        return self.lib.winton_biquad_create()

    def destroy_biquad(self, handle):
        self.lib.winton_biquad_destroy(handle)

    def update_biquad(self, handle, freq, gain, q, sample_rate, ramp_samples):
        self.lib.winton_biquad_update(handle, freq, gain, q, sample_rate, ramp_samples)

    def process_biquad(self, handle, samples):
        return np.array([self.lib.winton_biquad_process_sample(handle, float(s)) for s in samples], dtype=np.float32)

    def compute_rms(self, samples):
        n = len(samples)
        c_samples = (ctypes.c_float * n)(*samples)
        return self.lib.winton_engine_compute_rms(c_samples, n)

    def compute_centroid(self, magnitudes, sample_rate):
        n = len(magnitudes)
        c_mags = (ctypes.c_float * n)(*magnitudes)
        return self.lib.winton_engine_compute_spectral_centroid(c_mags, n, sample_rate)

    def compute_zcr(self, samples):
        n = len(samples)
        c_samples = (ctypes.c_float * n)(*samples)
        return self.lib.winton_engine_compute_zcr(c_samples, n)

    def apply_hann(self, samples):
        n = len(samples)
        c_samples = (ctypes.c_float * n)(*samples)
        self.lib.winton_engine_apply_hann_window(c_samples, n)
        return np.array(c_samples)

    def get_fft_magnitudes(self, samples):
        n = len(samples)
        c_samples = (ctypes.c_float * n)(*samples)
        c_mags = (ctypes.c_float * (n // 2 + 1))()
        self.lib.winton_engine_get_fft_magnitudes(c_samples, n, c_mags)
        return np.array(c_mags)

    def compute_mfcc(self, samples, sample_rate, n_mfcc=13):
        n = len(samples)
        c_samples = (ctypes.c_float * n)(*samples)
        c_mfcc = (ctypes.c_float * n_mfcc)()
        self.lib.winton_engine_compute_mfcc(c_samples, n, sample_rate, n_mfcc, c_mfcc)
        return np.array(c_mfcc)

    def create_engine(self):
        return self.lib.winton_engine_create()

    def destroy_engine(self, handle):
        self.lib.winton_engine_destroy(handle)

    def start_engine(self, handle, sample_rate):
        self.lib.winton_engine_start(handle, sample_rate)

    def set_engine_policy(self, handle, freq, gain, q):
        self.lib.winton_engine_set_policy(handle, freq, gain, q)

    def process_engine_frame(self, handle, samples):
        n = len(samples)
        c_samples = (ctypes.c_float * n)(*samples)
        self.lib.winton_engine_process_frame(handle, c_samples, n)

    def get_engine_analysis(self, handle):
        c_data = (ctypes.c_float * 8)()
        self.lib.winton_engine_get_latest_analysis(handle, c_data)
        return np.array(c_data)


def run_tests(lib_path):
    bridge = DspBridge(lib_path)
    oracle_filter = BiquadFilterOracle()
    oracle_fe = FeatureExtractorOracle()
    gen = SignalGenerator()

    print("--- Winton DSP Conformance Report ---")

    # 2a. RecurrenceTest (Steady-State)
    print("\n[Suite 2a] RecurrenceTest (Steady-State)")
    handle = bridge.create_biquad()
    test_signal = gen.white_noise(1024)

    # Force immediate jump in both
    bridge.lib.winton_biquad_reset(handle)
    oracle_filter.reset()
    bridge.update_biquad(handle, 1000, 6.0, 1.0, 48000, 0)
    oracle_filter.update(1000, 6.0, 1.0, 0)

    native_out = bridge.process_biquad(handle, test_signal)
    oracle_out = oracle_filter.process(test_signal)

    diff = np.abs(native_out - oracle_out)
    max_diff = np.max(diff)
    status = "PASS" if max_diff < 1e-5 else "FAIL"
    print(f"  Sample-by-sample match: {status} (Max Error: {max_diff:.2e})")
    bridge.destroy_biquad(handle)

    # 2b. SynthesisTest
    print("\n[Suite 2b] SynthesisTest")
    test_freq, test_gain, test_q = 1000.0, 6.0, 1.0

    # Check closed-form synthesis coefficients
    handle = bridge.create_biquad()
    bridge.update_biquad(handle, test_freq, test_gain, test_q, 48000, 0)

    n_b = (ctypes.c_double * 3)()
    n_a = (ctypes.c_double * 3)()
    bridge.lib.winton_biquad_get_coefficients(handle, n_b, n_a)

    o_coeffs = oracle_filter.generate_coefficients(test_freq, test_gain, test_q)
    # o_coeffs = (b0, b1, b2, a1, a2)

    b_match = np.allclose(list(n_b), o_coeffs[:3], atol=1e-7)
    a_match = np.allclose(list(n_a)[1:], o_coeffs[3:], atol=1e-7)

    print(f"  Closed-Form Coefficients (Appendix A.3): {'PASS' if b_match and a_match else 'FAIL'}")
    print("  [FEATURE] LM Curve-Fitting: Oracle READY, untestable — not implemented in native")
    bridge.destroy_biquad(handle)

    # 2c. FeatureTest
    print("\n[Suite 2c] FeatureTest")
    test_vectors = [
        ("Noise 1024", gen.white_noise(1024)),
        ("Noise 2048", gen.white_noise(2048)),
        ("Zero Edge Case", np.array([1.0, 0.0, 1.0, -1.0, 0.0, -1.0], dtype=np.float32))
    ]

    for name, test_frame in test_vectors:
        frame_size = len(test_frame)
        print(f"  Vector: {name} ({frame_size} samples)")

        # RMS
        n_rms = bridge.compute_rms(test_frame)
        o_rms = oracle_fe.compute_rms(test_frame)
        rms_err = abs(n_rms - o_rms)
        print(f"    RMS: {'PASS' if rms_err < 1e-6 else 'FAIL'} (Err: {rms_err:.2e})")

        # Hann Window (Skip for small edge case)
        if frame_size >= 1024:
            n_windowed = bridge.apply_hann(test_frame)
            o_windowed = test_frame * oracle_fe.hann_window(frame_size)
            window_err = np.max(np.abs(n_windowed - o_windowed))
            print(f"    Hann Window: {'PASS' if window_err < 1e-6 else 'FAIL'} (Err: {window_err:.2e})")

            n_mags = bridge.get_fft_magnitudes(n_windowed)
            o_mags = np.abs(np.fft.rfft(o_windowed, frame_size))
            fft_err = np.max(np.abs(n_mags - o_mags))

            # Dynamic FFT tolerance: 1e-4 base, scaled by log2(N)
            fft_limit = 1e-4 * (1.0 + 0.5 * (np.log2(frame_size) - 10))
            print(f"    FFT Magnitude: {'PASS' if fft_err <= fft_limit else 'FAIL'} (Err: {fft_err:.2e}, Limit: {fft_limit:.2e})")

            n_centroid = bridge.compute_centroid(n_mags, 48000)
            o_centroid = oracle_fe.compute_spectral_centroid(o_windowed)
            centroid_err = abs(n_centroid - o_centroid)
            print(f"    Spectral Centroid: {'PASS' if centroid_err < 1e-5 else 'FAIL'} (Err: {centroid_err:.2e})")

        # ZCR
        n_zcr = bridge.compute_zcr(test_frame)
        o_zcr = oracle_fe.compute_zcr(test_frame)
        zcr_err = abs(n_zcr - o_zcr)
        print(f"    ZCR: {'PASS' if zcr_err < 1e-7 else 'FAIL'} (Err: {zcr_err:.2e})")

    # 2f. MfccTest
    print("\n[Suite 2f] MfccTest")
    for frame_size in [1024, 2048]:
        print(f"  Frame Size: {frame_size}")
        test_frame = gen.white_noise(frame_size)

        n_mfcc = bridge.compute_mfcc(test_frame, 48000, 13)
        o_mfcc = oracle_fe.compute_mfcc(test_frame, 13)

        mfcc_err = np.max(np.abs(n_mfcc - o_mfcc))
        print(f"    MFCC: {'PASS' if mfcc_err < 1e-3 else 'FAIL'} (Max Err: {mfcc_err:.2e})")

    # Unimplemented Feature Status
    print("\n[STATUS] Advanced Primitives")

    # 2d. InterpolationTest
    print("\n[Suite 2d] InterpolationTest (Linear Ramping)")
    handle = bridge.create_biquad()
    test_signal = np.ones(100, dtype=np.float32) # Simple DC to see ramping clearly

    # Set initial state
    bridge.lib.winton_biquad_reset(handle)
    oracle_filter.reset()
    bridge.update_biquad(handle, 1000, 0.0, 1.0, 48000, 0)
    oracle_filter.update(1000, 0.0, 1.0, 0)

    # Trigger 50-sample ramp
    bridge.update_biquad(handle, 1000, 12.0, 1.0, 48000, 50)
    oracle_filter.update(1000, 12.0, 1.0, 50)

    n_ramp = bridge.process_biquad(handle, test_signal)
    o_ramp = oracle_filter.process(test_signal)

    ramp_diff = np.max(np.abs(n_ramp - o_ramp))
    status = "PASS" if ramp_diff < 1e-5 else "FAIL"
    print(f"  Linear Ramping Convergence (50 samples): {status} (Max Error: {ramp_diff:.2e})")
    bridge.destroy_biquad(handle)

    # 2e. Analysis-Order Regression
    print("\n[Suite 2e] Analysis-Order Regression")
    engine = bridge.create_engine()
    bridge.start_engine(engine, 48000)
    test_signal = gen.white_noise(1024)

    # 1. Measure in Bypass
    # Note: NativeProcessingEngine::submitFrame handles bypass flag,
    # but we are calling processFrame directly which uses internal bypassMode_
    bridge.lib.winton_engine_set_bypass_mode(engine, True)
    bridge.process_engine_frame(engine, test_signal)
    feat_bypass = bridge.get_engine_analysis(engine)

    # 2. Measure with heavy EQ
    bridge.lib.winton_engine_set_bypass_mode(engine, False)
    bridge.set_engine_policy(engine, 1000.0, 12.0, 1.0) # Harsh boost
    bridge.process_engine_frame(engine, test_signal)
    feat_eq = bridge.get_engine_analysis(engine)

    # Compare RMS [0] and Centroid [1]
    rms_diff = abs(feat_bypass[0] - feat_eq[0])
    centroid_diff = abs(feat_bypass[1] - feat_eq[1])

    print(f"  Bypass vs EQ RMS diff: {rms_diff:.2e}")
    print(f"  Bypass vs EQ Centroid diff: {centroid_diff:.2e}")

    status = "PASS" if rms_diff < 1e-7 and centroid_diff < 1e-7 else "FAIL"
    print(f"  Analysis-Order Correctness: {status}")

    bridge.destroy_engine(engine)

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: python conformance_tests.py <path_to_shared_lib>")
    else:
        run_tests(sys.argv[1])
