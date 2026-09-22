package com.doctool.service;

import com.doctool.mapper.AnalysisCacheMapper;
import com.doctool.mapper.TaskRecordMapper;
import com.doctool.model.AnalysisCache;
import com.doctool.model.ImageInfo;
import com.doctool.model.TaskRecord;
import com.doctool.model.VideoInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class VideoService {

    private static final Logger log = LoggerFactory.getLogger(VideoService.class);

    private final TaskRecordMapper taskRecordMapper;
    private final AnalysisCacheMapper analysisCacheMapper;
    private final OllamaService ollamaService;

    public VideoService(TaskRecordMapper taskRecordMapper, AnalysisCacheMapper analysisCacheMapper, OllamaService ollamaService) {
        this.taskRecordMapper = taskRecordMapper;
        this.analysisCacheMapper = analysisCacheMapper;
        this.ollamaService = ollamaService;
    }

    @Value("${ffmpeg.path:ffmpeg}")
    private String ffmpegPath;

    private static final Set<String> VIDEO_EXTS = Set.of(
        "mp4", "avi", "webm", "mov", "mkv", "flv", "wmv", "m4v", "3gp", "ts"
    );

    private static final Set<String> IMAGE_EXTS = Set.of(
        "jpg", "jpeg", "png", "gif", "bmp", "webp", "tiff", "tif", "svg"
    );

    private final ConcurrentHashMap<Long, byte[]> resultCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, String> resultFilenameCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, String> summaryCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Integer> progressCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, List<Map<String, String>>> partialFramesCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Integer> totalFramesCache = new ConcurrentHashMap<>();

    public TaskRecord createRecord(String originalFilename, String type) {
        TaskRecord record = new TaskRecord();
        record.setTaskType(type);
        record.setOriginalFilename(originalFilename);
        record.setStatus("SUCCESS");
        record.setCreateTime(LocalDateTime.now());
        record.setFinishTime(LocalDateTime.now());
        taskRecordMapper.insert(record);
        return record;
    }

        // [损坏数据已剥离：原为编码爆炸的乱码行]

    /**
        // [损坏数据已剥离：原为编码爆炸的乱码行]
     */
    public String computeVideoFingerprint(long fileSize, String duration, String resolution,
                                           String videoCodec, String frameRate) {
        String raw = fileSize + "|" + nvl(duration) + "|" + nvl(resolution)
            + "|" + nvl(videoCodec) + "|" + nvl(frameRate);
        return sha256(raw);
    }

    /**
     * 内容指纹（推荐）：基于文件内容哈希 + 模型 + 缓存版本。
     * 旧指纹只含元数据（大小/时长/分辨率），两个不同视频会撞车返回错误缓存，
     * 且改提示词/换模型后旧缓存永远不失效——务必使用本方法。
     */
    public String computeVideoFingerprint(byte[] content, String modelName) {
        String raw = CACHE_VERSION + "|" + nvl(modelName) + "|" + sha256Bytes(content)
            + "|" + content.length;
        return sha256(raw);
    }

    /** 缓存版本：提示词或指纹算法变更后递增，自动作废旧缓存 */
    private static final String CACHE_VERSION = "v2";

    private String sha256Bytes(byte[] data) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(java.util.Arrays.hashCode(data));
        }
    }

    /**
     * 计算图片指纹：基于文件大小、分辨率、格式
     */
    public String computeImageFingerprint(long fileSize, String resolution, String format) {
        String raw = fileSize + "|" + nvl(resolution) + "|" + nvl(format);
        return sha256(raw);
    }

    /**
    /**
     */
    public String getCachedResult(String fingerprint, String analysisType) {
        AnalysisCache cached = analysisCacheMapper.findByFingerprintAndType(fingerprint, analysisType);
        if (cached == null || cached.getResultData() == null) return null;
        String data = cached.getResultData();
        // 只校验 JSON 格式的缓存（VIDEO_RECOGNIZE/VIDEO_SUMMARY），纯文本跳过
        if (isJsonType(data) && !isValidJson(data)) {
            log.warn("检测到损坏缓存，自动清除: fingerprint={}, type={}", fingerprint, analysisType);
            analysisCacheMapper.deleteByFingerprintAndType(fingerprint, analysisType);
            return null;
        }
        return data;
    }

    private boolean isJsonType(String str) {
        if (str == null) return false;
        str = str.trim();
        return str.startsWith("{") || str.startsWith("[");
    }

    private boolean isValidJson(String str) {
        if (str == null || str.isBlank()) return false;
        str = str.trim();
        if (!(str.startsWith("{") || str.startsWith("["))) return false;
        try {
            new com.fasterxml.jackson.databind.ObjectMapper().readTree(str);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
    public void saveCachedResult(String fingerprint, String analysisType, String resultData) {
     */
    public void saveCachedResult(String fingerprint, String analysisType, String resultData) {
        AnalysisCache cache = new AnalysisCache();
        cache.setFingerprint(fingerprint);
        cache.setAnalysisType(analysisType);
        cache.setResultData(resultData);
        cache.setCreateTime(LocalDateTime.now());
        analysisCacheMapper.insert(cache);
        // [损坏数据已剥离：原为编码爆炸的乱码行]
    }

    /**
     * 更新缓存中的某帧描述（重新识别后调用）
     */
    public void updateCachedFrameDescription(String fingerprint, int frameIndex, String newDescription) {
        // 同时更新 VIDEO_RECOGNIZE 和 VIDEO_SUMMARY 两个缓存
        for (String type : List.of("VIDEO_RECOGNIZE", "VIDEO_SUMMARY")) {
            AnalysisCache cached = analysisCacheMapper.findByFingerprintAndType(fingerprint, type);
            if (cached == null || cached.getResultData() == null) continue;
            try {
                // 解析 JSON 并更新指定帧的描述
                String json = cached.getResultData();
                // 简单字符串替换：找到第 frameIndex 个 "description":"..." 并替换
                String updated = replaceFrameDescription(json, frameIndex, newDescription);
                if (!updated.equals(json)) {
                    cached.setResultData(updated);
                    analysisCacheMapper.updateResultData(cached);
                    log.info("已更新缓存描述: fingerprint={}, type={}, frame={}", fingerprint, type, frameIndex);
                }
            } catch (Exception e) {
                log.warn("更新缓存描述失败: type={}, error={}", type, e.getMessage());
            }
        }
    }
    private String replaceFrameDescription(String json, int frameIndex, String newDesc) {
        // 找到 "frames":[ 后的第 frameIndex 个 "description":"..." 进行替换
        String marker = "\"description\":\"";
        int searchFrom = 0;
        for (int i = 0; i <= frameIndex; i++) {
            int idx = json.indexOf(marker, searchFrom);
            if (idx < 0) return json;
            if (i == frameIndex) {
                int valStart = idx + marker.length();
                int valEnd = json.indexOf("\"", valStart);
                if (valEnd < 0) return json;
                String escaped = newDesc.replace("\\", "\\\\").replace("\"", "\\\"");
                return json.substring(0, valStart) + escaped + json.substring(valEnd);
            }
            searchFrom = idx + marker.length();
        }
        return json;
    }

    private String nvl(String s) { return s == null ? "" : s; }

    private String sha256(String input) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(input.hashCode());
        }
    }

    /**
     * 视频格式转换
     */
    public byte[] convert(MultipartFile file, String targetFormat) throws Exception {
        String originalName = file.getOriginalFilename();
        if (originalName == null || !isVideoFile(originalName)) {
            throw new IllegalArgumentException("不支持的视频格式");
        }

        Path workDir = Files.createTempDirectory("video_convert_");
        try {
            String ext = getExtension(originalName).toLowerCase();
            Path srcFile = workDir.resolve(UUID.randomUUID() + "." + ext);
            file.transferTo(srcFile.toFile());

            String baseName = originalName.contains(".")
                ? originalName.substring(0, originalName.lastIndexOf('.'))
                : originalName;
            Path destFile = workDir.resolve(baseName + "." + targetFormat);

            convertWithFfmpeg(srcFile, destFile, targetFormat);
            return Files.readAllBytes(destFile);
        } finally {
            deleteDir(workDir);
        }
    }

    /**
     * 提取视频信息（FFprobe）
     */
    public VideoInfo getInfo(MultipartFile file) throws Exception {
        String originalName = file.getOriginalFilename();
        if (originalName == null || !isVideoFile(originalName)) {
            throw new IllegalArgumentException("不支持的视频格式");
        }

        Path workDir = Files.createTempDirectory("video_info_");
        try {
            String ext = getExtension(originalName).toLowerCase();
            Path srcFile = workDir.resolve(UUID.randomUUID() + "." + ext);
            file.transferTo(srcFile.toFile());

            return probeVideoInfo(srcFile, originalName);
        } finally {
            deleteDir(workDir);
        }
    }

    /**
        // [损坏数据已剥离：原为编码爆炸的乱码行]
     */
    public List<byte[]> extractFrames(MultipartFile file, int intervalSeconds) throws Exception {
        String originalName = file.getOriginalFilename();
        if (originalName == null || !isVideoFile(originalName)) {
        // [损坏数据已剥离：原为编码爆炸的乱码行]
        }

        Path workDir = Files.createTempDirectory("video_frames_");
        try {
            String ext = getExtension(originalName).toLowerCase();
            Path srcFile = workDir.resolve(UUID.randomUUID() + "." + ext);
            file.transferTo(srcFile.toFile());

            Path framesDir = workDir.resolve("frames");
            Files.createDirectories(framesDir);

        // [损坏数据已剥离：原为编码爆炸的乱码行]
            List<String> cmd = new ArrayList<>();
            cmd.add(ffmpegPath);
            cmd.add("-i");
            cmd.add(srcFile.toAbsolutePath().toString());
            cmd.add("-vf");
            cmd.add("fps=1/" + intervalSeconds);
            cmd.add("-q:v");
            cmd.add("3");
            cmd.add("-frames:v");
            cmd.add("100");
            cmd.add(framesDir.resolve("frame_%04d.jpg").toAbsolutePath().toString());

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            drainStream(proc.getInputStream());
            int exitCode = proc.waitFor();
            if (exitCode != 0) {
                throw new RuntimeException("FFmpeg 抽帧失败，退出码: " + exitCode);
            }

        // [损坏数据已剥离：原为编码爆炸的乱码行]
            List<byte[]> frames = new ArrayList<>();
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(framesDir, "*.jpg")) {
                for (Path frame : ds) {
                    frames.add(Files.readAllBytes(frame));
                }
            }
            return frames;
        } finally {
            deleteDir(workDir);
        }
    }

    /**
        // [损坏数据已剥离：原为编码爆炸的乱码行]
     */
    public TaskRecord submitRecognize(MultipartFile file, int intervalSeconds, String fingerprint, String metadataFingerprint) throws Exception {
        return submitAnalyze(file, intervalSeconds, "VIDEO_RECOGNIZE", false, fingerprint, metadataFingerprint);
    }

    /**
        // [损坏数据已剥离：原为编码爆炸的乱码行]
     */
    public TaskRecord submitSummary(MultipartFile file, int intervalSeconds, String fingerprint, String metadataFingerprint) throws Exception {
        return submitAnalyze(file, intervalSeconds, "VIDEO_SUMMARY", true, fingerprint, metadataFingerprint);
    }

    private TaskRecord submitAnalyze(MultipartFile file, int intervalSeconds, String taskType,
                                     boolean withSummary, String fingerprint, String metadataFingerprint) throws Exception {
        String originalName = file.getOriginalFilename();
        TaskRecord record = new TaskRecord();
        record.setTaskType(taskType);
        record.setOriginalFilename(originalName);
        record.setStatus("PROCESSING");
        record.setCreateTime(LocalDateTime.now());
        taskRecordMapper.insert(record);

        Path tempFile = Files.createTempFile("video_recog_", "_" + originalName);
        file.transferTo(tempFile.toFile());

        new Thread(() -> processRecognize(record.getId(), tempFile, originalName, intervalSeconds, withSummary, fingerprint, metadataFingerprint),
            "video-recog-" + record.getId()).start();
        return record;
    }

    private void processRecognize(long taskId, Path tempFile, String originalName, int intervalSeconds,
                                  boolean withSummary, String fingerprint, String metadataFingerprint) {
        try {
            // 抽帧
            Path workDir = Files.createTempDirectory("video_recog_work_");
            Path framesDir = workDir.resolve("frames");
            Files.createDirectories(framesDir);

            List<String> cmd = new ArrayList<>();
            cmd.add(ffmpegPath);
            cmd.add("-i");
            cmd.add(tempFile.toAbsolutePath().toString());
            cmd.add("-vf");
            cmd.add("fps=1/" + intervalSeconds);
            cmd.add("-q:v");
            cmd.add("3");
            cmd.add("-frames:v");
            cmd.add("60");
            cmd.add(framesDir.resolve("frame_%04d.jpg").toAbsolutePath().toString());

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            drainStream(proc.getInputStream());
            int exitCode = proc.waitFor();

            if (exitCode != 0) {
                throw new RuntimeException("FFmpeg 抽帧失败");
            }

            // 读取帧图片
            List<byte[]> frames = new ArrayList<>();
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(framesDir, "*.jpg")) {
                for (Path frame : ds) {
                    frames.add(Files.readAllBytes(frame));
                }
            }

            log.info("视频识别任务 {}: 抽帧完成，共 {} 帧，开始 AI 分析...", taskId, frames.size());
            progressCache.put(taskId, 10);
            int totalFrames = frames.size();
            totalFramesCache.put(taskId, totalFrames);
            partialFramesCache.put(taskId, new java.util.concurrent.CopyOnWriteArrayList<>());

            // 逐帧分析
            for (int i = 0; i < totalFrames; i++) {
                int timeSec = i * intervalSeconds;
                String timeStr = String.format("%02d:%02d", timeSec / 60, timeSec % 60);

                int progressBase = withSummary ? 80 : 90;
                int progress = 10 + (int) ((i + 1) * progressBase / (double) totalFrames);
                progressCache.put(taskId, progress);

                String prompt = ollamaService.getFramePrompt();

                String description;
                try {
                    description = ollamaService.describeImage(frames.get(i), prompt);
                    log.info("任务 {} 第 {}/{} 帧 ({}) 识别完成，进度 {}%", taskId, i + 1, totalFrames, timeStr, progress);
                } catch (Exception e) {
                    log.warn("任务 {} 第 {} 帧识别失败: {}", taskId, i + 1, e.getMessage());
                    description = "识别失败: " + e.getMessage();
                }

                Map<String, String> frame = new java.util.LinkedHashMap<>();
                frame.put("time", timeStr);
                frame.put("image", Base64.getEncoder().encodeToString(frames.get(i)));
                frame.put("description", description.replace("\n", " ").replace("\\n", " ").replaceAll("\\s+", " ").trim());
                partialFramesCache.get(taskId).add(frame);
            }

            // 构建最终结果
            List<Map<String, String>> allFrames = partialFramesCache.get(taskId);
            StringBuilder framesJson = new StringBuilder("[");
            for (int i = 0; i < allFrames.size(); i++) {
                if (i > 0) framesJson.append(",");
                Map<String, String> f = allFrames.get(i);
                framesJson.append("{\"time\":\"").append(f.get("time")).append("\",");
                framesJson.append("\"image\":\"").append(f.get("image")).append("\",");
                framesJson.append("\"description\":\"").append(f.get("description").replace("\\", "\\\\").replace("\"", "\\\"")).append("\"}");
            }
            framesJson.append("]");

            String summaryJson = "";
            if (withSummary) {
                progressCache.put(taskId, 95);
                log.info("视频摘要任务 {}: 帧识别完成，开始汇总成段...", taskId);
                StringBuilder allDescriptions = new StringBuilder();
                String lastIncluded = null;
                for (Map<String, String> f : allFrames) {
                    String d = f.get("description");
                    if (lastIncluded != null && textSimilarity(d, lastIncluded) > 0.8) continue;
                    lastIncluded = d;
                    allDescriptions.append("[").append(f.get("time")).append("] ")
                        .append(d).append("\n");
                }
                try {
                    String summary = ollamaService.summarizeDescriptions(allDescriptions.toString());
                    summaryCache.put(taskId, summary);
                    summaryJson = "\"summary\":\"" + summary
                        .replace("\\", "\\\\").replace("\"", "\\\"")
                        .replace("\n", " ").replace("\r", " ") + "\",";
                } catch (Exception e) {
                    log.warn("任务 {} 摘要汇总失败: {}", taskId, e.getMessage());
                }
            }

            String result = "{" + summaryJson + "\"frames\":" + framesJson + "}";
            resultCache.put(taskId, result.getBytes());
            resultFilenameCache.put(taskId, "recognize_result.json");
            progressCache.put(taskId, 100);

            if (fingerprint != null && !fingerprint.isEmpty()) {
                String cacheType = withSummary ? "VIDEO_SUMMARY" : "VIDEO_RECOGNIZE";
                saveCachedResult(fingerprint, cacheType, result);
                saveCachedResult(metadataFingerprint, cacheType, result);
                // 识别结果也同时存一份到另一种类型，确保预览回显
                String otherType = withSummary ? "VIDEO_RECOGNIZE" : "VIDEO_SUMMARY";
                saveCachedResult(fingerprint, otherType, result);
                saveCachedResult(metadataFingerprint, otherType, result);
            }

            TaskRecord record = taskRecordMapper.findById(taskId);
            if (record != null) {
                record.setStatus("SUCCESS");
                record.setResultFilename("recognize_result.json");
                record.setFinishTime(LocalDateTime.now());
                taskRecordMapper.update(record);
            }
        } catch (Exception e) {
            log.error("视频识别任务失败: taskId={}", taskId, e);
            progressCache.put(taskId, -1);
            TaskRecord record = taskRecordMapper.findById(taskId);
            if (record != null) {
                record.setStatus("FAILED");
                record.setErrorMessage(e.getMessage());
                record.setFinishTime(LocalDateTime.now());
                taskRecordMapper.update(record);
            }
        } finally {
            try { Files.deleteIfExists(tempFile); } catch (IOException ignored) {}
        }
    }

    public TaskRecord getTask(Long id) {
        return taskRecordMapper.findById(id);
    }

    public byte[] getResultBytes(Long id) {
        return resultCache.remove(id);
    }

    public int getProgress(Long id) {
        return progressCache.getOrDefault(id, 0);
    }

    public List<Map<String, String>> getPartialFrames(Long id) {
        return partialFramesCache.getOrDefault(id, List.of());
    }

    public int getTotalFrames(Long id) {
        return totalFramesCache.getOrDefault(id, 0);
    }

    /**
     * 提取图片信息（尺寸、格式、大小）
     */
    public ImageInfo getImageInfo(MultipartFile file) throws Exception {
        String originalName = file.getOriginalFilename();
        if (originalName == null || !isImageFile(originalName)) {
            throw new IllegalArgumentException("不支持的图片格式");
        }

        byte[] imageBytes = file.getBytes();
        long fileSizeBytes = imageBytes.length;

        ImageInfo info = new ImageInfo();
        info.setFilename(originalName);

        // [损坏数据已剥离：原为编码爆炸的乱码行]
        if (fileSizeBytes > 1024 * 1024) {
            info.setFileSize(String.format("%.2f MB", fileSizeBytes / (1024.0 * 1024)));
        } else {
            info.setFileSize(String.format("%.2f KB", fileSizeBytes / 1024.0));
        }

        // 读取图片尺寸
        String ext = getExtension(originalName).toLowerCase();
        info.setFormat(ext.toUpperCase());

        // [损坏数据已剥离：原为编码爆炸的乱码行]
        try (java.io.ByteArrayInputStream bais = new java.io.ByteArrayInputStream(imageBytes)) {
            javax.imageio.ImageReader reader = javax.imageio.ImageIO.getImageReadersByFormatName(ext).next();
            try {
                javax.imageio.stream.ImageInputStream iis = javax.imageio.ImageIO.createImageInputStream(bais);
                reader.setInput(iis);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                info.setResolution(width + " x " + height);
            } finally {
                reader.dispose();
            }
        } catch (Exception e) {
        // [损坏数据已剥离：原为编码爆炸的乱码行]
        }

        return info;
    }

    public boolean isImageFile(String filename) {
        String ext = getExtension(filename).toLowerCase();
        return IMAGE_EXTS.contains(ext);
    }

    private VideoInfo probeVideoInfo(Path videoFile, String originalName) throws Exception {
        // 使用 ffmpeg -i 获取视频信息（输出到 stderr）
        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpegPath);
        cmd.add("-i");
        cmd.add(videoFile.toAbsolutePath().toString());

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process proc = pb.start();

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }
        proc.waitFor();

        // [损坏数据已剥离：原为编码爆炸的乱码行]
        long fileSizeBytes = Files.size(videoFile);

        return parseFfmpegOutput(output.toString(), originalName, fileSizeBytes);
    }

    private VideoInfo parseFfmpegOutput(String output, String originalName, long fileSizeBytes) {
        VideoInfo info = new VideoInfo();
        info.setFilename(originalName);

        // [损坏数据已剥离：原为编码爆炸的乱码行]
        if (fileSizeBytes > 1024 * 1024 * 1024) {
            info.setFileSize(String.format("%.2f GB", fileSizeBytes / (1024.0 * 1024 * 1024)));
        } else if (fileSizeBytes > 1024 * 1024) {
            info.setFileSize(String.format("%.2f MB", fileSizeBytes / (1024.0 * 1024)));
        } else {
            info.setFileSize(String.format("%.2f KB", fileSizeBytes / 1024.0));
        }

        // Duration: 00:05:30.12
        java.util.regex.Matcher durMatcher = java.util.regex.Pattern.compile("Duration:\\s*(\\d{2}:\\d{2}:\\d{2})").matcher(output);
        if (durMatcher.find()) {
            info.setDuration(durMatcher.group(1));
        }

        // [损坏数据已剥离：原为编码爆炸的乱码行]
        java.util.regex.Matcher fmtMatcher = java.util.regex.Pattern.compile("Input #0,\\s*(\\S+)").matcher(output);
        if (fmtMatcher.find()) {
            info.setFormat(fmtMatcher.group(1).replace(",", ""));
        }

        // [损坏数据已剥离：原为编码爆炸的乱码行]
        java.util.regex.Matcher brMatcher = java.util.regex.Pattern.compile("bitrate:\\s*(\\d+)\\s*kb/s").matcher(output);
        if (brMatcher.find()) {
            info.setVideoBitrate(brMatcher.group(1) + " kbps");
        }

        // [损坏数据已剥离：原为编码爆炸的乱码行]
        java.util.regex.Matcher videoMatcher = java.util.regex.Pattern.compile(
            "Video:\\s*(\\w+).*?(\\d{3,5})x(\\d{3,5}).*?(\\d+)\\s*kb/s.*?(\\d+(?:\\.\\d+)?)\\s*fps").matcher(output);
        if (videoMatcher.find()) {
            info.setVideoCodec(videoMatcher.group(1));
            info.setResolution(videoMatcher.group(2) + " x " + videoMatcher.group(3));
        }
        // 如果上面没匹配到分辨率，尝试简单匹配
        if (info.getResolution() == null) {
            java.util.regex.Matcher resMatcher = java.util.regex.Pattern.compile("(\\d{3,5})x(\\d{3,5})").matcher(output);
            if (resMatcher.find()) {
                info.setResolution(resMatcher.group(1) + " x " + resMatcher.group(2));
            }
        }

        // 帧率
        if (info.getFrameRate() == null) {
            java.util.regex.Matcher fpsMatcher = java.util.regex.Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*fps").matcher(output);
            if (fpsMatcher.find()) {
                info.setFrameRate(fpsMatcher.group(1) + " fps");
            }
        }

        // [损坏数据已剥离：原为编码爆炸的乱码行]
        if (info.getVideoCodec() == null) {
            java.util.regex.Matcher codecMatcher = java.util.regex.Pattern.compile("Video:\\s*(\\w+)").matcher(output);
            if (codecMatcher.find()) {
                info.setVideoCodec(codecMatcher.group(1));
            }
        }

        // [损坏数据已剥离：原为编码爆炸的乱码行]
        java.util.regex.Matcher audioMatcher = java.util.regex.Pattern.compile(
            "Audio:\\s*(\\w+).*?(\\d+)\\s*Hz.*?(\\d+)\\s*kb/s").matcher(output);
        if (audioMatcher.find()) {
            info.setAudioCodec(audioMatcher.group(1));
            info.setAudioBitrate(audioMatcher.group(3) + " kbps");
        } else {
            // 简单匹配音频编码
            java.util.regex.Matcher audioCodecMatcher = java.util.regex.Pattern.compile("Audio:\\s*(\\w+)").matcher(output);
            if (audioCodecMatcher.find()) {
                info.setAudioCodec(audioCodecMatcher.group(1));
            }
        }

        return info;
    }

    private void convertWithFfmpeg(Path src, Path dest, String format) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpegPath);
        cmd.add("-y");
        cmd.add("-i");
        cmd.add(src.toAbsolutePath().toString());
        cmd.add("-vf");
        cmd.add("scale=trunc(iw/2)*2:trunc(ih/2)*2");

        switch (format) {
            case "webm" -> {
                cmd.add("-c:v"); cmd.add("libvpx-vp9");
                cmd.add("-b:v"); cmd.add("2M");
                cmd.add("-c:a"); cmd.add("libopus");
            }
            case "avi" -> {
                cmd.add("-c:v"); cmd.add("mpeg4");
                cmd.add("-q:v"); cmd.add("4");
                cmd.add("-c:a"); cmd.add("mp3");
            }
            case "mov" -> {
                cmd.add("-c:v"); cmd.add("libx264");
                cmd.add("-preset"); cmd.add("medium");
                cmd.add("-crf"); cmd.add("23");
                cmd.add("-c:a"); cmd.add("aac");
            }
            case "mkv" -> {
                cmd.add("-c:v"); cmd.add("libx264");
                cmd.add("-preset"); cmd.add("medium");
                cmd.add("-crf"); cmd.add("23");
                cmd.add("-c:a"); cmd.add("aac");
            }
            case "gif" -> {
                cmd.remove(cmd.size() - 1); // remove scale filter
                cmd.add("-vf");
                cmd.add("fps=15,scale=480:-1:flags=lanczos");
                cmd.add("-loop"); cmd.add("0");
            }
            default -> {
                // MP4: H.264 + AAC
                cmd.add("-c:v"); cmd.add("libx264");
                cmd.add("-preset"); cmd.add("medium");
                cmd.add("-crf"); cmd.add("23");
                cmd.add("-pix_fmt"); cmd.add("yuv420p");
                cmd.add("-c:a"); cmd.add("aac");
                cmd.add("-movflags"); cmd.add("+faststart");
            }
        }

        cmd.add(dest.toAbsolutePath().toString());

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        String output = drainStream(proc.getInputStream());
        int exitCode = proc.waitFor();
        if (exitCode != 0) {
        // [损坏数据已剥离：原为编码爆炸的乱码行]
        }
    }

    private boolean isVideoFile(String filename) {
        String ext = getExtension(filename).toLowerCase();
        return VIDEO_EXTS.contains(ext);
    }

    private String getExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot + 1) : "";
    }

    private String drainStream(InputStream is) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
        }
        return sb.toString();
    }

    /**
        // [损坏数据已剥离：原为编码爆炸的乱码行]
     */
    private double textSimilarity(String a, String b) {
        if (a == null || b == null) return 0;
        Set<String> wordsA = new HashSet<>(List.of(a.split("\\s+")));
        Set<String> wordsB = new HashSet<>(List.of(b.split("\\s+")));
        if (wordsA.isEmpty() || wordsB.isEmpty()) return 0;
        Set<String> intersection = new HashSet<>(wordsA);
        intersection.retainAll(wordsB);
        double unionSize = wordsA.size() + wordsB.size() - intersection.size();
        return unionSize > 0 ? intersection.size() / unionSize : 0;
    }

    private void deleteDir(Path dir) {
        try {
            Files.walk(dir)
                .sorted(Comparator.reverseOrder())
                .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}
    }
}
