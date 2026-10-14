// Altair JNI bridge — Phase 0 stub.
// Validates the NDK + CMake + JNI pipeline in CI. llama.cpp bindings land in Phase 1.
#include <jni.h>
#include <string>

extern "C" JNIEXPORT jstring JNICALL
Java_com_altair_llm_AltairJni_version(JNIEnv* env, jobject /*thiz*/) {
    return env->NewStringUTF("altair-jni 0.1.0 (stub — llama.cpp pending)");
}
