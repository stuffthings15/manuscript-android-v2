# Host toolchain for building ggml-vulkan's vulkan-shaders-gen on this
# Windows machine with MSVC. Referenced via GGML_VULKAN_SHADERS_GEN_TOOLCHAIN
# because the auto-detected clang host toolchain cannot find the Windows SDK
# libraries under the Android cross build.
set(CMAKE_SYSTEM_NAME Windows)
set(CMAKE_C_COMPILER "C:/Program Files/Microsoft Visual Studio/18/Insiders/VC/Tools/MSVC/14.51.36231/bin/Hostx64/x64/cl.exe")
set(CMAKE_CXX_COMPILER "C:/Program Files/Microsoft Visual Studio/18/Insiders/VC/Tools/MSVC/14.51.36231/bin/Hostx64/x64/cl.exe")
