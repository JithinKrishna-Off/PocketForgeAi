cmake_minimum_required(VERSION 3.22)

project("pocketforge-llm" VERSION 0.1.0 LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED true)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED true)

option(POCKETFORGE_ENABLE_VULKAN "Build the ggml Vulkan backend (experimental on Android; requires glslc + a host compiler for vulkan-shaders-gen)" OFF)

if(ANDROID AND ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_SYSTEM_ARCH "ARM")
    set(GGML_CPU_KLEIDIAI ON)
    set(GGML_OPENMP ON)
else()
    message(FATAL_ERROR "PocketForge builds only for Android arm64-v8a; got ABI: ${ANDROID_ABI}")
endif()

if(POCKETFORGE_ENABLE_VULKAN)
    # ggml's ggml-vulkan does find_package(Vulkan COMPONENTS glslc) and
    # find_package(SPIRV-Headers CONFIG REQUIRED). The NDK ships C Vulkan
    # headers but no vulkan.hpp, so vendor Khronos headers pinned to the
    # same SDK release, and hand-write the SPIRV-Headers config package
    # (FetchContent's find_package redirect is not honoured for CONFIG lookups
    # of this name in CMake 3.31).
    include(FetchContent)
    FetchContent_Declare(
        spirv-headers
        GIT_REPOSITORY https://github.com/KhronosGroup/SPIRV-Headers.git
        GIT_TAG vulkan-sdk-1.3.283.0
    )
    FetchContent_Declare(
        vulkan-headers
        GIT_REPOSITORY https://github.com/KhronosGroup/Vulkan-Headers.git
        GIT_TAG v1.3.283
    )
    FetchContent_MakeAvailable(spirv-headers vulkan-headers)

    set(SPIRV_HEADERS_PKG_DIR ${CMAKE_BINARY_DIR}/pf-spirv-headers-config)
    file(MAKE_DIRECTORY ${SPIRV_HEADERS_PKG_DIR})
    file(WRITE ${SPIRV_HEADERS_PKG_DIR}/SPIRV-HeadersConfig.cmake "
if(NOT TARGET SPIRV-Headers::SPIRV-Headers)
    add_library(SPIRV-Headers::SPIRV-Headers INTERFACE IMPORTED)
    set_target_properties(SPIRV-Headers::SPIRV-Headers PROPERTIES
        INTERFACE_INCLUDE_DIRECTORIES \"${spirv-headers_SOURCE_DIR}/include\")
endif()
if(NOT TARGET SPIRV-Headers)
    add_library(SPIRV-Headers INTERFACE IMPORTED)
    set_target_properties(SPIRV-Headers PROPERTIES
        INTERFACE_INCLUDE_DIRECTORIES \"${spirv-headers_SOURCE_DIR}/include\")
endif()
set(SPIRV_HEADERS_VERSION 1.3.283.0)
")
    list(PREPEND CMAKE_PREFIX_PATH ${SPIRV_HEADERS_PKG_DIR})
    # The NDK toolchain restricts find_package to CMAKE_FIND_ROOT_PATH
    # (MODE_PACKAGE ONLY); register our config dir and the fetched Khronos
    # headers there so the lookups can succeed at all.
    list(APPEND CMAKE_FIND_ROOT_PATH ${SPIRV_HEADERS_PKG_DIR} ${vulkan-headers_SOURCE_DIR})

    # Point FindVulkan at the full Khronos headers (vulkan.hpp included);
    # glslc and the stub libvulkan.so still come from the NDK via the
    # cache variables the build script passes in.
    set(Vulkan_INCLUDE_DIR ${vulkan-headers_SOURCE_DIR}/include CACHE PATH "Vulkan headers" FORCE)
endif()

set(LLAMA_SRC ${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/llama.cpp)

# ggml links whatever find_package(OpenMP) finds; find_library prefers shared libs,
# which would impose a libomp.so runtime dependency that is not resolvable on
# Android. Prefer STATIC libraries while the llama.cpp tree (and its vendored
# FetchContent / OpenMP discovery) configures, then restore the default order.
set(_PF_OLD_FIND_SUFFIXES "${CMAKE_FIND_LIBRARY_SUFFIXES}")
set(CMAKE_FIND_LIBRARY_SUFFIXES ".a;.so")
add_subdirectory(${LLAMA_SRC} build-llama)
set(CMAKE_FIND_LIBRARY_SUFFIXES "${_PF_OLD_FIND_SUFFIXES}")

if(POCKETFORGE_ENABLE_VULKAN AND TARGET ggml-vulkan)
    # ggml-vulkan picks up <spirv/unified1/spirv.hpp> via __has_include;
    # upstream expects it from a Vulkan SDK install, we provide the fetched copy.
    target_include_directories(ggml-vulkan PRIVATE ${spirv-headers_SOURCE_DIR}/include)
endif()

add_library(${CMAKE_PROJECT_NAME} SHARED
        pf_llm.cpp)

target_compile_definitions(${CMAKE_PROJECT_NAME} PRIVATE
        GGML_SYSTEM_ARCH=${GGML_SYSTEM_ARCH}
        GGML_CPU_KLEIDIAI=$<BOOL:${GGML_CPU_KLEIDIAI}>
        GGML_OPENMP=$<BOOL:${GGML_OPENMP}>
)

target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE
        ${LLAMA_SRC}
        ${LLAMA_SRC}/common
        ${LLAMA_SRC}/include
        ${LLAMA_SRC}/ggml/include
        ${LLAMA_SRC}/ggml/src)

target_link_libraries(${CMAKE_PROJECT_NAME}
        llama
        llama-common
        android
        log)
