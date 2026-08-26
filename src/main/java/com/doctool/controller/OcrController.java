package com.doctool.controller;

import com.doctool.model.TaskRecord;
import com.doctool.service.OcrService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/ocr")
@RequiredArgsConstructor
public class OcrController {

    private final OcrService ocrService;

    @PostMapping
    public Object convert(@RequestParam("file") MultipartFile file) {
        try {
            TaskRecord record = ocrService.submit(file);
            Map<String, Object> resp = new HashMap<>();
            resp.put("status", "PROCESSING");
            resp.put("id", record.getId());
            return resp;
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("status", "FAILED");
            error.put("errorMessage", e.getMessage());
            return error;
        }
    }

    @GetMapping("/status/{id}")
    public ResponseEntity<?> status(@PathVariable Long id) {
        TaskRecord record = ocrService.getTask(id);
        if (record == null) {
            return ResponseEntity.badRequest().body(Map.of("status", "FAILED", "errorMessage", "任务不存在"));
        }

        if ("SUCCESS".equals(record.getStatus())) {
            // 从内存缓存取结果（一次性）
            byte[] resultBytes = ocrService.getResultBytes(id);
            if (resultBytes != null) {
                String fileName = record.getResultFilename();
                String encodedName = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
                return ResponseEntity.ok()
                        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encodedName)
                        .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                        .body(resultBytes);
            }
            // 缓存已过期（被取过或清理了）
            return ResponseEntity.ok(Map.of("status", "EXPIRED", "errorMessage", "结果已过期，请重新识别"));
        }

        if ("FAILED".equals(record.getStatus())) {
            return ResponseEntity.ok(Map.of("status", "FAILED", "errorMessage", record.getErrorMessage()));
        }

        // PROCESSING
        return ResponseEntity.ok(Map.of("status", "PROCESSING", "id", id));
    }
}
