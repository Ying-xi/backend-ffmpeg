# hiagent-video-http-service

这是一个给 HiAgent、Railway、Postman 或其他 HTTP 客户端调用的视频插帧/补帧服务。服务使用 Java + FFmpeg 实现，处理完成后返回可下载的视频 URL。

当前公网服务地址：

```text
https://backend-ffmpeg-production.up.railway.app
```

---

## 1. 功能说明

当前只支持两类标准处理：

| 功能 | `mode` | 推荐 `targetFps` | FFmpeg 实现 | 适用场景 |
|---|---|---:|---|---|
| 补帧 | `repair` | 30 | `fps=targetFps,scale=640:-2` | 低帧率视频补齐到稳定帧率 |
| 插帧 | `interpolate` | 60 | `scale=480:-2,minterpolate=fps=targetFps:mi_mode=blend` | 提升视频流畅度 |

输出视频：

- MP4
- H.264
- 不保留音频
- 返回 `outputUrl`
- 返回输入帧率、目标帧率、输出帧率、输出文件名和输出大小

---

## 2. 参数限制

| 参数 | 当前限制 |
|---|---|
| `targetFps` | 限制在 `15-60` |
| `maxSeconds` | 限制在 `1-60` |
| 默认 `maxSeconds` | `5` |
| FFmpeg 执行超时 | `600` 秒 |
| 输出目录 | 容器内 `/app/outputs` |

演示和标书验证建议：

```text
maxSeconds=5
```

如果需要更长片段，可以传：

```text
maxSeconds=30
```

或：

```text
maxSeconds=60
```

但 30-60 秒处理时间会明显变长，稳定性取决于视频分辨率、码率、Railway 当前资源和并发情况。插帧比补帧更耗 CPU。

---

## 3. 接口列表

### 3.1 健康检查

```http
GET /api/video/health
```

示例：

```bash
curl https://backend-ffmpeg-production.up.railway.app/api/video/health
```

成功返回会包含：

```json
{
  "success": true,
  "ffmpegOk": true,
  "hasMinterpolate": true,
  "defaultMaxSeconds": 5,
  "maxSecondsLimit": 60,
  "ffmpegTimeoutSeconds": 600
}
```

### 3.2 通过视频 URL 处理

适用于调用方已经有视频下载 URL 的场景，例如 HiAgent 上传文件后提供了文件 URL。

```http
POST /api/video/process
Content-Type: application/json
```

补帧示例：

```json
{
  "mode": "repair",
  "videoUrl": "https://example.com/input.mp4",
  "inputName": "input.mp4",
  "targetFps": 30,
  "maxSeconds": 5,
  "publicBaseUrl": "https://backend-ffmpeg-production.up.railway.app"
}
```

插帧示例：

```json
{
  "mode": "interpolate",
  "videoUrl": "https://example.com/input.mp4",
  "inputName": "input.mp4",
  "targetFps": 60,
  "maxSeconds": 5,
  "publicBaseUrl": "https://backend-ffmpeg-production.up.railway.app"
}
```

也支持直接传 HiAgent 的 `files`：

```json
{
  "mode": "repair",
  "files": [
    {
      "name": "input.mp4",
      "url": "https://example.com/input.mp4"
    }
  ],
  "targetFps": 30,
  "maxSeconds": 5,
  "publicBaseUrl": "https://backend-ffmpeg-production.up.railway.app"
}
```

### 3.3 上传本地文件并处理

适用于 Postman、本地网页、前端页面等需要直接上传本地视频文件的场景。

```http
POST /api/video/upload
Content-Type: multipart/form-data
```

字段：

| 字段 | 类型 | 必填 | 示例 |
|---|---|---:|---|
| `video` | File | 是 | `input.mp4` |
| `mode` | Text | 是 | `repair` / `interpolate` |
| `targetFps` | Text/Number | 否 | `30` / `60` |
| `maxSeconds` | Text/Number | 否 | `5` |
| `publicBaseUrl` | Text | 否 | `https://backend-ffmpeg-production.up.railway.app` |

补帧上传：

```bash
curl -X POST "https://backend-ffmpeg-production.up.railway.app/api/video/upload" \
  -F "video=@input.mp4" \
  -F "mode=repair" \
  -F "targetFps=30" \
  -F "maxSeconds=5" \
  -F "publicBaseUrl=https://backend-ffmpeg-production.up.railway.app"
```

插帧上传：

```bash
curl -X POST "https://backend-ffmpeg-production.up.railway.app/api/video/upload" \
  -F "video=@input.mp4" \
  -F "mode=interpolate" \
  -F "targetFps=60" \
  -F "maxSeconds=5" \
  -F "publicBaseUrl=https://backend-ffmpeg-production.up.railway.app"
```

### 3.4 下载输出视频

```http
GET /api/video/files/{filename}
```

处理接口成功后会返回：

```json
{
  "outputUrl": "https://backend-ffmpeg-production.up.railway.app/api/video/files/output_interpolate_60fps_xxxxxxxx.mp4"
}
```

下载：

```bash
curl -L "https://backend-ffmpeg-production.up.railway.app/api/video/files/output_interpolate_60fps_xxxxxxxx.mp4" -o output.mp4
```

也可以直接把 `outputUrl` 粘贴到浏览器打开。

---

## 4. 返回格式

成功：

```json
{
  "success": true,
  "message": "视频处理完成...",
  "mode": "插帧",
  "modeCode": "interpolate",
  "method": "FFmpeg minterpolate 轻量插帧",
  "inputFile": "input.mp4",
  "inputFps": "24",
  "targetFps": 60,
  "outputFps": "60",
  "outputFile": "output_interpolate_60fps_xxxxxxxx.mp4",
  "outputSize": 389277,
  "outputSizeMb": 0.37,
  "outputUrl": "https://backend-ffmpeg-production.up.railway.app/api/video/files/output_interpolate_60fps_xxxxxxxx.mp4",
  "video": {
    "name": "output_interpolate_60fps_xxxxxxxx.mp4",
    "filename": "output_interpolate_60fps_xxxxxxxx.mp4",
    "url": "https://backend-ffmpeg-production.up.railway.app/api/video/files/output_interpolate_60fps_xxxxxxxx.mp4",
    "mime_type": "video/mp4",
    "mimeType": "video/mp4",
    "type": "video",
    "size": 389277
  },
  "error": null
}
```

如果 `/api/video/process` 收到了视频 URL，但服务器无法下载源视频，会返回类似：

```json
{
  "success": false,
  "manualDownloadRequired": true,
  "manualDownloadUrl": "原始视频地址",
  "recommendedEndpoint": "/api/video/upload",
  "hint": "HiAgent 文件代理地址在外部服务器上可能返回 502。请改用 multipart/form-data 文件上传接口 /api/video/upload，或传入公网可直接下载的视频 URL。"
}
```

遇到这种情况，优先使用 `/api/video/upload` 直接上传文件。

---

## 5. Postman 调用

### 5.1 上传本地文件

1. Method 选择 `POST`
2. URL 填：

```text
https://backend-ffmpeg-production.up.railway.app/api/video/upload
```

3. Body 选择 `form-data`
4. 不要手动填 `Content-Type`，Postman 会自动生成 multipart boundary

字段：

```text
video          File    选择本地 mp4
mode           Text    repair / interpolate
targetFps      Text    30 / 60
maxSeconds     Text    5
publicBaseUrl  Text    https://backend-ffmpeg-production.up.railway.app
```

### 5.2 使用视频 URL

1. Method 选择 `POST`
2. URL 填：

```text
https://backend-ffmpeg-production.up.railway.app/api/video/process
```

3. Body 选择 `raw -> JSON`
4. Header 填：

```text
Content-Type: application/json
```

请求体：

```json
{
  "mode": "interpolate",
  "videoUrl": "https://example.com/input.mp4",
  "inputName": "input.mp4",
  "targetFps": 60,
  "maxSeconds": 5,
  "publicBaseUrl": "https://backend-ffmpeg-production.up.railway.app"
}
```

---

## 6. HiAgent / 插件工具配置

推荐工具接口：

```text
POST https://backend-ffmpeg-production.up.railway.app/api/video/process
```

请求体格式：

```text
json
```

不要使用 `form-data`，除非你明确要走 `/api/video/upload` 文件上传接口。

输入参数：

| 名称 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| `mode` | String | 是 | `repair` 补帧，`interpolate` 插帧 |
| `videoUrl` | String | 是 | 用户上传视频的下载 URL |
| `inputName` | String | 是 | 用户上传视频文件名；拿不到时填 `input.mp4` |
| `targetFps` | Number | 是 | 补帧填 `30`，插帧填 `60` |
| `maxSeconds` | Number | 是 | 默认 `5`，最大 `60` |
| `publicBaseUrl` | String | 是 | `https://backend-ffmpeg-production.up.railway.app` |

输出参数：

| 名称 | 类型 |
|---|---|
| `success` | Boolean |
| `message` | String |
| `mode` | String |
| `modeCode` | String |
| `inputFile` | String |
| `inputFps` | String |
| `targetFps` | Number |
| `outputFps` | String |
| `outputFile` | String |
| `outputSize` | Number |
| `outputSizeMb` | Number |
| `outputUrl` | String |
| `error` | String |

智能体判断规则：

- 用户说插帧、60fps、提升流畅度：传 `mode=interpolate`、`targetFps=60`
- 用户说补帧、30fps、帧率补齐：传 `mode=repair`、`targetFps=30`
- 用户没有上传视频：先要求用户上传视频
- 用户没有说明插帧还是补帧：先询问“补帧到 30fps 还是插帧到 60fps”
- 不要让模型自己拼接 `outputUrl`，必须使用工具返回的真实 `outputUrl`

---

## 7. 本地运行

前置要求：

- JDK 17+
- FFmpeg，并且支持 `minterpolate`

检查：

```bash
java -version
ffmpeg -version
ffmpeg -filters | grep minterpolate
```

运行：

```bash
chmod +x scripts/run-local.sh
./scripts/run-local.sh
```

Windows 手动运行：

```powershell
mkdir outputs -Force
mkdir tmp-inputs -Force
dir /s /b src\*.java > sources.txt
javac -encoding UTF-8 -d out @sources.txt
jar --create --file app.jar --main-class com.example.videoframe.VideoFrameHttpService -C out .
java -jar app.jar
```

测试：

```bash
curl http://127.0.0.1:8080/api/video/health
```

---

## 8. Docker

构建：

```bash
docker build -t hiagent-video-http-service:1.0 .
```

中国大陆环境可用：

```bash
docker build -f Dockerfile.cn -t hiagent-video-http-service:1.0 .
```

运行：

```bash
docker run -d \
  --name hiagent-video-http-service \
  -p 8080:8080 \
  -e PUBLIC_BASE_URL="http://localhost:8080" \
  -e DEFAULT_MAX_SECONDS=5 \
  -e MAX_SECONDS_LIMIT=60 \
  -e FFMPEG_TIMEOUT_SECONDS=600 \
  -v $(pwd)/outputs:/app/outputs \
  -v $(pwd)/tmp-inputs:/app/tmp-inputs \
  hiagent-video-http-service:1.0
```

Docker Compose：

```bash
docker compose up -d --build
```

---

## 9. Railway 部署

当前仓库可以直接通过 GitHub 部署到 Railway。Railway 会识别根目录 `Dockerfile` 并自动构建。

推荐环境变量：

```text
PUBLIC_BASE_URL=https://backend-ffmpeg-production.up.railway.app
DEFAULT_MAX_SECONDS=5
MAX_SECONDS_LIMIT=60
FFMPEG_TIMEOUT_SECONDS=600
```

Railway 会自动提供 `PORT`，不要在平台里写死端口。

注意：

- Railway 默认文件系统不是长期持久化存储
- `/app/outputs` 里的输出视频可能在重新部署、重启、迁移实例后丢失
- 如果需要长期保存结果视频，应接 Railway Volume 或对象存储

---

## 10. 注意事项

1. 当前公网服务没有鉴权，知道地址的人都可以调用。
2. 标书演示建议使用 `maxSeconds=5` 或 `10`。
3. 30-60 秒插帧会更慢，可能受到 Railway CPU/内存影响。
4. `/api/video/process` 适合智能体和工作流，因为它接收视频 URL。
5. `/api/video/upload` 适合 Postman、前端页面或本地文件上传。
6. 输出视频 URL 不是永久存储地址，长期保存需要接持久化存储。
