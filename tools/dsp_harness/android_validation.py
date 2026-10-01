import subprocess
import os
import numpy as np
import sys
import glob
import shlex

# Ensure dependencies are available
try:
    from dsp_harness import BiquadFilterOracle, FeatureExtractorOracle, SignalGenerator
except ImportError:
    # If dsp_harness isn't in path, try adding current dir
    sys.path.append(os.path.dirname(os.path.abspath(__file__)))
    from dsp_harness import BiquadFilterOracle, FeatureExtractorOracle, SignalGenerator

ADB = "/mnt/c/Users/scatt/AppData/Local/Android/Sdk/platform-tools/adb.exe"

# TARGET DEVICE SERIAL
SERIAL = "adb-R5CTB28VXDT-vhnSoy._adb-tls-connect._tcp"

BUILD_TYPE = "Debug"
TARGET_ABI = "arm64-v8a"

REMOTE_DIR = "/data/local/tmp/dsp_harness"
BASE_DIR = os.getcwd()
LOCAL_VECTORS = os.path.join(BASE_DIR, "tools/dsp_harness/vectors")
PULL_DIR = os.path.join(BASE_DIR, "tools/dsp_harness/vectors_android")

def run_cmd(*args):
    # Use -s SERIAL to target the physical phone
    full_cmd = " ".join([shlex.quote(ADB), "-s", shlex.quote(SERIAL)] + [shlex.quote(str(arg)) for arg in args])
    return subprocess.check_output(full_cmd, shell=True, text=True)

def to_win_path(path):
    if path.startswith("/"):
        return subprocess.check_output(["wslpath", "-w", path], text=True).strip()
    return path

def find_first_existing(patterns):
    for pattern in patterns:
        matches = sorted(glob.glob(pattern))
        if matches:
            return matches[0]
    return None

def find_runner_path(abi):
    patterns = [
        os.path.join(BASE_DIR, f"engine/.cxx/{BUILD_TYPE}/*/{abi}/native_test_runner"),
        os.path.join(BASE_DIR, f"engine/.cxx/{BUILD_TYPE}/*/obj/{abi}/native_test_runner"),
        os.path.join(BASE_DIR, f"engine/build/intermediates/cxx/{BUILD_TYPE}/*/obj/{abi}/native_test_runner"),
        os.path.join(BASE_DIR, f"engine/build/intermediates/cxx/{BUILD_TYPE}/*/{abi}/native_test_runner"),
    ]
    return find_first_existing(patterns)

def adb_push(local_path, remote_path):
    run_cmd("push", to_win_path(local_path), remote_path)

def adb_pull(remote_path, local_path):
    os.makedirs(os.path.dirname(local_path), exist_ok=True)
    run_cmd("pull", remote_path, to_win_path(local_path))

def print_table(headers, rows):
    widths = [len(h) for h in headers]
    for row in rows:
        for idx, cell in enumerate(row):
            widths[idx] = max(widths[idx], len(str(cell)))

    def format_row(values):
        return " | ".join(str(values[idx]).ljust(widths[idx]) for idx in range(len(values)))

    print(format_row(headers))
    print("-+-".join("-" * width for width in widths))
    for row in rows:
        print(format_row(row))

def main():
    print("--- Winton DSP Android Hardware Conformance Report ---")

    # 0. Detect ABI and Hardware Info
    try:
        abi = run_cmd("shell", "getprop", "ro.product.cpu.abi").strip()
        abi_list = run_cmd("shell", "getprop", "ro.product.cpu.abilist").strip()
        model = run_cmd("shell", "getprop", "ro.product.model").strip()
        hardware = run_cmd("shell", "getprop", "ro.hardware").strip()
        print(f"Device serial: {SERIAL}")
        print(f"Model: {model}")
        print(f"Primary ABI (ro.product.cpu.abi): {abi}")
        print(f"ABI list (ro.product.cpu.abilist): {abi_list}")
        print(f"Hardware (ro.hardware): {hardware}")
        print(f"Mechanism: native_test_runner.cpp (standalone HIL) via {ADB}")

        if abi != TARGET_ABI:
            print(f"WARNING: Target is NOT {TARGET_ABI} (detected {abi}). Results may not reflect ARM64 silicon.")
    except Exception as e:
        print(f"Error: Device {SERIAL} not connected or ADB failed: {e}")
        return

    # 1. Setup
    run_cmd("shell", "mkdir", "-p", REMOTE_DIR)

    # Find built runner
    runner_path = find_runner_path(abi)
    if not runner_path:
        patterns = [
            os.path.join(BASE_DIR, f"engine/.cxx/{BUILD_TYPE}/*/{abi}/native_test_runner"),
            os.path.join(BASE_DIR, f"engine/.cxx/{BUILD_TYPE}/*/obj/{abi}/native_test_runner"),
            os.path.join(BASE_DIR, f"engine/build/intermediates/cxx/{BUILD_TYPE}/*/obj/{abi}/native_test_runner"),
            os.path.join(BASE_DIR, f"engine/build/intermediates/cxx/{BUILD_TYPE}/*/{abi}/native_test_runner"),
        ]
        print(f"Error: Could not find native_test_runner for {abi} in any RelWithDebInfo build location:")
        for pattern in patterns:
            print(f"  - {pattern}")
        return

    RUNNER_PATH = runner_path

    # Push executable and its dependencies
    adb_push(RUNNER_PATH, f"{REMOTE_DIR}/native_test_runner")

    libs_to_push = {}
    lib_search_dirs = [
        os.path.dirname(RUNNER_PATH),
        os.path.join(os.path.dirname(RUNNER_PATH), "obj", abi),
    ]

    ndk_libcxx = os.path.join(
        os.environ.get("ANDROID_NDK_HOME", ""),
        "toolchains",
        "llvm",
        "prebuilt",
        "linux-x86_64",
        "sysroot",
        "usr",
        "lib",
        "aarch64-linux-android",
        "libc++_shared.so",
    )

    for lib in ["libc++_shared.so", "liboboe.so", "libengine.so"]:
        for lib_dir in lib_search_dirs:
            lib_path = os.path.join(lib_dir, lib)
            if os.path.exists(lib_path):
                libs_to_push[lib] = lib_path
                break
        if lib == "libc++_shared.so" and lib not in libs_to_push and os.path.exists(ndk_libcxx):
            libs_to_push[lib] = ndk_libcxx

    for lib, lib_path in libs_to_push.items():
        adb_push(lib_path, f"{REMOTE_DIR}/{lib}")

    run_cmd("shell", "chmod", "+x", f"{REMOTE_DIR}/native_test_runner")

    # Push individual files
    for f in ["noise_1024.bin", "noise_2048.bin", "zero_edge.bin"]:
        local_f = os.path.join(LOCAL_VECTORS, f)
        adb_push(local_f, f"{REMOTE_DIR}/{f}")

    os.makedirs(PULL_DIR, exist_ok=True)

    def run_remote(args):
        return run_cmd("shell", f"cd {REMOTE_DIR} && LD_LIBRARY_PATH=. ./native_test_runner {args}")

    oracle_filter = BiquadFilterOracle()
    oracle_fe = FeatureExtractorOracle()

    # --- Suite 2a: Recurrence ---
    print("\n[Suite 2a] RecurrenceTest (Android)")
    run_remote(f"recurrence {REMOTE_DIR}/noise_1024.bin {REMOTE_DIR}/rec_out.bin")

    local_rec_out = os.path.join(PULL_DIR, f"rec_out_{abi}.bin")
    adb_pull(f"{REMOTE_DIR}/rec_out.bin", local_rec_out)

    native_out = np.fromfile(local_rec_out, dtype=np.float32)
    test_signal = np.fromfile(os.path.join(LOCAL_VECTORS, "noise_1024.bin"), dtype=np.float32)
    oracle_filter.update(1000, 6.0, 1.0, 0)
    oracle_out = oracle_filter.process(test_signal)

    diff = np.abs(native_out - oracle_out)
    max_diff = np.max(diff)
    print(f"  Sample-by-sample match: {'PASS' if max_diff < 1e-5 else 'FAIL'} (Max Error: {max_diff:.2e})")

    # --- Suite 2b: Synthesis ---
    print("\n[Suite 2b] SynthesisTest (Android)")
    run_remote(f"synthesis {REMOTE_DIR}/coeffs.txt")

    local_coeffs = os.path.join(PULL_DIR, f"coeffs_{abi}.txt")
    adb_pull(f"{REMOTE_DIR}/coeffs.txt", local_coeffs)

    with open(local_coeffs, "r") as f:
        line = f.readline().split()
        n_coeffs = [float(x) for x in line]

    o_coeffs = oracle_filter.generate_coefficients(1000, 6.0, 1.0)
    match = np.allclose(n_coeffs, o_coeffs, atol=1e-7)
    print(f"  Closed-Form Coefficients (Appendix A.3): {'PASS' if match else 'FAIL'}")

    # --- Suite 2c: Features ---
    print("\n[Suite 2c] FeatureTest (Android)")
    feature_headers = ["Vector", "Frame", "RMS Err", "Hann Err", "FFT Err", "Cent Err", "ZCR Err", "Status"]
    feature_rows = []

    test_vectors = [
        ("Noise 1024", "noise_1024.bin", 1024),
        ("Noise 2048", "noise_2048.bin", 2048),
    ]

    feature_limits = {
        "rms": 1e-5,
        "hann": 1e-5,
        "fft": 1e-4,
        "centroid": 1e-5,
        "zcr": 1e-5,
    }

    for name, vector_name, frame_size in test_vectors:
        output_base = f"{REMOTE_DIR}/feat_{vector_name}"
        run_remote(f"features {REMOTE_DIR}/{vector_name} {output_base} {frame_size}")

        # Pull text results
        local_feat_txt = os.path.join(PULL_DIR, f"feat_{vector_name}_{abi}.txt")
        adb_pull(f"{output_base}.txt", local_feat_txt)

        results = {}
        with open(local_feat_txt, "r") as f:
            for line in f:
                if ":" in line:
                    k, v = line.split(":", 1)
                    results[k.strip()] = v.strip()

        test_frame = np.fromfile(os.path.join(LOCAL_VECTORS, vector_name), dtype=np.float32)

        # 1. RMS
        n_rms = float(results["rms"])
        o_rms = oracle_fe.compute_rms(test_frame)
        rms_err = abs(n_rms - o_rms)

        # 2. ZCR
        n_zcr = float(results["zcr"])
        o_zcr = oracle_fe.compute_zcr(test_frame)
        zcr_err = abs(n_zcr - o_zcr)

        hann_err_str = "N/A"
        fft_err_str = "N/A"
        cent_err_str = "N/A"

        if frame_size >= 1024:
            # Pull binary buffers
            local_win_bin = os.path.join(PULL_DIR, f"feat_{vector_name}_windowed_{abi}.bin")
            local_mags_bin = os.path.join(PULL_DIR, f"feat_{vector_name}_mags_{abi}.bin")
            adb_pull(f"{output_base}_windowed.bin", local_win_bin)
            adb_pull(f"{output_base}_mags.bin", local_mags_bin)

            n_windowed = np.fromfile(local_win_bin, dtype=np.float32)
            n_mags = np.fromfile(local_mags_bin, dtype=np.float32)

            # 3. Hann
            o_windowed = test_frame * oracle_fe.hann_window(frame_size)
            hann_err = np.max(np.abs(n_windowed - o_windowed))
            hann_err_str = f"{hann_err:.2e}"

            # 4. FFT
            o_mags = np.abs(np.fft.rfft(o_windowed, frame_size))
            fft_err = np.max(np.abs(n_mags - o_mags))
            fft_err_str = f"{fft_err:.2e}"

            # 5. Centroid
            n_centroid = float(results["centroid"])
            o_centroid = oracle_fe.compute_spectral_centroid(o_windowed)
            cent_err = abs(n_centroid - o_centroid)
            cent_err_str = f"{cent_err:.2e}"

            # Dynamic FFT tolerance: scale by log2(N)
            # Base: 1e-4 for N=1024 (log2=10)
            # For N=2048 (log2=11), limit = 1.5e-4
            fft_limit = feature_limits["fft"] * (1.0 + 0.5 * (np.log2(frame_size) - 10))

            status = "PASS" if (
                rms_err <= feature_limits["rms"]
                and hann_err <= feature_limits["hann"]
                and fft_err <= fft_limit
                and cent_err <= feature_limits["centroid"]
                and zcr_err <= feature_limits["zcr"]
            ) else "FAIL"

            feature_rows.append([
                name,
                str(frame_size),
                f"{rms_err:.2e}",
                hann_err_str,
                fft_err_str,
                cent_err_str,
                f"{zcr_err:.2e}",
                status,
            ])
        else:
            feature_rows.append([
                name,
                str(frame_size),
                f"{rms_err:.2e}",
                hann_err_str,
                fft_err_str,
                cent_err_str,
                f"{zcr_err:.2e}",
                "N/A",
            ])

    print_table(feature_headers, feature_rows)

    # --- Suite 2f: MFCC ---
    print("\n[Suite 2f] MfccTest (Android)")
    mfcc_headers = ["Vector", "Frame", "MFCC Max Err", "Status"]
    mfcc_rows = []

    for name, vector_name, frame_size in test_vectors:
        run_remote(f"mfcc {REMOTE_DIR}/{vector_name} {REMOTE_DIR}/mfcc_{vector_name}.bin {frame_size}")

        local_mfcc = os.path.join(PULL_DIR, f"mfcc_{vector_name}_{abi}.bin")
        adb_pull(f"{REMOTE_DIR}/mfcc_{vector_name}.bin", local_mfcc)

        n_mfcc = np.fromfile(local_mfcc, dtype=np.float32)
        test_frame = np.fromfile(os.path.join(LOCAL_VECTORS, vector_name), dtype=np.float32)
        o_mfcc = oracle_fe.compute_mfcc(test_frame, 13)

        mfcc_err = np.max(np.abs(n_mfcc - o_mfcc))
        status = "PASS" if mfcc_err < 1e-3 else "FAIL"
        mfcc_rows.append([name, str(frame_size), f"{mfcc_err:.2e}", status])

    print_table(mfcc_headers, mfcc_rows)

    # --- Suite 2g: Policy ---
    print("\n[Suite 2g] Auto-Policy Selection (Android)")
    run_remote(f"policy {REMOTE_DIR}/policy_out.txt")

    local_policy = os.path.join(PULL_DIR, f"policy_out_{abi}.txt")
    adb_pull(f"{REMOTE_DIR}/policy_out.txt", local_policy)

    policy_results = {}
    with open(local_policy, "r") as f:
        for line in f:
            if ":" in line:
                k, v = line.split(":", 1)
                policy_results[k.strip()] = v.strip()

    # 0 = Passthrough, 1 = Speech
    rapid_changes = int(policy_results.get("rapid_classification_changes_fed", 0))
    switches = int(policy_results.get("hysteresis_switches_occurred", -1))
    max_jump = float(policy_results.get("max_observed_discontinuity", 0.0))

    h_pass = (switches == 0)
    p_pass = (max_jump >= 0.1) # Confirms detector sees the jump
    b_pass = (policy_results.get("bluetooth_speech_signal_policy") == "0")

    print(f"  Hysteresis Audit: {rapid_changes} rapid changes -> {switches} switches (Expected 0) : {'PASS' if h_pass else 'FAIL'}")
    print(f"  Pop Detector: Max Discontinuity {max_jump:.4f} (Threshold 0.1) : {'PASS' if p_pass else 'FAIL'}")
    print(f"  Route Constraint: Speech on BT -> Policy {policy_results.get('bluetooth_speech_signal_policy')} (Expected 0) : {'PASS' if b_pass else 'FAIL'}")

    # --- Suite 2d: Interpolation ---
    print("\n[Suite 2d] InterpolationTest (Android)")
    run_remote(f"interpolation {REMOTE_DIR}/ramp_out.bin")

    local_ramp_out = os.path.join(PULL_DIR, f"ramp_out_{abi}.bin")
    adb_pull(f"{REMOTE_DIR}/ramp_out.bin", local_ramp_out)

    n_ramp = np.fromfile(local_ramp_out, dtype=np.float32)
    oracle_filter.reset()
    oracle_filter.update(1000, 0.0, 1.0, 0)
    oracle_filter.update(1000, 12.0, 1.0, 50)
    o_ramp = oracle_filter.process(np.ones(100, dtype=np.float32))

    ramp_diff = np.max(np.abs(n_ramp - o_ramp))
    print(f"  Linear Ramping Convergence (50 samples): {'PASS' if ramp_diff < 1e-5 else 'FAIL'} (Max Error: {ramp_diff:.2e})")

    # --- Suite 2e: Regression ---
    print("\n[Suite 2e] Analysis-Order Regression (Android)")
    run_remote(f"regression {REMOTE_DIR}/noise_1024.bin {REMOTE_DIR}/reg_out.txt")

    local_reg = os.path.join(PULL_DIR, f"reg_out_{abi}.txt")
    adb_pull(f"{REMOTE_DIR}/reg_out.txt", local_reg)

    reg_results = {}
    with open(local_reg, "r") as f:
        for line in f:
            if ":" in line:
                k, v = line.split(":", 1)
                reg_results[k.strip()] = v.strip()

    rms_diff = abs(float(reg_results["bypass_rms"]) - float(reg_results["eq_rms"]))
    centroid_diff = abs(float(reg_results["bypass_centroid"]) - float(reg_results["eq_centroid"]))
    print(f"  Bypass vs EQ RMS diff: {rms_diff:.2e}")
    print(f"  Bypass vs EQ Centroid diff: {centroid_diff:.2e}")
    print(f"  Analysis-Order Correctness: {'PASS' if rms_diff < 1e-7 and centroid_diff < 1e-7 else 'FAIL'}")

if __name__ == "__main__":
    main()
