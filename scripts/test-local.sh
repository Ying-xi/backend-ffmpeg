#!/usr/bin/env bash
set -euo pipefail
BASE_URL="${BASE_URL:-http://127.0.0.1:8080}"

echo "[1/4] health"
curl -sS "$BASE_URL/api/video/health" | python3 -m json.tool || true

echo

echo "[2/4] repair: 15fps -> 30fps"
curl -sS -X POST "$BASE_URL/api/video/process" \
  -H 'Content-Type: application/json' \
  -d '{
    "input":"补帧 30fps",
    "videoUrl":"'"$BASE_URL"'/api/video/test-videos/test_repair_input_15fps_360p_5s.mp4",
    "inputName":"test_repair_input_15fps_360p_5s.mp4",
    "targetFps":30,
    "maxSeconds":3,
    "publicBaseUrl":"'"$BASE_URL"'"
  }' | tee /tmp/repair_response.json | python3 -m json.tool || true

echo

echo "[3/4] interpolate: 24fps -> 60fps"
curl -sS -X POST "$BASE_URL/api/video/process" \
  -H 'Content-Type: application/json' \
  -d '{
    "input":"插帧 60fps",
    "videoUrl":"'"$BASE_URL"'/api/video/test-videos/test_insert_input_24fps_360p_5s.mp4",
    "inputName":"test_insert_input_24fps_360p_5s.mp4",
    "targetFps":60,
    "maxSeconds":3,
    "publicBaseUrl":"'"$BASE_URL"'"
  }' | tee /tmp/interpolate_response.json | python3 -m json.tool || true

echo

echo "[4/4] output URLs"
echo "repair outputUrl:" && python3 - <<'PY'
import json
try:
    print(json.load(open('/tmp/repair_response.json')).get('outputUrl'))
except Exception as e:
    print(e)
PY
echo "interpolate outputUrl:" && python3 - <<'PY'
import json
try:
    print(json.load(open('/tmp/interpolate_response.json')).get('outputUrl'))
except Exception as e:
    print(e)
PY
