#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p outputs tmp-inputs
find src -name "*.java" > sources.txt
javac -encoding UTF-8 -d out @sources.txt
jar --create --file app.jar --main-class com.example.videoframe.VideoFrameHttpService -C out .
PORT="${PORT:-8080}" \
FFMPEG_PATH="${FFMPEG_PATH:-ffmpeg}" \
OUTPUT_DIR="${OUTPUT_DIR:-./outputs}" \
INPUT_TMP_DIR="${INPUT_TMP_DIR:-./tmp-inputs}" \
TEST_VIDEO_DIR="${TEST_VIDEO_DIR:-./test-videos}" \
DEFAULT_MAX_SECONDS="${DEFAULT_MAX_SECONDS:-3}" \
PUBLIC_BASE_URL="${PUBLIC_BASE_URL:-}" \
java -jar app.jar
