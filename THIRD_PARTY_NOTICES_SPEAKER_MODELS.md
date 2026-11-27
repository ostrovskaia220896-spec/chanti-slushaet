# Third-party notices — offline speaker diarization

The following optional models are downloaded on demand to the user's device. They are not bundled into the APK. Model downloads are pinned to immutable revisions or release assets and verified by SHA-256 before use.

## Speaker segmentation
- Model: sherpa-onnx converted pyannote segmentation 3.0, int8 ONNX.
- Source revision: https://huggingface.co/csukuangfj/sherpa-onnx-pyannote-segmentation-3-0/tree/340b52f1f5cd12d45a30fa284691417eaad2ff92
- Original model: https://huggingface.co/pyannote/segmentation-3.0
- License: MIT, copyright (c) 2023 CNRS. The original model's license requires retaining the copyright and permission notice.
- Pinned SHA-256: `10a438c2e0d90ed5f5da545cec2244d887315f6dbbbf1d3d564d00745b01952e`.

## Speaker embedding
- Model: 3D-Speaker CAM++ trained on VoxCeleb (English).
- Source asset: https://github.com/k2-fsa/sherpa-onnx/releases/tag/speaker-recongition-models
- Upstream project: https://github.com/modelscope/3D-Speaker
- License: Apache License 2.0 (3D-Speaker project).
- Pinned SHA-256: `98daf9bcf1c8abf3db374fec4c27a5eda7c58705bb3f180d057b085a72a88bb5`.
- The model was trained on VoxCeleb English data; accuracy on Russian conversations is not established and must be measured on Russian recordings before release.

## Runtime
- sherpa-onnx Android AAR v2.0.3.
- Source: https://github.com/k2-fsa/sherpa-onnx/releases/tag/v2.0.3
- License: Apache License 2.0.
- AAR SHA-256: `4c766e2e74c9d33d595338c21fe3b305c73ba45d47e96a235675c33142a2ec50`.
