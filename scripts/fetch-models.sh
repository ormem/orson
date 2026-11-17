#!/bin/sh
# Downloads the speech library and models, too large for git, into the app.
set -e
cd "$(dirname "$0")/.."
R=https://github.com/k2-fsa/sherpa-onnx/releases/download
A=app/src/main/assets
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT

mkdir -p app/libs "$A/kws" "$A/asr" "$A/tts" "$A/whisper"
curl -fL -o app/libs/sherpa-onnx-static-link-onnxruntime-1.13.8.aar "$R/v1.13.8/sherpa-onnx-static-link-onnxruntime-1.13.8.aar"


get() { curl -fL "$R/$1" | tar xj -C "$T"; }

get kws-models/sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01.tar.bz2
K=$T/sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01
cp "$K/encoder-epoch-12-avg-2-chunk-16-left-64.onnx" "$A/kws/encoder.onnx"
cp "$K/decoder-epoch-12-avg-2-chunk-16-left-64.onnx" "$A/kws/decoder.onnx"
cp "$K/joiner-epoch-12-avg-2-chunk-16-left-64.onnx" "$A/kws/joiner.onnx"
cp "$K/tokens.txt" "$A/kws/tokens.txt"

get asr-models/sherpa-onnx-streaming-zipformer-en-2023-06-26-mobile.tar.bz2
S=$T/sherpa-onnx-streaming-zipformer-en-2023-06-26-mobile
cp "$S/encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx" "$A/asr/encoder.onnx"
cp "$S/decoder-epoch-99-avg-1-chunk-16-left-128.onnx" "$A/asr/decoder.onnx"
cp "$S/joiner-epoch-99-avg-1-chunk-16-left-128.int8.onnx" "$A/asr/joiner.onnx"
cp "$S/tokens.txt" "$A/asr/tokens.txt"

get asr-models/sherpa-onnx-whisper-base.en.tar.bz2
W=$T/sherpa-onnx-whisper-base.en
cp "$W/base.en-encoder.int8.onnx" "$A/whisper/encoder.onnx"
cp "$W/base.en-decoder.int8.onnx" "$A/whisper/decoder.onnx"
cp "$W/base.en-tokens.txt" "$A/whisper/tokens.txt"

get tts-models/vits-piper-en_US-lessac-medium.tar.bz2
V=$T/vits-piper-en_US-lessac-medium
cp "$V/en_US-lessac-medium.onnx" "$A/tts/model.onnx"
cp "$V/tokens.txt" "$A/tts/tokens.txt"
rm -rf "$A/tts/espeak-ng-data"
cp -R "$V/espeak-ng-data" "$A/tts/espeak-ng-data"

# Parakeet: the main speech model, copied to the phone at setup (too big for the app).
get asr-models/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8.tar.bz2
P=$T/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8
mkdir -p models/parakeet
cp "$P/encoder.int8.onnx" "$P/decoder.int8.onnx" "$P/joiner.int8.onnx" "$P/tokens.txt" models/parakeet/
