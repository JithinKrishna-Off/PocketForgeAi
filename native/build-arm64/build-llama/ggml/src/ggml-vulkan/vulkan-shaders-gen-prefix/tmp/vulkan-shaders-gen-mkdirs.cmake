# Distributed under the OSI-approved BSD 3-Clause License.  See accompanying
# file Copyright.txt or https://cmake.org/licensing for details.

cmake_minimum_required(VERSION ${CMAKE_VERSION}) # this file comes with cmake

# If CMAKE_DISABLE_SOURCE_CHANGES is set to true and the source directory is an
# existing directory in our source tree, calling file(MAKE_DIRECTORY) on it
# would cause a fatal error, even though it would be a no-op.
if(NOT EXISTS "C:/Users/ASUS/Desktop/PocketForgeAI/third_party/llama.cpp/ggml/src/ggml-vulkan/vulkan-shaders")
  file(MAKE_DIRECTORY "C:/Users/ASUS/Desktop/PocketForgeAI/third_party/llama.cpp/ggml/src/ggml-vulkan/vulkan-shaders")
endif()
file(MAKE_DIRECTORY
  "C:/Users/ASUS/Desktop/PocketForgeAI/native/build-arm64/build-llama/ggml/src/ggml-vulkan/vulkan-shaders-gen-prefix/src/vulkan-shaders-gen-build"
  "C:/Users/ASUS/Desktop/PocketForgeAI/native/build-arm64/build-llama/ggml/src/ggml-vulkan/vulkan-shaders-gen-prefix"
  "C:/Users/ASUS/Desktop/PocketForgeAI/native/build-arm64/build-llama/ggml/src/ggml-vulkan/vulkan-shaders-gen-prefix/tmp"
  "C:/Users/ASUS/Desktop/PocketForgeAI/native/build-arm64/build-llama/ggml/src/ggml-vulkan/vulkan-shaders-gen-prefix/src/vulkan-shaders-gen-stamp"
  "C:/Users/ASUS/Desktop/PocketForgeAI/native/build-arm64/build-llama/ggml/src/ggml-vulkan/vulkan-shaders-gen-prefix/src"
  "C:/Users/ASUS/Desktop/PocketForgeAI/native/build-arm64/build-llama/ggml/src/ggml-vulkan/vulkan-shaders-gen-prefix/src/vulkan-shaders-gen-stamp"
)

set(configSubDirs )
foreach(subDir IN LISTS configSubDirs)
    file(MAKE_DIRECTORY "C:/Users/ASUS/Desktop/PocketForgeAI/native/build-arm64/build-llama/ggml/src/ggml-vulkan/vulkan-shaders-gen-prefix/src/vulkan-shaders-gen-stamp/${subDir}")
endforeach()
if(cfgdir)
  file(MAKE_DIRECTORY "C:/Users/ASUS/Desktop/PocketForgeAI/native/build-arm64/build-llama/ggml/src/ggml-vulkan/vulkan-shaders-gen-prefix/src/vulkan-shaders-gen-stamp${cfgdir}") # cfgdir has leading slash
endif()
