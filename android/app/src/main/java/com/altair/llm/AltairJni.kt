package com.altair.llm

/**
 * JNI bridge to the native inference layer.
 * Phase 0: version stub. Phase 1: llama.cpp load/generate bindings.
 */
object AltairJni {
    init {
        System.loadLibrary("altair_jni")
    }

    external fun version(): String
}
