cmake_minimum_required(VERSION 3.22.1)

project(codm_native_booster LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

add_library(
    codm_native_booster
    SHARED
    native_booster.cpp
)

target_include_directories(
    codm_native_booster
    PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
)

target_compile_options(
    codm_native_booster
    PRIVATE
    -std=c++20
    -O3
    -Wall
    -Wextra
    -Werror=return-type
    -fvisibility=hidden
)

# Enforce 16KB ELF segment alignment for Android 15+ devices
target_link_options(
    codm_native_booster
    PRIVATE
    "-Wl,-z,max-page-size=16384"
)

find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(
    codm_native_booster
    PRIVATE
    ${android-lib}
    ${log-lib}
    dl
)
