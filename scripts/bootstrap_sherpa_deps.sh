
#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

ASSETS="app/src/main/assets"
MODEL="$ASSETS/sherpa-onnx-zipformer-en-2023-04-01"
JNI="app/src/main/jniLibs/arm64-v8a"

mkdir -p "$ASSETS" "$MODEL" "$JNI"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

ZIP_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-zipformer-en-2023-04-01.tar.bz2"
VAD_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx"
JNI_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.12.23/sherpa-onnx-v1.12.23-android.tar.bz2"

echo "Descargando modelo de voz..."
if [ ! -s "$MODEL/encoder-epoch-99-avg-1.int8.onnx" ]; then
  curl -fL --retry 3 "$ZIP_URL" -o "$TMP/model.tar.bz2"
  mkdir "$TMP/model"
  tar -xjf "$TMP/model.tar.bz2" -C "$TMP/model"

  for f in encoder-epoch-99-avg-1.int8.onnx decoder-epoch-99-avg-1.int8.onnx joiner-epoch-99-avg-1.int8.onnx tokens.txt bpe.model; do
    src="$(find "$TMP/model" -type f -name "$f" -print -quit)"
    [ -n "$src" ] || { echo "Falta archivo del modelo: $f"; exit 1; }
    cp "$src" "$MODEL/$f"
  done
fi

echo "Descargando Silero VAD..."
if [ ! -s "$ASSETS/silero_vad.onnx" ]; then
  curl -fL --retry 3 "$VAD_URL" -o "$ASSETS/silero_vad.onnx"
fi

echo "Descargando bibliotecas Android..."
curl -fL --retry 3 "$JNI_URL" -o "$TMP/sherpa.tar.bz2"
mkdir "$TMP/jni"
tar -xjf "$TMP/sherpa.tar.bz2" -C "$TMP/jni"

# Only copy ARM64 binaries. A generic find by filename silently selected
# x86_64 libraries and packaged them as arm64-v8a, crashing Voice Engine.
for f in libonnxruntime.so libsherpa-onnx-jni.so libsherpa-onnx-c-api.so libsherpa-onnx-cxx-api.so; do
  src="$(find "$TMP/jni" -type f -path "*/jniLibs/arm64-v8a/$f" -print -quit)"
  [ -n "$src" ] || { echo "No se encontró la biblioteca ARM64: $f"; exit 1; }
  if ! readelf -h "$src" | grep -q 'Machine:.*AArch64'; then
    echo "ERROR: $src no es una biblioteca ARM64 (AArch64)"
    exit 1
  fi
  cp "$src" "$JNI/$f"
done

if [ ! -s "$MODEL/bpe.vocab" ]; then
  echo "ERROR: Falta bpe.vocab en el repositorio"
  exit 1
fi

echo "Dependencias verificadas correctamente:"
ls -lh "$ASSETS/silero_vad.onnx" "$MODEL"/* "$JNI"/*
