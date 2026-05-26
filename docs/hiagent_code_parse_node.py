# HiAgent Code 节点：把 Start.query + Start.files 转成 HTTP 请求参数
# 输入变量：
#   input: String       <- Start.query
#   files: Array<File>  <- Start.files
# 输出变量：
#   key0: String  mode，repair 或 interpolate
#   key1: String  videoUrl，上传视频的可下载 URL
#   key2: Number  targetFps
#   key3: String  inputName
#   key4: Number  maxSeconds，默认 5，最大 60


def handler(params):
    import re

    query = str(params.get("input") or "")
    files = params.get("files") or []

    file0 = files[0] if isinstance(files, list) and len(files) > 0 else {}
    video_url = ""
    input_name = "input.mp4"

    if isinstance(file0, dict):
        video_url = (
            file0.get("url")
            or file0.get("download_url")
            or file0.get("file_url")
            or file0.get("remote_url")
            or file0.get("path")
            or ""
        )
        input_name = (
            file0.get("name")
            or file0.get("filename")
            or file0.get("file_name")
            or "input.mp4"
        )

    if "补帧" in query:
        mode = "repair"
        default_fps = 30
    else:
        mode = "interpolate"
        default_fps = 60

    m = re.search(r"(\d+)\s*fps", query.lower())
    target_fps = int(m.group(1)) if m else default_fps
    target_fps = max(15, min(60, target_fps))

    seconds_match = re.search(r"(\d+)\s*(?:秒|s|sec|seconds?)", query.lower())
    max_seconds = int(seconds_match.group(1)) if seconds_match else 5
    max_seconds = max(1, min(60, max_seconds))

    return {
        "key0": mode,
        "key1": video_url,
        "key2": target_fps,
        "key3": input_name,
        "key4": max_seconds
    }
