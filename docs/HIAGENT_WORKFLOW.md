# HiAgent 工作流配置说明

## 1. 总体流程

推荐工作流：

```text
Start → Code参数解析 → HTTP请求 → End
```

服务端接口地址：

```text
http://服务器IP:8080/api/video/process
```

如果有域名或内网代理，也可以用：

```text
https://你的域名/api/video/process
```

---

## 2. Start 节点

Start 保持两个输入：

| 变量 | 类型 | 说明 |
|---|---|---|
| `query` | String | 用户输入，例如 `插帧 60fps` / `补帧 30fps` |
| `files` | Array<File> | 用户上传的视频 |

---

## 3. Code 参数解析节点

新增一个 Code 节点，语言选 Python。

输入变量：

| 变量名 | 类型 | 来源 |
|---|---|---|
| `input` | String | Start.query |
| `files` | Array<File> | Start.files |

输出变量：

| 输出名 | 类型 | 含义 |
|---|---|---|
| `key0` | String | mode：`repair` 或 `interpolate` |
| `key1` | String | 上传视频 URL |
| `key2` | Number | 目标 FPS |
| `key3` | String | 输入文件名 |
| `key4` | Number | 处理时长，默认 3 秒 |

代码使用：

```python
# 直接粘贴 docs/hiagent_code_parse_node.py 里的内容
```

---

## 4. HTTP 请求节点

### 请求方式

```text
POST
```

### URL

```text
http://服务器IP:8080/api/video/process
```

### Header

| Key | Value |
|---|---|
| `Content-Type` | `application/json` |

### Body

选择 `JSON` 或 `Raw JSON`，填写：

```json
{
  "mode": "{{Code参数解析.key0}}",
  "input": "{{Start.query}}",
  "videoUrl": "{{Code参数解析.key1}}",
  "inputName": "{{Code参数解析.key3}}",
  "targetFps": {{Code参数解析.key2}},
  "maxSeconds": {{Code参数解析.key4}},
  "publicBaseUrl": "http://服务器IP:8080"
}
```

说明：

- `publicBaseUrl` 必须写成外部能访问到服务的地址。
- 如果服务部署在 `http://192.168.1.10:8080`，就填这个。
- 如果服务通过域名暴露，例如 `https://video.example.com`，就填域名。
- 如果返回 `manualDownloadRequired=true`，说明工作流已经调到服务，但服务端下载 HiAgent 文件代理地址失败。此时应改用 `/api/video/upload` 文件上传接口，或把视频先放到公网直链。

---

## 5. End 节点

### 文本输出

绑定 HTTP 节点返回的：

```text
message
```

回答内容可以写：

```text
{{HTTP请求01.message}}

视频地址：
{{HTTP请求01.outputUrl}}
```

### Video/File 输出

如果 End 支持 Video/File 输出，新增一个输出变量：

| 输出名 | 类型 | 绑定 |
|---|---|---|
| `video` | Video / File-Video | `HTTP请求01.video` |

服务返回的 `video` 对象格式：

```json
{
  "name": "output_repair_30fps_xxxxxxxx.mp4",
  "filename": "output_repair_30fps_xxxxxxxx.mp4",
  "url": "http://服务器IP:8080/api/video/files/output_repair_30fps_xxxxxxxx.mp4",
  "mime_type": "video/mp4",
  "mimeType": "video/mp4",
  "type": "video",
  "size": 607199
}
```

失败兜底字段：

```json
{
  "success": false,
  "manualDownloadRequired": true,
  "manualDownloadUrl": "原始视频下载地址",
  "recommendedEndpoint": "/api/video/upload"
}
```

看到这个结果时，不要回复处理完成；提示用户当前视频源不能被服务器下载，建议改用上传接口或公网直链。

---

## 6. 如何区分插帧和补帧

### 插帧

上传：

```text
test_insert_input_24fps_360p_5s.mp4
```

输入：

```text
插帧 60fps
```

预期结果：

```text
功能: 插帧
输入帧率: 24fps
目标帧率: 60fps
输出帧率: 60fps
视频地址: http://服务器IP:8080/api/video/files/xxx.mp4
```

### 补帧

上传：

```text
test_repair_input_15fps_360p_5s.mp4
```

输入：

```text
补帧 30fps
```

预期结果：

```text
功能: 补帧
输入帧率: 15fps
目标帧率: 30fps
输出帧率: 30fps
视频地址: http://服务器IP:8080/api/video/files/xxx.mp4
```

---

## 7. 软测截图建议

至少截 4 张：

| 截图 | 内容 |
|---|---|
| 1 | `/api/video/health` 返回 `ffmpegOk=true` |
| 2 | HiAgent 上传 24fps 视频，输入 `插帧 60fps` |
| 3 | 返回结果显示 `24fps → 60fps` 和视频地址 |
| 4 | 上传 15fps 视频，输入 `补帧 30fps`，返回 `15fps → 30fps` |
