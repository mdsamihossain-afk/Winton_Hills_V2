import numpy as np

def white_noise(length=1024, seed=42):
    np.random.seed(seed)
    return np.random.uniform(-1, 1, length).astype(np.float32)

def compute_rms_double_acc(samples):
    # Matches C++: double sum = 0.0; sum += (double)x * (double)x
    sum_sq = 0.0
    for x in samples:
        sum_sq += float(x) * float(x)
    return np.float32(np.sqrt(sum_sq / len(samples)))

def compute_rms_numpy(samples):
    # Matches oracle: np.sqrt(np.mean(np.square(samples)))
    return np.sqrt(np.mean(np.square(samples)))

for size in [1024, 2048]:
    data = white_noise(size)
    rms_c = compute_rms_double_acc(data)
    rms_o = compute_rms_numpy(data)
    diff = abs(rms_c - rms_o)
    print(f"Size {size}:")
    print(f"  First 5 samples: {data[:5]}")
    print(f"  RMS C++ (Double Acc): {rms_c:.10f}")
    print(f"  RMS Oracle (Numpy):   {rms_o:.10f}")
    print(f"  Diff: {diff:.2e}")
    print(f"  Mean of data: {np.mean(data):.10f}")
