cmake_minimum_required(VERSION 3.18.1)

# Always optimised: an unoptimised whisper.cpp is many times too slow to be usable on a phone.
set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)

project(whisperjni LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# whisper.cpp is downloaded at configure time (needs internet; GitHub Actions has it).
# Pinned to a release tag so the build is reproducible.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_CCACHE OFF CACHE BOOL "" FORCE)

if(ANDROID)
    # cross-compiling: never tune for the build machine's CPU
    set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
endif()

include(FetchContent)
FetchContent_Declare(
    whisper
    GIT_REPOSITORY https://github.com/ggml-org/whisper.cpp.git
    GIT_TAG v1.9.5
    GIT_SHALLOW TRUE
)
FetchContent_MakeAvailable(whisper)

add_library(whisperjni SHARED whisper_jni.cpp)
target_link_libraries(whisperjni PRIVATE whisper)

if(ANDROID)
    find_library(LOG_LIB log)
    target_link_libraries(whisperjni PRIVATE ${LOG_LIB})
else()
    # host build, only used to test the JNI glue on a development machine
    find_package(JNI REQUIRED)
    target_include_directories(whisperjni PRIVATE ${JNI_INCLUDE_DIRS})
endif()
