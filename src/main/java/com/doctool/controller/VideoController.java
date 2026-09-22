package com.doctool.controller;

import com.doctool.mapper.TaskRecordMapper;
import com.doctool.mapper.UserPreferenceMapper;
import com.doctool.model.ImageInfo;
import com.doctool.model.TaskRecord;
import com.doctool.model.UserPreference;
import com.doctool.model.VideoInfo;
import com.doctool.service.OllamaService;
import com.doctool.service.VideoService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/video")
@RequiredArgsConstructor
public class VideoController {

    private final VideoService videoService;
    private final OllamaService ollamaService;
    private final TaskRecordMapper taskRecordMapper;
    private final UserPreferenceMapper userPreferenceMapper;

    /**
     * 视频格式转换
     */
    @PostMapping("/convert")
    public ResponseEntity<?> convert(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "format", defaultValue = "mp4") String format) {
        try {
            if (file.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("status", "FAILED", "errorMessage", "请选择视频文件"));
            }

            byte[] result = videoService.convert(file, format);

            // 记录
            videoService.createRecord(file.getOriginalFilename(), "VIDEO_CONVERT");

            String baseName = getBaseName(file.getOriginalFilename());
            String fileName = baseName + "_converted." + format;
            String encodedName = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");

            String contentType = switch (format) {
                case "webm" -> "video/webm";
                case "avi" -> "video/x-msvideo";
                case "mov" -> "video/quicktime";
                case "mkv" -> "video/x-matroska";
                case "gif" -> "image/gif";
                default -> "video/mp4";
            };

            return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encodedName)
                .contentType(MediaType.parseMediaType(contentType))
                .body(result);

        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                .body(Map.of("status", "FAILED", "errorMessage", e.getMessage()));
        }
    }

    /**
     * 视频信息提取
     */
    @PostMapping("/info")
    public ResponseEntity<?> info(@RequestParam("file") MultipartFile file) {
        try {
            if (file.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("status", "FAILED", "errorMessage", "请选择视频文件"));
            }

            VideoInfo videoInfo = videoService.getInfo(file);
            videoService.createRecord(file.getOriginalFilename(), "VIDEO_INFO");

            return ResponseEntity.ok(Map.of("status", "SUCCESS", "data", videoInfo));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                .body(Map.of("status", "FAILED", "errorMessage", e.getMessage()));
        }
    }

    /**
     * 查询视频分析缓存（预览用）
     */
    @GetMapping("/cache/check")
    public Map<String, Object> cacheCheck(
            @RequestParam(value = "fileSize", defaultValue = "0") long fileSize,
            @RequestParam(value = "duration", required = false) String duration,
            @RequestParam(value = "resolution", required = false) String resolution,
            @RequestParam(value = "videoCodec", required = false) String videoCodec,
            @RequestParam(value = "frameRate", required = false) String frameRate) {
        String fingerprint = videoService.computeVideoFingerprint(fileSize, duration, resolution, videoCodec, frameRate);
        String recognize = videoService.getCachedResult(fingerprint, "VIDEO_RECOGNIZE");
        String summary = videoService.getCachedResult(fingerprint, "VIDEO_SUMMARY");
        Map<String, Object> result = new HashMap<>();
        result.put("status", "SUCCESS");
        result.put("fingerprint", fingerprint);
        if (recognize != null) result.put("recognize", recognize);
        if (summary != null) result.put("summary", summary);
        return result;
    }

    /**
     * 提交视频画面识别任务（异步），支持缓存
     */
    @PostMapping("/recognize")
    public Object recognize(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "interval", defaultValue = "5") int interval,
            @RequestParam(value = "fileSize", required = false, defaultValue = "0") long fileSize,
            @RequestParam(value = "duration", required = false) String duration,
            @RequestParam(value = "resolution", required = false) String resolution,
            @RequestParam(value = "videoCodec", required = false) String videoCodec,
            @RequestParam(value = "frameRate", required = false) String frameRate,
            @RequestParam(value = "force", required = false, defaultValue = "false") boolean force) {
        try {
            if (file.isEmpty()) {
                return Map.of("status", "FAILED", "errorMessage", "请选择视频文件");
            }

            // 内容指纹（文件字节哈希+模型+版本）：force=true 时跳过缓存
            String fingerprint = videoService.computeVideoFingerprint(file.getBytes(), ollamaService.getOllamaModelName());
            // 元数据指纹：用于预览回显
            String metadataFingerprint = videoService.computeVideoFingerprint(fileSize, duration, resolution, videoCodec, frameRate);
            if (!force) {
                String cached = videoService.getCachedResult(fingerprint, "VIDEO_RECOGNIZE");
                if (cached != null) {
                    return Map.of("status", "CACHED", "data", cached, "fingerprint", fingerprint);
                }
            }

            if (!ollamaService.isAvailable()) {
                return Map.of("status", "FAILED",
                    "errorMessage", "AI 服务未安装。请安装 Ollama 并拉取模型后重启应用。",
                    "aiAvailable", false);
            }

            TaskRecord record = videoService.submitRecognize(file, interval, fingerprint, metadataFingerprint);
            return Map.of("status", "PROCESSING", "id", record.getId(), "fingerprint", fingerprint);
        } catch (Exception e) {
            return Map.of("status", "FAILED", "errorMessage", e.getMessage());
        }
    }

    /**
     * 提交视频摘要任务（异步）：抽帧识别后将全部内容汇总为一段总述文字，支持缓存
     */
    @PostMapping("/summary")
    public Object summary(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "interval", defaultValue = "10") int interval,
            @RequestParam(value = "fileSize", required = false, defaultValue = "0") long fileSize,
            @RequestParam(value = "duration", required = false) String duration,
            @RequestParam(value = "resolution", required = false) String resolution,
            @RequestParam(value = "videoCodec", required = false) String videoCodec,
            @RequestParam(value = "frameRate", required = false) String frameRate,
            @RequestParam(value = "force", required = false, defaultValue = "false") boolean force) {
        try {
            if (file.isEmpty()) {
                return Map.of("status", "FAILED", "errorMessage", "请选择视频文件");
            }

            // 内容指纹（文件字节哈希+模型+版本）：force=true 时跳过缓存
            String fingerprint = videoService.computeVideoFingerprint(file.getBytes(), ollamaService.getOllamaModelName());
            // 元数据指纹：用于预览回显
            String metadataFingerprint = videoService.computeVideoFingerprint(fileSize, duration, resolution, videoCodec, frameRate);
            if (!force) {
                String cached = videoService.getCachedResult(fingerprint, "VIDEO_SUMMARY");
                if (cached != null) {
                    return Map.of("status", "CACHED", "data", cached, "fingerprint", fingerprint);
                }
            }

            if (!ollamaService.isAvailable()) {
                return Map.of("status", "FAILED",
                    "errorMessage", "AI 服务未安装。请安装 Ollama 并拉取模型后重启应用。",
                    "aiAvailable", false);
            }

            TaskRecord record = videoService.submitSummary(file, interval, fingerprint, metadataFingerprint);
            return Map.of("status", "PROCESSING", "id", record.getId(), "fingerprint", fingerprint);
        } catch (Exception e) {
            return Map.of("status", "FAILED", "errorMessage", e.getMessage());
        }
    }

    /**
     * 从已有帧描述直接生成摘要（跳过抽帧和识别），支持缓存
     */
    @PostMapping("/summary/from-descriptions")
    public Object summaryFromDescriptions(@RequestBody Map<String, Object> body) {
        try {
            if (!ollamaService.isAvailable()) {
                return Map.of("status", "FAILED",
                    "errorMessage", "AI 服务未安装。请安装 Ollama 并拉取模型后重启应用。",
                    "aiAvailable", false);
            }

            @SuppressWarnings("unchecked")
            List<String> descriptions = (List<String>) body.get("descriptions");
            if (descriptions == null || descriptions.isEmpty()) {
                return Map.of("status", "FAILED", "errorMessage", "未提供帧描述内容");
            }

            StringBuilder allDescriptions = new StringBuilder();
            String lastIncluded = null;
            for (int i = 0; i < descriptions.size(); i++) {
                String d = descriptions.get(i);
                if (d == null || d.isBlank()) continue;
                if (lastIncluded != null && textSimilarity(d, lastIncluded) > 0.8) continue;
                lastIncluded = d;
                allDescriptions.append("[").append(i).append("] ").append(d).append("\n");
            }

            String summary = ollamaService.summarizeDescriptions(allDescriptions.toString());

            // 保存摘要到缓存：只接受前端回传的内容指纹（body.fingerprint），
            // 不再用元数据指纹（会撞车写入错误缓存）
            Object fpObj = body.get("fingerprint");
            if (fpObj != null && !fpObj.toString().isEmpty()) {
                String summaryJson = "{\"summary\":\"" + summary
                    .replace("\\", "\\\\").replace("\"", "\\\"")
                    .replace("\n", " ").replace("\r", " ") + "\"}";
                videoService.saveCachedResult(fpObj.toString(), "VIDEO_SUMMARY", summaryJson);
            }

            return Map.of("status", "SUCCESS", "summary", summary);
        } catch (Exception e) {
            return Map.of("status", "FAILED", "errorMessage", e.getMessage());
        }
    }

    /**
     * 视频识别状态轮询
     */
    @GetMapping("/recognize/status/{id}")
    public ResponseEntity<?> recognizeStatus(@PathVariable Long id) {
        TaskRecord record = videoService.getTask(id);
        if (record == null) {
            return ResponseEntity.badRequest().body(Map.of("status", "FAILED", "errorMessage", "任务不存在"));
        }

        if ("SUCCESS".equals(record.getStatus())) {
            byte[] resultBytes = videoService.getResultBytes(id);
            if (resultBytes != null) {
                return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new String(resultBytes, StandardCharsets.UTF_8));
            }
            return ResponseEntity.ok(Map.of("status", "EXPIRED", "errorMessage", "结果已过期，请重新识别"));
        }

        if ("FAILED".equals(record.getStatus())) {
            return ResponseEntity.ok(Map.of("status", "FAILED", "errorMessage", record.getErrorMessage()));
        }

        return ResponseEntity.ok(Map.of("status", "PROCESSING", "id", id, "progress", videoService.getProgress(id)));
    }

    /**
     * 查询识别任务进度 + 已完成的帧（轻量级轮询）
     */
    @GetMapping("/recognize/progress/{id}")
    public Map<String, Object> recognizeProgress(@PathVariable Long id) {
        TaskRecord record = videoService.getTask(id);
        if (record == null) {
            return Map.of("status", "FAILED", "errorMessage", "任务不存在");
        }
        int progress = videoService.getProgress(id);
        var frames = videoService.getPartialFrames(id);
        int total = videoService.getTotalFrames(id);
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("status", record.getStatus());
        result.put("progress", progress);
        result.put("frames", frames);
        result.put("total", total);
        return result;
    }

    /**
     * 检查 AI 服务可用性
     */
    @GetMapping("/ai-status")
    public Map<String, Object> aiStatus() {
        boolean available = ollamaService.isAvailable();
        Map<String, Object> result = new HashMap<>();
        result.put("available", available);
        result.put("service", ollamaService.getActiveServiceName());
        result.put("model", ollamaService.getActiveModelName());
        result.put("models", ollamaService.listModels());
        result.put("services", ollamaService.getAvailableServices());
        if (!available) {
            result.put("message", "AI 服务未安装。请安装 Ollama 并拉取模型后重启应用。");
            result.put("installUrl", "https://ollama.ai/download");
            result.put("installCommand", "ollama pull qwen2.5-vl:3b");
        }
        return result;
    }

    /**
     * 切换 AI 服务（ai-server / ollama）
     */
    @PostMapping("/switch-service")
    public Map<String, Object> switchService(@RequestBody Map<String, String> body) {
        String service = body.get("service");
        if (service == null || service.isBlank()) {
            return Map.of("status", "FAILED", "errorMessage", "未指定服务");
        }
        ollamaService.switchService(service);
        return Map.of("status", "SUCCESS", "service", ollamaService.getActiveServiceName());
    }

    /**
     * 获取用户保存的服务偏好
     */
    @GetMapping("/preferred-service")
    public Map<String, Object> getPreferredService() {
        UserPreference pref = getUserPref("preferred_service");
        return Map.of("status", "SUCCESS", "service", pref != null ? pref.getPrefValue() : "");
    }

    /**
     * 立即释放 Ollama 模型显存
     */
    @PostMapping("/unload-model")
    public Map<String, Object> unloadModel() {
        ollamaService.unloadModel();
        return Map.of("status", "SUCCESS");
    }

    /**
     * 切换 AI 模型
     */
    @PostMapping("/switch-model")
    public Map<String, Object> switchModel(@RequestBody Map<String, String> body) {
        String model = body.get("model");
        if (model == null || model.isBlank()) {
            return Map.of("status", "FAILED", "errorMessage", "未指定模型");
        }
        ollamaService.switchModel(model);
        return Map.of("status", "SUCCESS", "model", ollamaService.getActiveModelName());
    }

    /**
     * 获取用户保存的模型偏好
     */
    @GetMapping("/preferred-model")
    public Map<String, Object> getPreferredModel() {
        UserPreference pref = getUserPref("preferred_model");
        return Map.of("status", "SUCCESS", "model", pref != null ? pref.getPrefValue() : "");
    }

    /**
     * 通用保存用户偏好
     */
    @PostMapping("/save-preference")
    public Map<String, Object> savePreference(@RequestBody Map<String, String> body) {
        String key = body.get("key");
        String value = body.get("value");
        if (key != null && value != null) {
            saveUserPref(key, value);
        }
        return Map.of("status", "SUCCESS");
    }

    private String getCurrentUsername() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : null;
    }

    private UserPreference getUserPref(String key) {
        String username = getCurrentUsername();
        if (username == null) return null;
        return userPreferenceMapper.findByUsernameAndKey(username, key);
    }

    private void saveUserPref(String key, String value) {
        String username = getCurrentUsername();
        if (username == null) return;
        UserPreference existing = userPreferenceMapper.findByUsernameAndKey(username, key);
        if (existing != null) {
            userPreferenceMapper.updateValue(username, key, value);
        } else {
            UserPreference pref = new UserPreference();
            pref.setUsername(username);
            pref.setPrefKey(key);
            pref.setPrefValue(value);
            userPreferenceMapper.insert(pref);
        }
    }

    /**
     * 单帧重新识别（接收 base64 图片）
     */
    @PostMapping("/frame/recognize")
    public Object frameRecognize(@RequestBody Map<String, String> body) {
        try {
            String imageBase64 = body.get("image");
            if (imageBase64 == null || imageBase64.isBlank()) {
                return Map.of("status", "FAILED", "errorMessage", "未提供图片数据");
            }
            if (!ollamaService.isAvailable()) {
                return Map.of("status", "FAILED",
                    "errorMessage", "AI 服务未安装。请安装 Ollama 并拉取模型后重启应用。",
                    "aiAvailable", false);
            }

            byte[] imageBytes = java.util.Base64.getDecoder().decode(imageBase64);
            String prompt = buildFramePrompt();

            String description = ollamaService.describeImage(imageBytes, prompt);
            description = description.replace("\n", " ").replace("\\n", " ").replaceAll("\\s+", " ").trim();

            return Map.of("status", "SUCCESS", "description", description);
        } catch (Exception e) {
            return Map.of("status", "FAILED", "errorMessage", e.getMessage());
        }
    }

    /**
     * 更新缓存中某帧的描述（重新识别后保存）
     */
    @PostMapping("/frame/update-cache")
    public Object frameUpdateCache(@RequestBody Map<String, Object> body) {
        try {
            int frameIndex = body.get("frameIndex") != null ? ((Number) body.get("frameIndex")).intValue() : -1;
            String newDescription = body.get("description") != null ? body.get("description").toString() : "";
            long fileSize = body.get("fileSize") != null ? ((Number) body.get("fileSize")).longValue() : 0;
            String duration = body.get("duration") != null ? body.get("duration").toString() : "";
            String resolution = body.get("resolution") != null ? body.get("resolution").toString() : "";
            String videoCodec = body.get("videoCodec") != null ? body.get("videoCodec").toString() : "";
            String frameRate = body.get("frameRate") != null ? body.get("frameRate").toString() : "";

            if (frameIndex < 0 || newDescription.isEmpty()) {
                return Map.of("status", "FAILED", "errorMessage", "参数不完整");
            }

            // 优先使用前端回传的内容指纹（recognize/summary 响应里的 fingerprint 字段）
            Object fpObj = body.get("fingerprint");
            String fingerprint = (fpObj != null && !fpObj.toString().isEmpty())
                ? fpObj.toString()
                : videoService.computeVideoFingerprint(fileSize, duration, resolution, videoCodec, frameRate);
            videoService.updateCachedFrameDescription(fingerprint, frameIndex, newDescription);
            return Map.of("status", "SUCCESS");
        } catch (Exception e) {
            return Map.of("status", "FAILED", "errorMessage", e.getMessage());
        }
    }

    /**
     * 查询图片分析缓存
     */
    @GetMapping("/image/cache/check")
    public Map<String, Object> imageCacheCheck(
            @RequestParam(value = "fileSize", defaultValue = "0") long fileSize,
            @RequestParam(value = "resolution", required = false) String resolution,
            @RequestParam(value = "format", required = false) String format) {
        String fingerprint = videoService.computeImageFingerprint(fileSize, resolution, format);
        String cached = videoService.getCachedResult(fingerprint, "IMAGE_RECOGNIZE");
        Map<String, Object> result = new HashMap<>();
        result.put("status", "SUCCESS");
        if (cached != null) result.put("description", cached);
        return result;
    }

    /**
     * 图片信息提取
     */
    @PostMapping("/image/info")
    public ResponseEntity<?> imageInfo(@RequestParam("file") MultipartFile file) {
        try {
            if (file.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("status", "FAILED", "errorMessage", "请选择图片文件"));
            }

            ImageInfo imageInfo = videoService.getImageInfo(file);
            return ResponseEntity.ok(Map.of("status", "SUCCESS", "data", imageInfo));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                .body(Map.of("status", "FAILED", "errorMessage", e.getMessage()));
        }
    }

    /**
     * 图片画面识别（同步），支持缓存
     */
    @PostMapping("/image/recognize")
    public Object imageRecognize(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "fileSize", required = false, defaultValue = "0") long fileSize,
            @RequestParam(value = "resolution", required = false) String resolution,
            @RequestParam(value = "format", required = false) String format,
            @RequestParam(value = "force", required = false, defaultValue = "false") boolean force) {
        try {
            if (file.isEmpty()) {
                return Map.of("status", "FAILED", "errorMessage", "请选择图片文件");
            }

            // 计算指纹并检查缓存（force=true 时跳过缓存）
            String fingerprint = videoService.computeImageFingerprint(fileSize, resolution, format);
            if (!force) {
                String cached = videoService.getCachedResult(fingerprint, "IMAGE_RECOGNIZE");
                if (cached != null) {
                    return Map.of("status", "CACHED", "description", cached);
                }
            }

            if (!ollamaService.isAvailable()) {
                return Map.of("status", "FAILED",
                    "errorMessage", "AI 服务未安装。请安装 Ollama 并拉取模型后重启应用。",
                    "aiAvailable", false);
            }

            String prompt = buildFramePrompt();

            String description = ollamaService.describeImage(file.getBytes(), prompt);
            description = description.replace("\n", " ").replace("\\n", " ").replaceAll("\\s+", " ").trim();

            videoService.createRecord(file.getOriginalFilename(), "IMAGE_RECOGNIZE");
            // 缓存图片分析结果
            videoService.saveCachedResult(fingerprint, "IMAGE_RECOGNIZE", description);

            return Map.of("status", "SUCCESS", "description", description);
        } catch (Exception e) {
            return Map.of("status", "FAILED", "errorMessage", e.getMessage());
        }
    }

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

    private String getBaseName(String filename) {
        if (filename == null) return "output";
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(0, dot) : filename;
    }

    private String buildFramePrompt() {
        return ollamaService.getFramePrompt();
    }
}