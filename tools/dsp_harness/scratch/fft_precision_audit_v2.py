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

def audit_size(n, bridge, base_dir, lib_path, arm64_path):
    vector_path = os.path.join(base_dir, f"vectors/noise_{n}.bin")
    if not os.path.exists(vector_path): return

    noise = np.fromfile(vector_path, dtype=np.float32)
    window = 0.5 * (1.0 - np.cos(2.0 * np.pi * np.arange(n) / (n - 1)))
    windowed_double = noise.astype(np.float64) * window
    fft_double = np.fft.fft(windowed_double)
    mags_double = np.abs(fft_double[:n//2])

    mags_arm64 = np.fromfile(arm64_path, dtype=np.float32)
    mags_x86 = bridge.get_fft_magnitudes(bridge.apply_hann(noise))

    err_arm64 = np.abs(mags_arm64 - mags_double)
    err_x86 = np.abs(mags_x86 - mags_double)

    print(f"Audit N={n}")
    print(f"  x86_64 vs Double: Max={np.max(err_x86):.2e}")
    print(f"  ARM64  vs Double: Max={np.max(err_arm64):.2e}")

def main():
    base_dir = "tools/dsp_harness"
    lib_path = os.path.join(base_dir, "libwinton_dsp.so")
    bridge = DspBridge(lib_path)

    audit_size(1024, bridge, base_dir, lib_path, os.path.join(base_dir, "vectors_android/feat_noise_1024.bin_mags_arm64-v8a.bin"))
    audit_size(2048, bridge, base_dir, lib_path, os.path.join(base_dir, "vectors_android/feat_noise_2048.bin_mags_arm64-v8a.bin"))

if __name__ == "__main__":
    main()
