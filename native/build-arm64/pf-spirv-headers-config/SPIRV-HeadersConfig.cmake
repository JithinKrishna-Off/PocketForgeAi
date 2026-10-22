
if(NOT TARGET SPIRV-Headers::SPIRV-Headers)
    add_library(SPIRV-Headers::SPIRV-Headers INTERFACE IMPORTED)
    set_target_properties(SPIRV-Headers::SPIRV-Headers PROPERTIES
        INTERFACE_INCLUDE_DIRECTORIES "C:/Users/ASUS/Desktop/PocketForgeAI/native/build-arm64/_deps/spirv-headers-src/include")
endif()
if(NOT TARGET SPIRV-Headers)
    add_library(SPIRV-Headers INTERFACE IMPORTED)
    set_target_properties(SPIRV-Headers PROPERTIES
        INTERFACE_INCLUDE_DIRECTORIES "C:/Users/ASUS/Desktop/PocketForgeAI/native/build-arm64/_deps/spirv-headers-src/include")
endif()
set(SPIRV_HEADERS_VERSION 1.3.283.0)
