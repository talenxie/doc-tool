package com.doctool.controller;

import com.doctool.mapper.TaskRecordMapper;
import com.doctool.model.TaskRecord;
import com.doctool.service.AnimToVideoService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/anim-to-video")
@RequiredArgsConstructor
public class AnimToVideoController {

    private final AnimToVideoService animToVideoService;
    private final TaskRecordMapper taskRecordMapper;

    @PostMapping
    public ResponseEntity<?> convert(
            @RequestParam("files") List<MultipartFile> files,
            @RequestParam(value = "format", defaultValue = "mp4") String format) {
        try {
            if (files == null || files.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("status", "FAILED", "errorMessage", "请至少选择一个文件"));
            }

            boolean hasValid = files.stream().anyMatch(f -> !f.isEmpty());
            if (!hasValid) {
                return ResponseEntity.badRequest().body(Map.of("status", "FAILED", "errorMessage", "所选文件均为空"));
            }

            byte[] result = animToVideoService.convertAll(files, format);

            // 记录处理记录
            String desc = files.size() == 1
                ? files.get(0).getOriginalFilename()
                : files.size() + " 个文件批量转换";
            TaskRecord record = new TaskRecord();
            record.setTaskType("ANIM_TO_VIDEO");
            record.setOriginalFilename(desc);
            record.setStatus("SUCCESS");
            record.setCreateTime(LocalDateTime.now());
            record.setFinishTime(LocalDateTime.now());
            taskRecordMapper.insert(record);

            String ext = "zip";
            if (files.size() == 1) {
                ext = "webm".equals(format) ? "webm"
                    : "avi".equals(format) ? "avi" : "mp4";
            }

            String baseName = files.get(0).getOriginalFilename();
            if (baseName != null && baseName.contains(".")) {
                baseName = baseName.substring(0, baseName.lastIndexOf('.'));
            } else {
                baseName = "output";
            }
            String fileName = files.size() == 1
                ? baseName + "_video." + ext
                : "videos_batch.zip";

            String encodedName = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
            String contentType = "zip".equals(ext) ? "application/zip"
                : "webm".equals(format) ? "video/webm"
                : "avi".equals(format) ? "video/x-msvideo"
                : "video/mp4";

            return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encodedName)
                .contentType(MediaType.parseMediaType(contentType))
                .body(result);

        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("status", "FAILED", "errorMessage", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("status", "FAILED", "errorMessage", e.getMessage()));
        }
    }
}
