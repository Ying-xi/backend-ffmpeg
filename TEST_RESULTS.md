# 本地测试结果

测试环境：当前沙箱环境，Java 21 运行，系统 FFmpeg 7.1.3。

> Docker 未在当前沙箱执行，因为当前环境没有 docker 命令；项目 Dockerfile 使用无 Maven 依赖方案，构建过程只需要 JDK + FFmpeg，已按中国大陆构建场景提供 `Dockerfile.cn`。

## 1. 编译测试

命令：

```bash
find src -name '*.java' > sources.txt
javac -encoding UTF-8 -d out @sources.txt
jar --create --file app.jar --main-class com.example.videoframe.VideoFrameHttpService -C out .
```

结果：成功生成：

```text
app.jar
```

## 2. 服务启动测试

启动命令：

```bash
PORT=18081 PUBLIC_BASE_URL=http://127.0.0.1:18081 java -jar app.jar
```

健康检查：

```bash
curl http://127.0.0.1:18081/api/video/health
```

关键结果：

```json
{
  "success": true,
  "ffmpegOk": true,
  "hasMinterpolate": true
}
```

## 3. 补帧测试

请求：

```bash
curl -X POST http://127.0.0.1:18081/api/video/process \
  -H 'Content-Type: application/json' \
  -d '{
    "input":"补帧 30fps",
    "videoUrl":"http://127.0.0.1:18081/api/video/test-videos/test_repair_input_15fps_360p_5s.mp4",
    "targetFps":30,
    "maxSeconds":3,
    "publicBaseUrl":"http://127.0.0.1:18081"
  }'
```

关键结果：

```json
{
  "success": true,
  "mode": "补帧",
  "inputFps": "15",
  "targetFps": 30,
  "outputFps": "30",
  "outputUrl": "http://127.0.0.1:18081/api/video/files/output_repair_30fps_1408af55.mp4"
}
```

输出视频 URL 访问测试：

```text
HTTP/1.1 200 OK
Content-type: video/mp4
Accept-ranges: bytes
```

Range 请求测试：

```text
HTTP/1.1 206 Partial Content
Content-range: bytes 0-99/607218
```

FFmpeg 验证输出帧率：

```text
30 fps
```

## 4. 插帧测试

请求：

```bash
curl -X POST http://127.0.0.1:18081/api/video/process \
  -H 'Content-Type: application/json' \
  -d '{
    "input":"插帧 60fps",
    "videoUrl":"http://127.0.0.1:18081/api/video/test-videos/test_insert_input_24fps_360p_5s.mp4",
    "targetFps":60,
    "maxSeconds":3,
    "publicBaseUrl":"http://127.0.0.1:18081"
  }'
```

关键结果：

```json
{
  "success": true,
  "mode": "插帧",
  "inputFps": "24",
  "targetFps": 60,
  "outputFps": "60",
  "outputUrl": "http://127.0.0.1:18081/api/video/files/output_interpolate_60fps_dbfa9c1f.mp4"
}
```

FFmpeg 验证输出帧率：

```text
60 fps
```
