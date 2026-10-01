import numpy as np

def compute_zcr_oracle(samples):
    if len(samples) < 2: return 0.0
    # count sign changes
    sign_data = np.sign(samples)
    diff_data = np.diff(sign_data)
    abs_diff = np.abs(diff_data)
    count = np.sum(abs_diff) / 2
    return count / (len(samples) - 1)

test_vectors = {
    "simple": [1.0, -1.0, 1.0],
    "into_zero": [1.0, 0.0, -1.0],
    "out_of_zero": [0.0, 1.0, 0.0],
    "zero_jump": [1.0, 0.0, 1.0],
    "double_zero": [1.0, 0.0, 0.0, -1.0],
    "all_zero": [0.0, 0.0, 0.0]
}

for name, vec in test_vectors.items():
    samples = np.array(vec, dtype=np.float32)
    zcr = compute_zcr_oracle(samples)
    print(f"Vector {name}: {vec}")
    print(f"  Signs: {np.sign(samples)}")
    print(f"  Abs Diffs: {np.abs(np.diff(np.sign(samples)))}")
    print(f"  ZCR: {zcr}")
