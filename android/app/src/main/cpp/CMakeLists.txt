cmake_minimum_required(VERSION 3.22)
project(altair_jni LANGUAGES CXX)

add_library(altair_jni SHARED altair_jni.cpp)

find_library(log-lib log)
target_link_libraries(altair_jni ${log-lib})

# Phase 1 will vendor llama.cpp here:
#   add_subdirectory(llama.cpp)  →  target_link_libraries(altair_jni llama)
