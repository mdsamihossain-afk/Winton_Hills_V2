#include <jni.h>
extern "C" {

JNIEXPORT void JNICALL
Java_com_winton_validationshell_engine_Engine_startAudioEngine(
        JNIEnv* env,
        jobject thiz,
        jboolean conservative) {
    // start engine
}

JNIEXPORT void JNICALL
Java_com_winton_validationshell_engine_Engine_stopAudioEngine(
        JNIEnv* env,
        jobject thiz) {
    // stop engine
}

JNIEXPORT jfloatArray JNICALL
Java_com_winton_validationshell_engine_Engine_getAnalysisData(
        JNIEnv* env,
        jobject thiz) {

    jfloatArray arr = env->NewFloatArray(4);
    float data[4] = {0.5f, 0.3f, 1.0f, 0.9f};
    env->SetFloatArrayRegion(arr, 0, 4, data);
    return arr;
}

JNIEXPORT void JNICALL
Java_com_winton_validationshell_engine_Engine_setPolicy(
        JNIEnv* env,
        jobject thiz,
        jint policy) {
}

JNIEXPORT void JNICALL
Java_com_winton_validationshell_engine_Engine_setAutoPolicy(
        JNIEnv* env,
        jobject thiz,
        jboolean enabled) {
}

}
