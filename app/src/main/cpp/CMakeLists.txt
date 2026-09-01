cmake_minimum_required(VERSION 3.22.1)
project(ndiviewer LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(ndiviewer SHARED ndi_jni.cpp)

add_library(ndi SHARED IMPORTED)
set_target_properties(ndi PROPERTIES IMPORTED_LOCATION
        ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libndi.so)

target_include_directories(ndiviewer PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/ndi)

target_compile_definitions(ndiviewer PRIVATE NDILIB_CPP_DEFAULT_CONSTRUCTORS=0)

target_link_libraries(ndiviewer
        ndi
        aaudio
        log
        android)

target_compile_options(ndiviewer PRIVATE -Wall -Wextra)
