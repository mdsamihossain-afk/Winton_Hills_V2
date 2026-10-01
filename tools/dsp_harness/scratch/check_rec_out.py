import numpy as np
import os

native_path = "tools/dsp_harness/vectors_arm64/rec_out_x86_64.bin"
oracle_vec_path = "tools/dsp_harness/vectors/noise_1024.bin"

if os.path.exists(native_path):
    native_out = np.fromfile(native_path, dtype=np.float32)
    test_signal = np.fromfile(oracle_vec_path, dtype=np.float32)

    print(f"Native output length: {len(native_out)}")
    print(f"Test signal length: {len(test_signal)}")

    if len(native_out) > 0:
        print(f"Native out (first 5): {native_out[:5]}")
        print(f"Native out (max/min): {np.max(native_out)}, {np.min(native_out)}")

        # Check if it's all zeros or garbage
        if np.all(native_out == 0):
            print("Native output is all ZEROS.")

    if len(test_signal) > 0:
        print(f"Test signal (first 5): {test_signal[:5]}")
