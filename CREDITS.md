# Credits

Orson is built on these projects. Models are downloaded by `scripts/fetch-models.sh`, not stored in this repo, except the Orson wake word.

| Part | Used for | Licence |
| --- | --- | --- |
| [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) | Speech engine | Apache-2.0 |
| [ONNX Runtime](https://github.com/microsoft/onnxruntime) | Running the wake word model | MIT |
| [NVIDIA Parakeet TDT 0.6B v2](https://huggingface.co/nvidia/parakeet-tdt-0.6b-v2) | Speech to text | CC-BY-4.0 |
| [Whisper base.en](https://github.com/openai/whisper) | Fallback speech to text | MIT |
| Streaming Zipformer (icefall) | Hearing when you stop talking | Apache-2.0 |
| Zipformer GigaSpeech keyword spotter (icefall) | Wake words other than Orson | Apache-2.0 |
| [Piper](https://github.com/rhasspy/piper), voice en_US-lessac-medium | Orson's voice | Piper: MIT. Check the voice's model card for its dataset terms. |
| [eSpeak NG](https://github.com/espeak-ng/espeak-ng) data | Pronunciation for Piper | GPL-3.0 |
| [livekit-wakeword](https://github.com/livekit/livekit-wakeword) | Training the "Orson" wake word (`app/src/main/assets/wake/orson.onnx`) | Apache-2.0 |
| [ZXing](https://github.com/zxing/zxing) | Scanning the key QR code | Apache-2.0 |

Parakeet requires attribution: speech recognition by NVIDIA Parakeet TDT 0.6B v2, CC-BY-4.0.
