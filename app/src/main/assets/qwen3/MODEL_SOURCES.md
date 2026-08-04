# Manuscript Qwen3 model sources

Model weights are not bundled with the app. Manuscript downloads only the tier
selected for the device after the user approves the exact download.

| Tier | Repository | Revision | File |
|---|---|---|---|
| Compact | `Qwen/Qwen3-0.6B-GGUF` | `23749fefcc72300e3a2ad315e1317431b06b590a` | `Qwen3-0.6B-Q8_0.gguf` |
| Balanced | `bartowski/Qwen_Qwen3-1.7B-GGUF` | `822702a8257cf1b05a27d73f775d8606cf2e33a4` | `Qwen_Qwen3-1.7B-Q4_K_M.gguf` |
| Maximum | `Qwen/Qwen3-4B-GGUF` | `bc640142c66e1fdd12af0bd68f40445458f3869b` | `Qwen3-4B-Q4_K_M.gguf` |

Qwen3 is distributed under the Apache-2.0 license. The balanced artifact is a
pinned GGUF quantization of `Qwen/Qwen3-1.7B`. Every download is checked against
the SHA-256 value compiled into Manuscript and must pass local inference before
activation.
