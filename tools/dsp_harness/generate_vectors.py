import numpy as np
import os

def generate_white_noise(length, seed=42):
    np.random.seed(seed)
    return np.random.uniform(-1, 1, length).astype(np.float32)

def main():
    base_dir = os.path.dirname(os.path.abspath(__file__))
    vectors_dir = os.path.join(base_dir, "vectors")
    os.makedirs(vectors_dir, exist_ok=True)

    # 2a Recurrence vector
    noise_1024 = generate_white_noise(1024)
    noise_1024.tofile(os.path.join(vectors_dir, "noise_1024.bin"))

    # 2c Feature vectors
    noise_2048 = generate_white_noise(2048)
    noise_2048.tofile(os.path.join(vectors_dir, "noise_2048.bin"))

    zero_edge = np.array([1.0, 0.0, 1.0, -1.0, 0.0, -1.0], dtype=np.float32)
    zero_edge.tofile(os.path.join(vectors_dir, "zero_edge.bin"))

    print(f"Generated vectors in {vectors_dir}")

if __name__ == "__main__":
    main()
