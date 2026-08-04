# Minimal hand-written config for the vendored header-only SPIRV-Headers
# (KhronosGroup/SPIRV-Headers @ 4015a33, CC-BY-4.0 spec headers / MIT tooling).
# Exists so ggml-vulkan's find_package(SPIRV-Headers CONFIG) resolves without
# a host Vulkan SDK install.
get_filename_component(_spirv_headers_prefix "${CMAKE_CURRENT_LIST_DIR}/../../.." ABSOLUTE)
if(NOT TARGET SPIRV-Headers::SPIRV-Headers)
    add_library(SPIRV-Headers::SPIRV-Headers INTERFACE IMPORTED)
    set_target_properties(SPIRV-Headers::SPIRV-Headers PROPERTIES
        INTERFACE_INCLUDE_DIRECTORIES "${_spirv_headers_prefix}/include")
endif()
set(SPIRV-Headers_FOUND TRUE)
set(SPIRV_HEADERS_INCLUDE_DIR "${_spirv_headers_prefix}/include")
