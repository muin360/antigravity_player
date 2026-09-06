cmake_minimum_required(VERSION 3.22.1)
project(antigravity_oboe)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# NOTE: -ffast-math is intentionally NOT enabled.
# It breaks NaN/Inf handling and the denormal-flushing semantics the biquad
# chain relies on, and makes golden-vector tests non-deterministic.
add_compile_options(
    -O3
    -funroll-loops
    -fvisibility=hidden
    -Wall
)

# 16 KB ELF page size alignment for Android 15+ / Google Play 2025+ requirements
add_link_options("-Wl,-z,max-page-size=16384")

find_package(oboe REQUIRED CONFIG)

add_library(
    antigravity_oboe
    SHARED
    dsp/biquad_filter.cpp
    dsp/audiophile_dsp.cpp
    resampler/audiophile_resampler.cpp
    dsd/dsd_engine.cpp
    oboe_bridge.cpp
)

target_include_directories(
    antigravity_oboe
    PRIVATE
    dsp
    resampler
    dsd
)

target_link_libraries(
    antigravity_oboe
    oboe::oboe
    android
    log
)
