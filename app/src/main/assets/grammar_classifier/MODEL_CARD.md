# Manuscript grammar routing classifier

- Source: `pszemraj/electra-small-discriminator-CoLA`
- Revision: `021bfb519b178ee4a5ebfa7a784e961498703456`
- Base model: `google/electra-small-discriminator`
- Dataset: GLUE CoLA
- License: Apache-2.0
- Parameters: 13.5 million
- Export: ONNX opset 17 with ONNX Runtime O2 optimization
- Quantization: dynamic signed INT8 weights for MatMul and Gemm operators

The classifier runs only after the user requests a Manuscript correction. It routes
text between deterministic corrections and Qwen3. It does not generate text and it
does not transmit text or embeddings.

The classifier is one signal. Deterministic grammar checks and protected-text
validation remain active because a statistical classifier can be wrong.
