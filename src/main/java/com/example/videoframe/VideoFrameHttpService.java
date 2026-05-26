package com.example.videoframe;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class VideoFrameHttpService {
    public static void main(String[] args) throws Exception {
        Config config = Config.fromEnv();
        Files.createDirectories(config.outputDir());
        Files.createDirectories(config.inputTmpDir());

        HttpServer server = HttpServer.create(new InetSocketAddress(config.port()), 0);
        VideoProcessor processor = new VideoProcessor(config);

        server.createContext("/api/video/health", new HealthHandler(processor));
        server.createContext("/api/video/process", new ProcessHandler(processor, null));
        server.createContext("/api/video/interpolate", new ProcessHandler(processor, "interpolate"));
        server.createContext("/api/video/repair", new ProcessHandler(processor, "repair"));
        server.createContext("/api/video/upload", new UploadHandler(processor, config));
        server.createContext("/api/video/files/", new FileHandler(config.outputDir()));
        server.createContext("/api/video/test-videos/", new FileHandler(config.testVideoDir()));

        server.setExecutor(Executors.newCachedThreadPool());
        server.start();

        System.out.println("video-frame-http-service started");
        System.out.println("port=" + config.port());
        System.out.println("ffmpegPath=" + config.ffmpegPath());
        System.out.println("outputDir=" + config.outputDir().toAbsolutePath().normalize());
        System.out.println("testVideoDir=" + config.testVideoDir().toAbsolutePath().normalize());
        System.out.println("defaultMaxSeconds=" + config.defaultMaxSeconds());
        System.out.println("maxSecondsLimit=" + config.maxSecondsLimit());
        System.out.println("ffmpegTimeoutSeconds=" + config.ffmpegTimeoutSeconds());
        System.out.println("publicBaseUrl=" + config.publicBaseUrl());
        System.out.println("health=http://127.0.0.1:" + config.port() + "/api/video/health");
    }
}

record Config(
        int port,
        String ffmpegPath,
        Path outputDir,
        Path inputTmpDir,
        Path testVideoDir,
        int defaultMaxSeconds,
        int maxSecondsLimit,
        int ffmpegTimeoutSeconds,
        String publicBaseUrl
) {
    static Config fromEnv() {
        int port = parseInt(env("PORT", "8080"), 8080);
        String ffmpegPath = env("FFMPEG_PATH", "ffmpeg");
        Path outputDir = Path.of(env("OUTPUT_DIR", "./outputs")).toAbsolutePath().normalize();
        Path inputTmpDir = Path.of(env("INPUT_TMP_DIR", "./tmp-inputs")).toAbsolutePath().normalize();
        Path testVideoDir = Path.of(env("TEST_VIDEO_DIR", "./test-videos")).toAbsolutePath().normalize();
        int maxSecondsLimit = clamp(parseInt(env("MAX_SECONDS_LIMIT", "60"), 60), 1, 600);
        int defaultMaxSeconds = clamp(parseInt(env("DEFAULT_MAX_SECONDS", "5"), 5), 1, maxSecondsLimit);
        int ffmpegTimeoutSeconds = clamp(parseInt(env("FFMPEG_TIMEOUT_SECONDS", "600"), 600), 30, 1800);
        String publicBaseUrl = env("PUBLIC_BASE_URL", "");
        return new Config(port, ffmpegPath, outputDir, inputTmpDir, testVideoDir, defaultMaxSeconds, maxSecondsLimit, ffmpegTimeoutSeconds, publicBaseUrl);
    }

    private static String env(String key, String defaultValue) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private static int parseInt(String s, int defaultValue) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}

final class VideoProcessor {
    private final Config config;
    private final HttpClient httpClient;

    VideoProcessor(Config config) {
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .connectTimeout(Duration.ofSeconds(20))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    Map<String, Object> health() {
        ProcessResult version = runCommand(List.of(config.ffmpegPath(), "-version"), 10);
        ProcessResult filters = runCommand(List.of(config.ffmpegPath(), "-filters"), 10);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", version.exitCode() == 0);
        result.put("ffmpegPath", config.ffmpegPath());
        result.put("ffmpegOk", version.exitCode() == 0);
        result.put("hasMinterpolate", filters.output().contains("minterpolate"));
        result.put("defaultMaxSeconds", config.defaultMaxSeconds());
        result.put("maxSecondsLimit", config.maxSecondsLimit());
        result.put("ffmpegTimeoutSeconds", config.ffmpegTimeoutSeconds());
        result.put("version", trim(version.output(), 500));
        return result;
    }

    Map<String, Object> process(String body, String forcedMode, HttpExchange exchange) {
        try {
            VideoRequest req = VideoRequest.fromJson(body);

            String videoUrl = firstNonBlank(req.videoUrl(), req.firstFileUrl());
            String videoPath = req.videoPath();
            String inputName = firstNonBlank(req.inputName(), req.firstFileName(), "input.mp4");

            if (isBlank(videoUrl) && isBlank(videoPath)) {
                return fail("没有拿到视频来源。请传 videoUrl，或传 files[0].url。", null);
            }

            ModeInfo mode = resolveMode(req, forcedMode);
            int targetFps = resolveTargetFps(req, mode.defaultFps());
            int maxSeconds = clamp(req.maxSeconds() == null ? config.defaultMaxSeconds() : req.maxSeconds(), 1, config.maxSecondsLimit());

            Path inputFile = resolveInput(videoUrl, videoPath, inputName);
            String inputFps = getFps(inputFile);

            String outputName = "output_" + mode.modeCode() + "_" + targetFps + "fps_" + shortId() + ".mp4";
            Path outputFile = config.outputDir().resolve(outputName).normalize();

            String vf;
            String method;
            if ("repair".equals(mode.modeCode())) {
                vf = "fps=" + targetFps + ",scale=640:-2";
                method = "FFmpeg fps 帧率补齐";
            } else {
                vf = "scale=480:-2,minterpolate=fps=" + targetFps + ":mi_mode=blend";
                method = "FFmpeg minterpolate 轻量插帧";
            }

            List<String> cmd = List.of(
                    config.ffmpegPath(),
                    "-hide_banner",
                    "-y",
                    "-t", String.valueOf(maxSeconds),
                    "-i", inputFile.toString(),
                    "-vf", vf,
                    "-map", "0:v:0",
                    "-an",
                    "-threads", "1",
                    "-c:v", "libx264",
                    "-pix_fmt", "yuv420p",
                    "-crf", "28",
                    "-preset", "ultrafast",
                    "-movflags", "+faststart",
                    outputFile.toString()
            );

            ProcessResult result = runCommand(cmd, config.ffmpegTimeoutSeconds());
            if (result.exitCode() != 0) {
                return fail("视频处理失败\n功能: " + mode.modeCn() +
                        "\n目标帧率: " + targetFps + "fps" +
                        "\n输入文件: " + inputName +
                        "\n处理方式: " + method +
                        "\n错误信息:\n" + trim(result.output(), 2000), null);
            }

            String outputFps = getFps(outputFile);
            long sizeBytes = Files.size(outputFile);
            double sizeMb = Math.round(sizeBytes / 1024.0 / 1024.0 * 100.0) / 100.0;
            String outputUrl = buildOutputUrl(exchange, req.publicBaseUrl(), outputName);

            String message = "视频处理完成\n" +
                    "功能: " + mode.modeCn() + "\n" +
                    "处理方式: " + method + "\n" +
                    "输入文件: " + inputName + "\n" +
                    "输入帧率: " + inputFps + "fps\n" +
                    "目标帧率: " + targetFps + "fps\n" +
                    "输出帧率: " + outputFps + "fps\n" +
                    "输出文件: " + outputName + "\n" +
                    "输出大小: " + sizeMb + " MB\n" +
                    "视频地址: " + outputUrl + "\n\n" +
                    "截图证明点：功能名称、输入帧率、目标帧率、输出帧率、视频地址。";

            Map<String, Object> video = new LinkedHashMap<>();
            video.put("name", outputName);
            video.put("filename", outputName);
            video.put("url", outputUrl);
            video.put("mime_type", "video/mp4");
            video.put("mimeType", "video/mp4");
            video.put("type", "video");
            video.put("size", sizeBytes);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);
            response.put("message", message);
            response.put("mode", mode.modeCn());
            response.put("modeCode", mode.modeCode());
            response.put("method", method);
            response.put("inputFile", inputName);
            response.put("inputFps", inputFps);
            response.put("targetFps", targetFps);
            response.put("outputFps", outputFps);
            response.put("outputFile", outputName);
            response.put("outputSize", sizeBytes);
            response.put("outputSizeMb", sizeMb);
            response.put("outputUrl", outputUrl);
            response.put("video", video);
            response.put("error", null);
            return response;
        } catch (VideoDownloadException e) {
            return failDownload(e);
        } catch (Exception e) {
            return fail("服务处理异常：" + e.getMessage(), e.toString());
        }
    }

    private Path resolveInput(String videoUrl, String videoPath, String inputName) throws Exception {
        if (!isBlank(videoPath)) {
            Path p = Path.of(videoPath).toAbsolutePath().normalize();
            if (!Files.exists(p) || !Files.isRegularFile(p)) {
                throw new RuntimeException("videoPath 文件不存在：" + p);
            }
            return p;
        }
        if (videoUrl.startsWith("file://")) {
            Path p = Path.of(URI.create(videoUrl)).toAbsolutePath().normalize();
            if (!Files.exists(p) || !Files.isRegularFile(p)) {
                throw new RuntimeException("file URL 文件不存在：" + p);
            }
            return p;
        }
        if (isHttpUrl(videoUrl)) {
            return downloadVideo(videoUrl, inputName);
        }
        try {
            Path localPath = Path.of(videoUrl);
            if (Files.exists(localPath)) {
                return localPath.toAbsolutePath().normalize();
            }
        } catch (Exception ignored) {
        }
        throw new VideoDownloadException(
                "视频地址不是可直接下载的 http/https URL：" + videoUrl +
                        "。如果 HiAgent 只给 upload/full/... 相对路径，请改用 /api/video/upload 文件上传接口。",
                videoUrl,
                null,
                null
        );
    }

    private Path downloadVideo(String videoUrl, String inputName) throws Exception {
        String ext = extOf(inputName);
        if (ext.isBlank()) ext = ".mp4";
        Path tempFile = Files.createTempFile(config.inputTmpDir(), "input_", ext);
        String javaError = null;
        Integer javaStatus = null;
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(videoUrl))
                    .timeout(Duration.ofSeconds(120))
                    .header("User-Agent", browserUserAgent())
                    .header("Accept", "video/mp4,video/*,*/*;q=0.8")
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                    .GET()
                    .build();
            HttpResponse<Path> response = httpClient.send(request, HttpResponse.BodyHandlers.ofFile(tempFile));
            javaStatus = response.statusCode();
            if (response.statusCode() >= 200 && response.statusCode() < 300 && Files.size(tempFile) > 0) {
                return tempFile;
            }
            javaError = "Java HTTP 下载失败，HTTP 状态码：" + response.statusCode();
        } catch (Exception e) {
            javaError = "Java HTTP 下载异常：" + e;
        }

        ProcessResult curl = downloadWithCurl(videoUrl, tempFile);
        if (curl.exitCode() == 0 && Files.exists(tempFile) && Files.size(tempFile) > 0) {
            return tempFile;
        }

        try {
            Files.deleteIfExists(tempFile);
        } catch (IOException ignored) {
        }
        String curlError = "curl fallback 失败，退出码：" + curl.exitCode() + "，输出：" + trim(curl.output(), 800);
        throw new VideoDownloadException(
                "下载视频失败。" + javaError + "；" + curlError +
                        "。如果这是 HiAgent 上传文件地址，请优先调用 /api/video/upload 直接上传文件，或换成服务器可访问的公网视频直链。",
                videoUrl,
                javaStatus,
                javaError + "；" + curlError
        );
    }

    private ProcessResult downloadWithCurl(String videoUrl, Path outputFile) {
        return runCommand(List.of(
                "curl",
                "-sS",
                "-L",
                "--fail",
                "--max-time", "120",
                "--retry", "2",
                "--retry-delay", "1",
                "-A", browserUserAgent(),
                "-H", "Accept: video/mp4,video/*,*/*;q=0.8",
                "-o", outputFile.toString(),
                videoUrl
        ), 140);
    }

    private String browserUserAgent() {
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/125.0 Safari/537.36";
    }

    private String getFps(Path videoPath) {
        ProcessResult result = runCommand(List.of(config.ffmpegPath(), "-hide_banner", "-i", videoPath.toString()), 20);
        Matcher matcher = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*fps").matcher(result.output());
        if (matcher.find()) return matcher.group(1);
        return "未知";
    }

    private ProcessResult runCommand(List<String> command, int timeoutSeconds) {
        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            process = pb.start();

            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            Process finalProcess = process;
            Thread reader = new Thread(() -> {
                try (InputStream is = finalProcess.getInputStream()) {
                    is.transferTo(buffer);
                } catch (IOException ignored) {
                }
            });
            reader.setDaemon(true);
            reader.start();

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new ProcessResult(-1, "命令执行超时：" + String.join(" ", command));
            }
            reader.join(1000);
            return new ProcessResult(process.exitValue(), buffer.toString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            if (process != null) process.destroyForcibly();
            return new ProcessResult(-1, e.toString());
        }
    }

    private ModeInfo resolveMode(VideoRequest request, String forcedMode) {
        String value = firstNonBlank(forcedMode, request.mode(), request.input(), "");
        String lower = value.toLowerCase(Locale.ROOT);
        if (value.contains("补帧") || lower.contains("repair")) {
            return new ModeInfo("补帧", "repair", 30);
        }
        return new ModeInfo("插帧", "interpolate", 60);
    }

    private int resolveTargetFps(VideoRequest request, int defaultFps) {
        if (request.targetFps() != null) return clamp(request.targetFps(), 15, 60);
        String input = Optional.ofNullable(request.input()).orElse("");
        Matcher matcher = Pattern.compile("(\\d+)\\s*fps", Pattern.CASE_INSENSITIVE).matcher(input);
        if (matcher.find()) return clamp(Integer.parseInt(matcher.group(1)), 15, 60);
        return defaultFps;
    }

    private String buildOutputUrl(HttpExchange exchange, String requestBaseUrl, String outputName) {
        String base = firstNonBlank(requestBaseUrl, config.publicBaseUrl());
        if (isBlank(base)) {
            Headers h = exchange.getRequestHeaders();
            String proto = firstNonBlank(h.getFirst("X-Forwarded-Proto"), "http");
            String host = firstNonBlank(h.getFirst("X-Forwarded-Host"), h.getFirst("Host"));
            if (isBlank(host)) host = "127.0.0.1:" + config.port();
            base = proto + "://" + host;
        }
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        String encoded = URLEncoder.encode(outputName, StandardCharsets.UTF_8).replace("+", "%20");
        return base + "/api/video/files/" + encoded;
    }

    private static Map<String, Object> fail(String message, String error) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", false);
        response.put("message", message);
        response.put("mode", null);
        response.put("method", null);
        response.put("inputFile", null);
        response.put("inputFps", null);
        response.put("targetFps", null);
        response.put("outputFps", null);
        response.put("outputFile", null);
        response.put("outputSize", null);
        response.put("outputSizeMb", null);
        response.put("outputUrl", null);
        response.put("video", null);
        response.put("error", error);
        return response;
    }

    private static Map<String, Object> failDownload(VideoDownloadException e) {
        Map<String, Object> response = fail(
                "视频源地址已收到，但服务器下载源视频失败：" + e.getMessage(),
                e.detail()
        );
        response.put("manualDownloadRequired", true);
        response.put("manualDownloadUrl", e.videoUrl());
        response.put("sourceVideoUrl", e.videoUrl());
        response.put("downloadStatus", e.statusCode());
        response.put("recommendedEndpoint", "/api/video/upload");
        response.put("hint", "HiAgent 文件代理地址在外部服务器上可能返回 502。请改用 multipart/form-data 文件上传接口 /api/video/upload，或传入公网可直接下载的视频 URL。");
        return response;
    }

    private String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private String extOf(String filename) {
        if (filename == null) return "";
        int idx = filename.lastIndexOf('.');
        return idx >= 0 ? filename.substring(idx) : "";
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static boolean isHttpUrl(String s) {
        if (isBlank(s)) return false;
        String lower = s.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String v : values) if (!isBlank(v)) return v.trim();
        return null;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String trim(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }
}

record ModeInfo(String modeCn, String modeCode, int defaultFps) {}
record ProcessResult(int exitCode, String output) {}

final class VideoDownloadException extends Exception {
    private final String videoUrl;
    private final Integer statusCode;
    private final String detail;

    VideoDownloadException(String message, String videoUrl, Integer statusCode, String detail) {
        super(message);
        this.videoUrl = videoUrl;
        this.statusCode = statusCode;
        this.detail = detail == null ? message : detail;
    }

    String videoUrl() {
        return videoUrl;
    }

    Integer statusCode() {
        return statusCode;
    }

    String detail() {
        return detail;
    }
}

record VideoRequest(
        String input,
        String mode,
        Integer targetFps,
        Integer maxSeconds,
        String videoUrl,
        String videoPath,
        String inputName,
        String publicBaseUrl,
        List<FileItem> files
) {
    String firstFileUrl() {
        if (files == null || files.isEmpty()) return null;
        return files.get(0).url();
    }

    String firstFileName() {
        if (files == null || files.isEmpty()) return null;
        return files.get(0).name();
    }

    static VideoRequest fromJson(String json) {
        if (json == null) json = "";
        String input = JsonLite.string(json, "input");
        String mode = JsonLite.string(json, "mode");
        Integer targetFps = JsonLite.integer(json, "targetFps");
        Integer maxSeconds = JsonLite.integer(json, "maxSeconds");
        String videoUrl = JsonLite.string(json, "videoUrl");
        String videoPath = JsonLite.string(json, "videoPath");
        String inputName = firstNonBlankStatic(JsonLite.string(json, "inputName"), JsonLite.string(json, "fileName"));
        String publicBaseUrl = JsonLite.string(json, "publicBaseUrl");
        List<FileItem> files = JsonLite.files(json);
        return new VideoRequest(input, mode, targetFps, maxSeconds, videoUrl, videoPath, inputName, publicBaseUrl, files);
    }

    private static String firstNonBlankStatic(String... values) {
        if (values == null) return null;
        for (String v : values) {
            if (v != null && !v.trim().isEmpty()) return v.trim();
        }
        return null;
    }
}

record FileItem(String name, String url) {}

final class JsonLite {
    private JsonLite() {}

    static String string(String json, String key) {
        Pattern p = Pattern.compile("\\\"" + Pattern.quote(key) + "\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"\\\\])*)\\\"");
        Matcher m = p.matcher(json);
        if (!m.find()) return null;
        return unescape(m.group(1));
    }

    static Integer integer(String json, String key) {
        Pattern p = Pattern.compile("\\\"" + Pattern.quote(key) + "\\\"\\s*:\\s*(-?\\d+)");
        Matcher m = p.matcher(json);
        if (!m.find()) return null;
        try {
            return Integer.parseInt(m.group(1));
        } catch (Exception e) {
            return null;
        }
    }

    static List<FileItem> files(String json) {
        List<FileItem> list = new ArrayList<>();
        Pattern filesPattern = Pattern.compile("\\\"files\\\"\\s*:\\s*\\[(.*?)]", Pattern.DOTALL);
        Matcher filesMatcher = filesPattern.matcher(json);
        if (!filesMatcher.find()) return list;

        String filesBody = filesMatcher.group(1);
        Pattern objPattern = Pattern.compile("\\{(.*?)\\}", Pattern.DOTALL);
        Matcher objMatcher = objPattern.matcher(filesBody);
        while (objMatcher.find()) {
            String obj = objMatcher.group(1);
            String name = firstNonBlank(string("{" + obj + "}", "name"), string("{" + obj + "}", "filename"), string("{" + obj + "}", "file_name"), "input.mp4");
            String url = firstNonBlank(string("{" + obj + "}", "url"), string("{" + obj + "}", "download_url"), string("{" + obj + "}", "file_url"), string("{" + obj + "}", "path"));
            if (url != null) list.add(new FileItem(name, url));
        }
        return list;
    }

    static String toJson(Object obj) {
        if (obj == null) return "null";
        if (obj instanceof String s) return quote(s);
        if (obj instanceof Number || obj instanceof Boolean) return String.valueOf(obj);
        if (obj instanceof Map<?, ?> map) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                sb.append(quote(String.valueOf(e.getKey()))).append(':').append(toJson(e.getValue()));
            }
            return sb.append('}').toString();
        }
        if (obj instanceof Iterable<?> it) {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Object item : it) {
                if (!first) sb.append(',');
                first = false;
                sb.append(toJson(item));
            }
            return sb.append(']').toString();
        }
        return quote(String.valueOf(obj));
    }

    private static String quote(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }

    private static String unescape(String s) {
        if (s == null) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) {
                sb.append(c);
                continue;
            }
            char n = s.charAt(++i);
            switch (n) {
                case '"' -> sb.append('"');
                case '\\' -> sb.append('\\');
                case '/' -> sb.append('/');
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                case 'u' -> {
                    if (i + 4 < s.length()) {
                        String hex = s.substring(i + 1, i + 5);
                        try {
                            sb.append((char) Integer.parseInt(hex, 16));
                            i += 4;
                        } catch (Exception e) {
                            sb.append("\\u").append(hex);
                            i += 4;
                        }
                    } else {
                        sb.append("\\u");
                    }
                }
                default -> sb.append(n);
            }
        }
        return sb.toString();
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String v : values) if (v != null && !v.trim().isEmpty()) return v.trim();
        return null;
    }
}

abstract class BaseHandler implements HttpHandler {
    @Override
    public final void handle(HttpExchange exchange) throws IOException {
        addCors(exchange);
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return;
        }
        try {
            doHandle(exchange);
        } catch (Exception e) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("success", false);
            err.put("message", "HTTP 处理异常：" + e.getMessage());
            err.put("error", e.toString());
            sendJson(exchange, 500, err);
        }
    }

    protected abstract void doHandle(HttpExchange exchange) throws Exception;

    protected String readBody(HttpExchange exchange) throws IOException {
        return new String(readBodyBytes(exchange), StandardCharsets.UTF_8);
    }

    protected byte[] readBodyBytes(HttpExchange exchange) throws IOException {
        try (InputStream is = exchange.getRequestBody()) {
            return is.readAllBytes();
        }
    }

    protected void sendJson(HttpExchange exchange, int status, Object data) throws IOException {
        byte[] bytes = JsonLite.toJson(data).getBytes(StandardCharsets.UTF_8);
        Headers h = exchange.getResponseHeaders();
        h.set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    protected void addCors(HttpExchange exchange) {
        Headers h = exchange.getResponseHeaders();
        h.set("Access-Control-Allow-Origin", "*");
        h.set("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
        h.set("Access-Control-Allow-Headers", "Content-Type,Authorization");
        h.set("Access-Control-Expose-Headers", "Content-Length,Content-Range,Accept-Ranges");
    }
}

class HealthHandler extends BaseHandler {
    private final VideoProcessor processor;

    HealthHandler(VideoProcessor processor) {
        this.processor = processor;
    }

    @Override
    protected void doHandle(HttpExchange exchange) throws Exception {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, Map.of("success", false, "message", "Method Not Allowed"));
            return;
        }
        sendJson(exchange, 200, processor.health());
    }
}

class ProcessHandler extends BaseHandler {
    private final VideoProcessor processor;
    private final String forcedMode;

    ProcessHandler(VideoProcessor processor, String forcedMode) {
        this.processor = processor;
        this.forcedMode = forcedMode;
    }

    @Override
    protected void doHandle(HttpExchange exchange) throws Exception {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, Map.of("success", false, "message", "Method Not Allowed"));
            return;
        }
        String body = readBody(exchange);
        Map<String, Object> result = processor.process(body, forcedMode, exchange);
        Object success = result.get("success");
        sendJson(exchange, Boolean.TRUE.equals(success) ? 200 : 400, result);
    }
}

class UploadHandler extends BaseHandler {
    private final VideoProcessor processor;
    private final Config config;

    UploadHandler(VideoProcessor processor, Config config) {
        this.processor = processor;
        this.config = config;
    }

    @Override
    protected void doHandle(HttpExchange exchange) throws Exception {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, Map.of("success", false, "message", "Method Not Allowed"));
            return;
        }

        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        String boundary = boundaryOf(contentType);
        if (boundary == null) {
            sendJson(exchange, 400, Map.of("success", false, "message", "请使用 multipart/form-data 上传，文件字段名为 video"));
            return;
        }

        MultipartForm form = MultipartForm.parse(readBodyBytes(exchange), boundary, config.inputTmpDir());
        if (form.videoPath() == null) {
            sendJson(exchange, 400, Map.of("success", false, "message", "没有收到视频文件，请用字段名 video 上传"));
            return;
        }

        String json = "{" +
                "\"mode\":" + jsonValue(form.field("mode")) + "," +
                "\"input\":" + jsonValue(form.field("input")) + "," +
                "\"targetFps\":" + jsonNumber(form.field("targetFps")) + "," +
                "\"maxSeconds\":" + jsonNumber(form.field("maxSeconds")) + "," +
                "\"videoPath\":" + jsonValue(form.videoPath().toString()) + "," +
                "\"inputName\":" + jsonValue(form.videoName()) + "," +
                "\"publicBaseUrl\":" + jsonValue(form.field("publicBaseUrl")) +
                "}";

        Map<String, Object> result = processor.process(json, null, exchange);
        Object success = result.get("success");
        sendJson(exchange, Boolean.TRUE.equals(success) ? 200 : 400, result);
    }

    private String boundaryOf(String contentType) {
        if (contentType == null) return null;
        Matcher matcher = Pattern.compile("boundary=(?:\"([^\"]+)\"|([^;]+))", Pattern.CASE_INSENSITIVE).matcher(contentType);
        if (!matcher.find()) return null;
        return firstNonBlank(matcher.group(1), matcher.group(2));
    }

    private String jsonValue(String value) {
        return value == null || value.isBlank() ? "null" : JsonLite.toJson(value);
    }

    private String jsonNumber(String value) {
        if (value == null || value.isBlank()) return "null";
        try {
            return String.valueOf(Integer.parseInt(value.trim()));
        } catch (Exception e) {
            return "null";
        }
    }

    private String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String v : values) if (v != null && !v.trim().isEmpty()) return v.trim();
        return null;
    }
}

record MultipartForm(Map<String, String> fields, Path videoPath, String videoName) {
    String field(String name) {
        return fields.get(name);
    }

    static MultipartForm parse(byte[] body, String boundary, Path inputTmpDir) throws IOException {
        Map<String, String> fields = new LinkedHashMap<>();
        Path videoPath = null;
        String videoName = null;
        byte[] marker = ("--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
        byte[] headerEndMarker = "\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);

        int pos = indexOf(body, marker, 0);
        while (pos >= 0) {
            int partStart = pos + marker.length;
            if (partStart + 1 < body.length && body[partStart] == '-' && body[partStart + 1] == '-') break;
            if (partStart + 1 < body.length && body[partStart] == '\r' && body[partStart + 1] == '\n') partStart += 2;

            int headersEnd = indexOf(body, headerEndMarker, partStart);
            if (headersEnd < 0) break;
            String headers = new String(body, partStart, headersEnd - partStart, StandardCharsets.ISO_8859_1);
            int dataStart = headersEnd + headerEndMarker.length;
            int next = indexOf(body, marker, dataStart);
            if (next < 0) break;
            int dataEnd = next;
            if (dataEnd >= dataStart + 2 && body[dataEnd - 2] == '\r' && body[dataEnd - 1] == '\n') dataEnd -= 2;

            String name = dispositionValue(headers, "name");
            String filename = dispositionValue(headers, "filename");
            if (name != null && filename != null && !filename.isBlank()) {
                String safeName = safeFilename(filename);
                String ext = extensionOf(safeName);
                if (ext.isBlank()) ext = ".mp4";
                Path target = Files.createTempFile(inputTmpDir, "upload_", ext);
                Files.write(target, Arrays.copyOfRange(body, dataStart, dataEnd));
                if ("video".equals(name) || videoPath == null) {
                    videoPath = target;
                    videoName = safeName;
                }
            } else if (name != null) {
                fields.put(name, new String(body, dataStart, dataEnd - dataStart, StandardCharsets.UTF_8).trim());
            }
            pos = next;
        }
        return new MultipartForm(fields, videoPath, videoName);
    }

    private static String dispositionValue(String headers, String key) {
        Matcher matcher = Pattern.compile(key + "=\"([^\"]*)\"").matcher(headers);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String safeFilename(String filename) {
        String normalized = filename.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        String name = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        name = name.replaceAll("[^A-Za-z0-9._-]", "_");
        return name.isBlank() ? "input.mp4" : name;
    }

    private static String extensionOf(String filename) {
        int idx = filename.lastIndexOf('.');
        if (idx < 0 || idx == filename.length() - 1) return "";
        return filename.substring(idx);
    }

    private static int indexOf(byte[] haystack, byte[] needle, int start) {
        outer:
        for (int i = Math.max(0, start); i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }
}

class FileHandler extends BaseHandler {
    private final Path root;

    FileHandler(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    protected void doHandle(HttpExchange exchange) throws Exception {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod()) && !"HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, Map.of("success", false, "message", "Method Not Allowed"));
            return;
        }
        String prefix = exchange.getHttpContext().getPath();
        String rawPath = exchange.getRequestURI().getPath();
        String name = rawPath.length() >= prefix.length() ? rawPath.substring(prefix.length()) : "";
        name = URLDecoder.decode(name, StandardCharsets.UTF_8);
        name = Path.of(name).getFileName().toString();

        Path file = root.resolve(name).normalize();
        if (!file.startsWith(root) || !Files.exists(file) || !Files.isRegularFile(file)) {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
        }
        sendVideoFile(exchange, file);
    }

    private void sendVideoFile(HttpExchange exchange, Path file) throws IOException {
        long fileSize = Files.size(file);
        String range = exchange.getRequestHeaders().getFirst("Range");
        Headers h = exchange.getResponseHeaders();
        h.set("Content-Type", "video/mp4");
        h.set("Accept-Ranges", "bytes");
        h.set("Content-Disposition", "inline; filename=\"" + file.getFileName() + "\"");

        boolean head = "HEAD".equalsIgnoreCase(exchange.getRequestMethod());
        if (range != null && range.startsWith("bytes=")) {
            long[] se = parseRange(range, fileSize);
            long start = se[0];
            long end = se[1];
            long len = end - start + 1;
            h.set("Content-Range", "bytes " + start + "-" + end + "/" + fileSize);
            h.set("Content-Length", String.valueOf(len));
            exchange.sendResponseHeaders(206, head ? -1 : len);
            if (!head) {
                try (OutputStream os = exchange.getResponseBody(); InputStream is = Files.newInputStream(file)) {
                    is.skipNBytes(start);
                    copyLimited(is, os, len);
                }
            } else {
                exchange.close();
            }
            return;
        }

        h.set("Content-Length", String.valueOf(fileSize));
        exchange.sendResponseHeaders(200, head ? -1 : fileSize);
        if (!head) {
            try (OutputStream os = exchange.getResponseBody(); InputStream is = Files.newInputStream(file)) {
                is.transferTo(os);
            }
        } else {
            exchange.close();
        }
    }

    private long[] parseRange(String range, long fileSize) {
        String r = range.substring("bytes=".length()).trim();
        int dash = r.indexOf('-');
        if (dash < 0) return new long[]{0, fileSize - 1};
        String startStr = r.substring(0, dash).trim();
        String endStr = r.substring(dash + 1).trim();
        long start = startStr.isBlank() ? 0 : Long.parseLong(startStr);
        long end = endStr.isBlank() ? fileSize - 1 : Long.parseLong(endStr);
        start = Math.max(0, Math.min(start, fileSize - 1));
        end = Math.max(start, Math.min(end, fileSize - 1));
        return new long[]{start, end};
    }

    private void copyLimited(InputStream is, OutputStream os, long len) throws IOException {
        byte[] buf = new byte[8192];
        long remaining = len;
        while (remaining > 0) {
            int n = is.read(buf, 0, (int) Math.min(buf.length, remaining));
            if (n < 0) break;
            os.write(buf, 0, n);
            remaining -= n;
        }
    }
}
