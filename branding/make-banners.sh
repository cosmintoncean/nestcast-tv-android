#!/usr/bin/env bash
# Regenerates the Android TV banner (320x180, packaged in the app) and the store banner (1280x720) from the icon.
# Run it again after changing the name or tagline below. Needs ffmpeg on the PATH and the Segoe UI fonts (Windows).
set -euo pipefail
cd "$(dirname "$0")/.."
NAME="CastBox"; TAGLINE="Your PC on your TV"
BOLD="C\:/Windows/Fonts/segoeuib.ttf"; REG="C\:/Windows/Fonts/segoeui.ttf"
# The icon's own background colour, so the banner and the icon blend.
BG=$(ffmpeg -loglevel error -i branding/castbox-icon-original.png -vf "crop=1:1:6:6" -f rawvideo -pix_fmt rgb24 - | od -An -tx1 | tr -d ' \n')
make() {
  local W=$1 H=$2 OUT=$3
  local TX=$((H + H * 5 / 100)) T1=$((H * 17 / 100)) T2=$((H * 65 / 1000)) Y1=$((H * 37 / 100)) Y2=$((H * 60 / 100))
  ffmpeg -y -loglevel error -f lavfi -i "color=c=0x${BG}:s=${W}x${H}" -i branding/castbox-icon-original.png -filter_complex \
    "[1:v]scale=${H}:${H}[ic];[0:v][ic]overlay=0:0[b];[b]drawtext=fontfile='${BOLD}':text='${NAME}':fontcolor=0xF5F1E6:fontsize=${T1}:x=${TX}:y=${Y1},drawtext=fontfile='${REG}':text='${TAGLINE}':fontcolor=0xFFB139:fontsize=${T2}:x=${TX}:y=${Y2}" \
    -frames:v 1 "$OUT"
}
make 320 180 app/src/main/res/drawable-xhdpi/banner.png
make 1280 720 branding/store/tv-banner-1280x720.png
