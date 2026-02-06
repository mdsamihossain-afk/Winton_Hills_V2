# Validation Shell - Audio Engine

An Android application built with Jetpack Compose to validate and test the Winton Audio Engine. It features real-time analysis visualization, processing policy selection, A/B testing, and session logging.

## Project Structure

- **:app**: The main Android application module using Jetpack Compose (Material3).
- **:engine**: The core engine module. Currently running in **Stub Mode** (Kotlin) to facilitate UI/UX validation while native C++ components are being integrated.

## Prerequisites

- **Android Studio** (Ladybug or newer recommended)
- **Android SDK** (API 24 minimum, API 36 target)
- **Kotlin 2.0.21**

## Getting Started

### 1. Clone the repository
bash git clone <repository-url> cd wintonhills-master

### 2. Open in Android Studio
- Open Android Studio and select **Open**.
- Navigate to the `wintonhills-master` folder and click **OK**.
- Wait for the Gradle sync to finish.

### 3. Build the Project
To assemble the debug APK, run the following command in the terminal:

## Features

- **Engine Online/Offline Toggle**: Safely starts and stops the audio analysis session.
- **Real-time Analysis**: Displays RMS Level, Spectral Centroid, and Audio Classification (Speech/Music/Mixed).
- **Processing Policies**: Switch between Auto, Speech Intelligibility, and Neutral Correction.
- **A/B Testing**: Compare "Bypass (A)" and "Processed (B)" modes instantly.
- **Session Logging**: Export session events and device metadata to a CSV file.

## Note on Native Engine
The `:engine` module currently uses a Kotlin-based stub (`Engine.kt`) to ensure the application compiles and launches without environment-specific native build issues. Native C++ build is currently disabled in `engine/build.gradle.kts`.

To restore native functionality:
1. Re-enable `externalNativeBuild` in `engine/build.gradle.kts`.
2. Ensure the NDK and CMake are correctly configured in your environment.
3. Update `Engine.kt` to use `external` declarations and `System.loadLibrary("engine")`.
