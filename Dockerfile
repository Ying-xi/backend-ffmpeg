# 无 Maven 依赖版本：只用 JDK + javac 编译，避免 Maven Central 下载慢/不可用。
# 普通环境可直接用：docker build -t hiagent-video-http-service:1.0 .
FROM ubuntu:22.04

ENV DEBIAN_FRONTEND=noninteractive \
    FFMPEG_PATH=ffmpeg \
    OUTPUT_DIR=/app/outputs \
    INPUT_TMP_DIR=/app/tmp-inputs \
    TEST_VIDEO_DIR=/app/test-videos \
    DEFAULT_MAX_SECONDS=5 \
    MAX_SECONDS_LIMIT=60 \
    FFMPEG_TIMEOUT_SECONDS=600 \
    PUBLIC_BASE_URL=""

RUN apt-get update \
    && apt-get install -y --no-install-recommends openjdk-17-jdk-headless ffmpeg curl ca-certificates \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY src ./src
COPY test-videos ./test-videos

RUN mkdir -p /app/outputs /app/tmp-inputs \
    && find src -name "*.java" > sources.txt \
    && javac -encoding UTF-8 -d out @sources.txt \
    && jar --create --file app.jar --main-class com.example.videoframe.VideoFrameHttpService -C out . \
    && rm -rf out sources.txt src

EXPOSE 8080
CMD ["java", "-jar", "/app/app.jar"]
