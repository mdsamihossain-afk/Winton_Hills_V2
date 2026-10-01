import numpy as np
from scipy import fftpack, signal

class BiquadFilterOracle:
    """
    Python implementation of the Biquad filter logic defined in native_processing_engine.cpp.
    Matches Step 1 findings: DF2T recurrence, Appendix A.3 synthesis, linear ramping.
    """
    def __init__(self, sample_rate=48000):
        self.sample_rate = sample_rate
        # Coefficients
        self.b0, self.b1, self.b2 = 1.0, 0.0, 0.0
        self.a1, self.a2 = 0.0, 0.0
        # State
        self.z1, self.z2 = 0.0, 0.0
        # Ramping
        self.target_b0, self.target_b1, self.target_b2 = 1.0, 0.0, 0.0
        self.target_a1, self.target_a2 = 0.0, 0.0
        self.step_b0, self.step_b1, self.step_b2 = 0.0, 0.0, 0.0
        self.step_a1, self.step_a2 = 0.0, 0.0
        self.ramp_remaining = 0

    def generate_coefficients(self, freq_hz, gain_db, q):
        """
        Implements Appendix A.3 peaking EQ synthesis.
        Exact match to native_processing_engine.cpp: lines 33-51.
        """
        f = np.clip(freq_hz, 20.0, self.sample_rate / 2.0 - 1.0)
        q = max(0.1, q)
        a = 10.0**(gain_db / 40.0)
        w0 = 2.0 * np.pi * f / self.sample_rate
        alpha = np.sin(w0) / (2.0 * q)
        cos_w0 = np.cos(w0)

        a0 = 1.0 + alpha / a
        b0 = (1.0 + alpha * a) / a0
        b1 = (-2.0 * cos_w0) / a0
        b2 = (1.0 - alpha * a) / a0
        a1 = (-2.0 * cos_w0) / a0
        a2 = (1.0 - alpha / a) / a0

        return b0, b1, b2, a1, a2

    def update(self, freq_hz, gain_db, q, ramp_samples=0):
        """
        Updates filter parameters with linear ramping.
        Matches native_processing_engine.cpp linear step logic.
        """
        b0, b1, b2, a1, a2 = self.generate_coefficients(freq_hz, gain_db, q)

        if ramp_samples <= 0:
            self.b0, self.b1, self.b2 = b0, b1, b2
            self.a1, self.a2 = a1, a2
            self.ramp_remaining = 0
            return

        self.target_b0, self.target_b1, self.target_b2 = b0, b1, b2
        self.target_a1, self.target_a2 = a1, a2
        self.ramp_remaining = max(1, ramp_samples)

        self.step_b0 = (self.target_b0 - self.b0) / self.ramp_remaining
        self.step_b1 = (self.target_b1 - self.b1) / self.ramp_remaining
        self.step_b2 = (self.target_b2 - self.b2) / self.ramp_remaining
        self.step_a1 = (self.target_a1 - self.a1) / self.ramp_remaining
        self.step_a2 = (self.target_a2 - self.a2) / self.ramp_remaining

    def process_sample(self, x):
        """
        Exact DF2T recurrence from spec §1.
        Matches native_processing_engine.cpp: lines 80-83.
        """
        if self.ramp_remaining > 0:
            self.b0 += self.step_b0
            self.b1 += self.step_b1
            self.b2 += self.step_b2
            self.a1 += self.step_a1
            self.a2 += self.step_a2
            self.ramp_remaining -= 1
            if self.ramp_remaining == 0:
                self.b0, self.b1, self.b2 = self.target_b0, self.target_b1, self.target_b2
                self.a1, self.a2 = self.target_a1, self.target_a2

        y = self.b0 * x + self.z1
        self.z1 = self.b1 * x - self.a1 * y + self.z2
        self.z2 = self.b2 * x - self.a2 * y
        return y

    def process(self, samples):
        return np.array([self.process_sample(s) for s in samples], dtype=np.float32)

    def reset(self):
        self.z1, self.z2 = 0.0, 0.0


class FeatureExtractorOracle:
    """
    Python oracle for audio feature extraction.
    Matches spec §2c/§5.2/§6.
    """
    def __init__(self, sample_rate=48000):
        self.sample_rate = sample_rate

    def hann_window(self, n):
        """Hann window matching spec §2c and native_processing_engine.cpp: line 231."""
        if n <= 1: return np.ones(n)
        return 0.5 * (1.0 - np.cos(2.0 * np.pi * np.arange(n) / (n - 1)))

    def compute_rms(self, samples):
        """RMS matching native_processing_engine.cpp: lines 215-225."""
        if len(samples) == 0: return 0.0
        return np.sqrt(np.mean(np.square(samples)))

    def compute_spectral_centroid(self, samples):
        """
        Spectral Centroid matching spec §2c and native_processing_engine.cpp: lines 260-281.
        Assumes samples are already windowed if that's how the implementation works.
        """
        n = len(samples)
        if n == 0: return 0.0

        # FFT Magnitude Spectrum (N/2 + 1 bins)
        # Updated to include Nyquist bin to match native FFT refinement (2026-09-11)
        magnitudes = np.abs(np.fft.rfft(samples))
        nyquist = self.sample_rate / 2.0
        freqs = np.fft.rfftfreq(n, 1.0 / self.sample_rate)

        magnitude_sum = np.sum(magnitudes)
        if magnitude_sum < 1e-10:
            return 0.0

        weighted_sum = np.sum(freqs * magnitudes)
        centroid_hz = weighted_sum / magnitude_sum
        return np.clip(centroid_hz / nyquist, 0.0, 1.0)

    def compute_zcr(self, samples):
        """Zero-Crossing Rate (ZCR) with 1e-5 threshold to match native core."""
        if len(samples) < 2: return 0.0
        # Apply deadband threshold to match C++ hardening (2026-09-11)
        thresh = 1e-5
        s = np.zeros_like(samples)
        s[samples > thresh] = 1
        s[samples < -thresh] = -1
        # count sign changes
        count = np.sum(np.abs(np.diff(s))) / 2
        return count / (len(samples) - 1)

    def compute_mfcc(self, samples, n_mfcc=13):
        """
        MFCC implementation per spec §5.2 and §6.
        - 13 Mel bands
        - Pre-emphasis alpha=0.97 (conventional default, not spec-mandated)
        - DCT-II (conventional default, not spec-mandated)
        """
        # 1. Pre-emphasis
        # y[n] = x[n] - alpha * x[n-1]
        alpha = 0.97  # conventional default, not spec-mandated
        emphasized = np.append(samples[0], samples[1:] - alpha * samples[:-1])

        # 2. Windowing (Hann)
        windowed = emphasized * self.hann_window(len(samples))

        # 3. FFT (Power Spectrum)
        n_fft = len(samples)
        pow_frames = ((np.absolute(np.fft.rfft(windowed, n_fft))) ** 2) / n_fft

        # 4. Mel Filterbank (13 bands per spec §6)
        low_freq_mel = 0
        high_freq_mel = (2595 * np.log10(1 + (self.sample_rate / 2) / 700))  # Mel scale formula per spec §2c
        mel_points = np.linspace(low_freq_mel, high_freq_mel, n_mfcc + 2)
        hz_points = (700 * (10**(mel_points / 2595) - 1))
        bin = np.floor((n_fft + 1) * hz_points / self.sample_rate)

        fbank = np.zeros((n_mfcc, int(np.floor(n_fft / 2 + 1))))
        for j in range(0, n_mfcc):
            for i in range(int(bin[j]), int(bin[j+1])):
                fbank[j,i] = (i - bin[j]) / (bin[j+1] - bin[j])
            for i in range(int(bin[j+1]), int(bin[j+2])):
                fbank[j,i] = (bin[j+2] - i) / (bin[j+2] - bin[j+1])

        filter_banks = np.dot(pow_frames, fbank.T)
        filter_banks = np.where(filter_banks == 0, np.finfo(float).eps, filter_banks)
        filter_banks = 20 * np.log10(filter_banks)  # dB

        # 5. DCT-II
        # Using scipy.fftpack.dct with type 2
        mfcc = fftpack.dct(filter_banks, type=2, norm='ortho')
        return mfcc[:n_mfcc]


class SignalGenerator:
    """Standard test signals matching spec §4."""
    @staticmethod
    def impulse(length=1024):
        x = np.zeros(length, dtype=np.float32)
        x[0] = 1.0
        return x

    @staticmethod
    def log_chirp(fs=48000, duration=1.0, f0=20.0, f1=20000.0):
        """20 Hz-20 kHz log sweep per spec §4 and Step 2 addition."""
        t = np.linspace(0, duration, int(fs * duration))
        return signal.chirp(t, f0=f0, f1=f1, t1=duration, method='logarithmic').astype(np.float32)

    @staticmethod
    def white_noise(length=1024, seed=42):
        """White noise with fixed seed per spec §4."""
        np.random.seed(seed)
        return np.random.uniform(-1, 1, length).astype(np.float32)


# Spec §3 Tolerances
TOLERANCE_MAGNITUDE_DB = 0.1
TOLERANCE_PHASE_DEG = 1.0
TOLERANCE_MFCC = 1e-3
