# Install script for directory: C:/Users/ASUS/AppData/Local/Temp/opencode/mnn-clone

# Set the install prefix
if(NOT DEFINED CMAKE_INSTALL_PREFIX)
  set(CMAKE_INSTALL_PREFIX "C:/Program Files (x86)/MNN")
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

if(CMAKE_INSTALL_COMPONENT STREQUAL "Unspecified" OR NOT CMAKE_INSTALL_COMPONENT)
  file(INSTALL DESTINATION "${CMAKE_INSTALL_PREFIX}/include" TYPE DIRECTORY FILES "C:/Users/ASUS/AppData/Local/Temp/opencode/mnn-clone/transformers/llm/engine/include/" FILES_MATCHING REGEX "/[^/]*\\.hpp$")
endif()

if(CMAKE_INSTALL_COMPONENT STREQUAL "Unspecified" OR NOT CMAKE_INSTALL_COMPONENT)
  file(INSTALL DESTINATION "${CMAKE_INSTALL_PREFIX}/include/MNN" TYPE FILE FILES
    "C:/Users/ASUS/AppData/Local/Temp/opencode/mnn-clone/include/MNN/MNNDefine.h"
    "C:/Users/ASUS/AppData/Local/Temp/opencode/mnn-clone/include/MNN/Interpreter.hpp"
    "C:/Users/ASUS/AppData/Local/Temp/opencode/mnn-clone/include/MNN/HalideRuntime.h"
    "C:/Users/ASUS/AppData/Local/Temp/opencode/mnn-clone/include/MNN/Tensor.hpp"
    "C:/Users/ASUS/AppData/Local/Temp/opencode/mnn-clone/include/MNN/ErrorCode.hpp"
    "C:/Users/ASUS/AppData/Local/Temp/opencode/mnn-clone/include/MNN/ImageProcess.hpp"
    "C:/Users/ASUS/AppData/Local/Temp/opencode/mnn-clone/include/MNN/Matrix.h"
    "C:/Users/ASUS/AppData/Local/Temp/opencode/mnn-clone/include/MNN/Rect.h"
    "C:/Users/ASUS/AppData/Local/Temp/opencode/mnn-clone/include/MNN/MNNForwardType.h"
    "C:/Users/ASUS/AppData/Local/Temp/opencode/mnn-clone/include/MNN/AutoTime.hpp"
    "C:/Users/ASUS/AppData/Local/Temp/opencode/mnn-clone/include/MNN/MNNSharedContext.h"
    )
endif()

if(CMAKE_INSTALL_COMPONENT STREQUAL "Unspecified" OR NOT CMAKE_INSTALL_COMPONENT)
  file(INSTALL DESTINATION "${CMAKE_INSTALL_PREFIX}/include/MNN/expr" TYPE FILE FILES
    "C:/Users/ASUS/AppData/Local/Temp/opencode/mnn-clone/include/MNN/expr/Expr.hpp"
    "C:/Users/ASUS/AppData/Local/Temp/opencode/mnn-clone/include/MNN/expr/ExprCreator.hpp"
    "C:/Users/ASUS/AppData/Local/Temp/opencode/mnn-clone/include/MNN/expr/MathOp.hpp"
    "C:/Users/ASUS/AppData/Local/Temp/opencode/mnn-clone/include/MNN/expr/NeuralNetWorkOp.hpp"
    "C:/Users/ASUS/AppData/Local/Temp/opencode/mnn-clone/include/MNN/expr/Optimizer.hpp"
    "C:/Users/ASUS/AppData/Local/Temp/opencode/mnn-clone/include/MNN/expr/Executor.hpp"
    "C:/Users/ASUS/AppData/Local/Temp/opencode/mnn-clone/include/MNN/expr/Module.hpp"
    "C:/Users/ASUS/AppData/Local/Temp/opencode/mnn-clone/include/MNN/expr/NeuralNetWorkOp.hpp"
    "C:/Users/ASUS/AppData/Local/Temp/opencode/mnn-clone/include/MNN/expr/ExecutorScope.hpp"
    "C:/Users/ASUS/AppData/Local/Temp/opencode/mnn-clone/include/MNN/expr/Scope.hpp"
    )
endif()

if(CMAKE_INSTALL_COMPONENT STREQUAL "Unspecified" OR NOT CMAKE_INSTALL_COMPONENT)
  if(EXISTS "$ENV{DESTDIR}${CMAKE_INSTALL_PREFIX}/lib/libMNN.so" AND
     NOT IS_SYMLINK "$ENV{DESTDIR}${CMAKE_INSTALL_PREFIX}/lib/libMNN.so")
    file(RPATH_CHECK
         FILE "$ENV{DESTDIR}${CMAKE_INSTALL_PREFIX}/lib/libMNN.so"
         RPATH "")
  endif()
  file(INSTALL DESTINATION "${CMAKE_INSTALL_PREFIX}/lib" TYPE SHARED_LIBRARY FILES "C:/Users/ASUS/Desktop/PocketForgeAI/research/mnn/build-android/libMNN.so")
  if(EXISTS "$ENV{DESTDIR}${CMAKE_INSTALL_PREFIX}/lib/libMNN.so" AND
     NOT IS_SYMLINK "$ENV{DESTDIR}${CMAKE_INSTALL_PREFIX}/lib/libMNN.so")
    if(CMAKE_INSTALL_DO_STRIP)
      execute_process(COMMAND "C:/Users/ASUS/AppData/Local/Android/Sdk/ndk/28.2.13676358/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-strip.exe" "$ENV{DESTDIR}${CMAKE_INSTALL_PREFIX}/lib/libMNN.so")
    endif()
  endif()
endif()

if(CMAKE_INSTALL_COMPONENT STREQUAL "Unspecified" OR NOT CMAKE_INSTALL_COMPONENT)
endif()

if(NOT CMAKE_INSTALL_LOCAL_ONLY)
  # Include the install script for each subdirectory.
  include("C:/Users/ASUS/Desktop/PocketForgeAI/research/mnn/build-android/express/cmake_install.cmake")
  include("C:/Users/ASUS/Desktop/PocketForgeAI/research/mnn/build-android/tools/train/cmake_install.cmake")
  include("C:/Users/ASUS/Desktop/PocketForgeAI/research/mnn/build-android/tools/audio/cmake_install.cmake")
  include("C:/Users/ASUS/Desktop/PocketForgeAI/research/mnn/build-android/tools/converter/cmake_install.cmake")

endif()

string(REPLACE ";" "\n" CMAKE_INSTALL_MANIFEST_CONTENT
       "${CMAKE_INSTALL_MANIFEST_FILES}")
if(CMAKE_INSTALL_LOCAL_ONLY)
  file(WRITE "C:/Users/ASUS/Desktop/PocketForgeAI/research/mnn/build-android/install_local_manifest.txt"
     "${CMAKE_INSTALL_MANIFEST_CONTENT}")
endif()
if(CMAKE_INSTALL_COMPONENT)
  if(CMAKE_INSTALL_COMPONENT MATCHES "^[a-zA-Z0-9_.+-]+$")
    set(CMAKE_INSTALL_MANIFEST "install_manifest_${CMAKE_INSTALL_COMPONENT}.txt")
  else()
    string(MD5 CMAKE_INST_COMP_HASH "${CMAKE_INSTALL_COMPONENT}")
    set(CMAKE_INSTALL_MANIFEST "install_manifest_${CMAKE_INST_COMP_HASH}.txt")
    unset(CMAKE_INST_COMP_HASH)
  endif()
else()
  set(CMAKE_INSTALL_MANIFEST "install_manifest.txt")
endif()

if(NOT CMAKE_INSTALL_LOCAL_ONLY)
  file(WRITE "C:/Users/ASUS/Desktop/PocketForgeAI/research/mnn/build-android/${CMAKE_INSTALL_MANIFEST}"
     "${CMAKE_INSTALL_MANIFEST_CONTENT}")
endif()
