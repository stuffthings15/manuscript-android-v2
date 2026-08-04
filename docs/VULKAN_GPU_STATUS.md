# Vulkan GPU backend status (Android)

## Where it stands

The llama.cpp Vulkan backend **builds, links, and detects the GPU**, but it is
**disabled by default** because the Adreno driver aborts the process.

Enable a build with it:

```powershell
.\gradlew.bat -Pmanuscript.vulkan=true :app:assembleDebug
```

## What works

- `libggml-vulkan.so` builds for `arm64-v8a` and `x86_64` and packages into the APK.
- On a Galaxy S23 (Adreno 740) llama.cpp selects it:
  `llama_prepare_model_devices: using device Vulkan0 (Adreno (TM) 740) - 7328 MiB free`

## The blocker

Backend initialisation dies inside the Qualcomm driver:

```
terminating due to uncaught exception of type vk::SystemError:
vk::Device::createComputePipeline: ErrorUnknown
```

This is a C++ `abort()` inside a native library. It cannot be caught in the
hosting process, so a device with this driver would hard-crash Manuscript on
first correction. Shipping it enabled is therefore not acceptable.

## How it is contained (implemented)

Two mechanisms, both required:

1. **Isolated probe process.** `VulkanProbeService` runs in `:vulkanprobe` and
   performs a trial model load plus one tiny generation. If the driver aborts,
   only that process dies; `VulkanProbe` observes the dead binding and records
   the device as CPU-only. A probe that starts and never reports is also
   treated as CPU-only, so a crashing driver is never retried on every launch.
   The result is remembered per model file.
2. **Device suppression in the hosting process.** llama.cpp auto-selects any
   Vulkan device it can see, so the probe result alone is too late - the main
   process would already have aborted. `GGML_VK_VISIBLE_DEVICES` is set to an
   empty list before native init unless a probe proved this device safe, which
   leaves the backend loaded but device-less.

Verified on the Galaxy S23 (Adreno 740): with `-Pmanuscript.vulkan=true` the
probe process aborts, `vulkan_usable=false` is recorded, and the app completes
the full-page correction normally instead of crashing. A device whose driver
survives the probe gets the GPU path automatically.

Still to do before enabling by default: a device whose probe passes has not yet
been measured, `minSdk` must rise to 28 for `vkGetPhysicalDeviceFeatures2`, and
the probe should also capture driver version for future allowlisting.

## Build prerequisites (already vendored, no host SDK install needed)

- `vendor/spirv-headers/` - KhronosGroup/SPIRV-Headers @ 4015a33, with a
  hand-written `SPIRV-HeadersConfig.cmake` so `find_package(... CONFIG)` resolves.
- `vendor/vulkan-headers/` - KhronosGroup/Vulkan-Headers v1.3.275, matching the
  NDK loader; supplies the C++ `vulkan.hpp` the NDK does not ship.
- `glslc` comes from the NDK `shader-tools`.
- The host shader generator needs MSVC, `rc.exe` on `PATH`, and the vcvars
  `INCLUDE`/`LIB` environment; `vendor/spirv-headers/msvc-host-toolchain.cmake`
  pins the compiler.
- `buildStagingDirectory "C:/m/ll"` keeps object paths under the Windows
  250-character limit that the nested shader-generator project otherwise breaks.
- `vkGetPhysicalDeviceFeatures2` requires `minSdk 28`; the shipped app stays at
  `minSdk 26`, so a Vulkan-enabled build must raise it.
