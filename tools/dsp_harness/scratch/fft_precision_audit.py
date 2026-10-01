import numpy as np
import os
import ctypes

class DspBridge:
    def __init__(self, lib_path):
        self.lib = ctypes.CDLL(lib_path)
        self.lib.winton_engine_apply_hann_window.argtypes = [ctypes.POINTER(ctypes.c_float), ctypes.c_int]
        self.lib.winton_engine_get_fft_magnitudes.argtypes = [ctypes.POINTER(ctypes.c_float), ctypes.c_int, ctypes.POINTER(ctypes.c_float)]

    def apply_hann(self, samples):
        n = len(samples)
        c_samples = (ctypes.c_float * n)(*samples)
        self.lib.winton_engine_apply_hann_window(c_samples, n)
        return np.array(c_samples)

    def get_fft_magnitudes(self, samples):
        n = len(samples)
        c_samples = (ctypes.c_float * n)(*samples)
        c_mags = (ctypes.c_float * (n // 2))()
        self.lib.winton_engine_get_fft_magnitudes(c_samples, n, c_mags)
        return np.array(c_mags)

def main():
    base_dir = "tools/dsp_harness"
    vector_path = os.path.join(base_dir, "vectors/noise_2048.bin")
    arm64_mags_path = os.path.join(base_dir, "vectors_android/feat_noise_2048.bin_mags_arm64-v8a.bin")
    lib_path = os.path.join(base_dir, "libwinton_dsp.so")

    # 1. Load raw input
    noise = np.fromfile(vector_path, dtype=np.float32)

    # 2. Compute Double Precision Reference
    # Use float64 for windowing and FFT
    n = len(noise)
    # Hann window formula matching C++: 0.5 * (1.0 - cos(2*pi*i / (n-1)))
    window = 0.5 * (1.0 - np.cos(2.0 * np.pi * np.arange(n) / (n - 1)))
    windowed_double = noise.astype(np.float64) * window
    fft_double = np.fft.fft(windowed_double)
    mags_double = np.abs(fft_double[:n//2])

    # 3. Load ARM64 Result
    mags_arm64 = np.fromfile(arm64_mags_path, dtype=np.float32)

    # 4. Generate x86_64 Result (via bridge)
    bridge = DspBridge(lib_path)
    windowed_x86 = bridge.apply_hann(noise)
    mags_x86 = bridge.get_fft_magnitudes(windowed_x86)

    # 5. Compare errors vs Double Precision Ground Truth
    err_arm64 = np.abs(mags_arm64 - mags_double)
    err_x86 = np.abs(mags_x86 - mags_double)

    max_err_arm64 = np.max(err_arm64)
    max_err_x86 = np.max(err_x86)

    mean_err_arm64 = np.mean(err_arm64)
    mean_err_x86 = np.mean(err_x86)

    print(f"FFT Magnitude Precision Audit (N=2048)")
    print(f"---------------------------------------")
    print(f"x86_64 (Single) vs Double:  Max={max_err_x86:.2e}, Mean={mean_err_x86:.2e}")
    print(f"ARM64  (Single) vs Double:  Max={max_err_arm64:.2e}, Mean={mean_err_arm64:.2e}")
    print(f"")

    if max_err_arm64 < max_err_x86:
        print("RESULT: ARM64 is MORE accurate than x86_64 (likely due to FMA).")
    elif max_err_arm64 == max_err_x86:
        print("RESULT: ARM64 and x86_64 have identical max error.")
    else:
        print("RESULT: ARM64 is LESS accurate than x86_64.")

    # 6. Check error growth across bins
    # For FFT, divergence usually happens in high-frequency bins or where magnitudes are small/large
    idx_max_arm = np.argmax(err_arm64)
    print(f"ARM64 Max Error at bin {idx_max_arm}: {mags_arm64[idx_max_arm]:.4f} (ARM) vs {mags_double[idx_max_arm]:.4f} (Double)")

if __name__ == "__main__":
    main()
