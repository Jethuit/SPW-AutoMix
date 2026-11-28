#!/usr/bin/env bash
set -euo pipefail

# Run from the pinned FFmpeg source tree in an MSYS2 shell with LLVM-MinGW, GNU Make and NASM.
# Keep the first audio stream, local file I/O, exact cue seeking and float PCM on stdout.
./configure \
  --target-os=mingw32 --arch=x86_64 --enable-cross-compile \
  --cc=x86_64-w64-mingw32-clang --host-cc=x86_64-w64-mingw32-clang \
  --ar=llvm-ar --nm=llvm-nm --ranlib=llvm-ranlib --strip=llvm-strip \
  --windres=x86_64-w64-mingw32-windres --x86asmexe=nasm \
  --enable-static --disable-shared --enable-small \
  --disable-autodetect --disable-network --disable-devices --disable-avdevice \
  --disable-doc --disable-ffplay --disable-ffprobe --disable-swscale \
  --disable-gpl --disable-nonfree --disable-librubberband --disable-everything \
  --enable-ffmpeg --enable-swresample --enable-filter=aresample \
  --enable-protocol=file,pipe --enable-muxer=pcm_f32le --enable-encoder=pcm_f32le \
  --enable-demuxer=aac,ac3,eac3,dts,aiff,ape,asf,caf,dsf,iff,flac,matroska,mov,mp3,ogg,wav,w64,wv,tta,tak,avi,mpc,mpc8,mpegps,mpegts \
  --enable-decoder='aac,aac_latm,ac3,eac3,alac,ape,dca,dsd_*,dst,flac,mp1*,mp2*,mp3*,mpc7,mpc8,opus,pcm_*,adpcm_*,tta,tak,vorbis,wavpack,wma*,mjpeg,png' \
  --enable-parser=aac,aac_latm,ac3,dca,flac,mpegaudio,opus,tak,vorbis \
  --extra-cflags=-ffunction-sections --extra-ldflags=-Wl,--gc-sections

make -r -j "${AUTOMIX_FFMPEG_JOBS:-8}" ffmpeg.exe
