cmake_minimum_required(VERSION 3.22.1)
project(gumjsbridge CXX)

# 预期目录结构：
# cpp/frida-gumjs-devkit/arm64-v8a/{libfrida-gumjs.a, frida-gumjs.h}
# （P0-1 2026-10-06：abiFilters 已收敛为 arm64-v8a；devkit 的 armeabi-v7a 目录保留未删，
#   需要 32 位时在 app/build.gradle.kts 的 abiFilters 中加回即可，本文件无需改动。）
set(GUMJS_DEVKIT_DIR ${CMAKE_CURRENT_SOURCE_DIR}/frida-gumjs-devkit)

if(NOT EXISTS ${GUMJS_DEVKIT_DIR}/${ANDROID_ABI}/libfrida-gumjs.a)
    message(FATAL_ERROR
        "缺少 frida-gumjs devkit: 请从 https://github.com/frida/frida/releases "
        "下载 frida-gumjs-devkit-*-android-${ANDROID_ABI}.tar.xz 并解压到 "
        "${GUMJS_DEVKIT_DIR}/${ANDROID_ABI}/")
endif()

add_library(frida-gumjs STATIC IMPORTED)
set_target_properties(frida-gumjs PROPERTIES
    IMPORTED_LOCATION ${GUMJS_DEVKIT_DIR}/${ANDROID_ABI}/libfrida-gumjs.a
    INTERFACE_INCLUDE_DIRECTORIES ${GUMJS_DEVKIT_DIR}/${ANDROID_ABI})

add_library(gumjs_bridge SHARED gumjs_bridge.cpp)

find_library(log-lib log)

# t14: 防 --gc-sections 回收 frida 内置 sqlite 初始化（运行期 SIGSEGV pc=0，见崩溃栈）
target_link_options(gumjs_bridge PRIVATE "-Wl,--undefined=_frida_sqlite3_initialize")

target_link_libraries(gumjs_bridge
    frida-gumjs
    ${log-lib}
    dl
    m)
