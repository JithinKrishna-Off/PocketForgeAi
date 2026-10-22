# Distributed under the OSI-approved BSD 3-Clause License.  See accompanying
# file Copyright.txt or https://cmake.org/licensing for details.

cmake_minimum_required(VERSION ${CMAKE_VERSION}) # this file comes with cmake

# If CMAKE_DISABLE_SOURCE_CHANGES is set to true and the source directory is an
# existing directory in our source tree, calling file(MAKE_DIRECTORY) on it
# would cause a fatal error, even though it would be a no-op.
if(NOT EXISTS "C:/Users/ASUS/Desktop/PocketForgeAI/native/build-arm64/_deps/vulkan-headers-src")
  file(MAKE_DIRECTORY "C:/Users/ASUS/Desktop/PocketForgeAI/native/build-arm64/_deps/vulkan-headers-src")
endif()
file(MAKE_DIRECTORY
  "C:/Users/ASUS/Desktop/PocketForgeAI/native/build-arm64/_deps/vulkan-headers-build"
  "C:/Users/ASUS/Desktop/PocketForgeAI/native/build-arm64/_deps/vulkan-headers-subbuild/vulkan-headers-populate-prefix"
  "C:/Users/ASUS/Desktop/PocketForgeAI/native/build-arm64/_deps/vulkan-headers-subbuild/vulkan-headers-populate-prefix/tmp"
  "C:/Users/ASUS/Desktop/PocketForgeAI/native/build-arm64/_deps/vulkan-headers-subbuild/vulkan-headers-populate-prefix/src/vulkan-headers-populate-stamp"
  "C:/Users/ASUS/Desktop/PocketForgeAI/native/build-arm64/_deps/vulkan-headers-subbuild/vulkan-headers-populate-prefix/src"
  "C:/Users/ASUS/Desktop/PocketForgeAI/native/build-arm64/_deps/vulkan-headers-subbuild/vulkan-headers-populate-prefix/src/vulkan-headers-populate-stamp"
)

set(configSubDirs )
foreach(subDir IN LISTS configSubDirs)
    file(MAKE_DIRECTORY "C:/Users/ASUS/Desktop/PocketForgeAI/native/build-arm64/_deps/vulkan-headers-subbuild/vulkan-headers-populate-prefix/src/vulkan-headers-populate-stamp/${subDir}")
endforeach()
if(cfgdir)
  file(MAKE_DIRECTORY "C:/Users/ASUS/Desktop/PocketForgeAI/native/build-arm64/_deps/vulkan-headers-subbuild/vulkan-headers-populate-prefix/src/vulkan-headers-populate-stamp${cfgdir}") # cfgdir has leading slash
endif()
