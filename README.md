# hiagent-video-http-service

这是给 HiAgent 调用的本地/服务器 HTTP 服务，用 Java + FFmpeg 实现视频插帧和补帧，并把处理后的视频以 HTTP URL 返回。

## 1. 结论

部署后，HiAgent 只需要请求：

```text
POST http://服务器IP:8080/api/video/process
```

请求体传入：

```json
{
  "mode": "repair",
  "videoUrl": "HiAgent 上传文件的下载 URL",
  "targetFps": 30,
  "maxSeconds": 5,
  "publicBaseUrl": "http://服务器IP:8080"
}
```

服务会返回：

```json
{
  "success": true,
  "message": "视频处理完成...",
  "outputUrl": "http://服务器IP:8080/api/video/files/output_xxx.mp4",
  "video": {
    "name": "output_xxx.mp4",
    "url": "http://服务器IP:8080/api/video/files/output_xxx.mp4",
    "mime_type": "video/mp4",
    "type": "video"
  }
}
```

---

## 2. 功能说明

| 功能 | 输入文字 | 默认目标帧率 | 处理方式 | 证明方式 |
|---|---|---:|---|---|
| 插帧 | `插帧 60fps` | 60fps | `minterpolate` 轻量插帧 | 24fps → 60fps |
| 补帧 | `补帧 30fps` | 30fps | `fps` 帧率补齐 | 15fps → 30fps |

说明：

- 插帧用于提高视频流畅度。
- 补帧用于把低帧率视频补齐到稳定目标帧率。
- 软测截图主要看：输入帧率、目标帧率、输出帧率、视频地址。

---

## 3. 接口列表

### 3.1 健康检查

```http
GET /api/video/health
```

示例：

```bash
curl http://127.0.0.1:8080/api/video/health
```

成功返回应包含：

```json
{
  "ffmpegOk": true,
  "hasMinterpolate": true
}
```

---

### 3.2 通用处理接口

```http
POST /api/video/process
```

请求体：

```json
{
  "input": "补帧 30fps",
  "mode": "repair",
  "videoUrl": "http://hiagent.../api/proxy/down?...",
  "inputName": "test_repair_input_15fps_360p_5s.mp4",
  "targetFps": 30,
  "maxSeconds": 5,
  "publicBaseUrl": "http://服务器IP:8080"
}
```

字段说明：

| 字段 | 必填 | 说明 |
|---|---:|---|
| `input` | 否 | 用户输入，可包含 `插帧 60fps` 或 `补帧 30fps` |
| `mode` | 否 | `interpolate` 插帧；`repair` 补帧 |
| `videoUrl` | 是 | HiAgent 上传文件的下载 URL |
| `inputName` | 否 | 输入文件名 |
| `targetFps` | 否 | 目标帧率，15-60 |
| `maxSeconds` | 否 | 处理前 N 秒，默认 5，当前最大 60 |
| `publicBaseUrl` | 建议填 | 服务外部访问地址，例如 `http://服务器IP:8080` |

也支持直接传 HiAgent 的 `files`：

```json
{
  "input": "补帧 30fps",
  "files": [
    {
      "name": "test_repair_input_15fps_360p_5s.mp4",
      "url": "http://hiagent.../api/proxy/down?..."
    }
  ],
  "publicBaseUrl": "http://服务器IP:8080"
}
```

---

### 3.3 显式插帧接口

```http
POST /api/video/interpolate
```

这个接口会强制走插帧。

---

### 3.4 显式补帧接口

```http
POST /api/video/repair
```

这个接口会强制走补帧。

---

### 3.5 输出视频访问

```http
GET /api/video/files/{filename}
```

示例：

```text
http://服务器IP:8080/api/video/files/output_repair_30fps_xxxxxxxx.mp4
```

服务支持 `Range` 请求，浏览器 video 组件可以播放。

---

## 4. 本地直接运行

### 4.1 前置要求

需要安装：

| 工具 | 用途 |
|---|---|
| JDK 17+ | 编译/运行 Java |
| FFmpeg | 视频处理 |

验证：

```bash
java -version
ffmpeg -version
ffmpeg -filters | grep minterpolate
```

Windows 可以用：

```cmd
ffmpeg -filters | findstr minterpolate
```

---

### 4.2 启动服务

Linux / macOS：

```bash
chmod +x scripts/run-local.sh
./scripts/run-local.sh
```

Windows 手动命令：

```cmd
mkdir outputs
mkdir tmp-inputs
dir /s /b src\*.java > sources.txt
javac -encoding UTF-8 -d out @sources.txt
jar --create --file app.jar --main-class com.example.videoframe.VideoFrameHttpService -C out .
java -jar app.jar
```

默认地址：

```text
http://127.0.0.1:8080
```

---

### 4.3 本地测试

服务启动后，新开一个终端：

```bash
chmod +x scripts/test-local.sh
./scripts/test-local.sh
```

这个脚本会测试：

| 测试 | 输入视频 | 预期 |
|---|---|---|
| 补帧 | 15fps 测试视频 | 输出 30fps |
| 插帧 | 24fps 测试视频 | 输出 60fps |

---

## 5. Docker 打包和运行

项目没有 Maven 依赖，不需要从 Maven Central 下载。Docker 构建只需要安装 JDK 和 FFmpeg。

### 5.1 中国大陆推荐构建

```bash
docker build -f Dockerfile.cn -t hiagent-video-http-service:1.0 .
```

`Dockerfile.cn` 做了两件事：

1. 使用阿里云镜像仓库的 Ubuntu 基础镜像。
2. 把 apt 源切到 `mirrors.aliyun.com`。

### 5.2 运行容器

```bash
docker run -d \
  --name hiagent-video-http-service \
  -p 8080:8080 \
  -e PUBLIC_BASE_URL="http://服务器IP:8080" \
  -v $(pwd)/outputs:/app/outputs \
  hiagent-video-http-service:1.0
```

Windows PowerShell 示例：

```powershell
docker run -d `
  --name hiagent-video-http-service `
  -p 8080:8080 `
  -e PUBLIC_BASE_URL="http://服务器IP:8080" `
  -v ${PWD}/outputs:/app/outputs `
  hiagent-video-http-service:1.0
```

### 5.3 docker-compose

```bash
docker compose up -d --build
```

---

## 6. 服务器部署后怎么给 HiAgent 调用

假设服务器 IP 是：

```text
192.168.1.10
```

服务端口是：

```text
8080
```

那么 HiAgent HTTP 节点 URL 填：

```text
http://192.168.1.10:8080/api/video/process
```

请求体填：

```json
{
  "mode": "{{Code参数解析.key0}}",
  "input": "{{Start.query}}",
  "videoUrl": "{{Code参数解析.key1}}",
  "inputName": "{{Code参数解析.key3}}",
  "targetFps": {{Code参数解析.key2}},
  "maxSeconds": 5,
  "publicBaseUrl": "http://192.168.1.10:8080"
}
```

如果是公网域名：

```text
https://video.example.com/api/video/process
```

则 `publicBaseUrl` 填：

```text
https://video.example.com
```

---

## 7. HiAgent 工作流

详细配置见：

```text
docs/HIAGENT_WORKFLOW.md
```

推荐流程：

```text
Start → Code参数解析 → HTTP请求 → End
```

用户操作：

| 功能 | 上传视频 | 用户输入 |
|---|---|---|
| 插帧 | `test_insert_input_24fps_360p_5s.mp4` | `插帧 60fps` |
| 补帧 | `test_repair_input_15fps_360p_5s.mp4` | `补帧 30fps` |

---

## 8. 注意事项

1. HiAgent 必须能访问你的服务地址。
2. `PUBLIC_BASE_URL` 必须是 HiAgent 和浏览器都能访问的地址。
3. 软测建议视频保持 3-5 秒、640×360。
4. 这个服务会把输出视频保存在 `outputs` 目录。
5. 正式部署建议加鉴权，否则别人知道地址也能调用接口。
6. 如果 HiAgent 文件代理地址在服务器侧下载失败，服务会返回 `manualDownloadRequired=true`、`manualDownloadUrl` 和 `recommendedEndpoint=/api/video/upload`。这表示请求已经到达服务，但源视频无法被服务器下载，应改用上传接口或公网直链。

---

## 9. 公网上传接口

如果调用方没有现成的视频下载 URL，可以直接上传视频文件：

```text
POST /api/video/upload
Content-Type: multipart/form-data
```

字段：

| 字段 | 必填 | 示例 | 说明 |
|---|---|---|---|
| `video` | 是 | `@input.mp4` | 视频文件，字段名固定为 `video` |
| `mode` | 是 | `repair` / `interpolate` | `repair` 是补帧，`interpolate` 是插帧 |
| `targetFps` | 否 | `30` / `60` | 目标帧率，支持 15-60 |
| `maxSeconds` | 否 | `5` | 最多处理秒数，服务默认最大 60 |
| `publicBaseUrl` | 否 | `https://xxx.up.railway.app` | 结果视频 URL 的公网前缀 |

补帧上传示例：

```bash
curl -X POST "https://你的域名/api/video/upload" \
  -F "video=@input.mp4" \
  -F "mode=repair" \
  -F "targetFps=30" \
  -F "maxSeconds=5" \
  -F "publicBaseUrl=https://你的域名"
```

插帧上传示例：

```bash
curl -X POST "https://你的域名/api/video/upload" \
  -F "video=@input.mp4" \
  -F "mode=interpolate" \
  -F "targetFps=60" \
  -F "maxSeconds=5" \
  -F "publicBaseUrl=https://你的域名"
```

返回里的 `outputUrl` 就是处理后视频的下载/播放地址。

如果 HiAgent 返回的 `videoUrl` 类似下面这种代理下载地址，并且 `/api/video/process` 返回下载失败：

```text
http://hiagent.aigc.smdata.com.cn/api/proxy/down?Action=DownloadForPresign&...
```

优先改用本上传接口。上传接口不需要服务端再次访问 HiAgent 的临时下载链接，稳定性更高。

---

## 10. Railway 部署

可以直接用 GitHub 仓库部署。Railway 会识别根目录的 `Dockerfile` 并构建镜像。

建议步骤：

1. 新建 GitHub 仓库，可以是 public。
2. 推送项目代码，但不要提交 `outputs/`、`tmp-inputs/`、`*.tar`。
3. Railway 新建项目，选择 `Deploy from GitHub repo`。
4. 部署成功后，在 Railway 服务里生成公网域名。
5. 设置变量：

```text
PUBLIC_BASE_URL=https://你的Railway域名
DEFAULT_MAX_SECONDS=5
MAX_SECONDS_LIMIT=60
FFMPEG_TIMEOUT_SECONDS=600
```

Railway 会自动提供 `PORT` 变量，服务会监听这个端口，不需要手动设置。

健康检查：

```text
https://你的Railway域名/api/video/health
```

公网上传接口：

```text
https://你的Railway域名/api/video/upload
```
