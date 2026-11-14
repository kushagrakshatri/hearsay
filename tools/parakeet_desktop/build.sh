#!/bin/sh
# Builds the desktop twin against a desktop ONNX Runtime (ideally 1.28.2, the version the sherpa-onnx 2.0.1 AAR bundles).
# usage: ORT_DIR=/path/to/onnxruntime-linux-<arch>-1.28.2 sh build.sh [out]
set -e
H=$(cd "$(dirname "$0")" && pwd); R="$H/../.."; OUT=${1:-$H/parakeet_desktop}
KNF="$R/third_party/kaldi-native-fbank/kaldi-native-fbank/csrc"
KISS="$R/third_party/kissfft"
SRCS="$H/main.cc $R/app/src/main/cpp/parakeet_core.cc"
for f in feature-fbank feature-functions feature-window kaldi-math mel-computations online-feature rfft \
         feature-mfcc feature-raw-audio-samples istft stft whisper-feature; do SRCS="$SRCS $KNF/$f.cc"; done
gcc -O3 -c -Dkiss_fft_scalar=float -I"$KISS" "$KISS/kiss_fft.c" -o /tmp/pk_kiss_fft.o
gcc -O3 -c -Dkiss_fft_scalar=float -I"$KISS" "$KISS/kiss_fftr.c" -o /tmp/pk_kiss_fftr.o
g++ -O3 -std=c++17 -Dkiss_fft_scalar=float -I"$R/app/src/main/cpp" -I"$R/third_party/kaldi-native-fbank" -I"$KISS" \
  -I"$R/third_party/onnxruntime-headers/include" $SRCS /tmp/pk_kiss_fft.o /tmp/pk_kiss_fftr.o \
  -L"$ORT_DIR/lib" -lonnxruntime -Wl,-rpath,"$ORT_DIR/lib" -o "$OUT"
echo "built $OUT"
