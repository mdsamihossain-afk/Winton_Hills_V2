#pragma once

// Mocking Android log priorities for desktop/WSL2 builds
#define ANDROID_LOG_UNKNOWN 0
#define ANDROID_LOG_DEFAULT 1
#define ANDROID_LOG_VERBOSE 2
#define ANDROID_LOG_DEBUG   3
#define ANDROID_LOG_INFO    4
#define ANDROID_LOG_WARN    5
#define ANDROID_LOG_ERROR   6
#define ANDROID_LOG_FATAL   7
#define ANDROID_LOG_SILENT  8

#ifdef __cplusplus
extern "C" {
#endif

// The actual function is already stubbed in dsp_bridge.cpp,
// but we declare it here to satisfy the header inclusion.
int __android_log_print(int prio, const char* tag, const char* fmt, ...);

#ifdef __cplusplus
}
#endif
