# Install script for directory: C:/Users/ASUS/Desktop/PocketForgeAI/third_party/llama.cpp/ggml

# Set the install prefix
if(NOT DEFINED CMAKE_INSTALL_PREFIX)
  set(CMAKE_INSTALL_PREFIX "C:/Program Files (x86)/pocketforge-llm")
endif()
string(REGEX REPLACE "/$" "" CMAKE_INSTALL_PREFIX "${CMAKE_INSTALL_PREFIX}")

# Set the install configuration name.
if(NOT DEFINED CMAKE_INSTALL_CONFIG_NAME)
  if(BUILD_TYPE)
    string(REGEX REPLACE "^[^A-Za-z0-9_]+" ""
           CMAKE_INSTALL_CONFIG_NAME "${BUILD_TYPE}")
  else()
    set(CMAKE_INSTALL_CONFIG_NAME "Release")
  endif()
  message(STATUS "Install configuration: \"${CMAKE_INSTALL_CONFIG_NAME}\"")
endif()

# Set the component getting installed.
if(NOT CMAKE_INSTALL_COMPONENT)
  if(COMPONENT)
    message(STATUS "Install component: \"${COMPONENT}\"")
    set(CMAKE_INSTALL_COMPONENT "${COMPONENT}")
  else()
    set(CMAKE_INSTALL_COMPONENT)
  endif()
endif()

# Install shared libraries without execute permission?
if(NOT DEFINED CMAKE_INSTALL_SO_NO_EXE)
  set(CMAKE_INSTALL_SO_NO_EXE "0")
endif()

# Is this installation the result of a crosscompile?
if(NOT DEFINED CMAKE_CROSSCOMPILING)
  set(CMAKE_CROSSCOMPILING "TRUE")
endif()

# Set path to fallback-tool for dependency-resolution.
if(NOT DEFINED CMAKE_OBJDUMP)
  set(CMAKE_OBJDUMP "C:/Users/ASUS/AppData/Local/Android/Sdk/ndk/28.2.13676358/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-objdump.exe")
endif()

if(NOT CMAKE_INSTALL_LOCAL_ONLY)
  # Include the install script for the subdirectory.
  include("C:/Users/ASUS/Desktop/PocketForgeAI/native/build-arm64/build-llama/ggml/src/cmake_install.cmake")
endif()

if(CMAKE_INSTALL_COMPONENT STREQUAL "Unspecified" OR NOT CMAKE_INSTALL_COMPONENT)
  if(EXISTS "$ENV{DESTDIR}${CMAKE_INSTALL_PREFIX}/lib/libggml.so" AND
     NOT IS_SYMLINK "$ENV{DESTDIR}${CMAKE_INSTALL_PREFIX}/lib/libggml.so")
    file(RPATH_CHECK
         FILE "$ENV{DESTDIR}${CMAKE_INSTALL_PREFIX}/lib/libggml.so"
         RPATH "")
  endif()
  file(INSTALL DESTINATION "${CMAKE_INSTALL_PREFIX}/lib" TYPE SHARED_LIBRARY FILES "C:/Users/ASUS/Desktop/PocketForgeAI/native/build-arm64/bin/libggml.so")
  if(EXISTS "$ENV{DESTDIR}${CMAKE_INSTALL_PREFIX}/lib/libggml.so" AND
     NOT IS_SYMLINK "$ENV{DESTDIR}${CMAKE_INSTALL_PREFIX}/lib/libggml.so")
    if(CMAKE_INSTALL_DO_STRIP)
      execute_process(COMMAND "C:/Users/ASUS/AppData/Local/Android/Sdk/ndk/28.2.13676358/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-strip.exe" "$ENV{DESTDIR}${CMAKE_INSTALL_PREFIX}/lib/libggml.so")
    endif()
  endif()
endif()

if(CMAKE_INSTALL_COMPONENT STREQUAL "Unspecified" OR NOT CMAKE_INSTALL_COMPONENT)
endif()

if(CMAKE_INSTALL_COMPONENT STREQUAL "Unspecified" OR NOT CMAKE_INSTALL_COMPONENT)
  file(INSTALL DESTINATION "${CMAKE_INSTALL_PREFIX}/include" TYPE FILE FILES
    "C:/Users/ASUS/Desktop/PocketForgeAI/third_party/llama.cpp/ggml/include/ggml.h"
    "C:/Users/ASUS/Desktop/PocketForgeAI/third_party/llama.cpp/ggml/include/ggml-cpu.h"
    "C:/Users/ASUS/Desktop/PocketForgeAI/third_party/llama.cpp/ggml/include/ggml-alloc.h"
    "C:/Users/ASUS/Desktop/PocketForgeAI/third_party/llama.cpp/ggml/include/ggml-backend.h"
    "C:/Users/ASUS/Desktop/PocketForgeAI/third_party/llama.cpp/ggml/include/ggml-blas.h"
    "C:/Users/ASUS/Desktop/PocketForgeAI/third_party/llama.cpp/ggml/include/ggml-cann.h"
    "C:/Users/ASUS/Desktop/PocketForgeAI/third_party/llama.cpp/ggml/include/ggml-cpp.h"
    "C:/Users/ASUS/Desktop/PocketForgeAI/third_party/llama.cpp/ggml/include/ggml-cuda.h"
    "C:/Users/ASUS/Desktop/PocketForgeAI/third_party/llama.cpp/ggml/include/ggml-opt.h"
    "C:/Users/ASUS/Desktop/PocketForgeAI/third_party/llama.cpp/ggml/include/ggml-metal.h"
    "C:/Users/ASUS/Desktop/PocketForgeAI/third_party/llama.cpp/ggml/include/ggml-rpc.h"
    "C:/Users/ASUS/Desktop/PocketForgeAI/third_party/llama.cpp/ggml/include/ggml-virtgpu.h"
    "C:/Users/ASUS/Desktop/PocketForgeAI/third_party/llama.cpp/ggml/include/ggml-sycl.h"
    "C:/Users/ASUS/Desktop/PocketForgeAI/third_party/llama.cpp/ggml/include/ggml-vulkan.h"
    "C:/Users/ASUS/Desktop/PocketForgeAI/third_party/llama.cpp/ggml/include/ggml-webgpu.h"
    "C:/Users/ASUS/Desktop/PocketForgeAI/third_party/llama.cpp/ggml/include/ggml-zendnn.h"
    "C:/Users/ASUS/Desktop/PocketForgeAI/third_party/llama.cpp/ggml/include/ggml-openvino.h"
    "C:/Users/ASUS/Desktop/PocketForgeAI/third_party/llama.cpp/ggml/include/gguf.h"
    )
endif()

if(CMAKE_INSTALL_COMPONENT STREQUAL "Unspecified" OR NOT CMAKE_INSTALL_COMPONENT)
  if(EXISTS "$ENV{DESTDIR}${CMAKE_INSTALL_PREFIX}/lib/libggml-base.so" AND
     NOT IS_SYMLINK "$ENV{DESTDIR}${CMAKE_INSTALL_PREFIX}/lib/libggml-base.so")
    file(RPATH_CHECK
         FILE "$ENV{DESTDIR}${CMAKE_INSTALL_PREFIX}/lib/libggml-base.so"
         RPATH "")
  endif()
  file(INSTALL DESTINATION "${CMAKE_INSTALL_PREFIX}/lib" TYPE SHARED_LIBRARY FILES "C:/Users/ASUS/Desktop/PocketForgeAI/native/build-arm64/bin/libggml-base.so")
  if(EXISTS "$ENV{DESTDIR}${CMAKE_INSTALL_PREFIX}/lib/libggml-base.so" AND
     NOT IS_SYMLINK "$ENV{DESTDIR}${CMAKE_INSTALL_PREFIX}/lib/libggml-base.so")
    if(CMAKE_INSTALL_DO_STRIP)
      execute_process(COMMAND "C:/Users/ASUS/AppData/Local/Android/Sdk/ndk/28.2.13676358/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-strip.exe" "$ENV{DESTDIR}${CMAKE_INSTALL_PREFIX}/lib/libggml-base.so")
    endif()
  endif()
endif()

if(CMAKE_INSTALL_COMPONENT STREQUAL "Unspecified" OR NOT CMAKE_INSTALL_COMPONENT)
endif()

if(CMAKE_INSTALL_COMPONENT STREQUAL "Unspecified" OR NOT CMAKE_INSTALL_COMPONENT)
  file(INSTALL DESTINATION "${CMAKE_INSTALL_PREFIX}/lib/cmake/ggml" TYPE FILE FILES
    "C:/Users/ASUS/Desktop/PocketForgeAI/native/build-arm64/build-llama/ggml/ggml-config.cmake"
    "C:/Users/ASUS/Desktop/PocketForgeAI/native/build-arm64/build-llama/ggml/ggml-config-version.cmake"
    )
endif()

string(REPLACE ";" "\n" CMAKE_INSTALL_MANIFEST_CONTENT
       "${CMAKE_INSTALL_MANIFEST_FILES}")
if(CMAKE_INSTALL_LOCAL_ONLY)
  file(WRITE "C:/Users/ASUS/Desktop/PocketForgeAI/native/build-arm64/build-llama/ggml/install_local_manifest.txt"
     "${CMAKE_INSTALL_MANIFEST_CONTENT}")
endif()
